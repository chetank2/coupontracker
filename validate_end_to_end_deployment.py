#!/usr/bin/env python3
"""
End-to-End Production Deployment Validation
Simulates the complete user experience from download to model usage.
"""

import os
import sys
import hashlib
import requests
import subprocess
import tempfile
import zipfile
from pathlib import Path
import json

class EndToEndValidator:
    def __init__(self):
        self.base_url = "https://github.com/chetank2/coupontracker"
        self.production_tag = "v2.0.0-production"
        self.model_filename = "minicpm_llama3_v25_android.zip"
        self.expected_hash = "bfc31f09be000e56c88d0a9f6360d342f401b36abc63f3c144a64e97224fb8f9"
        self.expected_size = 4701281
        self.validation_results = []

    def log_result(self, test_name, passed, message, details=None):
        """Log validation result with optional details"""
        status = "✅ PASS" if passed else "❌ FAIL"
        print(f"{status} {test_name}: {message}")
        if details:
            print(f"    📋 Details: {details}")
        
        self.validation_results.append({
            'test': test_name,
            'passed': passed,
            'message': message,
            'details': details
        })

    def validate_github_release_assets(self):
        """Validate GitHub release and all assets"""
        print("\n🔍 VALIDATING GITHUB RELEASE ASSETS")
        
        try:
            # Check release page
            release_url = f"{self.base_url}/releases/tag/{self.production_tag}"
            response = requests.get(release_url, timeout=10)
            release_exists = response.status_code == 200
            self.log_result("GitHub Release Page", release_exists,
                          f"Status: {response.status_code}")
            
            # Check model download
            model_url = f"{self.base_url}/releases/download/{self.production_tag}/{self.model_filename}"
            response = requests.head(model_url, timeout=10)
            model_available = response.status_code == 200
            self.log_result("Model Download Available", model_available,
                          f"Status: {response.status_code}")
            
            if model_available:
                content_length = response.headers.get('content-length')
                if content_length:
                    size_correct = int(content_length) == self.expected_size
                    self.log_result("Model File Size", size_correct,
                                  f"Expected: {self.expected_size}, Got: {content_length}")
            
            # Check APK downloads
            apk_variants = [
                "app-universal-release.apk",
                "app-arm64-v8a-release.apk", 
                "app-armeabi-v7a-release.apk"
            ]
            
            for apk in apk_variants:
                apk_url = f"{self.base_url}/releases/download/{self.production_tag}/{apk}"
                response = requests.head(apk_url, timeout=10)
                apk_available = response.status_code == 200
                self.log_result(f"APK Available: {apk}", apk_available,
                              f"Status: {response.status_code}")
                
        except requests.RequestException as e:
            self.log_result("GitHub Release Check", False, f"Network error: {e}")

    def validate_model_download_simulation(self):
        """Simulate the actual model download process"""
        print("\n🔍 SIMULATING MODEL DOWNLOAD PROCESS")
        
        try:
            model_url = f"{self.base_url}/releases/download/{self.production_tag}/{self.model_filename}"
            
            # Create temporary directory for download
            with tempfile.TemporaryDirectory() as temp_dir:
                temp_file = Path(temp_dir) / self.model_filename
                
                print(f"    📥 Downloading from: {model_url}")
                response = requests.get(model_url, timeout=60, stream=True)
                
                if response.status_code == 200:
                    # Download with progress simulation
                    total_size = int(response.headers.get('content-length', 0))
                    downloaded = 0
                    
                    with open(temp_file, 'wb') as f:
                        for chunk in response.iter_content(chunk_size=8192):
                            if chunk:
                                f.write(chunk)
                                downloaded += len(chunk)
                    
                    # Validate download
                    actual_size = temp_file.stat().st_size
                    size_ok = actual_size == self.expected_size
                    self.log_result("Download Size Validation", size_ok,
                                  f"Expected: {self.expected_size}, Downloaded: {actual_size}")
                    
                    # Validate checksum
                    with open(temp_file, 'rb') as f:
                        actual_hash = hashlib.sha256(f.read()).hexdigest()
                    
                    hash_ok = actual_hash == self.expected_hash
                    self.log_result("Download Checksum Validation", hash_ok,
                                  f"SHA-256 verified: {actual_hash[:16]}...")
                    
                    # Validate ZIP structure
                    if zipfile.is_zipfile(temp_file):
                        with zipfile.ZipFile(temp_file, 'r') as zip_ref:
                            file_list = zip_ref.namelist()
                            
                            # Expected files in MiniCPM model
                            expected_files = [
                                'minicpm_llm_q4f16_1.so',
                                'model.bin',
                                'vision_config.json',
                                'mlc-chat-config.json',
                                'tokenizer.model'
                            ]
                            
                            files_present = all(any(expected in f for f in file_list) 
                                              for expected in expected_files)
                            
                            self.log_result("Model ZIP Structure", files_present,
                                          f"Contains {len(file_list)} files",
                                          f"Files: {', '.join(file_list[:3])}...")
                    else:
                        self.log_result("Model ZIP Structure", False, "Not a valid ZIP file")
                        
                else:
                    self.log_result("Model Download", False, 
                                  f"HTTP {response.status_code}: {response.reason}")
                    
        except Exception as e:
            self.log_result("Model Download Simulation", False, f"Error: {e}")

    def validate_android_integration(self):
        """Validate Android app integration points"""
        print("\n🔍 VALIDATING ANDROID APP INTEGRATION")
        
        try:
            # Check ModelDownloadManager configuration
            manager_file = Path("app/src/main/kotlin/com/example/coupontracker/llm/ModelDownloadManager.kt")
            if manager_file.exists():
                content = manager_file.read_text()
                
                # Check URL configuration
                correct_url = f"v2.0.0-production" in content
                self.log_result("ModelDownloadManager URL", correct_url,
                              "Points to production release")
                
                # Check checksum configuration
                correct_checksum = self.expected_hash in content
                self.log_result("ModelDownloadManager Checksum", correct_checksum,
                              "Uses correct SHA-256 hash")
                
                # Check model filename
                correct_filename = self.model_filename in content
                self.log_result("ModelDownloadManager Filename", correct_filename,
                              "Uses correct model filename")
            else:
                self.log_result("ModelDownloadManager File", False, "File not found")
            
            # Check Settings integration
            settings_files = [
                "app/src/main/kotlin/com/example/coupontracker/ui/screen/SettingsScreen.kt",
                "app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/SettingsViewModel.kt"
            ]
            
            for settings_file in settings_files:
                file_path = Path(settings_file)
                if file_path.exists():
                    content = file_path.read_text()
                    has_model_download = "download" in content.lower() and "model" in content.lower()
                    self.log_result(f"Settings Integration: {file_path.name}", has_model_download,
                                  "Contains model download functionality")
                    
        except Exception as e:
            self.log_result("Android Integration Check", False, f"Error: {e}")

    def validate_build_reproducibility(self):
        """Validate that builds are reproducible and consistent"""
        print("\n🔍 VALIDATING BUILD REPRODUCIBILITY")
        
        try:
            # Check if we can build the same APK
            result = subprocess.run(['./gradlew', 'assembleDebug', '--quiet'], 
                                  capture_output=True, text=True, timeout=120)
            build_success = result.returncode == 0
            self.log_result("Debug Build Reproducibility", build_success,
                          "Can rebuild debug APK successfully")
            
            # Check APK outputs
            apk_dir = Path("app/build/outputs/apk/release")
            if apk_dir.exists():
                apk_files = list(apk_dir.glob("*.apk"))
                consistent_outputs = len(apk_files) >= 4
                self.log_result("Release APK Consistency", consistent_outputs,
                              f"Generated {len(apk_files)} APK variants")
                
                # Check universal APK size consistency
                universal_apk = apk_dir / "app-universal-release.apk"
                if universal_apk.exists():
                    size_mb = universal_apk.stat().st_size / (1024 * 1024)
                    size_consistent = 100 <= size_mb <= 120  # Expected range
                    self.log_result("Universal APK Size Consistency", size_consistent,
                                  f"Size: {size_mb:.1f}MB (expected: 100-120MB)")
                    
        except subprocess.TimeoutExpired:
            self.log_result("Build Reproducibility", False, "Build timed out")
        except Exception as e:
            self.log_result("Build Reproducibility", False, f"Error: {e}")

    def validate_user_experience_flow(self):
        """Validate the complete user experience flow"""
        print("\n🔍 VALIDATING USER EXPERIENCE FLOW")
        
        # Check onboarding and setup documentation
        setup_files = [
            ("README.md", "installation instructions"),
            ("RELEASE_NOTES_v2.0.0.md", "setup guide"),
            ("docs/IMPLEMENTATION_STATUS.md", "current status")
        ]
        
        for file_path, description in setup_files:
            path = Path(file_path)
            if path.exists():
                content = path.read_text()
                has_setup_info = any(keyword in content.lower() 
                                   for keyword in ['install', 'setup', 'download', 'configure'])
                self.log_result(f"User Documentation: {path.name}", has_setup_info,
                              f"Contains {description}")
            else:
                self.log_result(f"User Documentation: {path.name}", False, "File not found")

    def generate_deployment_report(self):
        """Generate comprehensive deployment report"""
        print("\n" + "=" * 60)
        print("📊 END-TO-END DEPLOYMENT VALIDATION REPORT")
        print("=" * 60)
        
        total_tests = len(self.validation_results)
        passed_tests = sum(1 for r in self.validation_results if r['passed'])
        failed_tests = total_tests - passed_tests
        
        print(f"📈 SUMMARY STATISTICS:")
        print(f"   Total Tests: {total_tests}")
        print(f"   ✅ Passed: {passed_tests}")
        print(f"   ❌ Failed: {failed_tests}")
        print(f"   📊 Success Rate: {(passed_tests/total_tests)*100:.1f}%")
        
        # Categorize results
        critical_failures = []
        minor_issues = []
        
        for result in self.validation_results:
            if not result['passed']:
                if any(keyword in result['test'].lower() 
                      for keyword in ['download', 'checksum', 'build', 'integration']):
                    critical_failures.append(result)
                else:
                    minor_issues.append(result)
        
        if critical_failures:
            print(f"\n🚨 CRITICAL FAILURES ({len(critical_failures)}):")
            for failure in critical_failures:
                print(f"   ❌ {failure['test']}: {failure['message']}")
        
        if minor_issues:
            print(f"\n⚠️  MINOR ISSUES ({len(minor_issues)}):")
            for issue in minor_issues:
                print(f"   ⚠️  {issue['test']}: {issue['message']}")
        
        # Overall assessment
        if failed_tests == 0:
            print("\n🎉 DEPLOYMENT FULLY VALIDATED - PRODUCTION READY! 🚀")
            deployment_status = "EXCELLENT"
        elif len(critical_failures) == 0:
            print("\n✅ DEPLOYMENT VALIDATED - MINOR ISSUES ONLY")
            deployment_status = "GOOD"
        elif len(critical_failures) <= 2:
            print("\n⚠️  DEPLOYMENT ISSUES DETECTED - REVIEW REQUIRED")
            deployment_status = "NEEDS_REVIEW"
        else:
            print("\n🚨 CRITICAL DEPLOYMENT ISSUES - NOT READY FOR PRODUCTION")
            deployment_status = "CRITICAL_ISSUES"
        
        return deployment_status, {
            'total_tests': total_tests,
            'passed': passed_tests,
            'failed': failed_tests,
            'success_rate': (passed_tests/total_tests)*100,
            'critical_failures': len(critical_failures),
            'minor_issues': len(minor_issues),
            'status': deployment_status
        }

    def run_validation(self):
        """Run complete end-to-end validation"""
        print("🚀 COUPONTRACKER v2.0.0 END-TO-END DEPLOYMENT VALIDATION")
        print("=" * 70)
        
        self.validate_github_release_assets()
        self.validate_model_download_simulation()
        self.validate_android_integration()
        self.validate_build_reproducibility()
        self.validate_user_experience_flow()
        
        status, metrics = self.generate_deployment_report()
        
        return status in ["EXCELLENT", "GOOD"], metrics

if __name__ == "__main__":
    validator = EndToEndValidator()
    success, metrics = validator.run_validation()
    
    # Save metrics for monitoring
    with open("deployment_validation_metrics.json", "w") as f:
        json.dump(metrics, f, indent=2)
    
    print(f"\n📄 Validation metrics saved to: deployment_validation_metrics.json")
    sys.exit(0 if success else 1)
