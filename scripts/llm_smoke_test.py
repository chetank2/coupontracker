#!/usr/bin/env python3
"""
MiniCPM LLM Smoke Test CLI
Manual regression testing harness for MiniCPM integration
"""

import os
import sys
import json
import time
import argparse
import subprocess
from pathlib import Path
from typing import Dict, List, Optional

class LlmSmokeTestRunner:
    """CLI harness for running MiniCPM smoke tests"""
    
    def __init__(self, project_root: str):
        self.project_root = Path(project_root)
        self.test_results = []
        
    def run_android_unit_tests(self) -> Dict:
        """Run Android unit tests for LLM integration"""
        print("🧪 Running Android Unit Tests...")
        
        start_time = time.time()
        
        try:
            # Run comprehensive LLM-specific unit tests
            cmd = [
                "./gradlew", 
                "testDebugUnitTest",
                "--tests", "*LlmIntegrationTest*",
                "--tests", "*EndToEndLlmIntegrationTest*",
                "--tests", "*LlmRuntimeManagerTest*",
                "--tests", "*LocalLlmOcrServiceTest*",
                "--tests", "*ModelDownloadManagerTest*",
                "--tests", "*LlmTelemetryServiceTest*",
                "--continue"  # Continue on test failures to get full report
            ]
            
            result = subprocess.run(
                cmd,
                cwd=self.project_root,
                capture_output=True,
                text=True,
                timeout=600  # 10 minutes for comprehensive tests
            )
            
            duration_ms = int((time.time() - start_time) * 1000)
            success = result.returncode == 0
            
            # Parse test results from gradle output
            test_count = self._extract_test_count(result.stdout)
            failure_count = self._extract_failure_count(result.stdout)
            
            test_result = {
                "test_name": "Android Unit Tests",
                "success": success,
                "duration_ms": duration_ms,
                "test_count": test_count,
                "failure_count": failure_count,
                "stdout": result.stdout[-1000:] if result.stdout else "",  # Last 1000 chars
                "stderr": result.stderr[-1000:] if result.stderr else ""
                "output": result.stdout,
                "error": result.stderr if not success else None
            }
            
            self.test_results.append(test_result)
            
            if success:
                print("✅ Android Unit Tests passed")
            else:
                print("❌ Android Unit Tests failed")
                print(f"Error: {result.stderr}")
            
            return test_result
            
        except subprocess.TimeoutExpired:
            print("⏰ Android Unit Tests timed out")
            return {"test_name": "Android Unit Tests", "success": False, "error": "Timeout"}
        except Exception as e:
            print(f"💥 Android Unit Tests crashed: {e}")
            return {"test_name": "Android Unit Tests", "success": False, "error": str(e)}
    
    def test_model_conversion(self) -> Dict:
        """Test model conversion pipeline"""
        print("🔧 Testing Model Conversion Pipeline...")
        
        try:
            start_time = time.time()
            
            # Run the real model conversion script
            cmd = ["python", "scripts/build_real_minicpm.py"]
            
            result = subprocess.run(
                cmd,
                cwd=self.project_root,
                capture_output=True,
                text=True,
                timeout=7200  # 2 hours for model conversion
            )
            
            duration_ms = int((time.time() - start_time) * 1000)
            success = result.returncode == 0
            
            test_result = {
                "test_name": "Model Conversion",
                "success": success,
                "duration_ms": duration_ms,
                "output": result.stdout,
                "error": result.stderr if not success else None
            }
            
            self.test_results.append(test_result)
            
            if success:
                print(f"✅ Model Conversion completed in {duration_ms/1000:.1f}s")
                
                # Check if artifacts were generated
                artifacts_path = self.project_root / "android_models" / "android_package"
                if artifacts_path.exists():
                    print(f"📦 Artifacts generated at: {artifacts_path}")
                    
                    # List generated files
                    files = list(artifacts_path.rglob("*"))
                    print(f"📁 Generated {len(files)} files")
                    
                else:
                    print("⚠️ Artifacts directory not found")
            else:
                print("❌ Model Conversion failed")
                print(f"Error: {result.stderr}")
            
            return test_result
            
        except subprocess.TimeoutExpired:
            print("⏰ Model Conversion timed out after 2 hours")
            return {"test_name": "Model Conversion", "success": False, "error": "Timeout"}
        except Exception as e:
            print(f"💥 Model Conversion crashed: {e}")
            return {"test_name": "Model Conversion", "success": False, "error": str(e)}
    
    def test_native_compilation(self) -> Dict:
        """Test native JNI compilation"""
        print("🔨 Testing Native JNI Compilation...")
        
        try:
            start_time = time.time()
            
            # Build native libraries
            cmd = ["./gradlew", "assembleDebug"]
            
            result = subprocess.run(
                cmd,
                cwd=self.project_root,
                capture_output=True,
                text=True,
                timeout=600  # 10 minutes
            )
            
            duration_ms = int((time.time() - start_time) * 1000)
            success = result.returncode == 0
            
            test_result = {
                "test_name": "Native Compilation",
                "success": success,
                "duration_ms": duration_ms,
                "output": result.stdout,
                "error": result.stderr if not success else None
            }
            
            self.test_results.append(test_result)
            
            if success:
                print(f"✅ Native Compilation completed in {duration_ms/1000:.1f}s")
                
                # Check for generated native libraries
                native_libs = list(self.project_root.glob("app/build/intermediates/cmake/**/*.so"))
                if native_libs:
                    print(f"📚 Generated {len(native_libs)} native libraries")
                    for lib in native_libs:
                        print(f"  - {lib.name}")
                else:
                    print("⚠️ No native libraries found")
            else:
                print("❌ Native Compilation failed")
                print(f"Error: {result.stderr}")
            
            return test_result
            
        except subprocess.TimeoutExpired:
            print("⏰ Native Compilation timed out")
            return {"test_name": "Native Compilation", "success": False, "error": "Timeout"}
        except Exception as e:
            print(f"💥 Native Compilation crashed: {e}")
            return {"test_name": "Native Compilation", "success": False, "error": str(e)}
    
    def test_checksum_validation(self) -> Dict:
        """Test model checksum validation"""
        print("🔐 Testing Checksum Validation...")
        
        try:
            # Check if build results exist
            build_results_file = self.project_root / "android_models" / "build_results.json"
            
            if not build_results_file.exists():
                return {
                    "test_name": "Checksum Validation",
                    "success": False,
                    "error": "Build results not found - run model conversion first"
                }
            
            # Load build results
            with open(build_results_file, 'r') as f:
                build_results = json.load(f)
            
            success = True
            errors = []
            
            # Validate ZIP checksum
            if "zip_checksum" in build_results:
                zip_checksum = build_results["zip_checksum"]
                if len(zip_checksum) == 64:  # SHA-256 length
                    print(f"✅ ZIP checksum valid: {zip_checksum[:16]}...")
                else:
                    success = False
                    errors.append(f"Invalid ZIP checksum length: {len(zip_checksum)}")
            else:
                success = False
                errors.append("ZIP checksum missing from build results")
            
            # Validate file checksums
            if "manifest" in build_results and "required_files" in build_results["manifest"]:
                file_checksums = build_results["manifest"]["required_files"]
                print(f"📁 Validating {len(file_checksums)} file checksums...")
                
                for filename, checksum in file_checksums.items():
                    if len(checksum) == 64:  # SHA-256 length
                        print(f"  ✅ {filename}: {checksum[:16]}...")
                    else:
                        success = False
                        errors.append(f"Invalid checksum for {filename}: {len(checksum)}")
            else:
                success = False
                errors.append("File checksums missing from manifest")
            
            test_result = {
                "test_name": "Checksum Validation",
                "success": success,
                "duration_ms": 0,
                "output": f"Validated checksums for {len(file_checksums) if 'file_checksums' in locals() else 0} files",
                "error": "; ".join(errors) if errors else None
            }
            
            self.test_results.append(test_result)
            
            if success:
                print("✅ Checksum Validation passed")
            else:
                print("❌ Checksum Validation failed")
                for error in errors:
                    print(f"  - {error}")
            
            return test_result
            
        except Exception as e:
            print(f"💥 Checksum Validation crashed: {e}")
            return {"test_name": "Checksum Validation", "success": False, "error": str(e)}
    
    def run_smoke_tests(self, include_conversion: bool = False) -> Dict:
        """Run complete smoke test suite"""
        print("🚀 Starting MiniCPM LLM Smoke Tests")
        print("=" * 50)
        
        start_time = time.time()
        
        # Run tests in order
        tests = [
            self.test_native_compilation,
            self.run_android_unit_tests,
            self.test_checksum_validation,
        ]
        
        if include_conversion:
            tests.insert(0, self.test_model_conversion)
        
        for test_func in tests:
            print()
            test_func()
        
        total_duration = time.time() - start_time
        
        # Generate summary
        total_tests = len(self.test_results)
        passed_tests = sum(1 for result in self.test_results if result["success"])
        failed_tests = total_tests - passed_tests
        
        summary = {
            "total_tests": total_tests,
            "passed_tests": passed_tests,
            "failed_tests": failed_tests,
            "success_rate": (passed_tests / total_tests * 100) if total_tests > 0 else 0,
            "total_duration_s": total_duration,
            "test_results": self.test_results
        }
        
        print("\n" + "=" * 50)
        print("🎯 SMOKE TEST SUMMARY")
        print("=" * 50)
        print(f"Total Tests: {total_tests}")
        print(f"Passed: {passed_tests} ✅")
        print(f"Failed: {failed_tests} ❌")
        print(f"Success Rate: {summary['success_rate']:.1f}%")
        print(f"Total Duration: {total_duration:.1f}s")
        
        if failed_tests > 0:
            print("\n❌ FAILED TESTS:")
            for result in self.test_results:
                if not result["success"]:
                    print(f"  - {result['test_name']}: {result.get('error', 'Unknown error')}")
        
        # Save results
        results_file = self.project_root / "smoke_test_results.json"
        with open(results_file, 'w') as f:
            json.dump(summary, f, indent=2)
        
        print(f"\n📄 Results saved to: {results_file}")
        
        return summary

    def _extract_test_count(self, gradle_output: str) -> int:
        """Extract total test count from gradle output"""
        import re
        match = re.search(r'(\d+) tests completed', gradle_output)
        return int(match.group(1)) if match else 0
    
    def _extract_failure_count(self, gradle_output: str) -> int:
        """Extract failure count from gradle output"""
        import re
        match = re.search(r'(\d+) failed', gradle_output)
        return int(match.group(1)) if match else 0

def main():
    parser = argparse.ArgumentParser(description="MiniCPM LLM Smoke Test Runner")
    parser.add_argument("--project-root", default=".", help="Project root directory")
    parser.add_argument("--include-conversion", action="store_true", 
                       help="Include model conversion test (slow)")
    parser.add_argument("--test", choices=["compilation", "unit", "checksum", "conversion"],
                       help="Run specific test only")
    
    args = parser.parse_args()
    
    runner = LlmSmokeTestRunner(args.project_root)
    
    if args.test:
        # Run specific test
        test_map = {
            "compilation": runner.test_native_compilation,
            "unit": runner.run_android_unit_tests,
            "checksum": runner.test_checksum_validation,
            "conversion": runner.test_model_conversion
        }
        
        if args.test in test_map:
            result = test_map[args.test]()
            success = result["success"]
            sys.exit(0 if success else 1)
        else:
            print(f"Unknown test: {args.test}")
            sys.exit(1)
    else:
        # Run full smoke test suite
        summary = runner.run_smoke_tests(include_conversion=args.include_conversion)
        success = summary["failed_tests"] == 0
        sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
