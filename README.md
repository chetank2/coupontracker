# CouponTracker - Complete Coupon Recognition System

A comprehensive coupon recognition system featuring both mobile Android app and web-based training interfaces with advanced ML capabilities.

## 🚀 System Overview

**Current Branch**: `main` ✅ **Production Ready**

This repository contains a complete production-ready coupon recognition system with:

### 📱 **Android Mobile App** ⭐ **NEW: LLM Integration**
- **🧠 Qwen2-1.5B-Instruct (Default)**: Lightweight on-device LLM with fast inference
- **🧠 MiniCPM-Llama3-V2.5 (Legacy Optional)**: Vision-enabled LLM for multimodal extraction
- **🎯 Two-Stage Multi-Coupon Detection**: YOLO-based detection with interactive boundary adjustment
- **🔄 Smart Fallback Chain**: LLM → Model-Based → Pattern → MLKit OCR with quality validation
- **📝 Deferred Persistence**: Preview-before-save workflow with duplicate detection
- **🎨 Enhanced UI/UX**: Comprehensive state management and user feedback
- Advanced OCR with Phase 4 ROI integration
- Multi-engine text recognition (ML Kit, Tesseract, Custom models)
- Intelligent coupon field extraction
- Offline-first architecture with local storage
- Smart capture and batch processing capabilities

### 🌐 **Web Training Interface**
- Complete ML training pipeline with orchestration
- Real-time annotation and pre-annotation services
- Model registry and automated packaging
- Comprehensive evaluation framework
- MLflow integration for experiment tracking

### 📱 **Mobile PWA for Offline Training**
- Touch-based annotation system for mobile devices
- Offline functionality with IndexedDB storage
- Service Worker for complete offline capability
- Progressive Web App installable on mobile
- Drag & drop upload interface

## 🏗️ Architecture Components

### **Core Training Pipeline**
- `coupon_scraper.py`: Web scraping for coupon data collection
- `image_processor.py`: Advanced image preprocessing and enhancement
- `coupon_annotator.py`: Intelligent field detection and annotation
- `coupon_trainer_cli.py`: CLI for model training workflows
- `update_app.py`: Android app model deployment

### **ML Training System** (`coupon-training/`)
- **Dataset Management**: Version control and organization
- **Pre-annotation**: Intelligent candidate generation
- **Training Orchestrator**: Job scheduling and monitoring
- **Model Registry**: Packaging and deployment pipeline
- **Evaluation Framework**: Golden sets and metrics tracking

### **Mobile PWA** (`mobile-coupon-trainer/`)
- **Progressive Web App**: Offline-capable mobile interface
- **Touch Annotation**: Mobile-optimized annotation tools
- **Local Storage**: IndexedDB for offline data persistence
- **Service Worker**: Complete offline functionality

## 🚀 Quick Start

### **Run Web Training Interface**
```bash
cd coupon-training
python3 run_web_ui.py
# Access at http://localhost:5002
```

### **Run Mobile PWA**
```bash
cd mobile-coupon-trainer
python3 -m http.server 8080
# Access at http://localhost:8080
```

### **Train Models**
```bash
python coupon_trainer_cli.py --url <URL> --output-dir <OUTPUT_DIR>
```

### **Build Android App**
```bash
./gradlew assembleDebug
```

## 🧪 Testing

### **Unit Tests (Local JVM Stubs)**
Android projects generated with the Android Gradle Plugin do **not** expose the generic `testClasses` task that plain JVM Gradle projects provide. Instead, run the unit-test tasks that correspond to the build variant you want to verify:

```bash
# Debug build variant
./gradlew testDebugUnitTest

# Release build variant
./gradlew testReleaseUnitTest

# Run both debug and release unit tests
./gradlew test
```

These commands execute the Robolectric-style JVM tests that exercise your view models, repositories, and other non-instrumented components. Pick the build variant that matches the code you are validating.

### **Instrumentation (Connected) Tests**
Run the connected test suite to verify on-device TensorFlow Lite integration, including the `TwoStageDetectorProductionTest` that exercises the production detector with the device/emulator's native TensorFlow Lite runtime:

```bash
./gradlew connectedAndroidTest
```

For CI or local scripting workflows, use the helper wrapper which simply forwards to the same Gradle task from the project root:

```bash
./scripts/run_connected_android_tests.sh
```

## 📊 Key Features

### **Advanced OCR Pipeline**
- ROI-based text extraction with confidence scoring
- Multi-engine OCR with fallback mechanisms
- Intelligent field recognition and validation
- Real-time processing with comprehensive metrics

### **Production ML Pipeline**
- End-to-end training workflow automation
- Continuous learning and model improvement
- Automated model packaging and deployment
- Comprehensive evaluation and testing framework

### **Mobile-First Design**
- PWA installable on mobile devices
- Touch-optimized annotation interface
- Offline-first architecture
- Cross-platform compatibility

## 🛠️ Technical Stack

- **Android**: Kotlin, Jetpack Compose, Room DB, ML Kit
- **Backend**: Python, Flask, MLflow, PyTorch
- **ML Models**: Qwen2-1.5B-Instruct (default), MiniCPM-Llama3-V2.5 (optional vision), YOLOv8, Custom OCR models, TensorFlow Lite
- **Frontend**: Progressive Web App, IndexedDB, Service Workers
- **Infrastructure**: Docker, Gradle, Git LFS

## 📋 Requirements

- **Android Development**: Android Studio, SDK 24+
- **Python Environment**: Python 3.8+, pip packages from requirements.txt
- **ML Training**: CUDA-capable GPU (recommended)
- **Web Development**: Modern browser with PWA support

## 🏆 Production Ready

This system is designed for enterprise deployment with:
- Comprehensive error handling and logging
- Automated testing and validation pipelines
- Production deployment scripts and monitoring
- Scalable architecture with containerization support

## 📄 License

This project is licensed under the MIT License - see the LICENSE file for details.

## 🤝 Contributing

We welcome improvements, bug fixes, and documentation updates from the community. To contribute:

1. Review and acknowledge the [Coupon Extraction Guardrail Rules](docs/ai_guardrails/COUPON_EXTRACTION_RULES.md) and the [AI Editing Checklist](docs/ai_guardrails/AI_EDITING_CHECKLIST.md) in your pull request description. Every contributor (human or AI) must confirm these policies before pushing changes.
2. Fork the repository and create a feature branch.
3. Make your changes and ensure all relevant tests pass.
4. Submit a pull request with a clear description of the updates, checklist verification, and any testing performed.

For significant updates, consider opening an issue first so we can discuss the proposed changes before you begin development.
