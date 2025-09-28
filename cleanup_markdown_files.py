#!/usr/bin/env python3
"""
Markdown Files Cleanup Script
Analyzes and cleans up unnecessary documentation files.
"""

import os
import shutil
from pathlib import Path

class MarkdownCleanup:
    def __init__(self):
        self.essential_files = {
            # Core documentation
            'README.md',
            'RELEASE_NOTES_v2.0.0.md',
            
            # Important docs folder
            'docs/IMPLEMENTATION_STATUS.md',
            'docs/LLM_INTEGRATION.md',
            
            # Third-party libraries (keep)
            'app/libs/mlc_llm/README.md',
            'coupon-training/README.md'
        }
        
        self.development_files = [
            # Development guides (can be archived)
            'ANDROID_DOWNLOAD_TEST_GUIDE.md',
            'COMPLETE_MULTI_COUPON_GUIDE.md', 
            'CREATE_GITHUB_RELEASE_NOW.md',
            'CREATE_PRODUCTION_MODEL.md',
            'DEPLOYMENT_GUIDE.md',
            'FINAL_TESTING_INSTRUCTIONS.md',
            'GITHUB_RELEASE_SETUP.md',
            'MINICPM_BUILD_GUIDE.md',
            'MINICPM_IMPLEMENTATION.md',
            
            # Status reports (outdated)
            'IMPLEMENTATION_SUMMARY.md',
            'NEXT_STEPS_SUMMARY.md',
            'bug_fixes_report.md',
            'fix_app_crashes.md',
            'coupon_flow_analysis_recommendations.md',
            
            # Duplicate/outdated READMEs
            'README_STANDARDIZED_PROCESS.md',
            'README-OCR-Improvements.md',
            'android_models/SETUP_INSTRUCTIONS.md',
            
            # Training documentation (can be consolidated)
            'coupon-training/README_INDIA.md',
            'coupon-training/README_OUTLIER_IMPROVEMENTS.md', 
            'coupon-training/README_STANDARDIZED_PROCESS.md',
            'coupon-training/data/coupon_summary.md',
            'coupon-training/data/sample_coupon_summary.md',
            'coupon-training/data_collection/README.md',
            'coupon-training/data_collection/scripts/README.md',
            'coupon-training/web_ui/README.md',
            
            # Outdated docs
            'docs/MLC_DEPLOYMENT_GUIDE.md',
            'docs/multi_coupon_model_delivery.md',
            
            # Mobile PWA (separate project)
            'mobile-coupon-trainer/README.md'
        ]
        
        self.cleanup_stats = {
            'archived': 0,
            'deleted': 0,
            'kept': 0,
            'errors': []
        }

    def create_archive_directory(self):
        """Create archive directory for development docs"""
        archive_dir = Path('docs/archive')
        archive_dir.mkdir(parents=True, exist_ok=True)
        return archive_dir

    def analyze_markdown_files(self):
        """Analyze all markdown files and categorize them"""
        print("📊 ANALYZING MARKDOWN FILES")
        print("=" * 50)
        
        all_md_files = []
        for root, dirs, files in os.walk('.'):
            # Skip virtual environments and build directories
            dirs[:] = [d for d in dirs if not d.startswith('.venv') and d != 'build']
            
            for file in files:
                if file.endswith('.md'):
                    file_path = os.path.relpath(os.path.join(root, file))
                    # Skip virtual environment files
                    if '.venv' not in file_path and 'web_ui_env' not in file_path:
                        all_md_files.append(file_path)
        
        print(f"📄 Found {len(all_md_files)} markdown files (excluding venv)")
        
        # Categorize files
        essential = []
        development = []
        unknown = []
        
        for file_path in all_md_files:
            if file_path in self.essential_files:
                essential.append(file_path)
            elif file_path in self.development_files:
                development.append(file_path)
            else:
                unknown.append(file_path)
        
        print(f"✅ Essential files: {len(essential)}")
        print(f"📚 Development files: {len(development)}")
        print(f"❓ Unknown files: {len(unknown)}")
        
        return essential, development, unknown

    def archive_development_docs(self, development_files):
        """Archive development documentation"""
        print("\n📚 ARCHIVING DEVELOPMENT DOCUMENTATION")
        
        archive_dir = self.create_archive_directory()
        
        for file_path in development_files:
            try:
                source = Path(file_path)
                if source.exists():
                    # Create archive filename
                    archive_name = file_path.replace('/', '_').replace('\\', '_')
                    destination = archive_dir / archive_name
                    
                    # Copy to archive
                    shutil.copy2(source, destination)
                    print(f"📦 Archived: {file_path} → docs/archive/{archive_name}")
                    
                    # Remove original
                    source.unlink()
                    self.cleanup_stats['archived'] += 1
                    
            except Exception as e:
                print(f"❌ Error archiving {file_path}: {e}")
                self.cleanup_stats['errors'].append(f"Archive {file_path}: {e}")

    def clean_empty_directories(self):
        """Remove empty directories after cleanup"""
        print("\n🧹 CLEANING EMPTY DIRECTORIES")
        
        empty_dirs = []
        for root, dirs, files in os.walk('.', topdown=False):
            for dir_name in dirs:
                dir_path = os.path.join(root, dir_name)
                try:
                    if not os.listdir(dir_path):  # Directory is empty
                        empty_dirs.append(dir_path)
                except OSError:
                    pass
        
        for dir_path in empty_dirs:
            try:
                os.rmdir(dir_path)
                print(f"🗑️  Removed empty directory: {dir_path}")
            except OSError as e:
                print(f"⚠️  Could not remove {dir_path}: {e}")

    def create_consolidated_readme(self):
        """Create a consolidated README for the docs folder"""
        print("\n📝 CREATING CONSOLIDATED DOCUMENTATION")
        
        docs_readme = Path('docs/README.md')
        content = """# CouponTracker Documentation

## 📚 Current Documentation

### Core Documentation
- **[Implementation Status](IMPLEMENTATION_STATUS.md)** - Current project status and branch information
- **[LLM Integration](LLM_INTEGRATION.md)** - MiniCPM-Llama3-V2.5 integration details

### Archived Documentation
The `archive/` folder contains historical development documentation that was used during the project development but is no longer actively maintained:

- Build guides and deployment instructions
- Development status reports and bug fix documentation  
- Training documentation and setup guides
- Implementation summaries and next steps

### Getting Started
For current setup and usage instructions, see the main [README.md](../README.md) in the project root.

### Release Information
For the latest release information, see [RELEASE_NOTES_v2.0.0.md](../RELEASE_NOTES_v2.0.0.md).
"""
        
        docs_readme.write_text(content)
        print(f"✅ Created consolidated docs/README.md")

    def generate_cleanup_report(self):
        """Generate cleanup summary"""
        print("\n" + "=" * 60)
        print("📊 MARKDOWN CLEANUP SUMMARY")
        print("=" * 60)
        
        print(f"📦 Files archived: {self.cleanup_stats['archived']}")
        print(f"🗑️  Files deleted: {self.cleanup_stats['deleted']}")
        print(f"✅ Files kept: {self.cleanup_stats['kept']}")
        print(f"❌ Errors: {len(self.cleanup_stats['errors'])}")
        
        if self.cleanup_stats['errors']:
            print(f"\n❌ ERRORS:")
            for error in self.cleanup_stats['errors']:
                print(f"   - {error}")
        
        # Check final count
        final_count = len(list(Path('.').rglob('*.md'))) - len(list(Path('.venv').rglob('*.md'))) if Path('.venv').exists() else len(list(Path('.').rglob('*.md')))
        print(f"\n📊 Remaining markdown files: {final_count}")
        
        if final_count <= 10:
            print("🎉 DOCUMENTATION SUCCESSFULLY STREAMLINED! 🚀")
            return True
        else:
            print("⚠️  DOCUMENTATION PARTIALLY CLEANED - REVIEW REMAINING FILES")
            return False

    def run_cleanup(self):
        """Execute markdown cleanup"""
        print("📝 COUPONTRACKER MARKDOWN CLEANUP")
        print("=" * 50)
        
        # Analyze files
        essential, development, unknown = self.analyze_markdown_files()
        
        # Show what will be kept
        print(f"\n✅ KEEPING ESSENTIAL FILES ({len(essential)}):")
        for file in essential:
            print(f"   📄 {file}")
            self.cleanup_stats['kept'] += 1
        
        # Archive development files
        if development:
            print(f"\n📚 ARCHIVING DEVELOPMENT FILES ({len(development)}):")
            for file in development:
                print(f"   📦 {file}")
            
            print(f"\nProceeding to archive {len(development)} development files...")
            self.archive_development_docs(development)
        
        # Handle unknown files
        if unknown:
            print(f"\n❓ UNKNOWN FILES ({len(unknown)}):")
            for file in unknown:
                print(f"   ❓ {file}")
            print("These files will be kept (manual review recommended)")
            self.cleanup_stats['kept'] += len(unknown)
        
        # Clean up empty directories
        self.clean_empty_directories()
        
        # Create consolidated documentation
        self.create_consolidated_readme()
        
        return self.generate_cleanup_report()

if __name__ == "__main__":
    cleanup = MarkdownCleanup()
    success = cleanup.run_cleanup()
    exit(0 if success else 1)
