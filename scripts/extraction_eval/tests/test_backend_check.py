"""Tests for backend_check — inference-backend parity guard."""
from __future__ import annotations
import json
import pytest
from extraction_eval.backend_check import (
    assert_backend_parity,
    read_backend,
    BackendMismatch,
)


def write_report(path, payload):
    path.write_text(json.dumps(payload))


def test_read_backend_from_top_level_field(tmp_path):
    p = tmp_path / "report.json"
    write_report(p, {"llmBackend": "llama.cpp", "samples": []})
    assert read_backend(p) == "llama.cpp"


def test_read_backend_from_run_meta(tmp_path):
    p = tmp_path / "report.json"
    write_report(p, {"run_meta": {"llmBackend": "MLC"}, "samples": []})
    assert read_backend(p) == "MLC"


def test_read_backend_from_build_config_uppercase(tmp_path):
    """Android may emit BuildConfig.LLM_BACKEND verbatim (uppercase key)."""
    p = tmp_path / "report.json"
    write_report(p, {"buildConfig": {"LLM_BACKEND": "llama.cpp"}, "samples": []})
    assert read_backend(p) == "llama.cpp"


def test_read_backend_returns_none_when_absent(tmp_path):
    p = tmp_path / "report.json"
    write_report(p, {"samples": []})
    assert read_backend(p) is None


def test_assert_backend_parity_passes_on_match(tmp_path):
    p = tmp_path / "report.json"
    write_report(p, {"llmBackend": "llama.cpp", "samples": []})
    assert_backend_parity(p, expected="llama.cpp")  # no raise


def test_assert_backend_parity_is_case_insensitive(tmp_path):
    p = tmp_path / "report.json"
    write_report(p, {"llmBackend": "Llama.Cpp", "samples": []})
    assert_backend_parity(p, expected="llama.cpp")


def test_assert_backend_parity_raises_on_mismatch(tmp_path):
    p = tmp_path / "report.json"
    write_report(p, {"llmBackend": "MLC", "samples": []})
    with pytest.raises(BackendMismatch, match="parity target"):
        assert_backend_parity(p, expected="llama.cpp")


def test_assert_backend_parity_raises_when_label_missing(tmp_path):
    """An unlabelled report can't be trusted — the Android side hasn't been instrumented."""
    p = tmp_path / "report.json"
    write_report(p, {"samples": []})
    with pytest.raises(BackendMismatch, match="no llmBackend field"):
        assert_backend_parity(p, expected="llama.cpp")
