"""Run pinned llama.cpp on a single image + prompt and capture raw output."""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
import subprocess
import time

@dataclass(frozen=True)
class LlmResult:
    raw: str
    stderr: str
    latency_ms: int
    return_code: int

def run_llm(
    *,
    binary: str,
    gguf: str,
    mmproj: str,
    image: Path,
    prompt: str,
    # Defaults match Android's LlmRuntimeManager.kt (temperature=0.1, topP=0.85).
    # Phase 0 parity work pinned these; do not change without verifying Android side.
    temp: float = 0.1,
    top_p: float = 0.85,
    seed: int = 42,
    n_predict: int = 512,
    grammar_file: str | None = "app/src/main/assets/coupon_schema.gbnf",
    grammar_path: str | None = None,
    extra_args: list[str] | None = None,
) -> LlmResult:
    start = time.monotonic()
    cmd = [
        binary,
        "-m", gguf,
        "--mmproj", mmproj,
        "--image", str(image),
        "-p", prompt,
        "--temp", str(temp),
        "--top-p", str(top_p),
        "--seed", str(seed),
        "-n", str(n_predict),
    ]
    # grammar_path (new, mirror-aware) takes precedence over legacy grammar_file.
    effective_grammar = grammar_path if grammar_path is not None else grammar_file
    if effective_grammar:
        cmd.extend(["--grammar-file", effective_grammar])
    if extra_args:
        cmd.extend(extra_args)
    proc = subprocess.run(
        cmd,
        capture_output=True,
        check=False,
    )
    elapsed_ms = int((time.monotonic() - start) * 1000)
    stderr = proc.stderr.decode("utf-8", errors="replace")
    if proc.returncode != 0:
        raise RuntimeError(
            "llama.cpp inference failed "
            f"(exit={proc.returncode}) for image {image}:\n{stderr[-4000:]}"
        )
    return LlmResult(
        raw=proc.stdout.decode("utf-8", errors="replace"),
        stderr=stderr,
        latency_ms=elapsed_ms,
        return_code=proc.returncode,
    )
