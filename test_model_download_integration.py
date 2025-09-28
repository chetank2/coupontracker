#!/usr/bin/env python3
"""Integration test to verify MiniCPM release download integrity."""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path
from typing import Final
from urllib import error, request


RELEASE_BASE_URL: Final[str] = "https://github.com/chetank2/coupontracker/releases/download/v1.0-minicpm"
ZIP_NAME: Final[str] = "minicpm_llama3_v25_android.zip"
EXPECTED_ZIP_SIZE: Final[int] = 4_701_281
EXPECTED_ZIP_CHECKSUM: Final[str] = "bfc31f09be000e56c88d0a9f6360d342f401b36abc63f3c144a64e97224fb8f9"
KOTLIN_FILE: Final[Path] = Path(
    "app/src/main/kotlin/com/example/coupontracker/llm/ModelDownloadManager.kt"
)
EXPECTED_DEFAULT_BASE_URL: Final[str] = RELEASE_BASE_URL
EXPECTED_MIN_MODEL_SIZE: Final[int] = 4_231_152
HTTP_HEADERS: Final[dict[str, str]] = {
    "User-Agent": "CouponTrackerReleaseVerifier/1.0 (+https://github.com/chetank2/coupontracker)"
}


class VerificationError(RuntimeError):
    """Raised when a verification step fails."""


def _assert(condition: bool, message: str) -> None:
    if condition:
        print(f"✅ {message}")
    else:
        raise VerificationError(message)


def fetch_release_asset() -> Path:
    """Download the release asset and validate HTTP responses."""
    release_url = f"{RELEASE_BASE_URL}/{ZIP_NAME}"
    print("\n🔍 Verifying release URL...")
    try:
        head_request = request.Request(release_url, headers=HTTP_HEADERS, method="HEAD")
        with request.urlopen(head_request, timeout=15) as response:
            status_code = response.status
            content_length = response.headers.get("Content-Length")
    except error.URLError as exc:  # pragma: no cover - network failure
        raise VerificationError(f"Network error during HEAD request: {exc}") from exc

    _assert(status_code == 200, f"HEAD request returned HTTP {status_code}")
    _assert(
        content_length is not None,
        "Content-Length header is missing from HEAD response",
    )
    actual_size = int(content_length)
    _assert(
        actual_size == EXPECTED_ZIP_SIZE,
        f"Expected ZIP size {EXPECTED_ZIP_SIZE:,} bytes, got {actual_size:,} bytes",
    )

    download_path = Path("test_model_download.zip")
    if download_path.exists():
        download_path.unlink()

    print("\n⬇️  Downloading release asset...")
    try:
        get_request = request.Request(release_url, headers=HTTP_HEADERS)
        with request.urlopen(get_request, timeout=60) as response, download_path.open("wb") as f:
            while True:
                chunk = response.read(8192)
                if not chunk:
                    break
                f.write(chunk)
    except error.URLError as exc:  # pragma: no cover - network failure
        raise VerificationError(f"Download failed: {exc}") from exc

    downloaded_size = download_path.stat().st_size
    _assert(
        downloaded_size == EXPECTED_ZIP_SIZE,
        f"Downloaded size {downloaded_size:,} bytes does not match expected {EXPECTED_ZIP_SIZE:,} bytes",
    )

    return download_path


def validate_checksum(download_path: Path) -> None:
    print("\n🧮 Calculating SHA-256 checksum...")
    sha256 = hashlib.sha256()
    with download_path.open("rb") as f:
        for block in iter(lambda: f.read(4096), b""):
            sha256.update(block)
    checksum = sha256.hexdigest()
    _assert(
        checksum.lower() == EXPECTED_ZIP_CHECKSUM.lower(),
        "Downloaded checksum does not match EXPECTED_ZIP_CHECKSUM",
    )


def verify_kotlin_constants() -> None:
    print("\n📄 Verifying ModelDownloadManager constants...")
    if not KOTLIN_FILE.exists():
        raise VerificationError(f"Kotlin file not found: {KOTLIN_FILE}")

    content = KOTLIN_FILE.read_text(encoding="utf-8")

    def extract(pattern: str, description: str) -> str:
        import re

        match = re.search(pattern, content, re.MULTILINE)
        if not match:
            raise VerificationError(f"Could not locate {description} in ModelDownloadManager.kt")
        return match.group(1)

    default_base_url = extract(
        r"private const val\s+DEFAULT_MODEL_BASE_URL\s*=\s*\"([^\"]+)\"",
        "DEFAULT_MODEL_BASE_URL",
    )
    checksum = extract(
        r"private const val\s+EXPECTED_ZIP_CHECKSUM\s*=\s*\"([^\"]+)\"",
        "EXPECTED_ZIP_CHECKSUM",
    )
    min_model_size_str = extract(
        r"private const val\s+MIN_MODEL_SIZE\s*=\s*(\d+)",
        "MIN_MODEL_SIZE",
    )

    _assert(
        default_base_url == EXPECTED_DEFAULT_BASE_URL,
        "DEFAULT_MODEL_BASE_URL does not match release URL",
    )
    _assert(
        checksum.lower() == EXPECTED_ZIP_CHECKSUM.lower(),
        "EXPECTED_ZIP_CHECKSUM does not match expected hash",
    )
    _assert(
        int(min_model_size_str) == EXPECTED_MIN_MODEL_SIZE,
        "MIN_MODEL_SIZE does not match expected minimum size",
    )


def main() -> int:
    print("🧪 Running MiniCPM release integration checks")
    download_path = None
    try:
        download_path = fetch_release_asset()
        validate_checksum(download_path)
        verify_kotlin_constants()
    except VerificationError as exc:
        print(f"\n❌ Verification failed: {exc}")
        return 1
    except Exception as exc:  # pragma: no cover - unexpected failure
        print(f"\n❌ Unexpected error: {exc}")
        return 1
    finally:
        if download_path and download_path.exists():
            download_path.unlink()

    print("\n🎉 All integration checks passed!")
    return 0


if __name__ == "__main__":
    sys.exit(main())
