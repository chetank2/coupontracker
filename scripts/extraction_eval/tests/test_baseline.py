import json
from pathlib import Path
from extraction_eval.baseline import diff_against_baseline, ChangedField


def write_run(p: Path, samples):
    p.write_text(json.dumps({"timestamp": "T", "run_meta": {}, "samples": samples}))


def test_diff_against_baseline_finds_changed_field(tmp_path):
    base = tmp_path / "baseline.json"
    latest = tmp_path / "latest.json"
    write_run(base, [{"id": "a", "image_sha256": "h", "parsed": {"redeemCode": "X"}, "passed": True, "field_diff": []}])
    write_run(latest, [{"id": "a", "image_sha256": "h", "parsed": {"redeemCode": "Y"}, "passed": False, "field_diff": []}])
    changes = diff_against_baseline(latest=latest, baseline=base)
    assert any(c.id == "a" and c.field == "redeemCode" for c in changes)


def test_per_field_accuracy(tmp_path):
    from extraction_eval.baseline import per_field_accuracy
    samples = [
        {"field_diff": [{"field": "redeemCode", "status": "match"}, {"field": "expiryDate", "status": "wrong"}]},
        {"field_diff": [{"field": "redeemCode", "status": "match"}, {"field": "expiryDate", "status": "match"}]},
    ]
    acc = per_field_accuracy(samples)
    assert acc["redeemCode"] == (2, 2)
    assert acc["expiryDate"] == (1, 2)
