from unittest.mock import patch
from extraction_eval.parser_bridge import parse_model_output, render_prompt

def test_parse_model_output_returns_dict():
    fake = b'{"storeName":"Acme"}'
    with patch("extraction_eval.parser_bridge.subprocess.check_output", return_value=fake):
        assert parse_model_output("ignored", jar="/x.jar") == {"storeName": "Acme"}

def test_render_prompt_returns_string():
    fake = b"PROMPT TEXT"
    with patch("extraction_eval.parser_bridge.subprocess.check_output", return_value=fake):
        assert render_prompt({}, jar="/x.jar") == "PROMPT TEXT"
