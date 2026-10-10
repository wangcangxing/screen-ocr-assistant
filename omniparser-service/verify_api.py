r"""Contract verifier / smoke test for omniparser-service.

Drives the running service over real HTTP and checks every field in
``docs/接口约定-SoM与解析服务.md`` §1 literally.  Exits non-zero if any check fails.

    python verify_api.py --image ..\shots\q2.png --annotate
    python verify_api.py --image ..\shots\q2.png --no-annotate --check-errors
    python verify_api.py --base-url http://127.0.0.1:8010 --image ..\shots\quiz.png \
        --annotate --max-elements 200 --out run\verify.json

Checks performed
----------------
/health : 200 + required keys and types (ok, service, version, mode, backend,
          device, caption_enabled, weights{som_model,caption_model}, uptime_s)
/parse  : 200, ok=true, image.width/height == submitted PNG size, elapsed_ms int,
          elements[].id == 1..N in order, text elements before icon elements,
          each group sorted top->bottom then left->right (30 px row buckets),
          type/label/text/interactable/source types, bbox_ratio in [0,1] with
          x1<x2 & y1<y2, bbox_px == round(ratio*size)
annotate: som_image_base64 decodes as an image whose size == submitted size
errors  : 400 (bad JSON / bad base64), 413 (oversized), 404 (unknown endpoint)
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

TEXT_OK = "text"
ICON_OK = "icon"
SOURCES = {"ocr", "icon"}
ROW_BUCKET = 30

RESULTS: list[tuple[bool, str]] = []


def check(condition: bool, label: str, detail: str = "") -> bool:
    ok = bool(condition)
    RESULTS.append((ok, label if ok else f"{label} :: {detail}"))
    mark = "PASS" if ok else "FAIL"
    line = f"[{mark}] {label}"
    if not ok and detail:
        line += f"  -- {detail}"
    print(line, flush=True)
    return ok


def http(base_url: str, path: str, payload: dict | None = None, timeout: float = 600.0):
    url = base_url.rstrip("/") + path
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method="POST" if data else "GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read()
            return resp.status, json.loads(body.decode("utf-8")), body
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        try:
            parsed = json.loads(raw.decode("utf-8"))
        except Exception:
            parsed = {"_raw": raw[:400].decode("utf-8", "replace")}
        return exc.code, parsed, raw


def post_raw(base_url: str, path: str, raw_body: bytes, timeout: float = 120.0):
    url = base_url.rstrip("/") + path
    req = urllib.request.Request(
        url, data=raw_body, headers={"Content-Type": "application/json"}, method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read()


def check_health(base_url: str) -> dict:
    status, body, _ = http(base_url, "/health")
    check(status == 200, f"GET /health -> 200 (got {status})")
    for key in ("ok", "service", "version", "mode", "backend", "device", "caption_enabled", "weights", "uptime_s"):
        check(key in body, f"/health has key '{key}'", f"body={body}")
    check(body.get("ok") is True, "/health ok is true")
    check(body.get("service") == "omniparser-service", "/health service == omniparser-service", repr(body.get("service")))
    check(body.get("version") == "1.0", "/health version == 1.0", repr(body.get("version")))
    check(body.get("mode") in ("real", "mock"), "/health mode in {real,mock}", repr(body.get("mode")))
    check(body.get("backend") in ("lite", "upstream"), "/health backend in {lite,upstream}", repr(body.get("backend")))
    check(isinstance(body.get("caption_enabled"), bool), "/health caption_enabled is bool")
    check(isinstance(body.get("weights"), dict), "/health weights is object")
    for key in ("som_model", "caption_model"):
        check(key in (body.get("weights") or {}), f"/health weights.{key} present")
    check(isinstance(body.get("uptime_s"), (int, float)), "/health uptime_s is number")
    print(f"[info] /health = {json.dumps(body, ensure_ascii=False)}", flush=True)
    return body


def check_element(el: dict, index: int, width: int, height: int) -> None:
    tag = f"elements[{index}]"
    check(el.get("id") == index + 1, f"{tag}.id == {index + 1}", repr(el.get("id")))
    check(el.get("type") in (TEXT_OK, ICON_OK), f"{tag}.type in {{text,icon}}", repr(el.get("type")))
    check(isinstance(el.get("label"), str), f"{tag}.label is str", repr(el.get("label")))
    check(isinstance(el.get("text"), str), f"{tag}.text is str", repr(el.get("text")))
    check(el.get("source") in SOURCES, f"{tag}.source in {{ocr,icon}}", repr(el.get("source")))
    check(isinstance(el.get("interactable"), bool), f"{tag}.interactable is bool", repr(el.get("interactable")))
    if el.get("type") == TEXT_OK:
        check(el.get("source") == "ocr", f"{tag} type=text implies source=ocr", repr(el.get("source")))
        check(el.get("text") != "", f"{tag} text element has non-empty text", repr(el.get("text")))
    else:
        check(el.get("source") == "icon", f"{tag} type=icon implies source=icon", repr(el.get("source")))
        check(el.get("text") == "", f"{tag} icon text is empty", repr(el.get("text")))

    ratio = el.get("bbox_ratio")
    check(isinstance(ratio, list) and len(ratio) == 4, f"{tag}.bbox_ratio is float[4]", repr(ratio))
    if isinstance(ratio, list) and len(ratio) == 4:
        x1, y1, x2, y2 = ratio
        check(all(isinstance(v, float) for v in ratio), f"{tag}.bbox_ratio elements are floats", repr(ratio))
        check(0.0 <= x1 < x2 <= 1.0 and 0.0 <= y1 < y2 <= 1.0,
              f"{tag}.bbox_ratio in [0,1] with x1<x2, y1<y2", repr(ratio))
        px = el.get("bbox_px")
        check(isinstance(px, list) and len(px) == 4 and all(isinstance(v, int) for v in px),
              f"{tag}.bbox_px is int[4]", repr(px))
        if isinstance(px, list) and len(px) == 4:
            expected = [round(x1 * width), round(y1 * height), round(x2 * width), round(y2 * height)]
            check(px == expected, f"{tag}.bbox_px == round(ratio*size)", f"{px} != {expected}")


def check_parse(base_url: str, image_path: Path, annotate: bool, max_elements: int | None,
                box_threshold: float | None) -> dict:
    raw = image_path.read_bytes()
    with_annotate = image_path
    from PIL import Image

    with Image.open(io.BytesIO(raw)) as img:
        width, height = img.size
    print(f"[info] submitted image: {image_path} {width}x{height} {len(raw)} bytes", flush=True)

    payload: dict = {"image_base64": base64.b64encode(raw).decode("ascii")}
    if annotate:
        payload["annotate"] = True
    if max_elements is not None:
        payload["max_elements"] = max_elements
    if box_threshold is not None:
        payload["box_threshold"] = box_threshold

    t0 = time.time()
    status, body, _raw = http(base_url, "/parse", payload)
    wall_ms = int(round((time.time() - t0) * 1000))
    print(f"[info] POST /parse annotate={annotate} -> HTTP {status}, wall={wall_ms} ms", flush=True)
    if status != 200:
        check(False, f"POST /parse -> 200 (got {status})", json.dumps(body, ensure_ascii=False)[:400])
        return body
    check(True, "POST /parse -> 200")
    check(body.get("ok") is True, "/parse ok is true", repr(body.get("ok")))
    check(body.get("mode") in ("real", "mock"), "/parse mode in {real,mock}", repr(body.get("mode")))
    image = body.get("image") or {}
    check(image.get("width") == width and image.get("height") == height,
          f"/parse image == submitted size ({width}x{height})", repr(image))
    check(isinstance(body.get("elapsed_ms"), int), "/parse elapsed_ms is int", repr(body.get("elapsed_ms")))
    elements = body.get("elements")
    check(isinstance(elements, list), "/parse elements is list", repr(type(elements)))
    elements = elements or []
    if max_elements is not None:
        check(len(elements) <= max_elements, f"/parse len(elements) <= max_elements({max_elements})", str(len(elements)))
    print(f"[info] elapsed_ms={body.get('elapsed_ms')} wall_ms={wall_ms} elements={len(elements)}", flush=True)

    for index, el in enumerate(elements):
        check_element(el, index, width, height)

    types = [el.get("type") for el in elements]
    if TEXT_OK in types and ICON_OK in types:
        first_icon = types.index(ICON_OK)
        check(all(t == TEXT_OK for t in types[:first_icon]) and all(t == ICON_OK for t in types[first_icon:]),
              "elements order: all text first, then all icons", repr(types))
    for group in (TEXT_OK, ICON_OK):
        keys = [
            (int((el["bbox_ratio"][1] + el["bbox_ratio"][3]) / 2 * height // ROW_BUCKET),
             (el["bbox_ratio"][0] + el["bbox_ratio"][2]) / 2)
            for el in elements if el.get("type") == group
        ]
        check(keys == sorted(keys), f"{group} elements sorted top->bottom, left->right")

    if annotate:
        som = body.get("som_image_base64")
        check(isinstance(som, str) and len(som) > 0, "/parse annotate=true returns som_image_base64")
        if isinstance(som, str) and som:
            png = base64.b64decode(som)
            with Image.open(io.BytesIO(png)) as img:
                check(img.size == (width, height),
                      f"som_image size == submitted size ({width}x{height})", f"{img.size}")
                print(f"[info] som_image: {img.size} {img.format} {len(png)} bytes", flush=True)
        print("[info] som_image_base64 length:", len(som or ""), flush=True)
        Path("run").mkdir(exist_ok=True)
        out = Path("run") / f"som_{image_path.stem}{'_annotated' if annotate else ''}.jpg"
        out.write_bytes(base64.b64decode(som))
        print(f"[info] wrote {out.resolve()}", flush=True)
    else:
        check("som_image_base64" not in body, "/parse annotate omitted -> no som_image_base64")

    for el in elements[:5]:
        print(f"[element {el['id']}] {json.dumps(el, ensure_ascii=False)}", flush=True)
    return body


def check_errors(base_url: str, image_path: Path, big_mb: float) -> None:
    raw = image_path.read_bytes()
    status, body = post_raw(base_url, "/parse", b"{not json")
    check(status == 400, f"invalid JSON -> 400 (got {status})")
    check(body and json.loads(body).get("ok") is False, "invalid JSON -> ok=false", body[:200].decode("utf-8", "replace"))
    json.loads(body)  # must be valid JSON even on error

    status, body = post_raw(base_url, "/parse", json.dumps({"image_base64": "!!!not-base64!!!"}).encode())
    check(status == 400, f"bad base64 -> 400 (got {status})")
    check(json.loads(body).get("ok") is False, "bad base64 -> ok=false")

    status, body = post_raw(base_url, "/parse", json.dumps({"image_base64": ""}).encode())
    check(status == 400, f"missing image_base64 -> 400 (got {status})")

    status, body = post_raw(base_url, "/parse", json.dumps({"image_base64": base64.b64encode(b"x").decode()}).encode())
    check(status == 400, f"not-an-image base64 -> 400 (got {status})", body[:200].decode("utf-8", "replace"))

    big = os.urandom(int(big_mb * 1024 * 1024))
    status, body = post_raw(
        base_url, "/parse", json.dumps({"image_base64": base64.b64encode(big).decode()}).encode(), timeout=300
    )
    check(status == 413, f"oversized image ({big_mb} MB) -> 413 (got {status})", body[:200].decode("utf-8", "replace"))
    print(f"[info] 413 body = {body[:200].decode('utf-8', 'replace')}", flush=True)

    status, body, _ = http(base_url, "/nope")
    check(status == 404, f"unknown endpoint -> 404 (got {status})")

    payload = {"image_base64": base64.b64encode(raw).decode()}
    status, body, _ = http(base_url, "/parse", payload)
    check(status == 200 and body.get("ok") is True, "sanity: /parse still healthy after error requests")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="omniparser-service contract verifier")
    parser.add_argument("--base-url", default="http://127.0.0.1:8010")
    parser.add_argument("--image", default=str(Path(__file__).resolve().parents[1] / "shots" / "q2.png"))
    parser.add_argument("--annotate", dest="annotate", action="store_true", default=True)
    parser.add_argument("--no-annotate", dest="annotate", action="store_false")
    parser.add_argument("--max-elements", type=int, default=200)
    parser.add_argument("--box-threshold", type=float, default=None)
    parser.add_argument("--check-errors", action="store_true")
    parser.add_argument("--big-image-mb", type=float, default=13.0)
    parser.add_argument("--skip-parse", action="store_true")
    parser.add_argument("--out", default=None)
    args = parser.parse_args(argv)

    image_path = Path(args.image).resolve()
    if not image_path.is_file():
        print(f"[fatal] image not found: {image_path}", file=sys.stderr)
        return 2

    health = check_health(args.base_url)
    body = {}
    if not args.skip_parse:
        body = check_parse(args.base_url, image_path, args.annotate, args.max_elements, args.box_threshold)
    if args.check_errors:
        check_errors(args.base_url, image_path, args.big_image_mb)

    failed = [label for ok, label in RESULTS if not ok]
    print("\n" + "=" * 72)
    print(f"checks: {len(RESULTS)}  passed: {len(RESULTS) - len(failed)}  failed: {len(failed)}")
    for label in failed:
        print(f"  FAILED: {label}")
    print("=" * 72, flush=True)

    if args.out:
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(
            json.dumps(
                {
                    "base_url": args.base_url,
                    "image": str(image_path),
                    "annotate": args.annotate,
                    "health": health,
                    "parse": body,
                    "checks_total": len(RESULTS),
                    "checks_failed": failed,
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        print(f"[info] wrote {out.resolve()}", flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
