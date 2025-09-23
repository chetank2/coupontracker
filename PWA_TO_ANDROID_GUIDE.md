# PWA → Android Model Update Guide

## 🎯 Complete Workflow: PWA Training → Android App Update

The system already has a complete pipeline to update your Android app with models trained from PWA data.

## 📱 Step 1: Export Data from PWA

1. **Open PWA** (running on `http://localhost:8081` or GitHub Pages)
2. **Go to Data Management** page
3. **Select annotations** you want to use for training
4. **Click "Export Selected"** - downloads `coupon-data-export-YYYY-MM-DD.json`

## 🌐 Step 2: Import to Web Training System

### Option A: Web Interface Upload
```bash
# Start the web training system
cd /Users/user/Downloads/CouponTracker3/coupon-training
python3 web_ui/fixed_app.py

# Open http://localhost:5002
# Go to "Training" page
# Upload your PWA export JSON file
# Click "Start Training"
```

### Option B: Command Line Import
```bash
# Use the import script we created
cd /Users/user/Downloads/CouponTracker3
python3 import_pwa_data.py coupon-data-export-2025-09-23.json --auto-train
```

## 🤖 Step 3: Model Training (Automatic)

The web system automatically:
- ✅ Processes PWA annotations
- ✅ Trains YOLOv8 model
- ✅ Evaluates performance
- ✅ Converts to TensorFlow Lite
- ✅ Packages for Android

## 📦 Step 4: Update Android App (Automatic)

The existing `update_app.py` automatically:
- ✅ Copies trained model to `app/src/main/assets/models/active/`
- ✅ Updates model manifest
- ✅ Creates model metadata
- ✅ Generates checksums

```bash
# Manual trigger (if needed)
cd /Users/user/Downloads/CouponTracker3/coupon-training
python3 update_app.py
```

## 📱 Step 5: Build & Install Android App

```bash
# Build the Android app with updated model
cd /Users/user/Downloads/CouponTracker3
./gradlew assembleDebug

# Install on device
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 🔄 **Complete Automated Workflow**

```bash
#!/bin/bash
# Complete PWA → Android Update Script

echo "🎯 Starting PWA → Android Model Update..."

# 1. Start web training system
cd /Users/user/Downloads/CouponTracker3/coupon-training
python3 web_ui/fixed_app.py &
WEB_PID=$!

# 2. Wait for web system to start
sleep 10

# 3. Import PWA data and train
python3 ../import_pwa_data.py ../pwa-export.json --auto-train

# 4. Update Android app
python3 update_app.py

# 5. Build Android app
cd ..
./gradlew assembleDebug

# 6. Stop web system
kill $WEB_PID

echo "✅ Complete! Android app updated with PWA-trained model"
```

## 📊 **Model Versioning & Management**

The system tracks:
- **Model versions** with timestamps
- **Training data sources** (PWA, web, batch)
- **Performance metrics** (accuracy, loss)
- **Model artifacts** (weights, manifests, checksums)

### Check Model Status:
```bash
# View current model in Android app
cat app/src/main/assets/models/active/manifest.json

# View training history
cat coupon-training/artifacts/packages/index.json
```

## 🎉 **That's It!**

Your PWA annotations automatically become production models in your Android app through the existing sophisticated training pipeline.

### Key Benefits:
- ✅ **Seamless Integration** - PWA → Web → Android
- ✅ **Version Control** - All models tracked and versioned
- ✅ **Quality Assurance** - Automatic evaluation and validation
- ✅ **Production Ready** - TensorFlow Lite optimization
- ✅ **Rollback Support** - Previous models preserved

The entire system is already built and ready to use!
