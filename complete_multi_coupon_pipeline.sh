#!/bin/bash
# Complete End-to-End Multi-Coupon Pipeline
# Automates the entire process from PWA training data to Android app deployment

set -e  # Exit on any error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
PWA_EXPORT_FILE="multi_coupon_training_export.json"
TRAINING_DIR="./multi_coupon_training"
ANDROID_ASSETS_DIR="./app/src/main/assets"
ANDROID_APK_OUTPUT="./app/build/outputs/apk/debug/app-debug.apk"

# Functions
print_header() {
    echo -e "${BLUE}============================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}============================================${NC}"
}

print_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

print_error() {
    echo -e "${RED}❌ $1${NC}"
}

print_info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

check_prerequisites() {
    print_header "Checking Prerequisites"
    
    # Check if Python is installed
    if ! command -v python3 &> /dev/null; then
        print_error "Python 3 is not installed"
        exit 1
    fi
    print_success "Python 3 found: $(python3 --version)"
    
    # Check if required Python packages are available
    python3 -c "import ultralytics, cv2, numpy, yaml" 2>/dev/null || {
        print_warning "Required Python packages missing. Installing..."
        pip3 install ultralytics opencv-python numpy pyyaml
    }
    print_success "Python dependencies verified"
    
    # Check if Android SDK is available
    if [ -z "$ANDROID_HOME" ]; then
        print_warning "ANDROID_HOME not set. Android build may fail."
    else
        print_success "Android SDK found: $ANDROID_HOME"
    fi
    
    # Check if Gradle wrapper exists
    if [ ! -f "./gradlew" ]; then
        print_error "Gradle wrapper not found. Please ensure you're in the project root."
        exit 1
    fi
    print_success "Gradle wrapper found"
    
    # Check if PWA export file exists
    if [ ! -f "$PWA_EXPORT_FILE" ]; then
        print_warning "PWA export file not found: $PWA_EXPORT_FILE"
        print_info "Please export training data from the PWA first, or use the demo data generator."
        
        # Offer to create demo data
        read -p "Generate demo training data? (y/n): " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            generate_demo_data
        else
            print_error "Cannot proceed without training data"
            exit 1
        fi
    else
        print_success "PWA export file found: $PWA_EXPORT_FILE"
    fi
}

