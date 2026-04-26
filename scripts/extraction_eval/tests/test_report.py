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
