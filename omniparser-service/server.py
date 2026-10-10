"""omniparser-service — local OmniParser HTTP parsing service.

Implements the frozen contract in ``docs/接口约定-SoM与解析服务.md`` §1:

    GET  /health   -> service status
    POST /parse    -> {"ok", "mode", "image", "elapsed_ms", "elements", ["som_image_base64"]}

Standard library only (http.server + threading), no FastAPI/Flask.

backends
--------
lite (default)
    util.yolov9.YOLOv9Detector (TorchScript icon detector, vendored upstream)
  + Florence-2 captioning of icon crops
  + easyocr text boxes
    ``util.utils`` is deliberately NOT imported: importing it builds
    ``easyocr.Reader`` + ``PaddleOCR`` at module import time and pulls
    openai/matplotlib/supervision.

upstream
    the untouched upstream pipeline ``util.omniparser.Omniparser`` (needs the
    full upstream dependency set: paddleocr, supervision, openai, ...).
    Kept for comparison only.

Real mode refuses to start without weights: exit code 2 plus a copyable
download command.  Mock mode (``--mock``) never touches a model and returns
explicitly fake elements whose labels are prefixed with ``mock-``.

Run:
    python server.py --mock
    python server.py --backend lite --weights ./weights --port 8010
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import os
import sys
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

SERVICE_NAME = "omniparser-service"
SERVICE_VERSION = "1.0"

SERVICE_DIR = Path(__file__).resolve().parent
if str(SERVICE_DIR) not in sys.path:
    sys.path.insert(0, str(SERVICE_DIR))

# weight layout (see download_weights.ps1)
SOM_MODEL_REL = Path("icon_detect_v3") / "model.pt"
CAPTION_MODEL_REL = Path("icon_caption_florence")
CAPTION_REQUIRED = ("config.json", "model.safetensors")
# optional local copy of the microsoft/Florence-2-base processor files
FLORENCE_PROCESSOR_REL = Path("florence2_base_processor")

ROW_BUCKET_PX = 30  # same "same row" tolerance as the Android side (§2.1)
MIN_BOX_PX = 2.0  # drop degenerate detections
ICON_TEXT_IOU = 0.6  # an icon that sits on a text box is a false positive

MOCK_ELEMENTS = (
    # (type, label, text, bbox_ratio, source)
    ("text", "mock-关闭", "mock-关闭", (0.05, 0.05, 0.25, 0.09), "ocr"),
    ("text", "mock-A. 对", "mock-A. 对", (0.08, 0.30, 0.30, 0.35), "ocr"),
    ("icon", "mock-magnifying glass", "", (0.70, 0.06, 0.82, 0.11), "icon"),
    ("icon", "mock-back arrow", "", (0.03, 0.90, 0.12, 0.95), "icon"),
)


class ModelNotReady(RuntimeError):
    """Raised when /parse is called while the backend is unusable."""


# --------------------------------------------------------------------------- #
# helpers
# --------------------------------------------------------------------------- #
def _norm_path(path: Path, base: Path) -> str:
    """Report a path relative to the service dir when possible, with '/'."""
    try:
        return path.resolve().relative_to(base.resolve()).as_posix()
    except Exception:
        return path.resolve().as_posix()


def _row_sort_key(ratio: tuple[float, float, float, float], height: int) -> tuple[int, float]:
    """Top->bottom, then left->right, on a normalized box.

    ``ratio`` is ``[x1,y1,x2,y2]`` in 0..1; ``height`` is the submitted image
    height in px, so the row bucket is the same 30 px tolerance the Android side
    uses (§2.1).  (Bug found by verify_api.py: an earlier version passed ratios
    into a pixel-based bucket, which degenerated to sorting by x only.)
    """
    cy = (ratio[1] + ratio[3]) / 2.0 * height
    cx = (ratio[0] + ratio[2]) / 2.0
    return int(cy // ROW_BUCKET_PX), cx


def _clip01(v: float) -> float:
    return 0.0 if v < 0.0 else (1.0 if v > 1.0 else v)


def _iou(a: tuple[float, float, float, float], b: tuple[float, float, float, float]) -> float:
    ix1, iy1 = max(a[0], b[0]), max(a[1], b[1])
    ix2, iy2 = min(a[2], b[2]), min(a[3], b[3])
    iw, ih = ix2 - ix1, iy2 - iy1
    if iw <= 0 or ih <= 0:
        return 0.0
    inter = iw * ih
    area_a = (a[2] - a[0]) * (a[3] - a[1])
    area_b = (b[2] - b[0]) * (b[3] - b[1])
    union = area_a + area_b - inter
    return inter / union if union > 0 else 0.0


def _load_font(size: int):
    from PIL import ImageFont

    for name in ("arial.ttf", "segoeui.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except Exception:
            continue
    try:
        return ImageFont.load_default(size=size)
    except Exception:
        return ImageFont.load_default()


def render_som_image(image, elements: list[dict]) -> bytes:
    """Draw our own numbered boxes; numbering == elements[].id. JPEG bytes.

    Output size is identical to the submitted image (no resize anywhere).
    """
    from PIL import ImageDraw

    canvas = image.convert("RGB").copy()
    draw = ImageDraw.Draw(canvas)
    width, height = canvas.size
    short = max(1, min(width, height))
    thickness = max(1, int(round(short / 400)))
    font = _load_font(max(10, int(round(short / 36))))
    pad = max(2, thickness)

    for el in elements:
        x1, y1, x2, y2 = (int(v) for v in el["bbox_px"])
        color = (255, 64, 64) if el["type"] == "icon" else (64, 160, 255)
        draw.rectangle([x1, y1, x2, y2], outline=color, width=thickness)
        tag = str(el["id"])
        try:
            tb = draw.textbbox((0, 0), tag, font=font)
            tw, th = tb[2] - tb[0], tb[3] - tb[1]
        except Exception:  # pragma: no cover - very old Pillow
            tw, th = 8 * len(tag), 12
        ty = y1 - th - 2 * pad
        if ty < 0:
            ty = min(y2 + pad, max(0, height - th - 2 * pad))
        tx = min(max(0, x1), max(0, width - tw - 2 * pad))
        draw.rectangle([tx, ty, tx + tw + 2 * pad, ty + th + 2 * pad], fill=color)
        draw.text((tx + pad, ty + pad), tag, fill=(255, 255, 255), font=font)

    buf = io.BytesIO()
    canvas.save(buf, format="JPEG", quality=88)
    return buf.getvalue()


# --------------------------------------------------------------------------- #
# mock backend
# --------------------------------------------------------------------------- #
class MockBackend:
    name = "lite"
    device = "cpu"
    caption_enabled = True
    ready = True

    def __init__(self, weights_dir: Path, caption_enabled: bool) -> None:
        self.weights_dir = weights_dir
        self.caption_enabled = caption_enabled

    def weight_report(self) -> dict:
        return {
            "som_model": _norm_path(self.weights_dir / SOM_MODEL_REL, SERVICE_DIR),
            "caption_model": _norm_path(self.weights_dir / CAPTION_MODEL_REL, SERVICE_DIR),
        }

    def parse(self, image, box_threshold: float, max_elements: int, caption_enabled: bool) -> list[dict]:
        width, height = image.size
        elements: list[dict] = []
        for etype, label, text, ratio, source in MOCK_ELEMENTS:
            if etype == "icon" and not caption_enabled:
                label = "icon"
            x1, y1, x2, y2 = ratio
            elements.append(
                _build_element(width, height, etype, label, text, (x1, y1, x2, y2), source, True)
            )
        return elements[:max_elements]


# --------------------------------------------------------------------------- #
# lite backend
# --------------------------------------------------------------------------- #
class LiteBackend:
    """YOLOv9 icon detection + Florence-2 icon captions + easyocr text boxes."""

    name = "lite"

    def __init__(
        self,
        weights_dir: Path,
        device: str,
        caption_batch: int,
        caption_enabled: bool,
        default_box_threshold: float,
    ) -> None:
        import numpy as np  # noqa: F401  (kept local: mock mode must not need it)
        import torch

        from util.yolov9 import YOLOv9Detector  # vendored upstream, read-only

        self.weights_dir = weights_dir
        self.device = device
        self.caption_batch = caption_batch
        self.caption_enabled = caption_enabled
        self.default_box_threshold = default_box_threshold
        self._torch = torch

        som_path = weights_dir / SOM_MODEL_REL
        self.som_model = YOLOv9Detector(model_path=str(som_path), device=device)
        print(f"[lite] icon detector loaded: {som_path} (device={device})", flush=True)

        self.caption = None
        if caption_enabled:
            self.caption = self._load_caption(weights_dir, device)

        import easyocr  # heavy import, only needed by the lite backend

        use_gpu = device == "cuda"
        self.reader = easyocr.Reader(["en"], gpu=use_gpu, verbose=False)
        print(f"[lite] easyocr ready (gpu={use_gpu})", flush=True)

    # -- Florence-2 -------------------------------------------------------- #
    @staticmethod
    def _load_caption(weights_dir: Path, device: str):
        """Adapted from util/utils.py::get_caption_model_processor (florence2 branch).

        Upstream:
            processor = AutoProcessor.from_pretrained("microsoft/Florence-2-base", trust_remote_code=True)
            model = AutoModelForCausalLM.from_pretrained(model_name_or_path,
                        torch_dtype=torch.float32, trust_remote_code=True)   # cpu
        Here the caption model always comes from the local weights dir; the
        processor comes from a local copy when present, else from the hub.

        The local ``icon_caption_florence/config.json`` carries
        ``auto_map.AutoModelForCausalLM =
        microsoft/Florence-2-base-ft--modeling_florence2...``, i.e. the upstream
        remote-code path, which is what actually works:

          * ``transformers==4.46.1`` + ``trust_remote_code=True``  -> 0 missing /
            0 unexpected keys, real captions  <-- the pinned, verified path.
          * the native implementation of ``transformers>=4.57`` renames nearly
            every parameter (measured: 668 missing / 667 unexpected keys), so it
            cannot load this checkpoint at all.  It is only tried as a fallback.

        The remote module itself is fetched from the hub on first use (a few
        small ``.py`` files); afterwards it is cached by huggingface_hub.
        """
        import torch
        from transformers import AutoModelForCausalLM, AutoProcessor

        local_proc = weights_dir / FLORENCE_PROCESSOR_REL
        processor_src = str(local_proc) if local_proc.is_dir() else "microsoft/Florence-2-base"
        processor = AutoProcessor.from_pretrained(processor_src, trust_remote_code=True)
        print(f"[lite] Florence-2 processor: {processor_src}", flush=True)

        model_path = weights_dir / CAPTION_MODEL_REL
        dtype = torch.float32 if device == "cpu" else torch.float16

        has_auto_map = False
        try:
            cfg = json.loads((model_path / "config.json").read_text(encoding="utf-8"))
            has_auto_map = bool(cfg.get("auto_map"))
        except Exception:  # noqa: BLE001 - config reading is best effort
            pass

        orders = [True, False] if has_auto_map else [False, True]
        errors: list[str] = []
        for trust_remote_code in orders:
            try:
                model = AutoModelForCausalLM.from_pretrained(
                    str(model_path), torch_dtype=dtype, trust_remote_code=trust_remote_code
                )
                print(
                    f"[lite] Florence-2 caption model loaded: {model_path} "
                    f"(dtype={dtype}, trust_remote_code={trust_remote_code})",
                    flush=True,
                )
                return {"model": model.to(device).eval(), "processor": processor}
            except Exception as exc:  # noqa: BLE001 - try the other load path
                errors.append(f"trust_remote_code={trust_remote_code}: {type(exc).__name__}: {str(exc)[:240]}")
                print(f"[lite] Florence-2 load failed -> {errors[-1]}", file=sys.stderr, flush=True)
        raise RuntimeError(
            "Florence-2 could not be loaded with the installed transformers. "
            "Pin it to the verified version: pip install transformers==4.46.1. Details: " + " | ".join(errors)
        )

    def _caption_boxes(self, image, boxes_px: list[tuple[float, float, float, float]]) -> list[str]:
        """Adapted from util/utils.py::get_parsed_content_icon (crop -> 64x64 -> <CAPTION>).

        Difference from upstream: crops are made with PIL instead of cv2, so the
        service does not need opencv for this step (easyocr still brings its own).
        """
        model = self.caption["model"]
        processor = self.caption["processor"]
        prompt = "<CAPTION>"
        torch = self._torch

        crops = []
        kept: list[int] = []
        for idx, (x1, y1, x2, y2) in enumerate(boxes_px):
            ix1, ix2 = int(x1), int(x2)
            iy1, iy2 = int(y1), int(y2)
            if ix2 - ix1 < 1 or iy2 - iy1 < 1:
                continue
            try:
                crop = image.crop((ix1, iy1, ix2, iy2)).convert("RGB").resize((64, 64))
            except Exception:
                continue
            crops.append(crop)
            kept.append(idx)

        captions: list[str] = [""] * len(boxes_px)
        device = model.device
        batch_size = max(1, self.caption_batch)
        for start in range(0, len(crops), batch_size):
            batch = crops[start : start + batch_size]
            inputs = processor(images=batch, text=[prompt] * len(batch), return_tensors="pt").to(
                device=device
            )
            with torch.inference_mode():
                generated_ids = model.generate(
                    input_ids=inputs["input_ids"],
                    pixel_values=inputs["pixel_values"],
                    max_new_tokens=20,
                    num_beams=1,
                    do_sample=False,
                )
            texts = processor.batch_decode(generated_ids, skip_special_tokens=True)
            for offset, raw in enumerate(texts):
                captions[kept[start + offset]] = raw.strip()
        return captions

    # -- inference --------------------------------------------------------- #
    def parse(self, image, box_threshold: float, max_elements: int, caption_enabled: bool) -> list[dict]:
        import numpy as np

        width, height = image.size
        rgb = image.convert("RGB")

        # --- icons: vendored TorchScript YOLOv9 detector (pixel xyxy) ------
        result = self.som_model.predict(
            source=rgb, conf=box_threshold, imgsz=640, iou=0.7, max_det=300
        )
        icon_boxes = [
            (float(b[0]), float(b[1]), float(b[2]), float(b[3]))
            for b in result[0].boxes.xyxy.tolist()
        ]

        # --- text: easyocr ------------------------------------------------
        # same reader/args as upstream util/utils.py::check_ocr_box, which calls
        # easyocr with easyocr_args={'text_threshold': 0.8}
        ocr_results = self.reader.readtext(np.asarray(rgb), text_threshold=0.8)
        text_items: list[tuple[tuple[float, float, float, float], str]] = []
        for quad, text, _conf in ocr_results:
            xs = [float(p[0]) for p in quad]
            ys = [float(p[1]) for p in quad]
            box = (min(xs), min(ys), max(xs), max(ys))
            text = (text or "").strip()
            if not text:
                continue
            if box[2] - box[0] < MIN_BOX_PX or box[3] - box[1] < MIN_BOX_PX:
                continue
            text_items.append((box, text))

        def to_ratio(box: tuple[float, float, float, float]) -> tuple[float, float, float, float]:
            return (box[0] / width, box[1] / height, box[2] / width, box[3] / height)

        text_ratios = [to_ratio(box) for box, _ in text_items]

        # --- icon captions (optional) --------------------------------------
        captions: list[str] = []
        if caption_enabled and self.caption is not None and icon_boxes:
            captions = self._caption_boxes(rgb, icon_boxes)

        icon_items: list[tuple[tuple[float, float, float, float], str]] = []
        for idx, box in enumerate(icon_boxes):
            if box[2] - box[0] < MIN_BOX_PX or box[3] - box[1] < MIN_BOX_PX:
                continue
            ratio = to_ratio(box)
            if any(_iou(ratio, t) > ICON_TEXT_IOU for t in text_ratios):
                continue  # icon detector firing on text -> false positive
            label = "icon"
            if caption_enabled and idx < len(captions) and captions[idx]:
                label = captions[idx]
            icon_items.append((ratio, label))

        # sort each group top->bottom then left->right, keeping label/text paired
        text_entries = sorted(
            ((to_ratio(box), text) for box, text in text_items),
            key=lambda rt: _row_sort_key(rt[0], height),
        )
        icon_entries = sorted(icon_items, key=lambda rt: _row_sort_key(rt[0], height))

        elements: list[dict] = []
        for ratio, label in text_entries:
            area_ratio = (ratio[2] - ratio[0]) * (ratio[3] - ratio[1])
            elements.append(
                _build_element(width, height, "text", label, label, ratio, "ocr", area_ratio <= 0.6)
            )
        for ratio, label in icon_entries:
            elements.append(_build_element(width, height, "icon", label, "", ratio, "icon", True))
        return elements[:max_elements]

    def weight_report(self) -> dict:
        return {
            "som_model": _norm_path(self.weights_dir / SOM_MODEL_REL, SERVICE_DIR),
            "caption_model": _norm_path(self.weights_dir / CAPTION_MODEL_REL, SERVICE_DIR),
        }


# --------------------------------------------------------------------------- #
# upstream backend (comparison only)
# --------------------------------------------------------------------------- #
class UpstreamBackend:
    name = "upstream"

    def __init__(self, weights_dir: Path, device: str, caption_enabled: bool, box_threshold: float):
        from PIL import Image  # noqa: F401

        from util.omniparser import Omniparser  # upstream, full dependency set

        self.weights_dir = weights_dir
        self.device = device
        self.caption_enabled = caption_enabled
        config = {
            "som_model_path": str(weights_dir / SOM_MODEL_REL),
            "caption_model_name": "florence2",
            "caption_model_path": str(weights_dir / CAPTION_MODEL_REL),
            "BOX_TRESHOLD": box_threshold,
        }
        self.impl = Omniparser(config)

    def parse(self, image, box_threshold: float, max_elements: int, caption_enabled: bool) -> list[dict]:
        import numpy as np

        width, height = image.size
        buf = io.BytesIO()
        image.convert("RGB").save(buf, format="PNG")
        _labeled, parsed = self.impl.parse(base64.b64encode(buf.getvalue()).decode("ascii"))
        elements: list[dict] = []
        for item in parsed or []:
            bbox = item.get("bbox")
            if not bbox or len(bbox) != 4:
                continue
            ratio = tuple(_clip01(float(v)) for v in bbox)
            if ratio[2] <= ratio[0] or ratio[3] <= ratio[1]:
                continue
            if item.get("type") == "icon" or item.get("content") is None:
                etype, source = "icon", "icon"
                label = "icon"
                if caption_enabled:
                    label = str(item.get("content") or "").strip() or "icon"
                text = ""
                interactable = True
            else:
                etype, source = "text", "ocr"
                label = text = str(item.get("content") or "")
                interactable = True
            elements.append(
                _build_element(width, height, etype, label, text, ratio, source, interactable)
            )
        text = [e for e in elements if e["type"] == "text"]
        icon = [e for e in elements if e["type"] == "icon"]
        text.sort(key=lambda e: _row_sort_key(e["bbox_ratio"], height))
        icon.sort(key=lambda e: _row_sort_key(e["bbox_ratio"], height))
        return (text + icon)[:max_elements]

    def weight_report(self) -> dict:
        return {
            "som_model": _norm_path(self.weights_dir / SOM_MODEL_REL, SERVICE_DIR),
            "caption_model": _norm_path(self.weights_dir / CAPTION_MODEL_REL, SERVICE_DIR),
        }


def _build_element(
    width: int,
    height: int,
    etype: str,
    label: str,
    text: str,
    ratio: tuple[float, float, float, float],
    source: str,
    interactable: bool,
) -> dict:
    x1, y1, x2, y2 = (_clip01(float(v)) for v in ratio)
    x1, x2 = min(x1, x2), max(x1, x2)
    y1, y2 = min(y1, y2), max(y1, y2)
    ratio = [x1, y1, x2, y2]
    px = [int(round(x1 * width)), int(round(y1 * height)), int(round(x2 * width)), int(round(y2 * height))]
    return {
        "type": etype,
        "label": label,
        "text": text,
        "bbox_ratio": ratio,
        "bbox_px": px,
        "interactable": bool(interactable),
        "source": source,
    }


# --------------------------------------------------------------------------- #
# service state
# --------------------------------------------------------------------------- #
class Service:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.started = time.time()
        self.lock = threading.Lock()
        self.mode = "mock" if args.mock else "real"
        self.weights_dir = Path(args.weights).resolve()
        self.device = self._resolve_device(args.device)
        self.backend = self._build_backend()

    def _resolve_device(self, requested: str) -> str:
        if requested != "auto":
            return requested
        try:
            import torch

            return "cuda" if torch.cuda.is_available() else "cpu"
        except Exception:
            return "cpu"

    def _build_backend(self):
        if self.mode == "mock":
            return MockBackend(self.weights_dir, not self.args.no_caption)
        if self.args.backend == "upstream":
            return UpstreamBackend(
                self.weights_dir, self.device, not self.args.no_caption, self.args.box_threshold
            )
        return LiteBackend(
            self.weights_dir,
            self.device,
            self.args.caption_batch,
            not self.args.no_caption,
            self.args.box_threshold,
        )

    @property
    def caption_active(self) -> bool:
        if self.args.no_caption:
            return False
        if self.mode == "mock":
            return True
        if self.args.backend == "upstream":
            return True
        return getattr(self.backend, "caption", None) is not None

    def health(self) -> dict:
        weights = self.backend.weight_report() if self.backend else {}
        return {
            "ok": True,
            "service": SERVICE_NAME,
            "version": SERVICE_VERSION,
            "mode": self.mode,
            "backend": self.backend.name if self.backend else self.args.backend,
            "device": self.device,
            "caption_enabled": bool(self.caption_active),
            "weights": weights,
            "uptime_s": round(time.time() - self.started, 1),
        }

    def parse(self, payload: dict) -> dict:
        image, image_bytes = decode_image(payload.get("image_base64"), self.args.max_image_mb)
        box_threshold = float(payload.get("box_threshold") or self.args.box_threshold)
        annotate = bool(payload.get("annotate") or False)
        max_elements = int(payload.get("max_elements") or 200)
        if max_elements < 0:
            max_elements = 0

        t0 = time.time()
        with self.lock:  # serialize inference: one parse at a time
            if self.backend is None or not getattr(self.backend, "ready", True):
                raise ModelNotReady("model not ready")
            elements = self.backend.parse(
                image, box_threshold, max_elements, self.caption_active
            )
        for index, el in enumerate(elements, start=1):
            el["id"] = index
        elapsed_ms = int(round((time.time() - t0) * 1000))

        body: dict[str, Any] = {
            "ok": True,
            "mode": self.mode,
            "image": {"width": image.width, "height": image.height},
            "elapsed_ms": elapsed_ms,
            "elements": elements,
        }
        if annotate:
            jpeg = render_som_image(image, elements)
            body["som_image_base64"] = base64.b64encode(jpeg).decode("ascii")
        del image_bytes
        return body


def decode_image(image_base64: Any, max_image_mb: float):
    """-> (PIL.Image, raw_bytes). Raises ValueError/BufferError for 400/413."""
    if not isinstance(image_base64, str) or not image_base64.strip():
        raise ValueError("image_base64 is missing or empty")
    raw = image_base64.strip()
    if raw.startswith("data:"):
        raise ValueError("image_base64 must not carry a data: prefix")
    try:
        data = base64.b64decode(raw, validate=True)
    except Exception as exc:
        raise ValueError(f"image_base64 is not valid base64: {exc}") from exc
    limit = int(max_image_mb * 1024 * 1024)
    if len(data) > limit:
        raise BufferError(f"image too large: {len(data)} bytes > {limit} bytes")
    from PIL import Image

    try:
        image = Image.open(io.BytesIO(data))
        image.load()
    except Exception as exc:
        raise ValueError(f"image_base64 is not a decodable image: {exc}") from exc
    return image, data


# --------------------------------------------------------------------------- #
# HTTP plumbing
# --------------------------------------------------------------------------- #
class Handler(BaseHTTPRequestHandler):
    server_version = f"{SERVICE_NAME}/{SERVICE_VERSION}"
    protocol_version = "HTTP/1.1"
    service: Service  # injected

    # keep the terminal readable
    def log_message(self, fmt, *args):  # noqa: A003
        sys.stdout.write(
            f"[{time.strftime('%H:%M:%S')}] {self.address_string()} {fmt % args}\n"
        )
        sys.stdout.flush()

    def _send_json(self, status: int, body: dict, extra_headers: dict | None = None) -> None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        for key, value in (extra_headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(data)

    def _error(self, status: int, message: str, headers: dict | None = None) -> None:
        self._send_json(status, {"ok": False, "error": message}, headers)

    def do_GET(self):  # noqa: N802
        if self.path.split("?")[0] == "/health":
            self._send_json(200, self.service.health())
            return
        self._error(404, f"unknown endpoint: {self.path} (only GET /health, POST /parse)")

    def do_POST(self):  # noqa: N802
        if self.path.split("?")[0] != "/parse":
            self._error(404, f"unknown endpoint: {self.path} (only GET /health, POST /parse)")
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if length <= 0:
            self._error(400, "empty request body")
            return
        raw = self.rfile.read(length)
        try:
            payload = json.loads(raw.decode("utf-8"))
        except Exception as exc:
            self._error(400, f"request body is not valid JSON: {exc}")
            return
        if not isinstance(payload, dict):
            self._error(400, "request body must be a JSON object")
            return

        try:
            body = self.service.parse(payload)
        except BufferError as exc:
            self._error(413, str(exc))
            return
        except ValueError as exc:
            self._error(400, str(exc))
            return
        except ModelNotReady as exc:
            self._error(503, str(exc) or "model not ready")
            return
        except Exception as exc:  # noqa: BLE001 - 500 with the literal reason
            traceback.print_exc()
            self._error(500, f"{type(exc).__name__}: {exc}")
            return
        self._send_json(200, body)

    def do_HEAD(self):  # noqa: N802
        self._error(404, f"unknown endpoint: {self.path}")


# --------------------------------------------------------------------------- #
# startup
# --------------------------------------------------------------------------- #
def missing_weight_files(args: argparse.Namespace) -> list[str]:
    weights = Path(args.weights)
    missing: list[str] = []
    if not (weights / SOM_MODEL_REL).is_file():
        missing.append((weights / SOM_MODEL_REL).as_posix())
    if not args.no_caption:
        cap = weights / CAPTION_MODEL_REL
        for name in CAPTION_REQUIRED:
            if not (cap / name).is_file():
                missing.append((cap / name).as_posix())
    return missing


def download_hint(args: argparse.Namespace) -> str:
    ps1 = SERVICE_DIR / "download_weights.ps1"
    py = SERVICE_DIR / "download_weights.py"
    return (
        "download the weights with one of:\n"
        f'  powershell -ExecutionPolicy Bypass -File "{ps1}"\n'
        f'  D:\\Python312\\python.exe "{py}" --weights-dir "{Path(args.weights).resolve()}" '
        "--endpoint https://hf-mirror.com"
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="OmniParser local parsing service (see docs/接口约定-SoM与解析服务.md §1)"
    )
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8010)
    parser.add_argument("--backend", choices=["lite", "upstream"], default="lite")
    parser.add_argument("--mock", action="store_true", help="no weights, clearly fake elements")
    parser.add_argument("--weights", default=str(SERVICE_DIR / "weights"))
    parser.add_argument("--device", choices=["auto", "cpu", "cuda"], default="auto")
    parser.add_argument("--box-threshold", type=float, default=0.05)
    parser.add_argument("--caption-batch", type=int, default=32)
    parser.add_argument("--no-caption", action="store_true", help="skip Florence-2, icons are 'icon'")
    parser.add_argument("--max-image-mb", type=float, default=12.0)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    if not args.mock:
        missing = missing_weight_files(args)
        if missing:
            print(f"[fatal] mode=real but weights are missing ({len(missing)}):", file=sys.stderr)
            for item in missing:
                print(f"  - {item}", file=sys.stderr)
            print("[fatal] " + download_hint(args), file=sys.stderr)
            return 2

    try:
        service = Service(args)
    except Exception as exc:  # noqa: BLE001
        print(f"[fatal] backend init failed: {type(exc).__name__}: {exc}", file=sys.stderr)
        traceback.print_exc()
        if not args.mock:
            print("[fatal] " + download_hint(args), file=sys.stderr)
        return 2

    Handler.service = service
    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    httpd.daemon_threads = True
    health = service.health()
    print(
        f"[start] {SERVICE_NAME} {SERVICE_VERSION} mode={health['mode']} backend={health['backend']} "
        f"device={health['device']} caption_enabled={health['caption_enabled']}",
        flush=True,
    )
    print(f"[start] listening on http://{args.host}:{args.port}  (GET /health, POST /parse)", flush=True)
    if args.port == 0:
        print(f"[start] actual port: {httpd.server_address[1]}", flush=True)
    print(f"[start] pid={os.getpid()}", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n[stop] KeyboardInterrupt", flush=True)
    finally:
        httpd.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
