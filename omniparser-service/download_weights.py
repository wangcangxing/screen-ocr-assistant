"""Download OmniParser v2 weights into ./weights.

Exact layout produced (what server.py expects):

    weights/icon_detect_v3/model.pt
    weights/icon_caption_florence/{config.json,generation_config.json,model.safetensors}
    weights/florence2_base_processor/{preprocessor_config.json,tokenizer*.json,vocab.json}

Facts verified against the Hub on 2026-10-10 (queried, not assumed):
  * repo ``microsoft/OmniParser-v2.0`` really contains ``icon_caption/*`` and
    ``icon_detect_v3/model.pt``; the detector only has the *new* checkpoint in
    PR #37, hence ``revision="refs/pr/37"`` for the detector (upstream README).
  * there is **no** ``icon_caption_florence`` folder upstream -- that name is
    only the local target directory, so ``icon_caption/*`` is renamed to it.
  * ``icon_caption/config.json`` -> ``auto_map.AutoModelForCausalLM =
    microsoft/Florence-2-base-ft--modeling_florence2...`` (remote code lives in
    ``microsoft/Florence-2-base-ft``); ``server.py`` tries the *native*
    transformers Florence-2 path first and only falls back to remote code.
  * the processor comes from ``microsoft/Florence-2-base`` (same repo that
    util/utils.py::get_caption_model_processor uses); its small files are
    mirrored into ``weights/florence2_base_processor`` so real mode is offline.
  * ``https://hf-mirror.com/<repo>/resolve/<rev>/<file>`` answers HTTP 308 to
    ``https://huggingface.co``.  huggingface_hub does its metadata probe with
    ``allow_redirects=False``, so the mirror fails with
    ``FileMetadataError: Distant resource does not seem to be on huggingface.co``.
    ``download_weights.ps1`` therefore retries with the official endpoint.

IMPORTANT: ``HF_ENDPOINT`` must be set **before** huggingface_hub is imported
(its ``constants.ENDPOINT`` is captured at import time and cannot be patched
afterwards), so this script handles exactly one endpoint per process and the
PowerShell wrapper re-invokes it for the fallback endpoint.

Usage:
    python download_weights.py [--weights-dir ./weights] [--endpoint https://huggingface.co]
"""

from __future__ import annotations

import argparse
import os
import shutil
import sys
from pathlib import Path

OMNI_REPO = "microsoft/OmniParser-v2.0"
DETECT_FILE = "icon_detect_v3/model.pt"
DETECT_REVISION = "refs/pr/37"
CAPTION_REMOTE_DIR = "icon_caption"
CAPTION_LOCAL_DIR = "icon_caption_florence"
CAPTION_FILES = ["config.json", "generation_config.json", "model.safetensors"]

PROC_REPO = "microsoft/Florence-2-base"
PROC_LOCAL_DIR = "florence2_base_processor"
PROC_REQUIRED = ["preprocessor_config.json", "tokenizer.json", "tokenizer_config.json", "vocab.json"]
PROC_OPTIONAL = ["processing_florence2.py", "special_tokens_map.json", "merges.txt", "added_tokens.json"]


def required_paths(weights_dir: Path) -> list[Path]:
    return [
        weights_dir / DETECT_FILE,
        *(weights_dir / CAPTION_LOCAL_DIR / name for name in CAPTION_FILES),
        *(weights_dir / PROC_LOCAL_DIR / name for name in PROC_REQUIRED),
    ]


def _merge_move(src: Path, dest: Path) -> None:
    """Move/merge src directory into dest (dest wins on conflicts)."""
    dest.mkdir(parents=True, exist_ok=True)
    for item in src.iterdir():
        target = dest / item.name
        if item.is_dir():
            _merge_move(item, target)
        else:
            if target.exists():
                target.unlink()
            shutil.move(str(item), str(target))
    shutil.rmtree(src, ignore_errors=True)


