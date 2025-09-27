#!/usr/bin/env python3
"""
Integration test for Android MiniCPM model download functionality.
Tests the complete download pipeline without requiring Android emulator.
"""

import requests
import hashlib
import tempfile
import os
import json
from pathlib import Path

# Test configuration matching ModelDownloadManager.kt
GITHUB_RELEASE_URL = "https://github.com/chetank2/coupontracker/releases/download/v1.0-minicpm/minicpm_llama3_v25_android.zip"
EXPECTED_ZIP_CHECKSUM = "bfc31f09be000e56c88d0a9f6360d342f401b36abc63f3c144a64e97224fb8f9"
MIN_MODEL_SIZE = 4231152  # 4.03MB (90% of 4.7MB)
EXPECTED_SIZE = 4701281  # 4.7MB

# Expected model files with checksums (from ModelDownloadManager.kt)
REQUIRED_FILES = {
    "minicpm_llm_q4f16_1.so": "65d9139e97c5a196b48ae08facc468bcc41fef82ef1325ecab2c32e85e1fbbde",
    "model.bin": "94d7d225fbf28a20ec30534207ec1a0ea017a20cf25674cde166a6d4f0c7bad1",
    "vision_config.json": "a1e7efdfb761c86a3b1a323b3e859eb61718babb036ce66574d75528c33ebb6c",
    "mlc-chat-config.json": "c039de2a0c0ec44016207af64a896f7cd3b6940962709c9321d6c666ff6",
    "tokenizer.model": "fd635c2e01878a509339a2d4a269c3600531d0e2c8757b553ab4dee59a215869",
    "tokenizer.json": "ba0c892b641f9804451f900a0aa3227555545fdc5f4bd33702436c595b313cf5"
}

def calculate_sha256(file_path):
    """Calculate SHA-256 checksum of a file."""
    sha256_hash = hashlib.sha256()
    with open(file_path, "rb") as f:
        for byte_block in iter(lambda: f.read(4096), b""):
            sha256_hash.update(byte_block)
    return sha256_hash.hexdigest()

def test_download_availability():
    """Test that the GitHub Release URL is accessible."""
    print("🌐 Testing GitHub Release availability...")
    
    try:
        response = requests.head(GITHUB_RELEASE_URL, timeout=30, allow_redirects=True)
        if response.status_code == 200:
            content_length = response.headers.get('content-length')
            if content_length:
                size = int(content_length)
                print(f"✅ Release accessible: {size:,} bytes ({size/1024/1024:.1f}MB)")
                
                if size >= MIN_MODEL_SIZE:
                    print(f"✅ Size validation passed: {size:,} >= {MIN_MODEL_SIZE:,}")
                else:
                    print(f"❌ Size validation failed: {size:,} < {MIN_MODEL_SIZE:,}")
                    return False
                    
                return True
            else:
                print("⚠️ No content-length header")
                return False
        else:
            print(f"❌ Release not accessible: HTTP {response.status_code}")
            return False
    except Exception as e:
        print(f"❌ Network error: {e}")
        return False

def test_download_and_verify():
    """Download the model and verify checksum."""
    print("\n📦 Testing model download and verification...")
    
    with tempfile.TemporaryDirectory() as temp_dir:
        zip_path = Path(temp_dir) / "minicpm_model.zip"
        
        try:
            # Download the file
            print("⬇️ Downloading model...")
            response = requests.get(GITHUB_RELEASE_URL, timeout=120, stream=True)
            response.raise_for_status()
            
            downloaded_size = 0
            with open(zip_path, 'wb') as f:
                for chunk in response.iter_content(chunk_size=8192):
                    f.write(chunk)
                    downloaded_size += len(chunk)
            
            print(f"✅ Downloaded {downloaded_size:,} bytes")
            
            # Verify size
            if downloaded_size != EXPECTED_SIZE:
                print(f"❌ Size mismatch: expected {EXPECTED_SIZE:,}, got {downloaded_size:,}")
                return False
            
            # Verify checksum
            print("🔍 Verifying checksum...")
            actual_checksum = calculate_sha256(zip_path)
            
            if actual_checksum == EXPECTED_ZIP_CHECKSUM:
                print(f"✅ Checksum verified: {actual_checksum}")
                return True
            else:
                print(f"❌ Checksum mismatch:")
                print(f"   Expected: {EXPECTED_ZIP_CHECKSUM}")
                print(f"   Actual:   {actual_checksum}")
                return False
                
        except Exception as e:
            print(f"❌ Download error: {e}")
            return False

def test_android_app_constants():
    """Verify that Android app constants match our test expectations."""
    print("\n🔧 Verifying Android app constants...")
    
    # Read ModelDownloadManager.kt
    manager_path = Path("app/src/main/kotlin/com/example/coupontracker/llm/ModelDownloadManager.kt")
    
    if not manager_path.exists():
        print(f"❌ ModelDownloadManager.kt not found at {manager_path}")
        return False
    
    content = manager_path.read_text()
    
    # Check URL
    if GITHUB_RELEASE_URL.replace("/minicpm_llama3_v25_android.zip", "") in content:
        print("✅ GitHub Release URL matches")
    else:
        print("❌ GitHub Release URL mismatch in ModelDownloadManager.kt")
        return False
    
    # Check checksum
    if EXPECTED_ZIP_CHECKSUM in content:
        print("✅ Expected checksum matches")
    else:
        print("❌ Expected checksum mismatch in ModelDownloadManager.kt")
        return False
    
    # Check minimum size
    if str(MIN_MODEL_SIZE) in content:
        print("✅ Minimum model size matches")
    else:
        print("❌ Minimum model size mismatch in ModelDownloadManager.kt")
        return False
    
    return True

def main():
    """Run all integration tests."""
    print("🧪 MiniCPM Android Model Download Integration Test")
    print("=" * 60)
    
    tests = [
        ("GitHub Release Availability", test_download_availability),
        ("Download and Verification", test_download_and_verify),
        ("Android App Constants", test_android_app_constants)
    ]
    
    results = []
    for test_name, test_func in tests:
        print(f"\n📋 Running: {test_name}")
        try:
            success = test_func()
            results.append((test_name, success))
            if success:
                print(f"✅ {test_name}: PASSED")
            else:
                print(f"❌ {test_name}: FAILED")
        except Exception as e:
            print(f"❌ {test_name}: ERROR - {e}")
            results.append((test_name, False))
    
    # Summary
    print("\n" + "=" * 60)
    print("📊 TEST SUMMARY")
    print("=" * 60)
    
    passed = sum(1 for _, success in results if success)
    total = len(results)
    
    for test_name, success in results:
        status = "✅ PASS" if success else "❌ FAIL"
        print(f"{status} {test_name}")
    
    print(f"\nResult: {passed}/{total} tests passed")
    
    if passed == total:
        print("🎉 All tests passed! Model download functionality is working correctly.")
        return True
    else:
        print("⚠️ Some tests failed. Please review the issues above.")
        return False

if __name__ == "__main__":
    success = main()
    exit(0 if success else 1)
