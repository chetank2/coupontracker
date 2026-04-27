from pathlib import Path
from unittest.mock import patch, MagicMock
from extraction_eval.runner import run_eval

def test_run_eval_invokes_pipeline_per_sample(tmp_path):
    manifest = tmp_path / "manifest.json"
    manifest.write_text('''{"schemaVersion":1,"samples":[
        {"id":"a","image":"a.png","imageSha256":"sha","expected":{"redeemCode":"X"}}
    ]}''')
    (tmp_path / "a.png").write_bytes(b"\x89PNG\r\n\x1a\n")
    eval_root = tmp_path / "eval"

    with patch("extraction_eval.runner.run_preprocess") as pre, \
         patch("extraction_eval.runner.render_prompt", return_value="P"), \
         patch("extraction_eval.runner.run_llm") as llm, \
         patch("extraction_eval.runner.parse_model_output", return_value={"redeemCode":"X"}), \
         patch("extraction_eval.runner.collect_meta", return_value={"gitSha":"deadbeef"}):
        pre.return_value = MagicMock(width=4, height=4, sha256="hh")
        llm.return_value = MagicMock(raw="O", latency_ms=10, return_code=0)
        run_eval(
            manifest_path=manifest,
            manifest_root=tmp_path,
            eval_root=eval_root,
            jar="/jar.jar",
            runtime_config_path=tmp_path / "runtime.json",
            binary="/x/binary",
            gguf="/x/qwen.gguf",
            mmproj="/x/mmproj.gguf",
        )

    assert (eval_root / "latest.json").exists()
    assert (eval_root / "failures.json").exists()

def test_run_eval_passes_ocr_sidecar_to_render_prompt(tmp_path):
    """render_prompt is called with the OCR payload from Sample.load_ocr."""
    manifest = tmp_path / "manifest.json"
    manifest.write_text('''{"schemaVersion":1,"samples":[
        {"id":"b","image":"b.png","imageSha256":"sha2","expected":{"redeemCode":"Y"}}
    ]}''')
    (tmp_path / "b.png").write_bytes(b"\x89PNG\r\n\x1a\n")
    eval_root = tmp_path / "eval"
    ocr_payload = {"text": "Kapiva discount", "tiles": [{"text": "Kapiva", "left": 0, "top": 0, "right": 100, "bottom": 30, "confidence": 0.9}]}

    with patch("extraction_eval.runner.run_preprocess") as pre, \
         patch("extraction_eval.runner.render_prompt", return_value="PROMPT") as mock_render, \
         patch("extraction_eval.runner.run_llm") as llm, \
         patch("extraction_eval.runner.parse_model_output", return_value={"redeemCode":"Y"}), \
         patch("extraction_eval.runner.collect_meta", return_value={"gitSha":"deadbeef"}), \
         patch("extraction_eval.manifest.Sample.load_ocr", return_value=ocr_payload):
        pre.return_value = MagicMock(width=4, height=4, sha256="hh")
        llm.return_value = MagicMock(raw="O", latency_ms=10, return_code=0)
        run_eval(
            manifest_path=manifest,
            manifest_root=tmp_path,
            eval_root=eval_root,
            jar="/jar.jar",
            runtime_config_path=tmp_path / "runtime.json",
            binary="/x/binary",
            gguf="/x/qwen.gguf",
            mmproj="/x/mmproj.gguf",
        )

    mock_render.assert_called_once_with(ocr_payload, jar="/jar.jar")
