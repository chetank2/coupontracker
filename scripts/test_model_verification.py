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
import shutil
from pathlib import Path

def test_zip_checksum():
    """Test ZIP checksum verification"""
    print("🔍 Testing ZIP checksum verification...")
    
    zip_path = Path("android_models/minicpm_llama3_v25_android.zip")
    expected_checksum = "7a45f222885f84fd0160eeac794ad56be91c6139c436724a56627f16a93d1a76"
    
    if not zip_path.exists():
        print(f"❌ ZIP file not found: {zip_path}")
        return False
    
    with open(zip_path, 'rb') as f:
        content = f.read()
        actual_checksum = hashlib.sha256(content).hexdigest()
    
    if actual_checksum == expected_checksum:
        print(f"✅ ZIP checksum matches: {actual_checksum}")
        return True
    else:
        print(f"❌ ZIP checksum mismatch:")
        print(f"   Expected: {expected_checksum}")
        print(f"   Actual:   {actual_checksum}")
        return False

def test_individual_file_checksums():
    """Test individual file checksum verification"""
    print("\n🔍 Testing individual file checksums...")
    
    expected_files = {
        "minicpm_llm_q4f16_1.so": "65d9139e97c5a196b48ae08facc468bcc41fef82ef1325ecab2c32e85e1fbbde",
        "model.bin": "94d7d225fbf28a20ec30534207ec1a0ea017a20cf25674cde166a6d4f0c7bad1",
        "vision_config.json": "a1e7efdfb761c86a3b1a323b3e859eb61718babb036ce66574d75528c33ebb6c",
        "mlc-chat-config.json": "c039de2a0c0ec44016207af64a896f7cd3b6940962709c3e49c9321d6c666ff6",
        "tokenizer.model": "fd635c2e01878a509339a2d4a269c3600531d0e2c8757b553ab4dee59a215869"
    }
    
    zip_path = Path("android_models/minicpm_llama3_v25_android.zip")
    
    with tempfile.TemporaryDirectory() as temp_dir:
        # Extract ZIP
        with zipfile.ZipFile(zip_path, 'r') as zip_ref:
            zip_ref.extractall(temp_dir)
        
        models_dir = Path(temp_dir) / "models"
        if not models_dir.exists():
            print(f"❌ Models directory not found in ZIP")
            return False
        
        all_match = True
        for filename, expected_checksum in expected_files.items():
            file_path = models_dir / filename
            
            if not file_path.exists():
                print(f"❌ File not found: {filename}")
                all_match = False
                continue
            
            with open(file_path, 'rb') as f:
                content = f.read()
                actual_checksum = hashlib.sha256(content).hexdigest()
            
            if actual_checksum == expected_checksum:
                print(f"✅ {filename}: checksum matches")
            else:
                print(f"❌ {filename}: checksum mismatch")
                print(f"   Expected: {expected_checksum}")
                print(f"   Actual:   {actual_checksum}")
                all_match = False
        
        return all_match

def test_file_sizes():
    """Test file sizes match expectations"""
    print("\n🔍 Testing file sizes...")
    
    zip_path = Path("android_models/minicpm_llama3_v25_android.zip")
    
    with tempfile.TemporaryDirectory() as temp_dir:
        # Extract ZIP
        with zipfile.ZipFile(zip_path, 'r') as zip_ref:
            zip_ref.extractall(temp_dir)
        
        models_dir = Path(temp_dir) / "models"
        total_size = 0
        
        for file_path in models_dir.glob("*"):
            if file_path.is_file():
                size = file_path.stat().st_size
                size_mb = size / (1024 * 1024)
                total_size += size
                print(f"   {file_path.name}: {size_mb:.2f}MB")
        
        total_mb = total_size / (1024 * 1024)
        min_expected_mb = 4.03  # 90% of 4.48MB as per ModelDownloadManager
        
        print(f"\n📊 Total size: {total_mb:.2f}MB")
        print(f"   Minimum expected: {min_expected_mb}MB")
        
        if total_mb >= min_expected_mb:
            print(f"✅ Size validation passed")
            return True
        else:
            print(f"❌ Size validation failed")
            return False

def test_download_simulation():
    """Simulate download from local server"""
    print("\n🔍 Testing download simulation...")
    
    try:
        import urllib.request
        import urllib.error
        
        url = "http://127.0.0.1:8080/minicpm_llama3_v25_android.zip"
        
        # Test if server is accessible
        try:
            with urllib.request.urlopen(url) as response:
                content_length = response.headers.get('Content-Length')
                content_type = response.headers.get('Content-Type')
                
                print(f"✅ Server accessible at {url}")
                print(f"   Content-Length: {content_length} bytes")
                print(f"   Content-Type: {content_type}")
                
                # Download first 1KB to test
                data = response.read(1024)
                print(f"   Downloaded sample: {len(data)} bytes")
                
                return True
                
        except urllib.error.URLError as e:
            print(f"❌ Server not accessible: {e}")
            print("   Make sure the HTTP server is running:")
            print("   cd android_models && python3 -m http.server 8080")
            return False
            
    except ImportError:
        print("❌ urllib not available")
        return False

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
            result = test_func()
            results.append((test_name, result))
        except Exception as e:
            print(f"❌ {test_name} failed with error: {e}")
            results.append((test_name, False))
    
    print("\n" + "=" * 50)
    print("📋 Test Results Summary:")
    
    all_passed = True
    for test_name, passed in results:
        status = "✅ PASS" if passed else "❌ FAIL"
        print(f"   {status}: {test_name}")
        if not passed:
            all_passed = False
    
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
