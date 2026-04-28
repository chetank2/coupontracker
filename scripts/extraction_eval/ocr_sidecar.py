"""Mac-side Tesseract OCR sidecar generator for the extraction harness.

IMPORTANT LIMITATIONS — read before use:
  1. This generates OCR using **Tesseract only**. Android's production OCR path
     uses **ML Kit + Tesseract merged** via ``OcrCoordinator`` / ``OcrMerger`` —
     Mac-side Tesseract output will differ in tile boundaries, word splits, and
     confidence values compared to what Android actually produces.
  2. The generated sidecars are useful for **regression-stable harness testing**
     (deterministic, repeatable) but **are not a substitute for actual Android
     device OCR capture** during Phase 0 parity verification. The phone wins on
     disagreements per the harness spec.
  3. The ``tessdata/`` files used MUST come from the canonical Android source
     (``app/src/main/assets/tessdata``) so even if the engines differ, at least
     the language model is identical.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from io import StringIO
from pathlib import Path
from typing import Optional


# ---------------------------------------------------------------------------
# Core sidecar generation
# ---------------------------------------------------------------------------

def _run_pytesseract(
    image_path: Path,
    *,
    tessdata_dir: Path,
    lang: str,
    psm: int,
) -> dict:
    """Use pytesseract.image_to_data to get word-level output."""
    import pytesseract  # type: ignore[import]
    from PIL import Image  # type: ignore[import]  # already in requirements

    config = f"--tessdata-dir {tessdata_dir} --psm {psm}"
    img = Image.open(image_path)
    tsv_text = pytesseract.image_to_data(img, lang=lang, config=config)
    return _parse_tsv(tsv_text)


def _run_cli(
    image_path: Path,
    *,
    tessdata_dir: Path,
    lang: str,
    psm: int,
) -> dict:
    """Shell out to the ``tesseract`` binary and parse its TSV output."""
    cmd = [
        "tesseract",
        str(image_path),
        "stdout",
        "--tessdata-dir", str(tessdata_dir),
        "-l", lang,
        "--psm", str(psm),
        "tsv",
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, check=True)
    return _parse_tsv(result.stdout)


def _parse_tsv(tsv_text: str) -> dict:
    """Parse Tesseract TSV output into a sidecar dict.

    TSV columns (0-indexed):
      0  level, 1  page_num, 2  block_num, 3  par_num, 4  line_num,
      5  word_num, 6  left, 7  top, 8  width, 9  height,
      10 conf, 11 text
    """
    reader = StringIO(tsv_text)
    header_line = reader.readline()  # consume header
    if not header_line:
        return {"text": "", "tiles": []}

    tiles: list[dict] = []
    words: list[str] = []

    for raw_line in reader:
        line = raw_line.rstrip("\n")
        parts = line.split("\t", 11)
        if len(parts) < 12:
            continue
        word_text = parts[11]
        if not word_text or not word_text.strip():
            continue  # skip empty / whitespace-only tokens

        try:
            left = int(parts[6])
            top = int(parts[7])
            width = int(parts[8])
            height = int(parts[9])
            conf_raw = float(parts[10])
        except (ValueError, IndexError):
            continue

        right = left + width
        bottom = top + height

        # Tesseract reports -1 confidence for non-word regions (e.g. block-level
        # rows). We treat -1 as "no confidence data" and map it to 1.0 so
        # downstream consumers that use confidence as a threshold aren't
        # incorrectly penalised. Any other value is normalised to [0, 1].
        if conf_raw < 0:
            confidence = 1.0
        else:
            confidence = conf_raw / 100.0

        tiles.append({
            "text": word_text,
            "left": left,
            "top": top,
            "right": right,
            "bottom": bottom,
            "confidence": confidence,
        })
        words.append(word_text)

    full_text = " ".join(words)
    return {"text": full_text, "tiles": tiles}


def generate_sidecar(
    image_path: Path,
    *,
    tessdata_dir: Path,
    lang: str = "eng",
    psm: int = 3,
) -> dict:
    """Run Tesseract OCR on *image_path* and return a sidecar dict.

    Shape::

        {
          "text": "...",
          "tiles": [
            {"text": "...", "left": int, "top": int,
             "right": int, "bottom": int, "confidence": float},
            ...
          ]
        }

    Prefers ``pytesseract`` when available; falls back to the ``tesseract``
    CLI.  Both require the ``tesseract`` binary to be present on the system.
    """
    try:
        import pytesseract as _pt  # noqa: F401 — availability check
        _has_pytesseract = True
    except ImportError:
        _has_pytesseract = False

    if _has_pytesseract:
        return _run_pytesseract(
            image_path, tessdata_dir=tessdata_dir, lang=lang, psm=psm
        )
    return _run_cli(
        image_path, tessdata_dir=tessdata_dir, lang=lang, psm=psm
    )


def write_sidecar(
    sample_id: str,
    sidecar: dict,
    *,
    ocr_root: Path,
) -> Path:
    """Write *sidecar* to ``<ocr_root>/<sample_id>.json`` with indent=2.

    Creates *ocr_root* if it does not exist.  Returns the path written.
    """
    ocr_root.mkdir(parents=True, exist_ok=True)
    out_path = ocr_root / f"{sample_id}.json"
    out_path.write_text(json.dumps(sidecar, indent=2, ensure_ascii=False) + "\n")
    return out_path


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

_DEFAULT_MANIFEST = Path("Coupons /manifest.json")
_DEFAULT_MANIFEST_ROOT = Path("Coupons ")
_DEFAULT_TESSDATA = Path("app/src/main/assets/tessdata")


def _build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description=(
            "Generate Tesseract OCR sidecar JSON files for the extraction harness. "
            "NOTE: Mac Tesseract output differs from Android ML Kit + Tesseract merged "
            "OCR — see module docstring for details."
        )
    )
    p.add_argument(
        "--manifest",
        type=Path,
        default=_DEFAULT_MANIFEST,
        help="Path to manifest.json (default: 'Coupons /manifest.json')",
    )
    p.add_argument(
        "--manifest-root",
        type=Path,
        default=_DEFAULT_MANIFEST_ROOT,
        help="Root for resolving image paths from the manifest (default: 'Coupons ')",
    )
    p.add_argument(
        "--ocr-root",
        type=Path,
        default=None,
        help="Directory where sidecar JSONs are written. Default: <manifest-root>/ocr/",
    )
    p.add_argument(
        "--tessdata",
        type=Path,
        default=_DEFAULT_TESSDATA,
        help="Tesseract data directory (default: app/src/main/assets/tessdata)",
    )
    p.add_argument(
        "--sample",
        dest="samples",
        action="append",
        metavar="ID",
        default=[],
        help="Generate only this sample (by manifest id). Repeatable.",
    )
    p.add_argument(
        "--force",
        action="store_true",
        help="Overwrite existing sidecar files. Without this, existing sidecars are skipped.",
    )
    p.add_argument(
        "--psm",
        type=int,
        default=3,
        help=(
            "Tesseract page segmentation mode (default: 3 = auto). "
            "Matches Android TesseractOcrEngine PSM_AUTO."
        ),
    )
    p.add_argument(
        "--lang",
        default="eng",
        help="Tesseract language code (default: eng)",
    )
    return p


def _load_manifest_samples(manifest_path: Path, manifest_root: Path) -> list[dict]:
    """Return a flat list of sample dicts from the manifest."""
    data = json.loads(manifest_path.read_text())
    return data.get("samples", [])


def main(argv: Optional[list[str]] = None) -> int:
    parser = _build_arg_parser()
    args = parser.parse_args(argv)

    ocr_root: Path = args.ocr_root if args.ocr_root is not None else (args.manifest_root / "ocr")

    # Resolve manifest (support both absolute and project-relative paths)
    manifest_path = args.manifest
    if not manifest_path.is_absolute():
        # Try relative to CWD first, then to the project root heuristic
        if not manifest_path.exists():
            print(
                f"WARNING: manifest not found at {manifest_path} — "
                "pass --manifest with an absolute path if running from a "
                "non-project directory.",
                file=sys.stderr,
            )
            return 1

    try:
        samples = _load_manifest_samples(manifest_path, args.manifest_root)
    except FileNotFoundError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    except json.JSONDecodeError as exc:
        print(f"ERROR: Invalid manifest JSON: {exc}", file=sys.stderr)
        return 1

    # Filter to requested samples if --sample was supplied
    filter_ids: set[str] = set(args.samples)
    if filter_ids:
        samples = [s for s in samples if s["id"] in filter_ids]
        missing = filter_ids - {s["id"] for s in samples}
        for mid in sorted(missing):
            print(f"FAIL {mid} (not found in manifest)", file=sys.stderr)

    exit_code = 0
    for sample in samples:
        sample_id: str = sample["id"]
        image_rel: str = sample.get("image", "")
        image_path: Path = args.manifest_root / image_rel

        sidecar_path = ocr_root / f"{sample_id}.json"

        # Skip if already exists and --force not set
        if sidecar_path.exists() and not args.force:
            print(f"SKIP {sample_id} tiles=- chars=-")
            continue

        if not image_path.exists():
            print(f"FAIL {sample_id} (image not found: {image_path})", file=sys.stderr)
            exit_code = 1
            continue

        try:
            sidecar = generate_sidecar(
                image_path,
                tessdata_dir=args.tessdata,
                lang=args.lang,
                psm=args.psm,
            )
            write_sidecar(sample_id, sidecar, ocr_root=ocr_root)
            tile_count = len(sidecar.get("tiles", []))
            char_count = len(sidecar.get("text", ""))
            print(f"OK {sample_id} tiles={tile_count} chars={char_count}")
        except Exception as exc:  # noqa: BLE001
            print(f"FAIL {sample_id} ({exc})", file=sys.stderr)
            exit_code = 1

    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
