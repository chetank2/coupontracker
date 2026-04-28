"""Inference-backend parity guard.

Mac harness always runs llama.cpp. Android can be built against either
llama.cpp (parity target) or MLC (in-flight migration). Output from MLC and
llama.cpp diverges even with identical model + prompt, so an Android smoke
report produced by an MLC build must not be compared field-for-field
against the Mac harness — the comparison would surface phantom diffs that
are actually engine differences, not extraction bugs.

Usage from compare_android.py::

    from extraction_eval.backend_check import assert_backend_parity
    assert_backend_parity(android_report_path, expected="llama.cpp")

The Android side is responsible for emitting ``llmBackend`` (or whichever
key it picks) in its smoke-report JSON. This helper centralises the
comparison so callers don't reinvent it.
"""
from __future__ import annotations
from pathlib import Path
from typing import Any
import json


class BackendMismatch(RuntimeError):
    """Raised when the Android report's backend doesn't match Mac's parity target."""


def read_backend(report_path: Path) -> str | None:
    """Read the LLM backend label from an Android smoke report, if present.

    Looks for a top-level ``llmBackend`` field first, then falls back to
    nested locations Android-side conventions might use. Returns None if
    no backend is recorded — that's a soft signal the Android report
    pre-dates the parity guard and must be regenerated.
    """
    data: Any = json.loads(Path(report_path).read_text())
    if isinstance(data, dict):
        if "llmBackend" in data:
            return str(data["llmBackend"])
        meta = data.get("run_meta") or data.get("buildConfig") or {}
        if isinstance(meta, dict) and "llmBackend" in meta:
            return str(meta["llmBackend"])
        if isinstance(meta, dict) and "LLM_BACKEND" in meta:
            return str(meta["LLM_BACKEND"])
    return None


def assert_backend_parity(report_path: Path, *, expected: str) -> None:
    """Raise BackendMismatch if the Android report wasn't produced by `expected`.

    Comparison is case-insensitive. Missing label is treated as a mismatch
    (the Android side hasn't been instrumented yet); regenerate the report
    after wiring BuildConfig.LLM_BACKEND.
    """
    actual = read_backend(report_path)
    if actual is None:
        raise BackendMismatch(
            f"Android report at {report_path} has no llmBackend field. "
            "Wire BuildConfig.LLM_BACKEND in app/build.gradle.kts and "
            "include it in the smoke-report JSON, then re-run the smoke."
        )
    if actual.casefold() != expected.casefold():
        raise BackendMismatch(
            f"Android report was produced by {actual!r}, but parity target "
            f"is {expected!r}. Rebuild Android with "
            f"`./scripts/build_native.sh llama` and re-run the smoke."
        )
