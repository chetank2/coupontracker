#!/usr/bin/env python3
"""
Real MiniCPM Build Script - Phase 1 Implementation
Generates actual MLC-LLM artifacts with real checksums for ModelDownloadManager
"""

import os
import sys
import json
import time
import logging
from pathlib import Path

# Add the script directory to Python path
script_dir = Path(__file__).parent
sys.path.insert(0, str(script_dir))

from convert_minicpm_to_mobile import MiniCPMAndroidConverter

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger("real_minicpm_builder")

def check_prerequisites():
    """Check if all prerequisites are available"""
    logger.info("🔍 Checking prerequisites...")
    
    # Check Python packages
    required_packages = ['transformers', 'torch', 'mlc_llm', 'numpy', 'pillow']
    missing_packages = []
    
    for package in required_packages:
        try:
            __import__(package)
            logger.info(f"✅ {package} available")
        except ImportError:
            missing_packages.append(package)
            logger.error(f"❌ {package} missing")
    
    if missing_packages:
        logger.error(f"Missing packages: {missing_packages}")
        logger.info("Install with: pip install " + " ".join(missing_packages))
        return False
    
    # Check disk space (need ~10GB for build process)
    import shutil
    free_space_gb = shutil.disk_usage('.').free / (1024**3)
    if free_space_gb < 10:
        logger.error(f"❌ Insufficient disk space: {free_space_gb:.1f}GB (need 10GB+)")
        return False
    
    logger.info(f"✅ Disk space: {free_space_gb:.1f}GB available")
    return True

def build_real_minicpm():
    """Build real MiniCPM artifacts"""
    logger.info("🚀 Starting REAL MiniCPM build process...")
    
    if not check_prerequisites():
        logger.error("❌ Prerequisites not met. Aborting build.")
        return None
    
    try:
        # Initialize converter with output directory
        output_dir = "android_models"
        converter = MiniCPMAndroidConverter(output_dir)
        
        # Configure for mobile deployment
        converter.mobile_config['quantization'] = 'q4f16_1'
        converter.mobile_config['max_seq_len'] = 2048
        converter.mobile_config['max_image_size'] = (768, 768)
        
        logger.info("⚙️ Configuration:")
        logger.info(f"   - Quantization: {converter.mobile_config['quantization']}")
        logger.info(f"   - Max sequence length: {converter.mobile_config['max_seq_len']}")
        logger.info(f"   - Max image size: {converter.mobile_config['max_image_size']}")
        logger.info(f"   - Output directory: {output_dir}")
        
        # Run the conversion
        start_time = time.time()
        result = converter.convert_to_mobile()
        build_time = time.time() - start_time
        
        logger.info(f"✅ Build completed in {build_time:.1f} seconds")
        
        # Save build results
        results_file = Path(output_dir) / "build_results.json"
        with open(results_file, 'w') as f:
            json.dump(result, f, indent=2)
        
        logger.info(f"📄 Build results saved to: {results_file}")
        
        return result
        
    except Exception as e:
        logger.error(f"❌ Build failed: {e}")
        import traceback
        traceback.print_exc()
        return None

def update_model_download_manager(build_result):
    """Generate code to update ModelDownloadManager with real checksums"""
    if not build_result or not build_result.get("success"):
        logger.error("❌ Cannot update ModelDownloadManager - build failed")
        return
    
    logger.info("📝 Generating ModelDownloadManager updates...")
    
    # Generate the update code
    manifest = build_result["manifest"]
    
    update_code = f'''
// GENERATED CODE - Update ModelDownloadManager.kt with these values
// Generated at: {time.strftime("%Y-%m-%d %H:%M:%S")}

// Replace EXPECTED_ZIP_CHECKSUM:
private const val EXPECTED_ZIP_CHECKSUM = "{build_result["zip_checksum"]}"

// Replace REQUIRED_FILES:
private val REQUIRED_FILES = mapOf(
'''
    
    for filename, checksum in manifest["required_files"].items():
        update_code += f'    "{filename}" to "{checksum}",\n'
    
    update_code += f''')

// Replace MODEL_VERSION:
private const val MODEL_VERSION = "{manifest["model_version"]}"

// Replace MIN_MODEL_SIZE:
private const val MIN_MODEL_SIZE = {int(manifest["total_size_mb"] * 1024 * 1024)}L // {manifest["total_size_mb"]}MB

// Additional metadata:
// Build timestamp: {build_result["build_timestamp"]}
// MLC-LLM version: {manifest.get("mlc_llm_version", "unknown")}
// Total artifacts: {len(manifest["required_files"])}
'''
    
    # Save update code
    update_file = Path("android_models") / "model_download_manager_updates.kt"
    with open(update_file, 'w') as f:
        f.write(update_code)
    
    logger.info(f"📄 ModelDownloadManager updates saved to: {update_file}")
    
    # Print summary
    print("\n" + "="*80)
    print("🎯 PHASE 1 COMPLETE - REAL MINICPM PACKAGE GENERATED")
    print("="*80)
    print(f"📦 Package: {build_result['zip_path']}")
    print(f"🔐 ZIP Checksum: {build_result['zip_checksum']}")
    print(f"📊 Total Size: {manifest['total_size_mb']}MB")
    print(f"📁 Artifacts: {len(manifest['required_files'])} files")
    print(f"⏱️  Build Time: {time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(build_result['build_timestamp']))}")
    print("\n📝 Next Steps:")
    print("1. Copy checksums from model_download_manager_updates.kt into ModelDownloadManager.kt")
    print("2. Host the ZIP file and update MODEL_BASE_URL")
    print("3. Proceed to Phase 2 - Native Runtime Integration")
    print("="*80)

def main():
    """Main build process"""
    logger.info("🎯 PHASE 1: Produce and Ship Verified MiniCPM Package")
    
    # Build real MiniCPM artifacts
    build_result = build_real_minicpm()
    
    if build_result:
        # Generate ModelDownloadManager updates
        update_model_download_manager(build_result)
        
        logger.info("✅ Phase 1 completed successfully!")
        return 0
    else:
        logger.error("❌ Phase 1 failed!")
        return 1

if __name__ == "__main__":
    sys.exit(main())