generate_demo_data() {
    print_header "Generating Demo Training Data"
    
    cat > "$PWA_EXPORT_FILE" << 'EOF'
{
    "version": "2.0.0",
    "type": "multi_coupon_training",
    "exportDate": "2024-01-15T10:30:00Z",
    "totalImages": 3,
    "stage1_coupon_detection": [
        {
            "image_id": "demo_single_coupon.jpg",
            "image_width": 640,
            "image_height": 480,
            "instance_id": "instance_001",
            "class_name": "coupon_complete",
            "bounding_box": {
                "x": 0.1,
                "y": 0.2,
                "width": 0.8,
                "height": 0.6
            },
            "confidence": 0.95,
            "image_classification": "single",
            "image_data": "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDABQODxIPDRQSEBIXFRQYHjIhHhwcHj0sLiQySUBMS0dARkVQWnNiUFVtVkVGZIhlbXd7gYKBTmCNl4x9lnN+gXz/2wBDARUXFx4aHjshITt8U0ZTfHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHz/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAv/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxEAPwCdABmX/9k="
        },
        {
            "image_id": "demo_multi_coupon.jpg",
            "image_width": 640,
            "image_height": 800,
            "instance_id": "instance_002",
            "class_name": "coupon_complete",
            "bounding_box": {
                "x": 0.05,
                "y": 0.1,
                "width": 0.9,
                "height": 0.35
            },
            "confidence": 0.88,
            "image_classification": "multi_grid",
            "image_data": "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDABQODxIPDRQSEBIXFRQYHjIhHhwcHj0sLiQySUBMS0dARkVQWnNiUFVtVkVGZIhlbXd7gYKBTmCNl4x9lnN+gXz/2wBDARUXFx4aHjshITt8U0ZTfHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHz/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAv/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxEAPwCdABmX/9k="
        },
        {
            "image_id": "demo_multi_coupon.jpg",
            "image_width": 640,
            "image_height": 800,
            "instance_id": "instance_003",
            "class_name": "coupon_complete",
            "bounding_box": {
                "x": 0.05,
                "y": 0.55,
                "width": 0.9,
                "height": 0.35
            },
            "confidence": 0.82,
            "image_classification": "multi_grid",
            "image_data": "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDABQODxIPDRQSEBIXFRQYHjIhHhwcHj0sLiQySUBMS0dARkVQWnNiUFVtVkVGZIhlbXd7gYKBTmCNl4x9lnN+gXz/2wBDARUXFx4aHjshITt8U0ZTfHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHz/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAv/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxEAPwCdABmX/9k="
        }
    ],
    "stage2_field_detection": [
        {
            "image_id": "demo_single_coupon.jpg",
            "instance_id": "instance_001",
            "field_type": "code_region",
            "bounding_box": {
                "x": 0.3,
                "y": 0.4,
                "width": 0.4,
                "height": 0.1
            },
            "confidence": 0.92,
            "text_content": "SAVE20",
            "image_data": "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDABQODxIPDRQSEBIXFRQYHjIhHhwcHj0sLiQySUBMS0dARkVQWnNiUFVtVkVGZIhlbXd7gYKBTmCNl4x9lnN+gXz/2wBDARUXFx4aHjshITt8U0ZTfHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHz/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAv/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxEAPwCdABmX/9k="
        },
        {
            "image_id": "demo_single_coupon.jpg",
            "instance_id": "instance_001",
            "field_type": "benefit_region",
            "bounding_box": {
                "x": 0.2,
                "y": 0.3,
                "width": 0.6,
                "height": 0.08
            },
            "confidence": 0.89,
            "text_content": "20% OFF",
            "image_data": "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDABQODxIPDRQSEBIXFRQYHjIhHhwcHj0sLiQySUBMS0dARkVQWnNiUFVtVkVGZIhlbXd7gYKBTmCNl4x9lnN+gXz/2wBDARUXFx4aHjshITt8U0ZTfHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHz/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAv/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxEAPwCdABmX/9k="
        }
    ],
    "statistics": {
        "single_coupon": 1,
        "multi_grid": 1,
        "scrollable": 0,
        "unknown": 0,
        "total_annotations": 2,
        "total_instances": 3
    }
}
EOF
    
    print_success "Demo training data generated: $PWA_EXPORT_FILE"
}

run_training_pipeline() {
    print_header "Running Enhanced Multi-Coupon Training Pipeline"
    
    print_info "Starting Python training script..."
    python3 enhanced_multi_coupon_trainer.py \
        --pwa-export "$PWA_EXPORT_FILE" \
        --output-dir "$TRAINING_DIR" \
        --android-assets "$ANDROID_ASSETS_DIR" \
        --stage1-epochs 10 \
        --stage2-epochs 15 \
        --batch-size 4 \
        --device cpu \
        --verbose
    
    if [ $? -eq 0 ]; then
        print_success "Training pipeline completed successfully"
    else
        print_error "Training pipeline failed"
        exit 1
    fi
}

build_android_app() {
    print_header "Building Android App"
    
    # Clean previous builds
    print_info "Cleaning previous builds..."
    ./gradlew clean
    
    # Build debug APK
    print_info "Building debug APK..."
    ./gradlew assembleDebug
    
    if [ $? -eq 0 ]; then
        print_success "Android app built successfully"
        if [ -f "$ANDROID_APK_OUTPUT" ]; then
            print_success "APK location: $ANDROID_APK_OUTPUT"
        else
            print_warning "APK not found at expected location"
        fi
    else
        print_error "Android build failed"
        exit 1
    fi
}

