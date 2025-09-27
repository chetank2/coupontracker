#!/usr/bin/env python3
"""
Test Settings UI integration for MiniCPM model download.
Verifies that the UI components and logic are properly configured.
"""

import re
from pathlib import Path

def test_settings_screen_components():
    """Test that SettingsScreen.kt has the required UI components."""
    print("🎨 Testing SettingsScreen UI components...")
    
    settings_path = Path("app/src/main/kotlin/com/example/coupontracker/ui/screen/SettingsScreen.kt")
    
    if not settings_path.exists():
        print(f"❌ SettingsScreen.kt not found")
        return False
    
    content = settings_path.read_text()
    
    # Check for required components
    required_components = [
        "ApiTypeSelector",
        "LlmStatusCard", 
        "downloadProgress",
        "downloadStatusMessage",
        "CircularProgressIndicator",
        "LinearProgressIndicator",
        "ModelDownloadManager"
    ]
    
    missing_components = []
    for component in required_components:
        if component not in content:
            missing_components.append(component)
    
    if missing_components:
        print(f"❌ Missing UI components: {missing_components}")
        return False
    
    print("✅ All required UI components found")
    
    # Check for progress callback
    if "progress.progressPercent" in content and "progress.statusMessage" in content:
        print("✅ Progress callback implementation found")
    else:
        print("❌ Progress callback implementation missing")
        return False
    
    return True

def test_api_type_enum():
    """Test that ApiType enum includes LOCAL_LLM."""
    print("\n🔧 Testing ApiType enum...")
    
    # Find ApiType enum file
    api_type_files = list(Path("app/src/main/kotlin").rglob("*ApiType*.kt"))
    
    if not api_type_files:
        print("❌ ApiType enum file not found")
        return False
    
    api_type_path = api_type_files[0]
    content = api_type_path.read_text()
    
    if "LOCAL_LLM" in content:
        print("✅ ApiType.LOCAL_LLM found")
        return True
    else:
        print("❌ ApiType.LOCAL_LLM not found")
        return False

def test_secure_preferences():
    """Test that SecurePreferencesManager supports LLM preferences."""
    print("\n🔒 Testing SecurePreferencesManager...")
    
    prefs_path = Path("app/src/main/kotlin/com/example/coupontracker/util/SecurePreferencesManager.kt")
    
    if not prefs_path.exists():
        print("❌ SecurePreferencesManager.kt not found")
        return False
    
    content = prefs_path.read_text()
    
    # Check for LLM-related methods
    llm_methods = [
        "getLlmModelDownloaded",
        "setLlmModelDownloaded", 
        "getSelectedApiType",
        "setSelectedApiType"
    ]
    
    missing_methods = []
    for method in llm_methods:
        if method not in content:
            missing_methods.append(method)
    
    if missing_methods:
        print(f"❌ Missing LLM preference methods: {missing_methods}")
        return False
    
    print("✅ All LLM preference methods found")
    return True

def test_hilt_integration():
    """Test that Hilt DI is properly configured for LLM components."""
    print("\n💉 Testing Hilt DI integration...")
    
    llm_module_path = Path("app/src/main/kotlin/com/example/coupontracker/di/LlmModule.kt")
    
    if not llm_module_path.exists():
        print("❌ LlmModule.kt not found")
        return False
    
    content = llm_module_path.read_text()
    
    # Check for required providers
    required_providers = [
        "provideLlmRuntimeManager",
        "provideLocalLlmOcrService",
        "provideLlmTelemetryService"
    ]
    
    missing_providers = []
    for provider in required_providers:
        if provider not in content:
            missing_providers.append(provider)
    
    if missing_providers:
        print(f"❌ Missing Hilt providers: {missing_providers}")
        return False
    
    print("✅ All Hilt providers found")
    return True

def main():
    """Run all Settings UI integration tests."""
    print("🧪 Settings UI Integration Test for MiniCPM")
    print("=" * 50)
    
    tests = [
        ("Settings Screen Components", test_settings_screen_components),
        ("ApiType Enum", test_api_type_enum),
        ("Secure Preferences", test_secure_preferences),
        ("Hilt Integration", test_hilt_integration)
    ]
    
    results = []
    for test_name, test_func in tests:
        print(f"\n📋 Running: {test_name}")
        try:
            success = test_func()
            results.append((test_name, success))
            if success:
                print(f"✅ {test_name}: PASSED")
            else:
                print(f"❌ {test_name}: FAILED")
        except Exception as e:
            print(f"❌ {test_name}: ERROR - {e}")
            results.append((test_name, False))
    
    # Summary
    print("\n" + "=" * 50)
    print("📊 TEST SUMMARY")
    print("=" * 50)
    
    passed = sum(1 for _, success in results if success)
    total = len(results)
    
    for test_name, success in results:
        status = "✅ PASS" if success else "❌ FAIL"
        print(f"{status} {test_name}")
    
    print(f"\nResult: {passed}/{total} tests passed")
    
    if passed == total:
        print("🎉 All UI integration tests passed!")
        return True
    else:
        print("⚠️ Some UI tests failed.")
        return False

if __name__ == "__main__":
    success = main()
    exit(0 if success else 1)
