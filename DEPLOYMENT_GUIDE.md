# 🚀 CouponTracker Deployment Guide

Complete deployment guide for the CouponTracker system with both mobile PWA and ML training interfaces.

## 📋 System Overview

The CouponTracker system consists of three main components:

1. **📱 Android Mobile App** - Native Android app for coupon recognition
2. **🌐 ML Training Web Interface** - Web-based training and annotation system  
3. **📱 Mobile PWA** - Progressive Web App for offline coupon annotation

## 🎯 Quick Start Deployment

### 1. 📱 Mobile PWA (Offline Annotation)

**Branch:** `mobile-pwa-final`

```bash
# Clone and switch to PWA branch
git clone https://github.com/chetank2/coupontracker.git
cd coupontracker
git checkout mobile-pwa-final

# Serve the PWA locally
cd mobile-coupon-trainer
python3 -m http.server 8080

# Access at: http://localhost:8080
```

**Features:**
- ✅ Complete offline functionality
- ✅ Touch-based annotation for 5 coupon field types
- ✅ IndexedDB local storage
- ✅ PWA installable on mobile devices
- ✅ Works without internet connection

### 2. 🤖 ML Training Web Interface

**Branch:** `feature/phase4-roi-ocr-final`

```bash
# Switch to ML training branch
git checkout feature/phase4-roi-ocr-final

# Install dependencies
cd coupon-training
pip3 install -r requirements.txt

# Start the training interface (simplified version)
cd ../web_ui
python3 fixed_app.py

# Access at: http://localhost:5000
```

**Features:**
- ✅ Complete ML training pipeline
- ✅ Dataset management and annotation
- ✅ Model training and evaluation
- ✅ MLflow integration for experiment tracking
- ✅ Real-time training metrics

### 3. 📱 Android Mobile App

**Branch:** `main` or `feature/phase4-roi-ocr-final`

```bash
# Build Android app
./gradlew assembleDebug

# Install APK
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 🌐 Production Deployment Options

### Option A: GitHub Pages (Mobile PWA)

```bash
# Deploy PWA to GitHub Pages
git checkout mobile-pwa-final
# Enable GitHub Pages in repository settings
# Point to mobile-coupon-trainer/ directory
```

### Option B: Netlify/Vercel (Mobile PWA)

```bash
# Deploy PWA to Netlify
cd mobile-coupon-trainer
# Drag and drop folder to Netlify dashboard
# Or connect GitHub repository
```

### Option C: Docker Deployment (ML Training)

```bash
# Build Docker container
cd coupon-training
docker build -t coupon-trainer .
docker run -p 5000:5000 coupon-trainer
```

### Option D: Cloud Deployment (Full System)

#### AWS/Google Cloud/Azure:
- **PWA**: Deploy to S3/Cloud Storage + CloudFront/CDN
- **ML Training**: Deploy to EC2/Compute Engine/App Service
- **Android App**: Publish to Google Play Store

## 📊 System Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Android App   │    │   Mobile PWA    │    │  ML Training    │
│                 │    │                 │    │   Web Interface │
│ • Native UI     │    │ • Offline PWA   │    │ • Training UI   │
│ • OCR Pipeline  │    │ • Touch Anno.   │    │ • Model Mgmt    │
│ • Local Storage │    │ • IndexedDB     │    │ • MLflow        │
│ • TFLite Models │    │ • Service Worker│    │ • Evaluation    │
└─────────────────┘    └─────────────────┘    └─────────────────┘
        │                        │                        │
        └──────────── Data Flow ──────────────────────────┘
```

## 🔧 Configuration

### Environment Variables

```bash
# ML Training Interface
export FLASK_ENV=production
export MODEL_PATH=/path/to/models
export DATA_PATH=/path/to/training/data

# Mobile PWA
export PWA_CACHE_VERSION=v1
export PWA_OFFLINE_MODE=true
```

### Database Setup

```bash
# Initialize training database
cd coupon-training
python3 -c "from ml.data.database import init_db; init_db()"
```

## 📱 Mobile Installation

### PWA Installation (iOS/Android):
1. Open PWA URL in mobile browser
2. Tap "Add to Home Screen" (iOS) or "Install App" (Android)
3. App will work offline with full functionality

### Android APK:
1. Enable "Unknown Sources" in Android settings
2. Download APK from releases
3. Install and grant camera/storage permissions

## 🔍 Monitoring & Maintenance

### Health Checks:
```bash
# PWA Health Check
curl http://localhost:8080/manifest.json

# ML Training Health Check
curl http://localhost:5000/api/health

# Android App Logs
adb logcat | grep CouponTracker
```

### Performance Monitoring:
- **PWA**: Browser DevTools → Application → Service Workers
- **ML Training**: MLflow UI → Experiments → Metrics
- **Android**: Android Studio → Profiler

## 🚨 Troubleshooting

### Common Issues:

1. **PWA not installing**: Check manifest.json and service worker
2. **ML training errors**: Verify Python dependencies and model paths
3. **Android build fails**: Check Gradle and SDK versions
4. **Import errors**: Ensure all Python modules are properly installed

### Debug Commands:
```bash
# Check PWA service worker
console.log(navigator.serviceWorker.controller)

# Check ML training logs
tail -f coupon-training/logs/training.log

# Check Android app logs
adb logcat -s CouponTracker
```

## 📈 Scaling Considerations

### High Traffic:
- Use load balancer for ML training interface
- Deploy PWA to CDN for global access
- Implement caching strategies

### Data Storage:
- Use cloud storage for training datasets
- Implement backup strategies for user annotations
- Consider database sharding for large datasets

## 🎯 Next Steps

1. **Set up CI/CD pipeline** for automated deployments
2. **Implement monitoring** and alerting
3. **Add user authentication** for production use
4. **Scale infrastructure** based on usage patterns
5. **Optimize performance** based on metrics

---

🎉 **Your complete CouponTracker system is now ready for production deployment!**

For support or questions, refer to the individual README files in each component directory.