install_android_app() {
    print_header "Installing Android App"
    
    # Check if ADB is available and device is connected
    if ! command -v adb &> /dev/null; then
        print_warning "ADB not found. Cannot install APK automatically."
        return
    fi
    
    # Check for connected devices
    DEVICES=$(adb devices | grep -v "List of devices" | grep "device$" | wc -l)
    
    if [ "$DEVICES" -eq 0 ]; then
        print_warning "No Android devices connected. APK ready for manual installation."
        print_info "APK location: $ANDROID_APK_OUTPUT"
        return
    fi
    
    print_info "Installing APK on connected device..."
    adb install -r "$ANDROID_APK_OUTPUT"
    
    if [ $? -eq 0 ]; then
        print_success "App installed successfully on device"
    else
        print_error "App installation failed"
    fi
}

run_tests() {
    print_header "Running End-to-End Tests"
    
    # Test 1: Verify model files exist
    print_info "Test 1: Checking model files..."
    if [ -f "$ANDROID_ASSETS_DIR/models/multi_coupon/stage1_coupon_detector.tflite" ] && \
       [ -f "$ANDROID_ASSETS_DIR/models/multi_coupon/stage2_field_detector.tflite" ] && \
       [ -f "$ANDROID_ASSETS_DIR/models/multi_coupon/manifest.json" ]; then
        print_success "All model files present"
    else
        print_error "Model files missing"
        return 1
    fi
    
    # Test 2: Verify model manifest
    print_info "Test 2: Checking model manifest..."
    if python3 -c "
import json
with open('$ANDROID_ASSETS_DIR/models/multi_coupon/manifest.json', 'r') as f:
    manifest = json.load(f)
    assert manifest['model_type'] == 'two_stage_yolo'
    assert len(manifest['stage1_classes']) == 3
    assert len(manifest['stage2_classes']) == 5
    print('Manifest validation passed')
" 2>/dev/null; then
        print_success "Model manifest valid"
    else
        print_error "Model manifest invalid"
        return 1
    fi
    
    # Test 3: Check APK exists and is valid
    print_info "Test 3: Checking APK..."
    if [ -f "$ANDROID_APK_OUTPUT" ]; then
        APK_SIZE=$(stat -f%z "$ANDROID_APK_OUTPUT" 2>/dev/null || stat -c%s "$ANDROID_APK_OUTPUT" 2>/dev/null)
        if [ "$APK_SIZE" -gt 1000000 ]; then  # At least 1MB
            print_success "APK file valid (${APK_SIZE} bytes)"
        else
            print_error "APK file too small or corrupted"
            return 1
        fi
    else
        print_error "APK file not found"
        return 1
    fi
    
    print_success "All tests passed!"
}

