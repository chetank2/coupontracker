from pathlib import Path
from unittest.mock import patch
from extraction_eval.llm import LlmResult, run_llm

def test_run_llm_invokes_binary_and_captures_output():
    fake_stdout = b"MODEL OUTPUT TEXT"
    with patch("extraction_eval.llm.subprocess.run") as mock_run:
        mock_run.return_value.stdout = fake_stdout
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
    assert result.latency_ms >= 0
    cmd = mock_run.call_args.args[0]
    assert "--grammar-file" in cmd
    assert "app/src/main/assets/coupon_schema.gbnf" in cmd


def test_run_llm_grammar_path_propagated_when_set():
    """When grammar_path is provided it overrides the legacy grammar_file default."""
    fake_stdout = b"OUTPUT"
    with patch("extraction_eval.llm.subprocess.run") as mock_run:
        mock_run.return_value.stdout = fake_stdout
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
