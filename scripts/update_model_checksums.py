#!/usr/bin/env python3
"""
Update ModelDownloadManager with real checksums from build artifacts
"""

import json
import sys
from pathlib import Path

def update_model_download_manager(build_results_file: str, model_manager_file: str):
    """Update ModelDownloadManager.kt with real checksums"""
    
    # Load build results
    with open(build_results_file, 'r') as f:
        build_result = json.load(f)
    
    if not build_result.get("success"):
        print("❌ Build results indicate failure - cannot update checksums")
        return False
    
    manifest = build_result["manifest"]
    
    # Read current ModelDownloadManager
    with open(model_manager_file, 'r') as f:
        content = f.read()
    
    # Update EXPECTED_ZIP_CHECKSUM
    old_checksum_line = [line for line in content.split('\n') if 'EXPECTED_ZIP_CHECKSUM' in line and 'private const val' in line]
    if old_checksum_line:
        old_line = old_checksum_line[0]
        new_line = f'        private const val EXPECTED_ZIP_CHECKSUM = "{build_result["zip_checksum"]}"'
        content = content.replace(old_line, new_line)
        print(f"✅ Updated EXPECTED_ZIP_CHECKSUM")
    
    # Update REQUIRED_FILES
    # Find the REQUIRED_FILES map
    start_marker = "private val REQUIRED_FILES = mapOf("
    end_marker = ")"
    
    start_idx = content.find(start_marker)
    if start_idx != -1:
        # Find the end of the map
        bracket_count = 0
        end_idx = start_idx + len(start_marker)
        
        for i, char in enumerate(content[end_idx:], end_idx):
            if char == '(':
                bracket_count += 1
            elif char == ')':
                if bracket_count == 0:
                    end_idx = i + 1
                    break
                bracket_count -= 1
        
        # Generate new REQUIRED_FILES content
        new_files_content = "private val REQUIRED_FILES = mapOf(\n"
        for filename, checksum in manifest["required_files"].items():
            new_files_content += f'            "{filename}" to "{checksum}",\n'
        new_files_content += "        )"
        
        # Replace the old content
        old_content = content[start_idx:end_idx]
        content = content.replace(old_content, new_files_content)
        print(f"✅ Updated REQUIRED_FILES with {len(manifest['required_files'])} files")
    
    # Update MODEL_VERSION
    old_version_line = [line for line in content.split('\n') if 'MODEL_VERSION' in line and 'private const val' in line]
    if old_version_line:
        old_line = old_version_line[0]
        new_line = f'        private const val MODEL_VERSION = "{manifest["model_version"]}"'
        content = content.replace(old_line, new_line)
        print(f"✅ Updated MODEL_VERSION to {manifest['model_version']}")
    
    # Update MIN_MODEL_SIZE
    old_size_line = [line for line in content.split('\n') if 'MIN_MODEL_SIZE' in line and 'private const val' in line]
    if old_size_line:
        old_line = old_size_line[0]
        size_bytes = int(manifest["total_size_mb"] * 1024 * 1024)
        new_line = f'        private const val MIN_MODEL_SIZE = {size_bytes}L // {manifest["total_size_mb"]}MB'
        content = content.replace(old_line, new_line)
        print(f"✅ Updated MIN_MODEL_SIZE to {manifest['total_size_mb']}MB")
    
    # Write updated content
    with open(model_manager_file, 'w') as f:
        f.write(content)
    
    print(f"\n🎯 ModelDownloadManager.kt updated with real checksums!")
    print(f"📦 ZIP Checksum: {build_result['zip_checksum']}")
    print(f"📁 {len(manifest['required_files'])} file checksums updated")
    print(f"📊 Package size: {manifest['total_size_mb']}MB")
    
    return True

def main():
    if len(sys.argv) != 3:
        print("Usage: python update_model_checksums.py <build_results.json> <ModelDownloadManager.kt>")
        return 1
    
    build_results_file = sys.argv[1]
    model_manager_file = sys.argv[2]
    
    if not Path(build_results_file).exists():
        print(f"❌ Build results file not found: {build_results_file}")
        return 1
    
    if not Path(model_manager_file).exists():
        print(f"❌ ModelDownloadManager file not found: {model_manager_file}")
        return 1
    
    success = update_model_download_manager(build_results_file, model_manager_file)
    return 0 if success else 1

if __name__ == "__main__":
    sys.exit(main())
