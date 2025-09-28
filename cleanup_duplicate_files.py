#!/usr/bin/env python3
"""
Duplicate Files Cleanup Script
Removes duplicate Python files and consolidates functionality.
"""

import os
import shutil
from pathlib import Path

class DuplicateCleanup:
    def __init__(self):
        self.duplicates_to_remove = [
            # Keep the main version, remove duplicates
            'utils/image_processor.py',  # Keep coupon-training/image_processor.py
            'update_app.py',            # Keep coupon-training/update_app.py  
            'train_model.py',           # Keep coupon-training/train_model.py
            'coupon_trainer_cli.py',    # Keep coupon-training/coupon_trainer_cli.py
            'coupon_annotator.py',      # Keep coupon-training/coupon_annotator.py
            'enhanced_field_extractor.py', # Keep coupon-training version
            'enhanced_image_processor.py', # Keep coupon-training version
            'enhanced_multi_coupon_trainer.py', # Keep coupon-training version
        ]
        
        self.unnecessary_root_files = [
            # These are development/testing files that can be removed
            'coupon_scraper.py',
            'evaluation_results.json',
            'smoke_test_results.json', 
            'test_model_download_integration.py',
            'test_settings_ui_integration.py',
            'verify_github_release.py',
            'create_github_release.py',
            'run_tests.sh'
        ]

    def remove_duplicate_files(self):
        """Remove duplicate files"""
        print("🔄 REMOVING DUPLICATE FILES")
        
        removed_count = 0
        removed_size = 0
        
        for file_path in self.duplicates_to_remove:
            path = Path(file_path)
            if path.exists():
                try:
                    size = path.stat().st_size
                    path.unlink()
                    print(f"   ✅ Removed: {file_path} ({size/1024:.1f} KB)")
                    removed_count += 1
                    removed_size += size
                except Exception as e:
                    print(f"   ❌ Failed to remove {file_path}: {e}")
        
        print(f"   📊 Removed {removed_count} duplicate files ({removed_size/1024:.1f} KB)")

    def remove_unnecessary_root_files(self):
        """Remove unnecessary files from root directory"""
        print("\n🗑️  REMOVING UNNECESSARY ROOT FILES")
        
        removed_count = 0
        removed_size = 0
        
        for file_path in self.unnecessary_root_files:
            path = Path(file_path)
            if path.exists():
                try:
                    size = path.stat().st_size
                    path.unlink()
                    print(f"   ✅ Removed: {file_path} ({size/1024:.1f} KB)")
                    removed_count += 1
                    removed_size += size
                except Exception as e:
                    print(f"   ❌ Failed to remove {file_path}: {e}")
        
        print(f"   📊 Removed {removed_count} unnecessary files ({removed_size/1024:.1f} KB)")

    def clean_empty_directories(self):
        """Remove empty directories"""
        print("\n📁 CLEANING EMPTY DIRECTORIES")
        
        removed_dirs = 0
        for root, dirs, files in os.walk('.', topdown=False):
            for dir_name in dirs:
                dir_path = os.path.join(root, dir_name)
                try:
                    if not os.listdir(dir_path):
                        os.rmdir(dir_path)
                        print(f"   🗑️  Removed empty directory: {dir_path}")
                        removed_dirs += 1
                except OSError:
                    pass
        
        print(f"   📊 Removed {removed_dirs} empty directories")

    def optimize_android_models(self):
        """Clean up android_models directory"""
        print("\n📱 OPTIMIZING ANDROID MODELS DIRECTORY")
        
        models_dir = Path('android_models')
        if not models_dir.exists():
            return
        
        # Keep only essential files
        essential_files = [
            'minicpm_llama3_v25_android.zip',
            'build_results.json',
            'conversion_plan.json'
        ]
        
        removed_count = 0
        for file_path in models_dir.rglob('*'):
            if file_path.is_file() and file_path.name not in essential_files:
                try:
                    size = file_path.stat().st_size
                    file_path.unlink()
                    print(f"   ✅ Removed: {file_path} ({size/1024:.1f} KB)")
                    removed_count += 1
                except Exception as e:
                    print(f"   ❌ Failed to remove {file_path}: {e}")
        
        print(f"   📊 Cleaned {removed_count} files from android_models/")

    def run_cleanup(self):
        """Execute duplicate cleanup"""
        print("🔄 DUPLICATE FILES CLEANUP")
        print("=" * 40)
        
        self.remove_duplicate_files()
        self.remove_unnecessary_root_files()
        self.optimize_android_models()
        self.clean_empty_directories()
        
        print(f"\n✅ DUPLICATE CLEANUP COMPLETE")
        print(f"   📊 Run 'find . -type f | wc -l' to check file count")
        print(f"   💾 Run 'du -sh .' to check repository size")

if __name__ == "__main__":
    cleanup = DuplicateCleanup()
    cleanup.run_cleanup()
