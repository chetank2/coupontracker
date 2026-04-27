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

def _mirror_meta(cfg: dict, repo_root: Path) -> dict:
    """Return mirror manifest SHA-256 and mirrored file entries, if manifest is configured."""
    manifest_rel = cfg.get("mirrorManifest")
    if not manifest_rel:
        return {"mirrorManifestSha256": None, "mirroredFiles": None}
    manifest_path = repo_root / manifest_rel
    if not manifest_path.exists():
        return {"mirrorManifestSha256": None, "mirroredFiles": None}
    manifest_sha = file_sha256(manifest_path)
    manifest_data = json.loads(manifest_path.read_text())
    mirrored = [
        {
            "androidSource": rec["androidSource"],
            "macPath": rec["macPath"],
            "sha256": rec["sha256"],
        }
        for rec in manifest_data.get("files", [])
    ]
    return {"mirrorManifestSha256": manifest_sha, "mirroredFiles": mirrored}


def collect_meta(*, runtime_config_path: Path, repo_root: Path) -> dict:
    cfg = json.loads(runtime_config_path.read_text())
    git_sha = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=repo_root
    ).decode().strip()
    meta = {
        "gitSha": git_sha,
        "host": f"{platform.system().lower()}-{platform.machine()}",
        "qwenGgufSha256": cfg["qwenGgufSha256"],
        "mmprojSha256": cfg["mmprojSha256"],
        "llamaCppCommit": cfg["llamaCppCommit"],
        "llamaCppBuildFlags": cfg["llamaCppBuildFlags"],
        "promptTemplateSha256": cfg.get("promptTemplateSha256"),
        "schemaSha256": cfg.get("schemaSha256"),
    }
    meta.update(_mirror_meta(cfg, repo_root))
    return meta
