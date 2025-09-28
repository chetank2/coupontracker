#!/usr/bin/env python3
"""
Comprehensive File Analysis Script
Analyzes all files and folders to identify unnecessary content for cleanup.
"""

import os
import sys
from pathlib import Path
import subprocess
from collections import defaultdict

class FileAnalyzer:
    def __init__(self):
        self.file_categories = {
            'build_artifacts': [],
            'virtual_environments': [],
            'cache_files': [],
            'log_files': [],
            'temporary_files': [],
            'duplicate_files': [],
            'large_files': [],
            'unnecessary_data': [],
            'test_artifacts': [],
            'development_files': []
        }
        
        self.size_stats = {}
        self.total_files = 0
        self.total_size = 0
        
        # Patterns for unnecessary files
        self.unnecessary_patterns = {
            'build_artifacts': [
                'app/build/**',
                'app/.cxx/**',
                '**/*.apk',
                '**/*.aab',
                '**/build/**',
                '**/.gradle/**',
                '**/gradle-wrapper.jar'
            ],
            'virtual_environments': [
                '.venv/**',
                'coupon-training/web_ui_env/**',
                '**/__pycache__/**',
                '**/*.pyc',
                '**/*.pyo'
            ],
            'cache_files': [
                '**/.DS_Store',
                '**/Thumbs.db',
                '**/*.tmp',
                '**/*.cache',
                '**/node_modules/**'
            ],
            'log_files': [
                '**/*.log',
                '**/logs/**',
                'coupon-training/coupon_tracker*.log'
            ],
            'temporary_files': [
                '**/*~',
                '**/*.bak',
                '**/*.swp',
                '**/*.orig',
                '**/temp/**',
                '**/tmp/**'
            ],
            'test_artifacts': [
                'smoke_test_results.json',
                'evaluation_results.json',
                'deployment_validation_metrics.json',
                'logs/evaluation_*.json'
            ],
            'development_files': [
                'local.properties',
                '**/.idea/**',
                '**/.vscode/**',
                '**/debug.keystore'
            ]
        }

    def get_directory_size(self, path):
        """Get directory size in bytes"""
        try:
            result = subprocess.run(['du', '-sb', str(path)], 
                                  capture_output=True, text=True)
            if result.returncode == 0:
                return int(result.stdout.split()[0])
        except:
            pass
        return 0

    def analyze_file_sizes(self):
        """Analyze file sizes and identify large files"""
        print("📊 ANALYZING FILE SIZES")
        
        large_files = []
        for root, dirs, files in os.walk('.'):
            # Skip certain directories
            dirs[:] = [d for d in dirs if not d.startswith('.git')]
            
            for file in files:
                file_path = Path(root) / file
                try:
                    size = file_path.stat().st_size
                    self.total_files += 1
                    self.total_size += size
                    
                    # Files larger than 10MB
                    if size > 10 * 1024 * 1024:
                        large_files.append((str(file_path), size))
                        
                except (OSError, PermissionError):
                    continue
        
        # Sort by size
        large_files.sort(key=lambda x: x[1], reverse=True)
        self.file_categories['large_files'] = large_files[:20]  # Top 20
        
        print(f"   📄 Total files: {self.total_files:,}")
        print(f"   💾 Total size: {self.total_size / (1024**3):.1f} GB")
        print(f"   🔍 Large files (>10MB): {len(large_files)}")

    def analyze_directory_sizes(self):
        """Analyze directory sizes"""
        print("\n📁 ANALYZING DIRECTORY SIZES")
        
        directories = []
        for item in Path('.').iterdir():
            if item.is_dir() and not item.name.startswith('.git'):
                size = self.get_directory_size(item)
                if size > 0:
                    directories.append((str(item), size))
        
        directories.sort(key=lambda x: x[1], reverse=True)
        
        print("   🔝 Largest directories:")
        for dir_path, size in directories[:10]:
            size_mb = size / (1024**2)
            print(f"      {size_mb:>8.1f} MB  {dir_path}")
        
        return directories

    def categorize_unnecessary_files(self):
        """Categorize files that can be removed"""
        print("\n🗑️  CATEGORIZING UNNECESSARY FILES")
        
        # Build artifacts
        build_dirs = [
            'app/build',
            'app/.cxx', 
            '.gradle'
        ]
        
        for build_dir in build_dirs:
            if Path(build_dir).exists():
                size = self.get_directory_size(build_dir)
                self.file_categories['build_artifacts'].append((build_dir, size))
        
        # Virtual environments
        venv_dirs = [
            '.venv',
            'coupon-training/web_ui_env'
        ]
        
        for venv_dir in venv_dirs:
            if Path(venv_dir).exists():
                size = self.get_directory_size(venv_dir)
                self.file_categories['virtual_environments'].append((venv_dir, size))
        
        # Cache and temporary files
        cache_patterns = [
            '**/__pycache__',
            '**/*.pyc',
            '**/.DS_Store',
            '**/Thumbs.db'
        ]
        
        for pattern in cache_patterns:
            for file_path in Path('.').rglob(pattern.split('/')[-1]):
                if '__pycache__' in str(file_path) or file_path.suffix in ['.pyc', '.pyo']:
                    try:
                        size = file_path.stat().st_size
                        self.file_categories['cache_files'].append((str(file_path), size))
                    except:
                        pass
        
        # Log files
        for log_file in Path('.').rglob('*.log'):
            try:
                size = log_file.stat().st_size
                self.file_categories['log_files'].append((str(log_file), size))
            except:
                pass
        
        # Test and development artifacts
        dev_files = [
            'local.properties',
            'smoke_test_results.json',
            'evaluation_results.json',
            'deployment_validation_metrics.json'
        ]
        
        for dev_file in dev_files:
            file_path = Path(dev_file)
            if file_path.exists():
                try:
                    size = file_path.stat().st_size
                    self.file_categories['development_files'].append((str(file_path), size))
                except:
                    pass

    def analyze_duplicate_content(self):
        """Analyze potential duplicate content"""
        print("\n🔍 ANALYZING DUPLICATE CONTENT")
        
        # Check for duplicate Python files
        python_files = defaultdict(list)
        for py_file in Path('.').rglob('*.py'):
            if '.venv' not in str(py_file) and 'web_ui_env' not in str(py_file):
                python_files[py_file.name].append(str(py_file))
        
        duplicates = {name: paths for name, paths in python_files.items() if len(paths) > 1}
        
        print(f"   🔄 Potential duplicate Python files: {len(duplicates)}")
        for name, paths in list(duplicates.items())[:5]:
            print(f"      📄 {name}: {len(paths)} copies")
            for path in paths[:3]:
                print(f"         - {path}")
        
        self.file_categories['duplicate_files'] = duplicates

    def analyze_data_directories(self):
        """Analyze data directories for unnecessary content"""
        print("\n📊 ANALYZING DATA DIRECTORIES")
        
        data_dirs = [
            'coupon-training/data',
            'coupon-training/data_collection',
            'models',
            'static/uploads',
            'android_models'
        ]
        
        for data_dir in data_dirs:
            dir_path = Path(data_dir)
            if dir_path.exists():
                size = self.get_directory_size(dir_path)
                file_count = len(list(dir_path.rglob('*')))
                print(f"   📁 {data_dir}: {size/(1024**2):.1f} MB, {file_count} files")
                
                # Check for large data files
                for file_path in dir_path.rglob('*'):
                    if file_path.is_file():
                        try:
                            size = file_path.stat().st_size
                            if size > 5 * 1024 * 1024:  # >5MB
                                self.file_categories['unnecessary_data'].append((str(file_path), size))
                        except:
                            pass

    def generate_cleanup_recommendations(self):
        """Generate cleanup recommendations"""
        print("\n" + "=" * 70)
        print("🧹 CLEANUP RECOMMENDATIONS")
        print("=" * 70)
        
        total_removable_size = 0
        total_removable_files = 0
        
        categories = [
            ('build_artifacts', '🏗️  Build Artifacts', True),
            ('virtual_environments', '🐍 Virtual Environments', False),
            ('cache_files', '💾 Cache Files', True),
            ('log_files', '📝 Log Files', True),
            ('development_files', '🔧 Development Files', True),
            ('test_artifacts', '🧪 Test Artifacts', True),
            ('large_files', '📦 Large Files', False),
            ('unnecessary_data', '📊 Unnecessary Data', False)
        ]
        
        for category, title, auto_remove in categories:
            files = self.file_categories[category]
            if files:
                category_size = sum(item[1] if isinstance(item, tuple) else 0 for item in files)
                category_count = len(files)
                
                print(f"\n{title}:")
                print(f"   📊 Count: {category_count}")
                print(f"   💾 Size: {category_size / (1024**2):.1f} MB")
                print(f"   🗑️  Auto-remove: {'✅ YES' if auto_remove else '⚠️  MANUAL REVIEW'}")
                
                if auto_remove:
                    total_removable_size += category_size
                    total_removable_files += category_count
                
                # Show examples
                for i, item in enumerate(files[:3]):
                    if isinstance(item, tuple):
                        path, size = item
                        print(f"      📄 {path} ({size / (1024**2):.1f} MB)")
                    else:
                        print(f"      📄 {item}")
                
                if len(files) > 3:
                    print(f"      ... and {len(files) - 3} more")
        
        print(f"\n📈 CLEANUP POTENTIAL:")
        print(f"   🗑️  Auto-removable files: {total_removable_files:,}")
        print(f"   💾 Auto-removable size: {total_removable_size / (1024**3):.2f} GB")
        print(f"   📊 Size reduction: {(total_removable_size / self.total_size) * 100:.1f}%")
        
        return total_removable_size, total_removable_files

    def create_cleanup_script(self):
        """Create automated cleanup script"""
        print(f"\n📝 CREATING CLEANUP SCRIPT")
        
        cleanup_script = """#!/bin/bash
# Automated Repository Cleanup Script
# Generated by analyze_unnecessary_files.py

echo "🧹 Starting repository cleanup..."

# Remove build artifacts
echo "🏗️  Removing build artifacts..."
rm -rf app/build/
rm -rf app/.cxx/
rm -rf .gradle/
find . -name "*.apk" -delete
find . -name "*.aab" -delete

# Remove cache files
echo "💾 Removing cache files..."
find . -name "__pycache__" -type d -exec rm -rf {} + 2>/dev/null
find . -name "*.pyc" -delete
find . -name "*.pyo" -delete
find . -name ".DS_Store" -delete
find . -name "Thumbs.db" -delete

# Remove log files
echo "📝 Removing log files..."
find . -name "*.log" -delete
rm -rf logs/

# Remove development artifacts
echo "🔧 Removing development artifacts..."
rm -f smoke_test_results.json
rm -f evaluation_results.json
rm -f deployment_validation_metrics.json

# Remove temporary files
echo "🗑️  Removing temporary files..."
find . -name "*~" -delete
find . -name "*.bak" -delete
find . -name "*.swp" -delete
find . -name "*.orig" -delete

echo "✅ Cleanup completed!"
echo "📊 Run 'du -sh .' to check new repository size"
"""
        
        with open('cleanup_repository_files.sh', 'w') as f:
            f.write(cleanup_script)
        
        os.chmod('cleanup_repository_files.sh', 0o755)
        print("   ✅ Created cleanup_repository_files.sh")

    def run_analysis(self):
        """Run complete file analysis"""
        print("🔍 COUPONTRACKER REPOSITORY FILE ANALYSIS")
        print("=" * 60)
        
        self.analyze_file_sizes()
        self.analyze_directory_sizes()
        self.categorize_unnecessary_files()
        self.analyze_duplicate_content()
        self.analyze_data_directories()
        
        removable_size, removable_files = self.generate_cleanup_recommendations()
        self.create_cleanup_script()
        
        print(f"\n🎯 ANALYSIS COMPLETE")
        print(f"   📊 Repository can be reduced by {removable_size / (1024**3):.2f} GB")
        print(f"   🗑️  {removable_files:,} files can be safely removed")
        print(f"   📝 Run './cleanup_repository_files.sh' to execute cleanup")
        
        return removable_size > 100 * 1024 * 1024  # Return True if >100MB can be saved

if __name__ == "__main__":
    analyzer = FileAnalyzer()
    needs_cleanup = analyzer.run_analysis()
    sys.exit(0 if needs_cleanup else 1)
