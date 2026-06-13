"""Split Android OCR sidecar capture report into Mac harness sidecar files."""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def write_sidecars(*, report: Path, out_dir: Path) -> list[Path]:
    data = json.loads(report.read_text())
    out_dir.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    for row in data.get("sidecars", []):
        sample_id = row["id"]
        ocr = row.get("ocr", {"text": "", "tiles": []})
        ocr["_source"] = "android-mlkit-tesseract"
        path = out_dir / f"{sample_id}.json"
        path.write_text(json.dumps(ocr, indent=2, ensure_ascii=False) + "\n")
        written.append(path)
    return written


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--out-dir", required=True, type=Path)
    args = parser.parse_args(argv)

    written = write_sidecars(report=args.report, out_dir=args.out_dir)
    for path in written:
        print(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