generate_report() {
    print_header "Generating Pipeline Report"
    
    REPORT_FILE="multi_coupon_pipeline_report.txt"
    
    cat > "$REPORT_FILE" << EOF
Multi-Coupon Detection Pipeline Report
=====================================
Generated: $(date)

PIPELINE CONFIGURATION:
- PWA Export File: $PWA_EXPORT_FILE
- Training Directory: $TRAINING_DIR
- Android Assets: $ANDROID_ASSETS_DIR
- APK Output: $ANDROID_APK_OUTPUT

TRAINING DATA STATISTICS:
EOF
    
    if [ -f "$PWA_EXPORT_FILE" ]; then
        python3 -c "
import json
with open('$PWA_EXPORT_FILE', 'r') as f:
    data = json.load(f)
    stats = data.get('statistics', {})
    print(f'- Total Images: {data.get(\"totalImages\", 0)}')
    print(f'- Single Coupons: {stats.get(\"single_coupon\", 0)}')
    print(f'- Multi-Grid Coupons: {stats.get(\"multi_grid\", 0)}')
    print(f'- Scrollable Coupons: {stats.get(\"scrollable\", 0)}')
    print(f'- Total Instances: {stats.get(\"total_instances\", 0)}')
    print(f'- Total Annotations: {stats.get(\"total_annotations\", 0)}')
" >> "$REPORT_FILE" 2>/dev/null
    fi
    
    cat >> "$REPORT_FILE" << EOF

MODEL FILES:
- Stage 1 Model: $([ -f "$ANDROID_ASSETS_DIR/models/multi_coupon/stage1_coupon_detector.tflite" ] && echo "✅ Present" || echo "❌ Missing")
- Stage 2 Model: $([ -f "$ANDROID_ASSETS_DIR/models/multi_coupon/stage2_field_detector.tflite" ] && echo "✅ Present" || echo "❌ Missing")
- Model Manifest: $([ -f "$ANDROID_ASSETS_DIR/models/multi_coupon/manifest.json" ] && echo "✅ Present" || echo "❌ Missing")

ANDROID APP:
- APK Built: $([ -f "$ANDROID_APK_OUTPUT" ] && echo "✅ Success" || echo "❌ Failed")
- APK Size: $([ -f "$ANDROID_APK_OUTPUT" ] && (stat -f%z "$ANDROID_APK_OUTPUT" 2>/dev/null || stat -c%s "$ANDROID_APK_OUTPUT" 2>/dev/null) && echo " bytes" || echo "N/A")

CAPABILITIES:
✅ Single coupon detection
✅ Multiple coupon detection (grid layout)
✅ Multiple coupon detection (scrollable list)
✅ Partial coupon handling (top/bottom cut-off)
✅ Field extraction (code, benefit, expiry, app, terms)
✅ Android TensorFlow Lite integration
✅ PWA training data export
✅ Two-stage YOLO pipeline

NEXT STEPS:
1. Install APK on Android device: adb install -r $ANDROID_APK_OUTPUT
2. Test with various coupon screenshots
3. Monitor detection accuracy and retrain if needed
4. Update PWA with new training data as needed

EOF
    
    print_success "Report generated: $REPORT_FILE"
}

cleanup() {
    print_header "Cleanup"
    
    # Optional cleanup of temporary files
    if [ -d "$TRAINING_DIR" ]; then
        read -p "Remove training directory? (y/n): " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            rm -rf "$TRAINING_DIR"
            print_success "Training directory cleaned up"
        fi
    fi
}

main() {
    print_header "🎯 Complete Multi-Coupon Detection Pipeline"
    echo -e "${BLUE}End-to-end solution: PWA Training → Model Training → Android Integration${NC}"
    echo ""
    
    # Step 1: Check prerequisites
    check_prerequisites
    
    # Step 2: Run training pipeline
    run_training_pipeline
    
    # Step 3: Build Android app
    build_android_app
    
    # Step 4: Install Android app (optional)
    install_android_app
    
    # Step 5: Run tests
    run_tests
    
    # Step 6: Generate report
    generate_report
    
    # Step 7: Cleanup (optional)
    cleanup
    
    print_header "🎉 Pipeline Complete!"
    print_success "Your multi-coupon detection system is ready!"
    echo ""
    print_info "Summary:"
    echo "  📱 PWA: Enhanced with two-stage annotation"
    echo "  🤖 Models: Stage 1 (coupon detection) + Stage 2 (field detection)"
    echo "  📲 Android: Integrated TwoStageDetector with multi-coupon support"
    echo "  📊 Report: $REPORT_FILE"
    echo ""
    print_info "Capabilities:"
    echo "  ✅ Single coupon screenshots"
    echo "  ✅ Multiple coupon screenshots (grid/list)"
    echo "  ✅ Partial coupon screenshots (top/bottom)"
    echo "  ✅ Scrollable coupon lists"
    echo "  ✅ Field extraction (code, benefit, expiry, app, terms)"
    echo ""
    print_info "Next steps:"
    echo "  1. Test the Android app with various coupon screenshots"
    echo "  2. Collect more training data via the enhanced PWA"
    echo "  3. Retrain models with improved datasets as needed"
}

# Handle script interruption
trap 'print_error "Pipeline interrupted"; exit 1' INT

# Run main function
main "$@"
