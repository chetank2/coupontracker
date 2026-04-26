from pathlib import Path
from unittest.mock import patch
from extraction_eval.preprocess import PreprocessResult, run_preprocess

def test_run_preprocess_parses_jvm_output():
    sample_image = Path(__file__).parent / "fixtures" / "mini_image.png"
    fake_json = b'{"width":800,"height":600,"sha256":"abc123"}'
    with patch("extraction_eval.preprocess._invoke_jvm", return_value=fake_json):
        result = run_preprocess(sample_image, jar="/x/y.jar")
    assert isinstance(result, PreprocessResult)
    assert result.width == 800
    assert result.height == 600
    assert result.sha256 == "abc123"
