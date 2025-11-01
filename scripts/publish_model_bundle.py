#!/usr/bin/env python3
"""Publish verified model bundles to a connected Android device via adb.

The script performs the following steps:
1. Validates that the local bundle directory contains the expected assets.
2. Confirms the `.verified` sentinel exists and is non-empty.
3. Computes checksums for logging and confirmation.
4. Pushes the assets to `/data/data/<package>/files/models/<model_id>` using `adb`.

Example:
    python scripts/publish_model_bundle.py \
        --bundle android_models/qwen25_package \
        --model-id qwen25_1.5b_instruct_q4
"""

from __future__ import annotations

import argparse
import hashlib
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List

PACKAGE_NAME = "com.example.coupontracker"
DEFAULT_MODEL_ID = "qwen25_1.5b_instruct_q4"
ADB_COMMAND = os.environ.get("ADB", "adb")


@dataclass(frozen=True)
class AssetSpec:
    relative_path: str
    min_size_bytes: int
    description: str
    optional: bool = False


MODEL_LAYOUTS: Dict[str, List[AssetSpec]] = {
    "qwen25_1.5b_instruct_q4": [
        AssetSpec("Qwen2.5-1.5B-Instruct-Q4_K_M.gguf", 900_000_000, "Qwen2.5 GGUF weights"),
        AssetSpec("mlc-chat-config.json", 1_024, "Runtime configuration"),
        AssetSpec("tokenizer.json", 10_000, "Tokenizer"),
        AssetSpec("coupon_schema.gbnf", 128, "JSON grammar", optional=True),
        AssetSpec(".verified", 1, "Verification sentinel"),
    ],
    "qwen2_1.5b_instruct_q4": [
        AssetSpec("qwen2-1_5b-instruct-q4_k_m.gguf", 900_000_000, "Qwen2 GGUF weights"),
        AssetSpec("mlc-chat-config.json", 1_024, "Runtime configuration"),
        AssetSpec("tokenizer.json", 10_000, "Tokenizer"),
        AssetSpec(".verified", 1, "Verification sentinel"),
    ],
    "minicpm_llama3_v25_q4": [
        AssetSpec("ggml-model-Q4_K_M.gguf", 4_500_000_000, "MiniCPM GGUF weights"),
        AssetSpec("mmproj-model-f16.gguf", 800_000_000, "MiniCPM vision projector"),
        AssetSpec("mlc-chat-config.json", 1_024, "Runtime configuration"),
        AssetSpec("tokenizer.model", 100_000, "Tokenizer"),
        AssetSpec(".verified", 1, "Verification sentinel"),
    ],
}


class PublishError(RuntimeError):
    """Raised when the bundle cannot be published."""


def run_command(command: Iterable[str], *, capture_output: bool = False) -> subprocess.CompletedProcess:
    """Run an external command and raise a helpful error if it fails."""
    command = list(command)
    try:
        result = subprocess.run(
            command,
            check=True,
            capture_output=capture_output,
            text=True,
        )
        return result
    except subprocess.CalledProcessError as exc:  # pragma: no cover - manual invocations
        stdout = exc.stdout or ""
        stderr = exc.stderr or ""
        raise PublishError(
            f"Command {' '.join(command)} failed with exit code {exc.returncode}\n"
            f"stdout: {stdout}\n"
            f"stderr: {stderr}"
        ) from exc


def compute_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_bundle(bundle_dir: Path, model_id: str) -> Dict[str, str]:
    if model_id not in MODEL_LAYOUTS:
        raise PublishError(f"Unknown model id '{model_id}'. Known ids: {', '.join(sorted(MODEL_LAYOUTS))}")

    if not bundle_dir.exists() or not bundle_dir.is_dir():
        raise PublishError(f"Bundle directory does not exist: {bundle_dir}")

    checksums: Dict[str, str] = {}
    for spec in MODEL_LAYOUTS[model_id]:
        asset_path = bundle_dir / spec.relative_path
        if not asset_path.exists():
            if spec.optional:
                continue
            raise PublishError(
                f"Missing required asset '{spec.relative_path}' ({spec.description})"
            )
        size = asset_path.stat().st_size
        if size < spec.min_size_bytes:
            raise PublishError(
                f"Asset '{spec.relative_path}' is too small: {size} bytes (expected >= {spec.min_size_bytes})"
            )
        if asset_path.is_file() and size > 0:
            checksums[spec.relative_path] = compute_sha256(asset_path)
    return checksums


