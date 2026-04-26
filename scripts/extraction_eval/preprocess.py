"""Invoke the JVM extraction-tool CLI to preprocess an image."""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
import json
import subprocess

@dataclass(frozen=True)
class PreprocessResult:
    width: int
    height: int
    sha256: str

def _invoke_jvm(jar: str, image_path: Path) -> bytes:
    with image_path.open("rb") as f:
        return subprocess.check_output(
            ["java", "-jar", jar, "preprocess", "--stdin"],
            stdin=f,
        )

def run_preprocess(image_path: Path, *, jar: str) -> PreprocessResult:
    raw = _invoke_jvm(jar, image_path)
    data = json.loads(raw)
    return PreprocessResult(
        width=int(data["width"]),
        height=int(data["height"]),
        sha256=str(data["sha256"]),
    )
