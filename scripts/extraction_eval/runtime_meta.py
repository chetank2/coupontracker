"""Collect run-level metadata for reproducibility."""
from __future__ import annotations
from pathlib import Path
import hashlib
import json
import platform
import subprocess

def file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def collect_meta(*, runtime_config_path: Path, repo_root: Path) -> dict:
    cfg = json.loads(runtime_config_path.read_text())
    git_sha = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=repo_root
    ).decode().strip()
    return {
        "gitSha": git_sha,
        "host": f"{platform.system().lower()}-{platform.machine()}",
        "qwenGgufSha256": cfg["qwenGgufSha256"],
        "mmprojSha256": cfg["mmprojSha256"],
        "llamaCppCommit": cfg["llamaCppCommit"],
        "llamaCppBuildFlags": cfg["llamaCppBuildFlags"],
        "promptTemplateSha256": cfg.get("promptTemplateSha256"),
        "schemaSha256": cfg.get("schemaSha256"),
    }
