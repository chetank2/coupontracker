"""Top-level orchestration: load manifest, run pipeline per sample, write reports."""
from __future__ import annotations
from datetime import datetime, timezone
from pathlib import Path

from extraction_eval.diff import FieldStatus, compare_fields
from extraction_eval.llm import run_llm
from extraction_eval.manifest import load_manifest
from extraction_eval.parser_bridge import parse_model_output, render_prompt
from extraction_eval.preprocess import run_preprocess
from extraction_eval.report import RunResult, SampleResult, write_run
from extraction_eval.runtime_meta import collect_meta

def run_eval(
    *,
    manifest_path: Path,
    manifest_root: Path,
    eval_root: Path,
    jar: str,
    runtime_config_path: Path,
    binary: str,
    gguf: str,
    mmproj: str,
    grammar_path: str | None = None,
) -> Path:
    samples = load_manifest(manifest_path, root=manifest_root)
    meta = collect_meta(runtime_config_path=runtime_config_path, repo_root=Path.cwd())
    results: list[SampleResult] = []
    for s in samples:
        pre = run_preprocess(s.image_path, jar=jar)
        prompt = render_prompt({"text": "", "tiles": []}, jar=jar)
        llm = run_llm(
            binary=binary,
            gguf=gguf,
            mmproj=mmproj,
            image=s.image_path,
            prompt=prompt,
            grammar_path=grammar_path,
        )
        parsed = parse_model_output(llm.raw, jar=jar)
        if s.is_pending:
            results.append(SampleResult(
                id=s.id,
                image_sha256=s.image_sha256,
                image_path=str(s.image_path),
                expected=None,
                prompt_text=prompt,
                raw_model_output=llm.raw,
                parsed=parsed,
                preprocessed_image_sha256=pre.sha256,
                latency_ms=llm.latency_ms,
                field_diff=[],
                passed=False,
                pending=True,
            ))
        else:
            diffs = compare_fields(expected=s.expected, got=parsed)
            passed = all(d.status == FieldStatus.MATCH for d in diffs if d.field in s.expected)
            results.append(SampleResult(
                id=s.id,
                image_sha256=s.image_sha256,
                image_path=str(s.image_path),
                expected=s.expected,
                prompt_text=prompt,
                raw_model_output=llm.raw,
                parsed=parsed,
                preprocessed_image_sha256=pre.sha256,
                latency_ms=llm.latency_ms,
                field_diff=diffs,
                passed=passed,
            ))
    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")
    return write_run(RunResult(timestamp=timestamp, run_meta=meta, samples=results), eval_root=eval_root)
