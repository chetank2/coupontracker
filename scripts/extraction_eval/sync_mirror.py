"""Sync Android extraction assets to the Mac-side mirror directory.

Usage (standalone):
    python -m extraction_eval.sync_mirror [--project-root ROOT] [--manifest PATH]

The script:
  1. Reads the android_mirror_manifest.json.
  2. Creates models/extraction/android-mirror/ (and any subdirs) if absent.
  3. For each manifest entry:
       kind=asset  -> cp -f  androidSource  macPath
       kind=model  -> ln -sf (absolute symlink) androidSource -> macPath
  4. Verifies that the SHA-256 of the *source* file matches the manifest value
     (stale manifest detection).
  5. Verifies that the SHA-256 of the *mirror* file (resolving symlinks) matches
     the source (copy/symlink integrity).
  6. Prints a STATUS table and exits 0 on full success, non-zero on any failure.

Public API (for testing):
    sync_mirror(project_root: Path, manifest_path: Path) -> list[SyncEntry]
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path


# ---------------------------------------------------------------------------
# Data types
# ---------------------------------------------------------------------------

@dataclass
class SyncEntry:
    android_source: str
    mac_path: str
    expected_sha256: str
    kind: str
    # filled in during sync
    source_sha256: str = ""
    mirror_sha256: str = ""
    status: str = "PENDING"   # OK | STALE_MANIFEST | INTEGRITY_FAIL | ERROR
    size_bytes: int = 0
    error_msg: str = ""


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _size_of(path: Path) -> int:
    return path.stat().st_size


def _human_size(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if abs(n) < 1024.0 or unit == "GB":
            return f"{n:.1f} {unit}" if unit != "B" else f"{n} B"
        n /= 1024.0
    return str(n)  # unreachable but satisfies type checker


# ---------------------------------------------------------------------------
# Core sync logic
# ---------------------------------------------------------------------------

def sync_mirror(
    project_root: Path,
    manifest_path: Path,
) -> list[SyncEntry]:
    """Perform the sync and return a list of SyncEntry with results populated.

    Raises SystemExit with a non-zero code on any failure after printing the
    full table so the caller can see all results.
    """
    manifest_abs = (project_root / manifest_path) if not manifest_path.is_absolute() else manifest_path
    manifest_data = json.loads(manifest_abs.read_text())

    entries: list[SyncEntry] = []
    for rec in manifest_data["files"]:
        entries.append(SyncEntry(
            android_source=rec["androidSource"],
            mac_path=rec["macPath"],
            expected_sha256=rec["sha256"],
            kind=rec["kind"],
        ))

    any_failure = False

    for entry in entries:
        src = project_root / entry.android_source
        dst = project_root / entry.mac_path

        try:
            # Ensure destination directory exists.
            dst.parent.mkdir(parents=True, exist_ok=True)

            # -- 1. Verify source hash matches manifest --
            if not src.exists():
                entry.status = "ERROR"
                entry.error_msg = f"source not found: {src}"
                any_failure = True
                continue

            entry.source_sha256 = _sha256_of(src)
            entry.size_bytes = _size_of(src)

            if entry.source_sha256 != entry.expected_sha256:
                entry.status = "STALE_MANIFEST"
                entry.error_msg = (
                    f"source SHA {entry.source_sha256[:16]}… != manifest SHA "
                    f"{entry.expected_sha256[:16]}… — rerun audit to refresh manifest"
                )
                any_failure = True
                continue

            # -- 2. Copy or symlink --
            if entry.kind == "asset":
                shutil.copy2(str(src), str(dst))
            else:
                # model: absolute symlink to avoid gigabyte duplication
                src_abs = src.resolve()
                if dst.is_symlink() or dst.exists():
                    dst.unlink()
                dst.symlink_to(src_abs)

            # -- 3. Verify mirror hash (resolve symlinks) --
            resolved_dst = dst.resolve()
            entry.mirror_sha256 = _sha256_of(resolved_dst)

            if entry.mirror_sha256 != entry.source_sha256:
                entry.status = "INTEGRITY_FAIL"
                entry.error_msg = (
                    f"mirror SHA {entry.mirror_sha256[:16]}… != source SHA "
                    f"{entry.source_sha256[:16]}…"
                )
                any_failure = True
                continue

            entry.status = "OK"

        except Exception as exc:  # pylint: disable=broad-except
            entry.status = "ERROR"
            entry.error_msg = str(exc)
            any_failure = True

    # -- Print table --
    _print_table(entries)

    if any_failure:
        print("\nFAIL: one or more files did not sync cleanly.", file=sys.stderr)
        sys.exit(1)

    return entries


# ---------------------------------------------------------------------------
# Table formatter
# ---------------------------------------------------------------------------

def _print_table(entries: list[SyncEntry]) -> None:
    header = f"{'STATUS':<18}  {'KIND':<6}  {'PATH':<60}  {'SIZE':>10}  {'SHA256_PREFIX':<20}"
    sep = "-" * len(header)
    print(sep)
    print(header)
    print(sep)
    for e in entries:
        sha_prefix = e.mirror_sha256[:16] + "…" if e.mirror_sha256 else e.expected_sha256[:16] + "…"
        size_str = _human_size(e.size_bytes) if e.size_bytes else "—"
        status_col = e.status
        if e.status != "OK":
            status_col += f"  [{e.error_msg[:40]}]" if e.error_msg else ""
        print(f"{status_col:<18}  {e.kind:<6}  {e.mac_path:<60}  {size_str:>10}  {sha_prefix:<20}")
    print(sep)
    ok_count = sum(1 for e in entries if e.status == "OK")
    print(f"Synced {ok_count}/{len(entries)} files OK.")


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

def _main(argv: list[str] | None = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(
        description="Sync Android extraction assets to models/extraction/android-mirror/"
    )
    parser.add_argument(
        "--project-root",
        default=".",
        help="Repository root (default: cwd)",
    )
    parser.add_argument(
        "--manifest",
        default="config/extraction/android_mirror_manifest.json",
        help="Path to android_mirror_manifest.json (relative to project-root)",
    )
    args = parser.parse_args(argv)

    project_root = Path(args.project_root).resolve()
    manifest_path = Path(args.manifest)

    sync_mirror(project_root=project_root, manifest_path=manifest_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())
