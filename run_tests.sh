#!/bin/bash
# Correct Test Runner Script for CouponTracker3
# This script ensures you always use the correct Gradle test tasks

set -e

echo "🧪 CouponTracker3 Test Runner"
echo "=============================="
echo ""

# Function to run tests with proper error handling
run_tests() {
    local test_type=$1
    local gradle_task=$2
    
    echo "🔄 Running $test_type..."
    if ./gradlew $gradle_task; then
        echo "✅ $test_type PASSED"
    else
        echo "❌ $test_type FAILED"
        exit 1
    fi
    echo ""
}

# Make gradlew executable
chmod +x ./gradlew

echo "Available test options:"
echo "1. All tests (debug + release)"
echo "2. Debug unit tests only"
echo "3. Release unit tests only"
echo "4. Build + test (full verification)"
echo ""

# Default to all tests if no argument provided
TEST_OPTION=${1:-1}

case $TEST_OPTION in
    1)
        echo "🎯 Running all unit tests..."
        run_tests "All Unit Tests" "test"
        ;;
    2)
        echo "🎯 Running debug unit tests..."
        run_tests "Debug Unit Tests" "testDebugUnitTest"
        ;;
    3)
        echo "🎯 Running release unit tests..."
        run_tests "Release Unit Tests" "testReleaseUnitTest"
        ;;
    4)
        echo "🎯 Running full build with tests..."
        run_tests "Full Build + Tests" "build"
        ;;
    *)
        echo "❌ Invalid option. Use 1-4"
        exit 1
        ;;
esac

echo "🎉 All tests completed successfully!"
echo ""
echo "📋 Remember: Never use 'testClasses' - it doesn't exist in Android projects!"
echo "✅ Always use: test, testDebugUnitTest, testReleaseUnitTest, or build"


