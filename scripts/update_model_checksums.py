#!/usr/bin/env python3
"""
Update Model Checksums Script
Updates ModelDownloadManager.kt with real checksums from build results
"""

import json
import re
import sys
from pathlib import Path
from typing import Dict, Any

def load_build_results(build_results_path: str) -> Dict[str, Any]:
    """Load build results JSON"""
    try:
        with open(build_results_path, 'r') as f:
            return json.load(f)
    except Exception as e:
        print(f"❌ Failed to load build results: {e}")
        sys.exit(1)

def update_model_download_manager(build_results: Dict[str, Any], kotlin_file_path: str) -> bool:
    """Update ModelDownloadManager.kt with real checksums"""
    
    try:
        # Read the current Kotlin file
        kotlin_file = Path(kotlin_file_path)
        if not kotlin_file.exists():
            print(f"❌ Kotlin file not found: {kotlin_file_path}")
            return False
            
        content = kotlin_file.read_text()
        
        # Extract checksums from build results
        manifest = build_results.get('manifest', {})
        zip_checksum = build_results.get('zip_checksum', '')
        required_files = manifest.get('required_files', {})
        total_size_mb = manifest.get('total_size_mb', 0)
        
        print(f"📋 Updating checksums:")
        print(f"   - ZIP checksum: {zip_checksum}")
        print(f"   - Total size: {total_size_mb}MB")
        print(f"   - Required files: {len(required_files)}")
        
        # Update EXPECTED_ZIP_CHECKSUM
        content = re.sub(
            r'private const val EXPECTED_ZIP_CHECKSUM = "[^"]*"',
            f'private const val EXPECTED_ZIP_CHECKSUM = "{zip_checksum}"',
            content
        )
        
        # Update MIN_MODEL_SIZE (convert MB to bytes)
        min_size_bytes = int(total_size_mb * 1024 * 1024 * 0.8)  # 80% of actual size as minimum
        content = re.sub(
            r'private const val MIN_MODEL_SIZE = \d+L',
            f'private const val MIN_MODEL_SIZE = {min_size_bytes}L',
            content
        )
        
        # Update MODEL_VERSION
        model_version = manifest.get('model_version', 'v2.5-q4-android')
        content = re.sub(
            r'private const val MODEL_VERSION = "[^"]*"',
            f'private const val MODEL_VERSION = "{model_version}"',
            content
        )
        
        # Update REQUIRED_FILES map
        required_files_kotlin = "private val REQUIRED_FILES = mapOf(\n"
        for filename, checksum in required_files.items():
            required_files_kotlin += f'        "{filename}" to "{checksum}",\n'
        required_files_kotlin = required_files_kotlin.rstrip(',\n') + '\n    )'
        
        # Replace the REQUIRED_FILES map
        content = re.sub(
            r'private val REQUIRED_FILES = mapOf\([^}]+\)',
            required_files_kotlin,
            content,
            flags=re.DOTALL
        )
        
        # Write updated content back
        kotlin_file.write_text(content)
        
        print(f"✅ Updated {kotlin_file_path}")
        return True
        
    except Exception as e:
        print(f"❌ Failed to update Kotlin file: {e}")
        return False

def main():
    if len(sys.argv) != 3:
        print("Usage: python update_model_checksums.py <build_results.json> <ModelDownloadManager.kt>")
        sys.exit(1)
    
    build_results_path = sys.argv[1]
    kotlin_file_path = sys.argv[2]
    
    print(f"🔄 Updating model checksums...")
    print(f"   - Build results: {build_results_path}")
    print(f"   - Kotlin file: {kotlin_file_path}")
    
    # Load build results
    build_results = load_build_results(build_results_path)
    
    # Update Kotlin file
    success = update_model_download_manager(build_results, kotlin_file_path)
    
    if success:
        print("🎉 Model checksums updated successfully!")
        sys.exit(0)
    else:
        print("❌ Failed to update model checksums")
        sys.exit(1)

if __name__ == "__main__":
    main()