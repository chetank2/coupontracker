from pathlib import Path
from unittest.mock import patch
from extraction_eval.llm import LlmResult, run_llm

def test_run_llm_invokes_binary_and_captures_output():
    fake_stdout = b"MODEL OUTPUT TEXT"
    with patch("extraction_eval.llm.subprocess.run") as mock_run:
        mock_run.return_value.stdout = fake_stdout
        mock_run.return_value.stderr = b""
        mock_run.return_value.returncode = 0
        result = run_llm(
            binary="/x/llama-mtmd-cli",
            gguf="/x/qwen.gguf",
            mmproj="/x/mmproj.gguf",
            image=Path("/img.png"),
            prompt="Hello",
        )
    assert isinstance(result, LlmResult)
    assert result.raw == "MODEL OUTPUT TEXT"
    assert result.stderr == ""
    assert result.latency_ms >= 0
    cmd = mock_run.call_args.args[0]
    assert "--grammar-file" in cmd
    assert "app/src/main/assets/coupon_schema.gbnf" in cmd


def test_run_llm_grammar_path_propagated_when_set():
    """When grammar_path is provided it overrides the legacy grammar_file default."""
    fake_stdout = b"OUTPUT"
    with patch("extraction_eval.llm.subprocess.run") as mock_run:
        mock_run.return_value.stdout = fake_stdout
        mock_run.return_value.stderr = b""
        mock_run.return_value.returncode = 0
        run_llm(
            binary="/x/llama-mtmd-cli",
            gguf="/x/qwen.gguf",
            mmproj="/x/mmproj.gguf",
            image=Path("/img.png"),
            prompt="Hello",
            grammar_path="/mirror/coupon_schema.gbnf",
        )
    cmd = mock_run.call_args.args[0]
    assert "--grammar-file" in cmd
    idx = cmd.index("--grammar-file")
    assert cmd[idx + 1] == "/mirror/coupon_schema.gbnf"
    # Legacy default path must NOT appear when overridden
    assert "app/src/main/assets/coupon_schema.gbnf" not in cmd


def test_run_llm_grammar_path_not_appended_when_none():
    """When grammar_path=None and grammar_file=None, --grammar-file is omitted."""
    fake_stdout = b"OUTPUT"
    with patch("extraction_eval.llm.subprocess.run") as mock_run:
        mock_run.return_value.stdout = fake_stdout
        mock_run.return_value.stderr = b""
        mock_run.return_value.returncode = 0
        run_llm(
            binary="/x/llama-mtmd-cli",
            gguf="/x/qwen.gguf",
            mmproj="/x/mmproj.gguf",
            image=Path("/img.png"),
            prompt="Hello",
            grammar_file=None,
            grammar_path=None,
        )
    cmd = mock_run.call_args.args[0]
    assert "--grammar-file" not in cmd


def test_run_llm_appends_extra_args_after_core_options():
    with patch("extraction_eval.llm.subprocess.run") as mock_run:
        mock_run.return_value.stdout = b"OUTPUT"
        mock_run.return_value.stderr = b""
        mock_run.return_value.returncode = 0
        run_llm(
            binary="/x/llama-mtmd-cli",
            gguf="/x/qwen.gguf",
            mmproj="/x/mmproj.gguf",
            image=Path("/img.png"),
            prompt="Hello",
            extra_args=["--device", "none", "-ngl", "0"],
        )
    cmd = mock_run.call_args.args[0]
    assert cmd[-4:] == ["--device", "none", "-ngl", "0"]


def test_run_llm_defaults_match_android_sampling():
    """Verify Mac harness uses the same temperature/top_p as Android's LlmRuntimeManager.

    Android pins temperature=0.1f and topP=0.85f at LlmRuntimeManager.kt:455-457.
    Drifting these breaks Phase 0 parity.
    """
    with patch("extraction_eval.llm.subprocess.run") as mock_run:
        mock_run.return_value.stdout = b"OUTPUT"
        mock_run.return_value.stderr = b""
        mock_run.return_value.returncode = 0
        run_llm(
            binary="/x/llama-mtmd-cli",
            gguf="/x/qwen.gguf",
            mmproj="/x/mmproj.gguf",
            image=Path("/img.png"),
            prompt="Hello",
        )
    cmd = mock_run.call_args.args[0]
    temp_idx = cmd.index("--temp")
    assert cmd[temp_idx + 1] == "0.1"
    top_p_idx = cmd.index("--top-p")
    assert cmd[top_p_idx + 1] == "0.85"


def test_run_llm_raises_with_stderr_on_nonzero_exit():
    with patch("extraction_eval.llm.subprocess.run") as mock_run:
        mock_run.return_value.stdout = b""
        mock_run.return_value.stderr = b"metal failed"
        mock_run.return_value.returncode = 1
        try:
            run_llm(
                binary="/x/llama-mtmd-cli",
                gguf="/x/qwen.gguf",
                mmproj="/x/mmproj.gguf",
                image=Path("/img.png"),
                prompt="Hello",
            )
        except RuntimeError as exc:
            assert "metal failed" in str(exc)
            assert "exit=1" in str(exc)
        else:
            raise AssertionError("expected RuntimeError")
