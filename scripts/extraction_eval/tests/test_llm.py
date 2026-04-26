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
