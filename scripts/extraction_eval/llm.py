"""Run pinned llama.cpp on a single image + prompt and capture raw output."""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
import subprocess
import time

@dataclass(frozen=True)
class LlmResult:
    raw: str
    latency_ms: int
    return_code: int

def run_llm(
    *,
    binary: str,
    gguf: str,
    mmproj: str,
    image: Path,
    prompt: str,
    temp: float = 0.0,
    seed: int = 42,
    n_predict: int = 512,
) -> LlmResult:
    start = time.monotonic()
    proc = subprocess.run(
        [
            binary,
            "-m", gguf,
            "--mmproj", mmproj,
            "--image", str(image),
            "-p", prompt,
            "--temp", str(temp),
            "--seed", str(seed),
            "-n", str(n_predict),
        ],
        capture_output=True,
        check=False,
    )
    elapsed_ms = int((time.monotonic() - start) * 1000)
    return LlmResult(
        raw=proc.stdout.decode("utf-8", errors="replace"),
        latency_ms=elapsed_ms,
        return_code=proc.returncode,
    )