def adb_push_file(local_path: Path, remote_path: str) -> None:
    run_command([ADB_COMMAND, "push", str(local_path), remote_path])


def adb_run_as(package: str, *args: str) -> None:
    run_command([ADB_COMMAND, "shell", "run-as", package, *args])


def publish_bundle(bundle_dir: Path, model_id: str, package: str) -> None:
    print(f"📦 Publishing bundle for model '{model_id}' from {bundle_dir}")
    checksums = validate_bundle(bundle_dir, model_id)
    print("✅ Local bundle validated")

    remote_model_dir = f"files/models/{model_id}"
    staging_root = f"/data/local/tmp/{model_id}_bundle"

    # Prepare staging directory on device
    run_command([ADB_COMMAND, "shell", "rm", "-rf", staging_root])
    run_command([ADB_COMMAND, "shell", "mkdir", "-p", staging_root])

    # Push files to staging root
    for asset in MODEL_LAYOUTS[model_id]:
        if asset.optional:
            optional_path = bundle_dir / asset.relative_path
            if not optional_path.exists():
                continue
        local_path = bundle_dir / asset.relative_path
        remote_temp = f"{staging_root}/{asset.relative_path.replace('/', '_')}"
        adb_push_file(local_path, remote_temp)

    # Create target directory inside the app sandbox
    adb_run_as(package, "rm", "-rf", remote_model_dir)
    adb_run_as(package, "mkdir", "-p", remote_model_dir)

    # Move staged files into the sandbox
    for asset in MODEL_LAYOUTS[model_id]:
        local_exists = (bundle_dir / asset.relative_path).exists()
        if asset.optional and not local_exists:
            continue
        remote_temp = f"{staging_root}/{asset.relative_path.replace('/', '_')}"
        parent = os.path.dirname(asset.relative_path)
        if parent:
            adb_run_as(package, "mkdir", "-p", f"{remote_model_dir}/{parent}")
        adb_run_as(
            package,
            "cp",
            remote_temp,
            f"{remote_model_dir}/{asset.relative_path}"
        )

    # Clean up staging directory
    run_command([ADB_COMMAND, "shell", "rm", "-rf", staging_root])

    # Log remote directory listing
    listing = run_command(
        [
            ADB_COMMAND,
            "shell",
            "run-as",
            package,
            "ls",
            "-l",
            remote_model_dir,
        ],
        capture_output=True,
    )
    print("📂 Remote directory contents:")
    print(listing.stdout.strip())

    # Print checksums for confirmation
    print("\n🔐 Local asset checksums:")
    for path, checksum in checksums.items():
        print(f"  {path}: {checksum}")

    print("\n🚀 Bundle published successfully.")


def parse_args(argv: List[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Publish verified model bundle via adb")
    parser.add_argument(
        "--bundle",
        required=True,
        help="Path to the local directory containing verified model assets",
    )
    parser.add_argument(
        "--model-id",
        default=DEFAULT_MODEL_ID,
        help="Model identifier (default: %(default)s)",
    )
    parser.add_argument(
        "--package",
        default=PACKAGE_NAME,
        help="Android application id (default: %(default)s)",
    )
    return parser.parse_args(argv)


def main(argv: List[str]) -> int:
    try:
        args = parse_args(argv)
        publish_bundle(Path(args.bundle).resolve(), args.model_id, args.package)
        return 0
    except PublishError as exc:
        print(f"❌ {exc}", file=sys.stderr)
        return 1
    except FileNotFoundError as exc:
        print(f"❌ Missing file: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