def run(weights_dir: Path) -> int:
    # lazy import: HF_ENDPOINT has already been set by main()
    try:
        import huggingface_hub
        import huggingface_hub.constants as hub_constants
        from huggingface_hub import hf_hub_download
    except Exception as exc:  # pragma: no cover
        print(f"[download] FATAL: huggingface_hub unavailable: {exc!r}", file=sys.stderr)
        return 1

    print(f"[download] huggingface_hub={huggingface_hub.__version__} endpoint={hub_constants.ENDPOINT}")
    print(f"[download] HF_HOME={os.environ.get('HF_HOME', '(default)')}")
    print(f"[download] weights dir={weights_dir}")
    weights_dir.mkdir(parents=True, exist_ok=True)

    failures: list[str] = []
    total = 0

    def fetch(label: str, repo: str, repo_file: str, local_dir: Path, revision: str) -> None:
        nonlocal total
        print(f"[download] {label} <- {repo}:{repo_file} (revision={revision}) ...", flush=True)
        try:
            path = Path(
                hf_hub_download(
                    repo_id=repo,
                    filename=repo_file,
                    revision=revision,
                    local_dir=str(local_dir),
                )
            )
            size = path.stat().st_size
            total += size
            print(f"[download]   ok: {path} ({size / 1e6:.1f} MB)", flush=True)
        except Exception as exc:  # noqa: BLE001 - report the literal error only
            print(f"[download]   FAILED {label}: {type(exc).__name__}: {exc}", file=sys.stderr, flush=True)
            failures.append(label)

    fetch("icon_detect_v3/model.pt", OMNI_REPO, DETECT_FILE, weights_dir, DETECT_REVISION)
    for name in CAPTION_FILES:
        fetch(
            f"icon_caption/{name}",
            OMNI_REPO,
            f"{CAPTION_REMOTE_DIR}/{name}",
            weights_dir,
            "main",
        )
    for name in PROC_REQUIRED:
        fetch(f"florence2_base_processor/{name}", PROC_REPO, name, weights_dir / PROC_LOCAL_DIR, "main")
    for name in PROC_OPTIONAL:
        try:
            hf_hub_download(
                repo_id=PROC_REPO, filename=name, revision="main",
                local_dir=str(weights_dir / PROC_LOCAL_DIR),
            )
            print(f"[download]   ok (optional): {name}", flush=True)
        except Exception as exc:  # noqa: BLE001
            print(f"[download]   optional absent: {name} ({type(exc).__name__})", flush=True)

    # upstream folder name -> the local target name the service uses
    remote_dir = weights_dir / CAPTION_REMOTE_DIR
    local_dir_target = weights_dir / CAPTION_LOCAL_DIR
    if remote_dir.is_dir():
        _merge_move(remote_dir, local_dir_target)
        print(f"[download] renamed {CAPTION_REMOTE_DIR} -> {CAPTION_LOCAL_DIR}", flush=True)

    missing = [str(p) for p in required_paths(weights_dir) if not p.is_file()]
    if missing or failures:
        print(f"\n[download] INCOMPLETE endpoint={hub_constants.ENDPOINT}", file=sys.stderr)
        for item in missing:
            print(f"  missing: {item}", file=sys.stderr)
        return 1

    print(f"\n[download] COMPLETE via {hub_constants.ENDPOINT}; fetched {total / 1e6:.1f} MB this run")
    for path in required_paths(weights_dir):
        print(f"[download]   {path.relative_to(weights_dir).as_posix()}  {path.stat().st_size / 1e6:.1f} MB")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Download OmniParser v2 weights (one endpoint per run)")
    parser.add_argument("--weights-dir", default=str(Path(__file__).resolve().parent / "weights"))
    parser.add_argument("--endpoint", default="https://hf-mirror.com")
    args = parser.parse_args(argv)

    # must happen before huggingface_hub is imported anywhere in this process
    os.environ["HF_ENDPOINT"] = args.endpoint
    return run(Path(args.weights_dir))


if __name__ == "__main__":
    raise SystemExit(main())
