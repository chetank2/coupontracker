from pathlib import Path
import json

from extraction_eval.sync_failures_to_androidtest import sync


def _write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data))


def test_sync_copies_failures_into_android_assets(tmp_path: Path) -> None:
    image = tmp_path / "coupon.png"
    image.write_bytes(b"png")
    eval_root = tmp_path / "eval"
    assets = tmp_path / "assets"
    _write_json(eval_root / "failures.json", {"samples": [{"id": "bad", "image_path": str(image), "expected": {"code": "X"}}]})
    _write_json(assets / "canary_expected.json", {"schemaVersion": 1, "samples": []})

    sync(eval_root=eval_root, target_assets=assets)

    data = json.loads((assets / "canary_expected.json").read_text())
    assert (assets / "failures" / "coupon.png").read_bytes() == b"png"
    assert data["failures"] == [{"id": "bad", "image": "failures/coupon.png", "expected": {"code": "X"}}]


def test_include_changed_adds_non_failure_baseline_drift(tmp_path: Path) -> None:
    failed_image = tmp_path / "failed.png"
    changed_image = tmp_path / "changed.png"
    failed_image.write_bytes(b"failed")
    changed_image.write_bytes(b"changed")
    eval_root = tmp_path / "eval"
    assets = tmp_path / "assets"
    _write_json(
        eval_root / "failures.json",
        {"samples": [{"id": "failed", "image_path": str(failed_image), "parsed": {"code": "bad"}}]},
    )
    _write_json(
        eval_root / "baseline.json",
        {"samples": [{"id": "changed", "parsed": {"code": "old"}}, {"id": "failed", "parsed": {"code": "old"}}]},
    )
    _write_json(
        eval_root / "latest.json",
        {
            "samples": [
                {"id": "changed", "image_path": str(changed_image), "parsed": {"code": "new"}},
                {"id": "failed", "image_path": str(failed_image), "parsed": {"code": "bad"}},
            ]
        },
    )
    _write_json(assets / "canary_expected.json", {"schemaVersion": 1, "samples": []})

    sync(eval_root=eval_root, target_assets=assets, include_changed=True)

    data = json.loads((assets / "canary_expected.json").read_text())
    assert (assets / "changed" / "changed.png").read_bytes() == b"changed"
    assert data["changed"] == [{"id": "changed", "image": "changed/changed.png"}]


def test_sync_without_include_changed_removes_stale_changed_block(tmp_path: Path) -> None:
    eval_root = tmp_path / "eval"
    assets = tmp_path / "assets"
    _write_json(eval_root / "failures.json", {"samples": []})
    _write_json(assets / "canary_expected.json", {"schemaVersion": 1, "samples": [], "changed": [{"id": "old"}]})

    sync(eval_root=eval_root, target_assets=assets, include_changed=False)

    data = json.loads((assets / "canary_expected.json").read_text())
    assert "changed" not in data
