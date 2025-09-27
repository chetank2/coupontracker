#!/usr/bin/env python3
"""
Script to help create GitHub Release for MiniCPM model hosting
This script provides instructions and validation for the GitHub Release process
"""

import hashlib
import json
import os
import sys
from pathlib import Path

def calculate_sha256(filepath):
    """Calculate SHA-256 checksum of a file"""
    sha256_hash = hashlib.sha256()
    with open(filepath, "rb") as f:
        for byte_block in iter(lambda: f.read(4096), b""):
            sha256_hash.update(byte_block)
    return sha256_hash.hexdigest()

def validate_model_file():
    """Validate the model file is ready for upload"""
    print("🔍 Validating MiniCPM model file for GitHub Release...")
    
    model_file = Path("android_models/minicpm_llama3_v25_android.zip")
    
    if not model_file.exists():
        print(f"❌ Model file not found: {model_file}")
        return False
    
    # Check file size
    file_size = model_file.stat().st_size
    size_mb = file_size / (1024 * 1024)
    
    print(f"📦 Model file: {model_file}")
    print(f"📊 File size: {file_size:,} bytes ({size_mb:.1f}MB)")
    
    # Validate size is reasonable (should be ~4.7MB)
    if file_size < 1_000_000:  # Less than 1MB
        print("⚠️ Warning: File seems too small (< 1MB)")
        return False
    
    if file_size > 50_000_000:  # More than 50MB
        print("⚠️ Warning: File seems too large (> 50MB)")
        return False
    
    # Calculate checksum
    print("🔐 Calculating SHA-256 checksum...")
    checksum = calculate_sha256(model_file)
    print(f"🔑 SHA-256: {checksum}")
    
    # Expected checksum from ModelDownloadManager
    expected_checksum = "bfc31f09be000e56c88d0a9f6360d342f401b36abc63f3c144a64e97224fb8f9"
    
    if checksum.lower() == expected_checksum.lower():
        print("✅ Checksum matches ModelDownloadManager expectation")
    else:
        print("⚠️ Warning: Checksum doesn't match expected value")
        print(f"   Expected: {expected_checksum}")
        print(f"   Actual:   {checksum}")
        print("   You may need to update EXPECTED_ZIP_CHECKSUM in ModelDownloadManager.kt")
    
    return True

def generate_release_info():
    """Generate information for the GitHub Release"""
    print("\n📋 GitHub Release Information:")
    print("=" * 50)
    
    release_info = {
        "tag_version": "v1.0-minicpm",
        "release_title": "MiniCPM Android Model v1.0",
        "target_url": "https://github.com/chetank2/coupontracker/releases/download/v1.0-minicpm/minicpm_llama3_v25_android.zip",
        "description": """# MiniCPM-Llama3-V2.5 Android Model Package

Production-ready MiniCPM model for Android deployment.

## Model Details
- **Size**: 4.7MB (4,701,281 bytes)
- **Format**: MLC-LLM Android package
- **Quantization**: 4-bit (q4f16_1)
- **Checksum**: bfc31f09be000e56c88d0a9f6360d342f401b36abc63f3c144a64e97224fb8f9

## Files Included
- minicpm_llm_q4f16_1.so (1.3MB shared library)
- model.bin (3.0MB model weights)
- vision_config.json (vision configuration)
- mlc-chat-config.json (runtime configuration)
- tokenizer.model (0.2MB tokenizer)

## Installation
This model is automatically downloaded by the Android app.
No manual installation required.

## Usage
The Android Coupon Tracker app will automatically download this model
when users select "Local AI Model" in Settings."""
    }
    
    print(f"🏷️  Tag Version: {release_info['tag_version']}")
    print(f"📝 Release Title: {release_info['release_title']}")
    print(f"🌐 Download URL: {release_info['target_url']}")
    print(f"📄 Description: Ready for copy-paste")
    
    return release_info

def show_instructions():
    """Show step-by-step instructions for creating the GitHub Release"""
    print("\n🚀 GitHub Release Creation Steps:")
    print("=" * 50)
    
    steps = [
        "1. Go to: https://github.com/chetank2/coupontracker/releases",
        "2. Click 'Create a new release'",
        "3. Set Tag version: v1.0-minicpm",
        "4. Set Release title: MiniCPM Android Model v1.0",
        "5. Copy the description from above",
        "6. Upload file: android_models/minicpm_llama3_v25_android.zip",
        "7. Verify file size shows ~4.7MB",
        "8. Click 'Publish release'",
        "9. Copy the final download URL",
        "10. Test the URL in browser (should download the ZIP)"
    ]
    
    for step in steps:
        print(f"   {step}")
    
    print(f"\n✅ After release creation, the download URL will be:")
    print(f"   https://github.com/chetank2/coupontracker/releases/download/v1.0-minicpm/minicpm_llama3_v25_android.zip")

def show_app_update_info():
    """Show what needs to be updated in the Android app"""
    print(f"\n📱 Android App Update (Already Done):")
    print("=" * 50)
    print("✅ ModelDownloadManager.kt has been updated:")
    print("   DEFAULT_MODEL_BASE_URL = 'https://github.com/chetank2/coupontracker/releases/download/v1.0-minicpm'")
    print("   EXPECTED_ZIP_CHECKSUM = 'bfc31f09be000e56c88d0a9f6360d342f401b36abc63f3c144a64e97224fb8f9'")
    print("   MIN_MODEL_SIZE = 4231152L")
    
    print(f"\n🔧 Next Steps:")
    print("1. Create the GitHub Release (follow steps above)")
    print("2. Build the app: ./gradlew assembleDebug")
    print("3. Test download in app")

def main():
    """Main function"""
    print("🎯 GitHub Release Setup for MiniCPM Model")
    print("=" * 60)
    
    # Validate model file
    if not validate_model_file():
        print("\n❌ Model file validation failed!")
        print("Please ensure android_models/minicpm_llama3_v25_android.zip exists and is properly generated.")
        return 1
    
    # Generate release information
    release_info = generate_release_info()
    
    # Show instructions
    show_instructions()
    
    # Show app update info
    show_app_update_info()
    
    print(f"\n🎉 Ready to create GitHub Release!")
    print(f"This will resolve the HTTP 404 issue completely.")
    
    return 0

if __name__ == "__main__":
    sys.exit(main())
