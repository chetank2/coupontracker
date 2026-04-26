"""Invoke the JVM extraction-tool CLI for parse and prompt."""
from __future__ import annotations
from pathlib import Path
import json
import subprocess

def parse_model_output(raw: str, *, jar: str) -> dict:
    out = subprocess.check_output(
        ["java", "-jar", jar, "parse", "--stdin"],
        input=raw.encode("utf-8"),
    )
    return json.loads(out)

def render_prompt(ocr_json: dict, *, jar: str) -> str:
    out = subprocess.check_output(
        ["java", "-jar", jar, "prompt", "--stdin"],
        input=json.dumps(ocr_json).encode("utf-8"),
    )
    return out.decode("utf-8")
