"""Write run.json, run.md, latest.* pointers, and failures.json."""
from __future__ import annotations
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any
import json
import shutil

from extraction_eval.diff import FieldDiff
from extraction_eval.baseline import per_field_accuracy, ChangedField

@dataclass(frozen=True)
class SampleResult:
    id: str
    image_sha256: str
    image_path: str
    expected: dict | None
    prompt_text: str
    raw_model_output: str
    parsed: dict
    preprocessed_image_sha256: str
    latency_ms: int
    field_diff: list[FieldDiff]
    passed: bool
    pending: bool = False

@dataclass(frozen=True)
class RunResult:
    timestamp: str
    run_meta: dict
    samples: list[SampleResult]

def _sample_to_dict(s: SampleResult) -> dict:
    return {
        **{k: v for k, v in asdict(s).items() if k != "field_diff"},
        "field_diff": [{"field": d.field, "expected": d.expected, "got": d.got, "status": d.status.value} for d in s.field_diff],
        "pending": s.pending,
    }

def _run_to_dict(r: RunResult) -> dict:
    return {"timestamp": r.timestamp, "run_meta": r.run_meta, "samples": [_sample_to_dict(s) for s in r.samples]}

def _render_md(r: RunResult, *, eval_root: Path | None = None) -> str:
    evaluated = [s for s in r.samples if not s.pending]
    pending_samples = [s for s in r.samples if s.pending]

    lines = [
        f"# Extraction Eval — {r.timestamp}",
        "",
        f"- Total: {len(evaluated)}",
        f"- Passed: {sum(1 for s in evaluated if s.passed)}",
        f"- Failed: {sum(1 for s in evaluated if not s.passed)}",
        "",
        "| Sample | Pass | Latency ms | Failed fields |",
        "|---|---|---|---|",
    ]
    for s in evaluated:
        failed = [d.field for d in s.field_diff if d.status.value != "match"]
        lines.append(f"| {s.id} | {'✅' if s.passed else '❌'} | {s.latency_ms} | {', '.join(failed) if failed else '-'} |")

    # Per-field accuracy section (pending samples excluded)
    samples_as_dicts = [
        {"field_diff": [{"field": d.field, "status": d.status.value} for d in s.field_diff]}
        for s in evaluated
    ]
    acc = per_field_accuracy(samples_as_dicts)
    lines += ["", "## Per-field accuracy", "", "| Field | Pass | Total |", "|---|---|---|"]
    for field, (passed_count, total) in sorted(acc.items()):
        lines.append(f"| {field} | {passed_count} | {total} |")

    # Pending section
    if pending_samples:
        lines += ["", "## Pending", ""]
        lines.append("These samples have no expected block yet. Raw model output is recorded for human review.")
        lines.append("")
        for s in pending_samples:
            lines.append(f"### {s.id}")
            lines.append("")
            lines.append(f"**Image SHA256:** {s.image_sha256}")
            lines.append("")
            lines.append("**Parsed output:**")
            lines.append("```json")
            lines.append(json.dumps(s.parsed, indent=2))
            lines.append("```")
            lines.append("")

    # Changed since baseline section (only if baseline.json exists)
    if eval_root is not None:
        baseline_path = eval_root / "baseline.json"
        if baseline_path.exists():
            base = {s["id"]: s for s in json.loads(baseline_path.read_text())["samples"]}
            changes: list[ChangedField] = []
            for s in r.samples:
                if s.id not in base:
                    continue
                old = base[s.id].get("parsed", {})
                new = s.parsed
                for k in set(old) | set(new):
                    if old.get(k) != new.get(k):
                        changes.append(ChangedField(s.id, k, old.get(k), new.get(k)))
            lines += ["", "## Changed since baseline", ""]
            if changes:
                lines += ["| Sample | Field | Baseline | Latest |", "|---|---|---|---|"]
                for c in changes:
                    lines.append(f"| {c.id} | {c.field} | {c.baseline} | {c.latest} |")
            else:
                lines.append("_No changes since baseline._")

    return "\n".join(lines) + "\n"

def write_run(run: RunResult, *, eval_root: Path) -> Path:
    runs_dir = eval_root / "runs" / run.timestamp
    runs_dir.mkdir(parents=True, exist_ok=True)
    run_json = runs_dir / "run.json"
    run_md = runs_dir / "run.md"
    run_json.write_text(json.dumps(_run_to_dict(run), indent=2))
    run_md.write_text(_render_md(run, eval_root=eval_root))
    shutil.copyfile(run_json, eval_root / "latest.json")
    shutil.copyfile(run_md, eval_root / "latest.md")
    failures = RunResult(
        timestamp=run.timestamp,
        run_meta=run.run_meta,
        samples=[s for s in run.samples if not s.passed and not s.pending],
    )
    (eval_root / "failures.json").write_text(json.dumps(_run_to_dict(failures), indent=2))
    return runs_dir
