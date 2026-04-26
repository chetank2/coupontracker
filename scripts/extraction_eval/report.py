"""Write run.json, run.md, latest.* pointers, and failures.json."""
from __future__ import annotations
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any
import json
import shutil

from extraction_eval.diff import FieldDiff

@dataclass(frozen=True)
class SampleResult:
    id: str
    image_sha256: str
    image_path: str
    expected: dict
    prompt_text: str
    raw_model_output: str
    parsed: dict
    preprocessed_image_sha256: str
    latency_ms: int
    field_diff: list[FieldDiff]
    passed: bool

@dataclass(frozen=True)
class RunResult:
    timestamp: str
    run_meta: dict
    samples: list[SampleResult]

def _sample_to_dict(s: SampleResult) -> dict:
    return {
        **{k: v for k, v in asdict(s).items() if k != "field_diff"},
        "field_diff": [{"field": d.field, "expected": d.expected, "got": d.got, "status": d.status.value} for d in s.field_diff],
    }

def _run_to_dict(r: RunResult) -> dict:
    return {"timestamp": r.timestamp, "run_meta": r.run_meta, "samples": [_sample_to_dict(s) for s in r.samples]}

def _render_md(r: RunResult) -> str:
    lines = [
        f"# Extraction Eval — {r.timestamp}",
        "",
        f"- Total: {len(r.samples)}",
        f"- Passed: {sum(1 for s in r.samples if s.passed)}",
        f"- Failed: {sum(1 for s in r.samples if not s.passed)}",
        "",
        "| Sample | Pass | Latency ms | Failed fields |",
        "|---|---|---|---|",
    ]
    for s in r.samples:
        failed = [d.field for d in s.field_diff if d.status.value != "match"]
        lines.append(f"| {s.id} | {'✅' if s.passed else '❌'} | {s.latency_ms} | {', '.join(failed) if failed else '-'} |")
    return "\n".join(lines) + "\n"

def write_run(run: RunResult, *, eval_root: Path) -> Path:
    runs_dir = eval_root / "runs" / run.timestamp
    runs_dir.mkdir(parents=True, exist_ok=True)
    run_json = runs_dir / "run.json"
    run_md = runs_dir / "run.md"
    run_json.write_text(json.dumps(_run_to_dict(run), indent=2))
    run_md.write_text(_render_md(run))
    shutil.copyfile(run_json, eval_root / "latest.json")
    shutil.copyfile(run_md, eval_root / "latest.md")
    failures = RunResult(
        timestamp=run.timestamp,
        run_meta=run.run_meta,
        samples=[s for s in run.samples if not s.passed],
    )
    (eval_root / "failures.json").write_text(json.dumps(_run_to_dict(failures), indent=2))
    return runs_dir
