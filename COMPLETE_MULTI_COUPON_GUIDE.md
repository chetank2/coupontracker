# Complete Multi-Coupon Detection System

## 🎯 Overview

This is a **complete end-to-end multi-coupon detection system** that handles:

- **Single coupon screenshots** - Traditional single coupon detection
- **Multiple coupon screenshots** - Grid or list layouts with multiple coupons
- **Partial coupon screenshots** - Top or bottom portions cut off
- **Scrollable coupon lists** - Long lists requiring scrolling

## 🏗️ System Architecture

### Two-Stage Detection Pipeline

```
Input Image
     ↓
┌─────────────────┐
│   Stage 1:      │
│ Coupon Boundary │  ← Detects all coupon instances
│   Detection     │    (complete, partial_top, partial_bottom)
└─────────────────┘
     ↓
┌─────────────────┐
│   Stage 2:      │
│ Field Detection │  ← Detects fields within each coupon crop
│  (per coupon)   │    (code, benefit, expiry, app, terms)
└─────────────────┘
     ↓
Multi-Coupon Results
```

### Components

1. **PWA Coupon Trainer** - Enhanced for two-stage annotation
2. **Python Training Pipeline** - Trains YOLOv8 models for both stages
3. **Android TwoStageDetector** - Runs inference using TensorFlow Lite
4. **Multi-Coupon Selection UI** - Handles multiple coupon results

## 🚀 Quick Start

### 1. Run Complete Pipeline

```bash
./complete_multi_coupon_pipeline.sh
```

This script will:
- ✅ Check prerequisites
- ✅ Generate demo data (if needed)
- ✅ Train both stage models
- ✅ Build Android app with models
- ✅ Install APK (if device connected)
- ✅ Run integration tests
- ✅ Generate comprehensive report

### 2. Manual Steps

If you prefer manual control:

#### A. PWA Training Data Collection
1. Open `mobile-coupon-trainer/upload.html`
2. Upload coupon screenshots
3. **Stage 1**: Draw boundaries around each coupon instance
4. **Stage 2**: Select instance and annotate fields within it
5. Export training data as JSON

#### B. Model Training
```bash
python3 enhanced_multi_coupon_trainer.py \
    --pwa-export pwa_training_export.json \
    --output-dir training_data \
    --android-assets ./app/src/main/assets \
    --stage1-epochs 100 \
    --stage2-epochs 150
```

#### C. Android App Build
```bash
./gradlew clean assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 📱 PWA Enhanced Features

### Two-Stage Annotation Interface

#### Stage 1: Coupon Boundaries
- **Purpose**: Define where each coupon is located in the image
- **Classes**: 
  - `coupon_complete` - Full coupon visible
  - `coupon_partial_top` - Top portion cut off
  - `coupon_partial_bottom` - Bottom portion cut off
- **UI**: Green/orange/red boundary drawing tools

#### Stage 2: Field Detection
- **Purpose**: Annotate specific fields within each coupon
- **Classes**:
  - `code_region` - Coupon/promo codes
  - `benefit_region` - Discount amount/offer description
  - `expiry_region` - Expiration date
  - `app_region` - App/brand name
  - `terms_region` - Terms and conditions
- **UI**: Color-coded field annotation tools with instance selection

### Enhanced Storage System
- **Multi-coupon metadata** - Image classification, instance tracking
- **Two-stage annotations** - Separate storage for boundaries and fields
- **Training dataset export** - YOLO-compatible format generation

## 🤖 Android Integration

### TwoStageDetector Class

```kotlin
class TwoStageDetector(context: Context) {
    // Loads both stage1_coupon_detector.tflite and stage2_field_detector.tflite
    fun detectMultiCoupons(bitmap: Bitmap): List<CouponInstance>
}

data class CouponInstance(
    val id: String,
    val boundingBox: RectF,
    val status: CouponStatus,
    val confidence: Float,
    val fields: List<FieldDetection>,
    val cropBitmap: Bitmap
)
```

### Enhanced ScannerViewModel

The scanner now handles three scenarios:

1. **No coupons detected** → Fallback to traditional OCR
2. **Single coupon detected** → Process directly
3. **Multiple coupons detected** → Show selection interface

### Multi-Coupon Selection Activity

When multiple coupons are detected:
- Shows original image with overlay annotations
- Lists each detected coupon with preview
- Allows individual or batch processing
- Displays confidence scores and detected fields

## 🎯 Supported Coupon Scenarios

### 1. Single Coupon Screenshots
- **Description**: Traditional single coupon in image
- **Handling**: Direct processing through both stages
- **Example**: Screenshot of one coupon from an app

### 2. Multiple Coupon Grid
- **Description**: Multiple coupons arranged in grid layout
- **Handling**: Stage 1 detects all instances, Stage 2 processes each
- **Example**: App showing 4-6 coupons in a grid

### 3. Scrollable Coupon Lists
- **Description**: Long list of coupons, often with partial visibility
- **Handling**: Detects complete and partial coupons appropriately
- **Example**: Scrolled coupon list with top/bottom coupons cut off

### 4. Partial Coupon Screenshots
- **Description**: Screenshot with coupons partially visible
- **Handling**: Stage 1 classifies as partial_top or partial_bottom
- **Example**: User scrolled mid-coupon when taking screenshot

## 🔧 Technical Implementation

### Model Architecture

#### Stage 1: Coupon Detection Model
- **Input**: Full screenshot (640x640)
- **Output**: Coupon bounding boxes with status classification
- **Classes**: 3 (complete, partial_top, partial_bottom)
- **Format**: YOLOv8 → TensorFlow Lite

#### Stage 2: Field Detection Model
- **Input**: Cropped coupon instances (320x320)
- **Output**: Field bounding boxes within coupon
- **Classes**: 5 (code, benefit, expiry, app, terms)
- **Format**: YOLOv8 → TensorFlow Lite

### Data Flow

```
PWA Annotation → JSON Export → Python Training → TFLite Models → Android App
     ↓              ↓              ↓               ↓              ↓
