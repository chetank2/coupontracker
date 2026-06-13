"""Copy Mac eval failures into androidTest assets for extraction smoke tests."""
from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from extraction_eval.baseline import diff_against_baseline


def _load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text())


def _samples_by_id(path: Path) -> dict[str, dict[str, Any]]:
    return {sample["id"]: sample for sample in _load_json(path).get("samples", [])}


def _android_sample(sample: dict[str, Any], asset_dir: str, dst_name: str) -> dict[str, Any]:
    out = {
        "id": sample["id"],
        "image": f"{asset_dir}/{dst_name}",
    }
    if "expected" in sample:
        out["expected"] = sample["expected"]
    return out


def _copy_samples(samples: list[dict[str, Any]], *, target_assets: Path, asset_dir: str) -> list[dict[str, Any]]:
    dest_dir = target_assets / asset_dir
    dest_dir.mkdir(parents=True, exist_ok=True)
    copied: list[dict[str, Any]] = []
    for sample in samples:
        src = Path(sample["image_path"])
        dst_name = src.name
        shutil.copyfile(src, dest_dir / dst_name)
        copied.append(_android_sample(sample, asset_dir, dst_name))
    return copied


def sync(*, eval_root: Path, target_assets: Path, include_changed: bool = False) -> Path:
    failures_path = eval_root / "failures.json"
    latest_path = eval_root / "latest.json"
    baseline_path = eval_root / "baseline.json"
    expected_path = target_assets / "canary_expected.json"

    if not failures_path.exists():
        raise FileNotFoundError(f"Missing {failures_path}")
    if include_changed and not latest_path.exists():
        raise FileNotFoundError(f"Missing {latest_path}")
    if not expected_path.exists():
        raise FileNotFoundError(f"Missing {expected_path}")

    canary_expected = _load_json(expected_path)
    failure_samples = _load_json(failures_path).get("samples", [])
    canary_expected["failures"] = _copy_samples(
        failure_samples,
        target_assets=target_assets,
        asset_dir="failures",
    )

    if include_changed:
        latest_by_id = _samples_by_id(latest_path)
        failure_ids = {sample["id"] for sample in failure_samples}
        changed_ids = {
            change.id
            for change in diff_against_baseline(latest=latest_path, baseline=baseline_path)
            if change.id not in failure_ids
        }
        changed_samples = [latest_by_id[sample_id] for sample_id in sorted(changed_ids) if sample_id in latest_by_id]
        canary_expected["changed"] = _copy_samples(
            changed_samples,
            target_assets=target_assets,
            asset_dir="changed",
        )
    else:
        canary_expected.pop("changed", None)

    expected_path.write_text(json.dumps(canary_expected, indent=2) + "\n")
    return expected_path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--eval-root", type=Path, default=Path("build/extraction-eval"))
    parser.add_argument("--target-assets", type=Path, default=Path("app/src/androidTest/assets"))
    parser.add_argument("--include-changed", action="store_true")
    args = parser.parse_args(argv)

    path = sync(
        eval_root=args.eval_root,
        target_assets=args.target_assets,
        include_changed=args.include_changed,
    )
    print(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
