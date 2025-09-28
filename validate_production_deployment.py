#!/usr/bin/env python3
"""
Production Deployment Validation Script
Validates that the CouponTracker v2.0.0 production deployment is working correctly.
"""

import os
import sys
import hashlib
import requests
import subprocess
from pathlib import Path

class ProductionValidator:
    def __init__(self):
        self.base_url = "https://github.com/chetank2/coupontracker"
        self.expected_model_hash = "bfc31f09be000e56c88d0a9f6360d342f401b36abc63f3c144a64e97224fb8f9"
        self.expected_model_size = 4701281
        self.validation_results = []

    def log_result(self, test_name, passed, message):
        """Log validation result"""
        status = "✅ PASS" if passed else "❌ FAIL"
        print(f"{status} {test_name}: {message}")
        self.validation_results.append({
            'test': test_name,
            'passed': passed,
            'message': message
        })

    def validate_local_files(self):
        """Validate local build artifacts"""
        print("\n🔍 VALIDATING LOCAL BUILD ARTIFACTS")
        
        # Check model file
        model_path = Path("android_models/minicpm_llama3_v25_android.zip")
        if model_path.exists():
            # Check size
            size = model_path.stat().st_size
            size_ok = size == self.expected_model_size
            self.log_result("Model File Size", size_ok, 
                          f"Expected: {self.expected_model_size}, Got: {size}")
            
            # Check hash
            with open(model_path, 'rb') as f:
                file_hash = hashlib.sha256(f.read()).hexdigest()
            hash_ok = file_hash == self.expected_model_hash
            self.log_result("Model File Hash", hash_ok,
                          f"SHA-256 verified: {file_hash[:16]}...")
        else:
            self.log_result("Model File Exists", False, "Model file not found")

        # Check APK files
        apk_dir = Path("app/build/outputs/apk/release")
        if apk_dir.exists():
            apk_files = list(apk_dir.glob("*.apk"))
            apk_count_ok = len(apk_files) >= 4  # Universal, ARM64, ARM32, x86_64
            self.log_result("APK Files Generated", apk_count_ok,
                          f"Found {len(apk_files)} APK files")
            
            # Check universal APK size (should be largest)
            universal_apk = apk_dir / "app-universal-release.apk"
            if universal_apk.exists():
                size_mb = universal_apk.stat().st_size / (1024 * 1024)
                size_ok = 100 <= size_mb <= 150  # Expected range
                self.log_result("Universal APK Size", size_ok,
                              f"Size: {size_mb:.1f}MB")
        else:
            self.log_result("APK Directory", False, "APK output directory not found")

    def validate_git_state(self):
        """Validate git repository state"""
        print("\n🔍 VALIDATING GIT REPOSITORY STATE")
        
        try:
            # Check current branch
            result = subprocess.run(['git', 'branch', '--show-current'], 
                                  capture_output=True, text=True)
            current_branch = result.stdout.strip()
            branch_ok = current_branch == 'main'
            self.log_result("Current Branch", branch_ok,
                          f"On branch: {current_branch}")
            
            # Check for uncommitted changes
            result = subprocess.run(['git', 'status', '--porcelain'], 
                                  capture_output=True, text=True)
            clean_ok = len(result.stdout.strip()) == 0
            self.log_result("Working Directory Clean", clean_ok,
                          "No uncommitted changes" if clean_ok else "Has uncommitted changes")
            
            # Check tags
            result = subprocess.run(['git', 'tag', '-l', 'v2.0.0-production'], 
                                  capture_output=True, text=True)
            tag_ok = 'v2.0.0-production' in result.stdout
            self.log_result("Production Tag Exists", tag_ok,
                          "v2.0.0-production tag found")
            
        except Exception as e:
            self.log_result("Git Validation", False, f"Error: {e}")

    def validate_github_release(self):
        """Validate GitHub release and downloads"""
        print("\n🔍 VALIDATING GITHUB RELEASE")
        
        try:
            # Check if release exists
            release_url = f"{self.base_url}/releases/tag/v2.0.0-production"
            response = requests.head(release_url, timeout=10)
            release_ok = response.status_code == 200
            self.log_result("GitHub Release Exists", release_ok,
                          f"Release page: {response.status_code}")
            
            # Check model download URL
            model_url = f"{self.base_url}/releases/download/v2.0.0-production/minicpm_llama3_v25_android.zip"
            response = requests.head(model_url, timeout=10)
            model_download_ok = response.status_code == 200
            self.log_result("Model Download URL", model_download_ok,
                          f"Model download: {response.status_code}")
            
            if model_download_ok:
                # Check content length
                content_length = response.headers.get('content-length')
                if content_length:
                    size_ok = int(content_length) == self.expected_model_size
                    self.log_result("Model Download Size", size_ok,
                                  f"Content-Length: {content_length}")
            
        except requests.RequestException as e:
            self.log_result("GitHub Release Check", False, f"Network error: {e}")

    def validate_android_build(self):
        """Validate Android build configuration"""
        print("\n🔍 VALIDATING ANDROID BUILD CONFIGURATION")
        
        try:
            # Check if gradlew is executable
            gradlew_path = Path("gradlew")
            executable_ok = gradlew_path.exists() and os.access(gradlew_path, os.X_OK)
            self.log_result("Gradlew Executable", executable_ok,
                          "gradlew script is executable")
            
            # Try a quick build check
            result = subprocess.run(['./gradlew', 'tasks', '--quiet'], 
                                  capture_output=True, text=True, timeout=30)
            gradle_ok = result.returncode == 0
            self.log_result("Gradle Configuration", gradle_ok,
                          "Gradle tasks run successfully")
            
        except subprocess.TimeoutExpired:
            self.log_result("Gradle Check", False, "Gradle command timed out")
        except Exception as e:
            self.log_result("Android Build Check", False, f"Error: {e}")

    def validate_documentation(self):
        """Validate documentation is up to date"""
        print("\n🔍 VALIDATING DOCUMENTATION")
        
        # Check key documentation files
        docs_to_check = [
            ("README.md", "main"),
            ("docs/IMPLEMENTATION_STATUS.md", "main"),
            ("RELEASE_NOTES_v2.0.0.md", "v2.0.0"),
        ]
        
        for doc_file, expected_content in docs_to_check:
            doc_path = Path(doc_file)
            if doc_path.exists():
                content = doc_path.read_text()
                content_ok = expected_content in content
                self.log_result(f"Documentation: {doc_file}", content_ok,
                              f"Contains '{expected_content}'" if content_ok else f"Missing '{expected_content}'")
            else:
                self.log_result(f"Documentation: {doc_file}", False, "File not found")

    def run_validation(self):
        """Run all validation checks"""
        print("🚀 COUPONTRACKER v2.0.0 PRODUCTION DEPLOYMENT VALIDATION")
        print("=" * 60)
        
        self.validate_local_files()
        self.validate_git_state()
        self.validate_android_build()
        self.validate_documentation()
        self.validate_github_release()
        
        # Summary
        print("\n" + "=" * 60)
        print("📊 VALIDATION SUMMARY")
        
        total_tests = len(self.validation_results)
        passed_tests = sum(1 for r in self.validation_results if r['passed'])
        failed_tests = total_tests - passed_tests
        
        print(f"Total Tests: {total_tests}")
        print(f"✅ Passed: {passed_tests}")
        print(f"❌ Failed: {failed_tests}")
        print(f"Success Rate: {(passed_tests/total_tests)*100:.1f}%")
        
        if failed_tests > 0:
            print("\n❌ FAILED TESTS:")
            for result in self.validation_results:
                if not result['passed']:
                    print(f"  - {result['test']}: {result['message']}")
        
        # Overall status
        if failed_tests == 0:
            print("\n🎉 ALL VALIDATIONS PASSED - PRODUCTION READY! 🚀")
            return True
        elif failed_tests <= 2:
            print("\n⚠️  MINOR ISSUES DETECTED - REVIEW RECOMMENDED")
            return True
        else:
            print("\n🚨 CRITICAL ISSUES DETECTED - DEPLOYMENT NOT RECOMMENDED")
            return False

if __name__ == "__main__":
    validator = ProductionValidator()
    success = validator.run_validation()
    sys.exit(0 if success else 1)