Two-stage UI → Training Data → YOLO Training → Model Files → Multi-Coupon Detection
```

### File Structure

```
CouponTracker3/
├── mobile-coupon-trainer/           # Enhanced PWA
│   ├── js/
│   │   ├── storage.js              # Multi-coupon storage
│   │   └── annotation.js           # Two-stage annotation
│   └── upload.html                 # Enhanced UI
├── enhanced_multi_coupon_trainer.py # Training pipeline
├── app/src/main/kotlin/.../ml/
│   └── TwoStageDetector.kt         # Android detector
├── app/src/main/assets/models/
│   └── multi_coupon/               # TFLite models
└── complete_multi_coupon_pipeline.sh # Automation script
```

## 📊 Performance Characteristics

### Accuracy Expectations
- **Stage 1 (Coupon Detection)**: 85-95% accuracy
- **Stage 2 (Field Detection)**: 80-90% accuracy per field
- **Overall System**: 70-85% end-to-end accuracy

### Speed Performance
- **Stage 1 Inference**: ~200-500ms on mobile CPU
- **Stage 2 Inference**: ~100-300ms per coupon crop
- **Total Processing**: ~500ms-2s depending on coupon count

### Resource Usage
- **Model Size**: ~10-20MB total (both stages)
- **Memory**: ~100-200MB during inference
- **CPU**: Optimized for mobile ARM processors

## 🧪 Testing & Validation

### Test Scenarios

1. **Single Coupon Test**
   - Upload single coupon screenshot
   - Verify correct field extraction
   - Confirm single result handling

2. **Multi-Coupon Grid Test**
   - Upload 4-6 coupon grid screenshot
   - Verify all coupons detected
   - Test selection interface

3. **Scrollable List Test**
   - Upload scrolled coupon list
   - Verify partial coupon detection
   - Test boundary classification

4. **Edge Cases**
   - Very small coupons
   - Low quality images
   - Overlapping coupons
   - Unusual layouts

### Validation Metrics

- **Detection Rate**: % of coupons correctly identified
- **Field Accuracy**: % of fields correctly extracted
- **False Positives**: Non-coupon regions detected as coupons
- **Processing Speed**: Time per image/coupon

## 🔄 Training Data Collection

### PWA Annotation Workflow

1. **Upload Images**: Various coupon screenshot types
2. **Classify Image**: Single/multi_grid/scrollable
3. **Stage 1 Annotation**: Draw coupon boundaries
4. **Stage 2 Annotation**: Annotate fields per coupon
5. **Export Data**: Generate training dataset

### Data Quality Guidelines

- **Image Quality**: Clear, readable text
- **Variety**: Different apps, layouts, lighting
- **Boundary Accuracy**: Precise coupon boundaries
- **Field Precision**: Accurate field annotations
- **Balanced Dataset**: Mix of single/multi-coupon images

## 🚀 Deployment & Scaling

### Production Deployment

1. **Model Optimization**: Quantization for mobile deployment
2. **A/B Testing**: Compare with existing single-stage system
3. **Performance Monitoring**: Track detection accuracy
4. **Continuous Learning**: Regular model updates

### Scaling Considerations

- **Cloud Processing**: Offload heavy inference to server
- **Model Updates**: OTA model updates via app updates
- **Data Pipeline**: Automated training data collection
- **Quality Assurance**: Automated testing pipeline

## 📈 Future Enhancements

### Short Term
- **OCR Integration**: Text extraction from detected fields
- **Confidence Thresholding**: Adaptive confidence based on image quality
- **User Feedback**: Learn from user corrections

### Long Term
- **Real-time Detection**: Live camera detection
- **Multi-language Support**: International coupon formats
- **Advanced Layouts**: Complex coupon arrangements
- **Semantic Understanding**: Context-aware field extraction

## 🐛 Troubleshooting

### Common Issues

#### PWA Issues
- **Stage 2 not working**: Ensure Stage 1 boundaries are created first
- **Export fails**: Check browser storage limits
- **Slow annotation**: Reduce image size for better performance

#### Training Issues
- **Low accuracy**: Need more diverse training data
- **Overfitting**: Reduce epochs or add regularization
- **Memory errors**: Reduce batch size

#### Android Issues
- **Model not loading**: Check asset file paths
- **Slow inference**: Optimize model or use GPU delegation
- **Crashes**: Check memory usage and error handling

### Debug Tools

- **PWA Console**: Browser developer tools for JavaScript debugging
- **Android Logs**: `adb logcat | grep TwoStageDetector`
- **Model Validation**: Test models with known good inputs

## 📞 Support

For issues or questions:
1. Check the troubleshooting section above
2. Review the generated pipeline report
3. Test with demo data first
4. Validate each component individually

## 🎉 Success Metrics

Your implementation is successful when:

- ✅ PWA can annotate both single and multi-coupon images
- ✅ Training pipeline produces valid TFLite models
- ✅ Android app detects multiple coupons correctly
- ✅ Selection interface handles user choices properly
- ✅ End-to-end accuracy meets requirements (>70%)
- ✅ Processing speed is acceptable for mobile use (<3s)

**Congratulations! You now have a complete multi-coupon detection system!** 🎯
