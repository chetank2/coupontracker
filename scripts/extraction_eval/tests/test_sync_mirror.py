"""Tests for sync_mirror.py — mirror creation, hash verification, failure modes."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from extraction_eval.sync_mirror import SyncEntry, sync_mirror


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def sha256_of(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_fake_asset(path: Path, content: bytes) -> str:
    """Write content to path (creating parents) and return its SHA-256."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    return sha256_of(content)


def make_manifest(entries: list[dict], manifest_path: Path) -> None:
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps({"schemaVersion": 1, "files": entries}))


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

def test_sync_creates_mirror_dir_and_copies_asset(tmp_path):
    """A single asset entry is copied to the mirror and hashes match."""
    content = b"fake grammar content"
    sha = write_fake_asset(tmp_path / "app/src/main/assets/coupon_schema.gbnf", content)

    entry = {
        "androidSource": "app/src/main/assets/coupon_schema.gbnf",
        "macPath": "models/extraction/android-mirror/coupon_schema.gbnf",
        "sha256": sha,
        "kind": "asset",
    }
    manifest_path = tmp_path / "config/extraction/android_mirror_manifest.json"
    make_manifest([entry], manifest_path)

    results = sync_mirror(
        project_root=tmp_path,
        manifest_path=Path("config/extraction/android_mirror_manifest.json"),
    )

    assert len(results) == 1
    r = results[0]
    assert r.status == "OK"
    mirror_file = tmp_path / "models/extraction/android-mirror/coupon_schema.gbnf"
    assert mirror_file.exists()
    assert mirror_file.read_bytes() == content


def test_sync_preserves_subdirectory_structure(tmp_path):
    """Subdirectories in macPath (e.g. prompt_templates/) are created."""
    content = b"template content"
    sha = write_fake_asset(
        tmp_path / "app/src/main/assets/prompt_templates/coupon_extraction_prompt.txt",
        content,
    )

    entry = {
        "androidSource": "app/src/main/assets/prompt_templates/coupon_extraction_prompt.txt",
        "macPath": "models/extraction/android-mirror/prompt_templates/coupon_extraction_prompt.txt",
        "sha256": sha,
        "kind": "asset",
    }
    manifest_path = tmp_path / "config/extraction/android_mirror_manifest.json"
    make_manifest([entry], manifest_path)

    results = sync_mirror(
        project_root=tmp_path,
        manifest_path=Path("config/extraction/android_mirror_manifest.json"),
    )

    assert results[0].status == "OK"
    mirror_file = (
        tmp_path
        / "models/extraction/android-mirror/prompt_templates/coupon_extraction_prompt.txt"
    )
    assert mirror_file.exists()
    assert mirror_file.read_bytes() == content


def test_sync_creates_symlink_for_model_kind(tmp_path):
    """A model entry produces a symlink (not a copy) pointing at the source."""
    content = b"\x00" * 1024  # fake GGUF bytes
    sha = write_fake_asset(tmp_path / "models/extraction/ggml-model-Q4_K_M.gguf", content)

    entry = {
        "androidSource": "models/extraction/ggml-model-Q4_K_M.gguf",
        "macPath": "models/extraction/android-mirror/ggml-model-Q4_K_M.gguf",
        "sha256": sha,
        "kind": "model",
    }
    manifest_path = tmp_path / "config/extraction/android_mirror_manifest.json"
    make_manifest([entry], manifest_path)

    results = sync_mirror(
        project_root=tmp_path,
        manifest_path=Path("config/extraction/android_mirror_manifest.json"),
    )

    assert results[0].status == "OK"
    mirror_link = tmp_path / "models/extraction/android-mirror/ggml-model-Q4_K_M.gguf"
    assert mirror_link.is_symlink()
    assert mirror_link.resolve().read_bytes() == content


def test_sync_fails_on_stale_manifest(tmp_path):
    """If the source file hash differs from the manifest value, sync exits non-zero."""
    content = b"original content"
    write_fake_asset(tmp_path / "app/src/main/assets/coupon_schema.gbnf", content)
    wrong_sha = sha256_of(b"different content")

    entry = {
        "androidSource": "app/src/main/assets/coupon_schema.gbnf",
        "macPath": "models/extraction/android-mirror/coupon_schema.gbnf",
        "sha256": wrong_sha,  # stale
        "kind": "asset",
    }
    manifest_path = tmp_path / "config/extraction/android_mirror_manifest.json"
    make_manifest([entry], manifest_path)

    with pytest.raises(SystemExit) as exc_info:
        sync_mirror(
            project_root=tmp_path,
            manifest_path=Path("config/extraction/android_mirror_manifest.json"),
        )
    assert exc_info.value.code != 0


def test_sync_fails_when_source_missing(tmp_path):
    """If an androidSource file is absent, sync exits non-zero."""
    entry = {
        "androidSource": "app/src/main/assets/nonexistent_file.json",
        "macPath": "models/extraction/android-mirror/nonexistent_file.json",
        "sha256": "a" * 64,
        "kind": "asset",
    }
    manifest_path = tmp_path / "config/extraction/android_mirror_manifest.json"
    make_manifest([entry], manifest_path)

    with pytest.raises(SystemExit) as exc_info:
        sync_mirror(
            project_root=tmp_path,
            manifest_path=Path("config/extraction/android_mirror_manifest.json"),
        )
    assert exc_info.value.code != 0


def test_sync_multiple_entries_all_ok(tmp_path):
    """Multiple entries of mixed kinds all succeed."""
    asset_content = b"grammar"
    asset_sha = write_fake_asset(tmp_path / "app/src/main/assets/coupon_schema.gbnf", asset_content)

    model_content = b"\xff\xfe" * 512
    model_sha = write_fake_asset(tmp_path / "models/extraction/model.gguf", model_content)

    entries = [
        {
            "androidSource": "app/src/main/assets/coupon_schema.gbnf",
            "macPath": "models/extraction/android-mirror/coupon_schema.gbnf",
            "sha256": asset_sha,
            "kind": "asset",
        },
        {
            "androidSource": "models/extraction/model.gguf",
            "macPath": "models/extraction/android-mirror/model.gguf",
            "sha256": model_sha,
            "kind": "model",
        },
    ]
    manifest_path = tmp_path / "config/extraction/android_mirror_manifest.json"
    make_manifest(entries, manifest_path)

    results = sync_mirror(
        project_root=tmp_path,
        manifest_path=Path("config/extraction/android_mirror_manifest.json"),
    )

    assert len(results) == 2
    assert all(r.status == "OK" for r in results)
