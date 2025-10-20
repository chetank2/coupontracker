#!/usr/bin/env python3

"""
Test Model Verification Script
Tests the ModelDownloadManager verification logic with our mock artifacts
"""

import os
import sys
import hashlib
import zipfile
import tempfile
from pathlib import Path

import pytest

MODEL_ZIP_PATH = Path("android_models/minicpm_llama3_v25_android.zip")


def _require_model_zip() -> Path:
    if not MODEL_ZIP_PATH.exists():
        pytest.skip(f"Model ZIP not available at {MODEL_ZIP_PATH}")
    return MODEL_ZIP_PATH


def test_zip_checksum():
    """Verify the checksum of the packaged MiniCPM ZIP artifact."""
    print("🔍 Testing ZIP checksum verification...")

    zip_path = _require_model_zip()
    expected_checksum = "bfc31f09be000e56c88d0a9f6360d342f401b36abc63f3c144a64e97224fb8f9"

    with open(zip_path, "rb") as f:
        content = f.read()
    actual_checksum = hashlib.sha256(content).hexdigest()

    assert actual_checksum == expected_checksum, (
        "ZIP checksum mismatch.\n"
        f"Expected: {expected_checksum}\n"
        f"Actual:   {actual_checksum}"
    )
    print(f"✅ ZIP checksum matches: {actual_checksum}")

def test_individual_file_checksums():
    """Verify checksums for each extracted artifact within the ZIP."""
    print("\n🔍 Testing individual file checksums...")

    expected_files = {
        "minicpm_llm_q4f16_1.so": "65d9139e97c5a196b48ae08facc468bcc41fef82ef1325ecab2c32e85e1fbbde",
        "model.bin": "94d7d225fbf28a20ec30534207ec1a0ea017a20cf25674cde166a6d4f0c7bad1",
        "vision_config.json": "a1e7efdfb761c86a3b1a323b3e859eb61718babb036ce66574d75528c33ebb6c",
        "mlc-chat-config.json": "c039de2a0c0ec44016207af64a896f7cd3b6940962709c3e49c9321d6c666ff6",
        "tokenizer.model": "fd635c2e01878a509339a2d4a269c3600531d0e2c8757b553ab4dee59a215869",
    }

    zip_path = _require_model_zip()

    with tempfile.TemporaryDirectory() as temp_dir:
        with zipfile.ZipFile(zip_path, "r") as zip_ref:
            zip_ref.extractall(temp_dir)

        extraction_root = Path(temp_dir)

        for filename, expected_checksum in expected_files.items():
            file_path = extraction_root / filename
            assert file_path.exists(), f"Required model artifact missing: {filename}"

            with open(file_path, "rb") as f:
                content = f.read()
            actual_checksum = hashlib.sha256(content).hexdigest()

            assert actual_checksum == expected_checksum, (
                f"Checksum mismatch for {filename}.\n"
                f"Expected: {expected_checksum}\n"
                f"Actual:   {actual_checksum}"
            )
            print(f"✅ {filename}: checksum matches")

def test_file_sizes():
    """Ensure the extracted artifacts meet the minimum expected size budget."""
    print("\n🔍 Testing file sizes...")

    zip_path = _require_model_zip()

    with tempfile.TemporaryDirectory() as temp_dir:
        with zipfile.ZipFile(zip_path, "r") as zip_ref:
            zip_ref.extractall(temp_dir)

        extraction_root = Path(temp_dir)

        total_size = 0
        for file_path in extraction_root.glob("*"):
            if file_path.is_file():
                size = file_path.stat().st_size
                size_mb = size / (1024 * 1024)
                total_size += size
                print(f"   {file_path.name}: {size_mb:.2f}MB")

        total_mb = total_size / (1024 * 1024)
        min_expected_mb = 4.03  # 90% of 4.48MB as per ModelDownloadManager

        print(f"\n📊 Total size: {total_mb:.2f}MB")
        print(f"   Minimum expected: {min_expected_mb}MB")

        assert total_mb >= min_expected_mb, (
            "Model artifacts are smaller than expected.\n"
            f"Total: {total_mb:.2f}MB\n"
            f"Minimum expected: {min_expected_mb:.2f}MB"
        )
        print("✅ Size validation passed")

def test_download_simulation():
    """Ensure the lightweight HTTP download simulation succeeds when the server is running."""
    print("\n🔍 Testing download simulation...")

    try:
        import urllib.request
        import urllib.error
    except ImportError:  # pragma: no cover - urllib is part of stdlib but guard just in case
        pytest.skip("urllib not available in runtime environment")

    url = "http://127.0.0.1:8080/minicpm_llama3_v25_android.zip"

    try:
        with urllib.request.urlopen(url) as response:
            content_length = response.headers.get("Content-Length")
            content_type = response.headers.get("Content-Type")

            print(f"✅ Server accessible at {url}")
            print(f"   Content-Length: {content_length} bytes")
            print(f"   Content-Type: {content_type}")

            data = response.read(1024)
            print(f"   Downloaded sample: {len(data)} bytes")

            assert len(data) > 0, "Server responded but returned an empty payload"
    except urllib.error.URLError as e:
        pytest.skip(f"Local model HTTP server not running: {e}")

def main():
    """Run all verification tests"""
    print("🧪 MiniCPM Model Verification Tests")
    print("=" * 50)
    
    # Change to project root
    os.chdir(Path(__file__).parent.parent)
    
    tests = [
        ("ZIP Checksum", test_zip_checksum),
        ("Individual File Checksums", test_individual_file_checksums),
        ("File Sizes", test_file_sizes),
        ("Download Simulation", test_download_simulation)
    ]
    
    results = []
    for test_name, test_func in tests:
        try:
            test_func()
            results.append((test_name, True))
        except pytest.skip.Exception as skipped:
            print(f"⚠️ {test_name} skipped: {skipped}")
            results.append((test_name, None))
        except AssertionError as assertion_error:
            print(f"❌ {test_name} failed: {assertion_error}")
            results.append((test_name, False))
        except Exception as e:  # pragma: no cover - defensive guard for CLI usage
            print(f"❌ {test_name} failed with unexpected error: {e}")
            results.append((test_name, False))
    
    print("\n" + "=" * 50)
    print("📋 Test Results Summary:")
    
    all_passed = True
    for test_name, passed in results:
        if passed is None:
            status = "⚠️ SKIP"
        elif passed:
            status = "✅ PASS"
        else:
            status = "❌ FAIL"
            all_passed = False
        print(f"   {status}: {test_name}")
    
    print(f"\n🎯 Overall Result: {'✅ ALL TESTS PASSED' if all_passed else '❌ SOME TESTS FAILED'}")
    
    if all_passed:
        print("\n🚀 Mock artifacts are ready for Android integration!")
        print("   Next steps:")
        print("   1. Build and run the Android app")
        print("   2. Test the Settings > Local AI Model download")
        print("   3. Verify OCR engine selection works")
    
    return 0 if all_passed else 1

if __name__ == "__main__":
    sys.exit(main())
