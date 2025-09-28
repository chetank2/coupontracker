#!/usr/bin/env python3
"""
Script to verify the GitHub Release is working correctly
Run this after creating the GitHub Release
"""

import hashlib
import requests
import sys
from pathlib import Path

def test_github_release():
    """Test the GitHub Release URL and verify the download"""
    print("🧪 Testing GitHub Release for MiniCPM Model")
    print("=" * 60)
    
    # Expected values
    expected_url = "https://github.com/chetank2/coupontracker/releases/download/v1.0-minicpm/minicpm_llama3_v25_android.zip"
    expected_checksum = "bfc31f09be000e56c88d0a9f6360d342f401b36abc63f3c144a64e97224fb8f9"
    expected_size = 4701281
    
    print(f"🌐 Testing URL: {expected_url}")
    
    # Test 1: HEAD request to check if file exists (follow redirects)
    print("\n🔍 Step 1: Checking if release exists...")
    try:
        response = requests.head(expected_url, timeout=10, allow_redirects=True)
        
        if response.status_code == 200:
            content_length = int(response.headers.get('Content-Length', 0))
            print(f"✅ Release exists!")
            print(f"   Status: HTTP {response.status_code}")
            print(f"   Content-Length: {content_length:,} bytes ({content_length/1024/1024:.1f}MB)")
            
            # Check size
            if content_length == expected_size:
                print(f"✅ File size matches expected: {expected_size:,} bytes")
            else:
                print(f"⚠️ File size mismatch:")
                print(f"   Expected: {expected_size:,} bytes")
                print(f"   Actual: {content_length:,} bytes")
        
        elif response.status_code == 404:
            print("❌ Release not found (HTTP 404)")
            print("   The GitHub Release hasn't been created yet.")
            print("   Please follow the steps in CREATE_GITHUB_RELEASE_NOW.md")
            return False
        
        else:
            print(f"⚠️ Unexpected status: HTTP {response.status_code}")
            print(f"   Response headers: {dict(response.headers)}")
            return False
    
    except requests.exceptions.RequestException as e:
        print(f"❌ Network error: {e}")
        return False
    
    # Test 2: Download and verify checksum
    print("\n🔍 Step 2: Downloading and verifying checksum...")
    try:
        test_file = Path("test_github_download.zip")
        
        # Clean up any existing test file
        if test_file.exists():
            test_file.unlink()
        
        # Download the file
        print("   Downloading...")
        with requests.get(expected_url, stream=True, timeout=30, allow_redirects=True) as response:
            response.raise_for_status()
            
            with open(test_file, 'wb') as f:
                for chunk in response.iter_content(chunk_size=8192):
                    if chunk:
                        f.write(chunk)
        
        # Verify file was downloaded
        if not test_file.exists():
            print("❌ Download failed - file not found")
            return False
        
        actual_size = test_file.stat().st_size
        print(f"   Downloaded: {actual_size:,} bytes")
        
        # Calculate checksum
        print("   Calculating SHA-256...")
        sha256_hash = hashlib.sha256()
        with open(test_file, "rb") as f:
            for byte_block in iter(lambda: f.read(4096), b""):
                sha256_hash.update(byte_block)
        
        actual_checksum = sha256_hash.hexdigest()
        
        # Verify checksum
        if actual_checksum.lower() == expected_checksum.lower():
            print(f"✅ Checksum verified!")
            print(f"   SHA-256: {actual_checksum}")
        else:
            print(f"❌ Checksum mismatch!")
            print(f"   Expected: {expected_checksum}")
            print(f"   Actual: {actual_checksum}")
            return False
        
        # Clean up test file
        test_file.unlink()
        
    except Exception as e:
        print(f"❌ Download/verification failed: {e}")
        return False
    
    # Test 3: Verify Android app compatibility
    print("\n🔍 Step 3: Verifying Android app compatibility...")
    
    # Check if size meets minimum requirement
    min_size = 4231152  # From ModelDownloadManager
    if actual_size >= min_size:
        print(f"✅ Size requirement met: {actual_size:,} >= {min_size:,}")
    else:
        print(f"❌ Size requirement failed: {actual_size:,} < {min_size:,}")
        return False
    
    print("\n🎉 SUCCESS: GitHub Release is working correctly!")
    print("=" * 60)
    print("✅ Release exists and is downloadable")
    print("✅ File size matches expected value")
    print("✅ SHA-256 checksum verified")
    print("✅ Android app compatibility confirmed")
    print("\n🚀 The Android app should now download successfully!")
    print("   Next: Build and test the Android app")
    
    return True

def main():
    """Main function"""
    success = test_github_release()
    
    if not success:
        print("\n❌ GitHub Release verification failed!")
        print("Please check the issues above and ensure the release is properly created.")
        return 1
    
    return 0

if __name__ == "__main__":
    sys.exit(main())
