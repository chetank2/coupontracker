import json
from pathlib import Path
from extraction_eval.diff import FieldDiff, FieldStatus
from extraction_eval.report import write_run, RunResult, SampleResult

def make_sample(passed: bool) -> SampleResult:
    return SampleResult(
        id="sample_a",
        image_sha256="aaaa",
        image_path="images/sample_a.png",
        expected={"redeemCode": "X"},
        prompt_text="P",
        raw_model_output="O",
        parsed={"redeemCode": "X" if passed else "Y"},
        preprocessed_image_sha256="hhhh",
        latency_ms=123,
        field_diff=[FieldDiff("redeemCode", "X", "X" if passed else "Y",
                              FieldStatus.MATCH if passed else FieldStatus.WRONG)],
        passed=passed,
    )

def make_pending_sample() -> SampleResult:
    return SampleResult(
        id="pending_a",
        image_sha256="ffff",
        image_path="images/pending_a.png",
        expected=None,
        prompt_text="P",
        raw_model_output='{"storeName": "Pending Corp"}',
        parsed={"storeName": "Pending Corp"},
        preprocessed_image_sha256="pppp",
        latency_ms=99,
        field_diff=[],
        passed=False,
        pending=True,
    )

def test_write_run_emits_run_json_and_md_and_failures(tmp_path):
    run = RunResult(
        timestamp="2026-04-26T20-00-00Z",
        run_meta={"git": "deadbeef"},
        samples=[make_sample(True), make_sample(False)],
    )
    write_run(run, eval_root=tmp_path)
    runs = sorted((tmp_path / "runs").iterdir())
    assert len(runs) == 1
    assert (runs[0] / "run.json").exists()
    assert (runs[0] / "run.md").exists()
    latest = json.loads((tmp_path / "latest.json").read_text())
    assert latest["timestamp"] == "2026-04-26T20-00-00Z"
    failures = json.loads((tmp_path / "failures.json").read_text())
    assert len(failures["samples"]) == 1
    assert failures["samples"][0]["passed"] is False


def test_run_md_per_field_accuracy_section(tmp_path):
    run = RunResult(
        timestamp="2026-04-26T21-00-00Z",
        run_meta={"git": "cafebabe"},
        samples=[make_sample(True), make_sample(False)],
    )
    write_run(run, eval_root=tmp_path)
    runs = sorted((tmp_path / "runs").iterdir())
    md = (runs[0] / "run.md").read_text()
    assert "## Per-field accuracy" in md
    assert "redeemCode" in md


def test_run_md_changed_since_baseline_section(tmp_path):
    # Write a baseline with redeemCode = "OLD"
    baseline_sample = {
        "id": "sample_a",
        "image_sha256": "aaaa",
        "image_path": "images/sample_a.png",
        "expected": {"redeemCode": "OLD"},
        "prompt_text": "P",
        "raw_model_output": "O",
        "parsed": {"redeemCode": "OLD"},
        "preprocessed_image_sha256": "hhhh",
        "latency_ms": 100,
        "field_diff": [],
        "passed": True,
    }
    (tmp_path / "baseline.json").write_text(
        json.dumps({"timestamp": "T0", "run_meta": {}, "samples": [baseline_sample]})
    )
    # Current run has redeemCode = "X" (from make_sample(True)) — different from "OLD"
    run = RunResult(
        timestamp="2026-04-26T22-00-00Z",
        run_meta={"git": "cafebabe"},
        samples=[make_sample(True)],
    )
    write_run(run, eval_root=tmp_path)
    runs = sorted((tmp_path / "runs").iterdir())
    md = (runs[0] / "run.md").read_text()
    assert "## Changed since baseline" in md
    assert "redeemCode" in md


def test_run_md_pending_section_and_accuracy_exclusion(tmp_path):
    """Pending samples appear in ## Pending; they are excluded from counts and ## Per-field accuracy."""
    run = RunResult(
        timestamp="2026-04-26T23-00-00Z",
        run_meta={"git": "0000"},
        samples=[make_sample(True), make_sample(False), make_pending_sample()],
    )
    write_run(run, eval_root=tmp_path)
    runs = sorted((tmp_path / "runs").iterdir())
    md = (runs[0] / "run.md").read_text()

    # ## Pending section must exist and contain the pending sample id
    assert "## Pending" in md
    assert "pending_a" in md

    # The summary counts must reflect only the 2 evaluated samples (1 pass + 1 fail)
    assert "- Total: 2" in md
    assert "- Passed: 1" in md
    assert "- Failed: 1" in md

    # pending_a must NOT appear in ## Per-field accuracy (no field_diff)
    # The per-field accuracy section should not reference pending_a at all
    per_field_idx = md.index("## Per-field accuracy")
    pending_idx = md.index("## Pending")
    # pending_a id should only appear after the ## Pending header
    assert md.index("pending_a") > pending_idx
