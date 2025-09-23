# End-to-End Coupon Training Pipeline

This document describes the complete end-to-end implementation of the coupon training pipeline, including all phases from data collection to production deployment.

## 🚀 Overview

The system now implements a complete MLOps pipeline with:

- **Phase 1**: Foundation (Dataset Manager, Annotation Stack, Pre-annotation, Evaluation) ✅
- **Phase 2**: Training & Orchestration (Training Orchestrator, Hyperparameter Forms, Metrics, Model Registry) ✅
- **Phase 3**: Packaging & Registry Integration (Packager, Bundle Validation, Registry UI/API, CLI Tools) ✅
- **Phase 4**: Active Learning & Continuous Improvement (Android Logging, Continuous Learning, Release Playbook) ✅

## 🏗️ Architecture

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   Android App   │───▶│  Feedback API    │───▶│  Priority Queue │
└─────────────────┘    └──────────────────┘    └─────────────────┘
                                                         │
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│  Web UI         │◀───│  End-to-End      │◀───│  Active Learning│
│  Dashboard      │    │  Workflow        │    │  & Uncertainty  │
└─────────────────┘    └──────────────────┘    └─────────────────┘
                                │
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│  Model Registry │◀───│  Continuous      │◀───│  Golden Sets    │
│  & Packaging    │    │  Learning        │    │  & Regression   │
└─────────────────┘    └──────────────────┘    └─────────────────┘
```

## 📁 Project Structure

```
coupon-training/
├── ml/
│   ├── dataset_manager/          # Dataset versioning and management
│   ├── orchestrator/             # Training job orchestration
│   ├── packaging/                # Model packaging and registry
│   ├── evaluation/               # Golden sets and regression testing
│   ├── feedback/                 # Android feedback collection
│   ├── continuous_learning/      # Continuous improvement
│   └── workflow/                 # End-to-end workflow orchestration
├── web_ui/                       # Web interface and API
├── scripts/                      # CLI tools and utilities
└── data/                         # Data storage and artifacts
    ├── annotated/                # Annotated training data
    ├── golden_sets/              # Golden test sets
    ├── feedback/                 # Android feedback data
    └── releases/                 # Release artifacts
```

## 🚀 Quick Start

### 1. Start the Web UI

```bash
cd web_ui
python app.py
```

The web interface will be available at `http://localhost:8080`

### 2. Start the End-to-End Workflow

```bash
# Using CLI
python scripts/workflow_cli.py start

# Or using API
curl -X POST http://localhost:8080/api/workflow/start
```

### 3. Monitor the System

```bash
# Check workflow status
python scripts/workflow_cli.py status

# Check queue status
python scripts/workflow_cli.py queue

# View workflow metrics
curl http://localhost:8080/api/workflow/metrics
```

## 🔧 Components

### 1. Dataset Manager (`ml/dataset_manager/`)

- **Purpose**: Manages dataset versions, splits, and metadata
- **Features**:
  - Auto-builds manifests from `data/annotated`
  - Tracks train/val/test splits
  - Stores provenance in JSON
  - Version management

**Usage**:
```bash
# Create new dataset
python scripts/dataset_manager.py create --description "New dataset version"

# List datasets
python scripts/dataset_manager.py list

# Get dataset info
python scripts/dataset_manager.py info <version>
```

### 2. Training Orchestrator (`ml/orchestrator/`)

- **Purpose**: Manages training jobs with queue and status tracking
- **Features**:
  - FastAPI service for job management
  - Background job execution
  - MLflow integration
  - Job status monitoring

**Usage**:
```bash
# Start orchestrator
python scripts/run_orchestrator.py

# Submit training job
curl -X POST http://localhost:8001/jobs \
  -H 'Content-Type: application/json' \
  -d '{"config_path": "ml/config/training.yaml", "notes": "baseline"}'
```

### 3. Model Packaging (`ml/packaging/`)

- **Purpose**: Exports models to deployment-ready formats
- **Features**:
  - ONNX/TFLite conversion
  - INT8 quantization
  - Compliance validation
  - Registry management

**Usage**:
```bash
# Package model
python scripts/package_model.py --weights artifacts/detector/yolo/weights/best.pt

# List packages
python scripts/packaging_registry.py list

# Promote package
python scripts/packaging_registry.py promote <package_id>
```

### 4. Feedback Collection (`ml/feedback/`)

- **Purpose**: Collects failure feedback from Android app
- **Features**:
  - Android-side logging
  - Upload endpoint
  - Priority queue management
  - Uncertainty sampling

**Usage**:
```bash
# Check feedback queue
curl http://localhost:8080/api/feedback/queue

# Upload feedback (from Android)
curl -X POST http://localhost:8080/api/feedback/upload \
  -F "image=@failure_image.jpg" \
  -F "failure_type=detection_failed" \
  -F "confidence_scores={\"detection\": 0.3}"
```

### 5. Continuous Learning (`ml/continuous_learning/`)

- **Purpose**: Automates model improvement based on feedback
- **Features**:
  - Automatic retraining
  - Model comparison
  - Improvement triggers
  - Performance monitoring

**Usage**:
```bash
# Trigger manual retrain
python scripts/workflow_cli.py retrain

# Check retrain history
ls data/continuous_learning/results/
```

### 6. Golden Sets (`ml/evaluation/`)

- **Purpose**: Provides regression testing and validation
- **Features**:
  - Golden test sets
  - Regression testing
  - Performance validation
  - Quality assessment

**Usage**:
```bash
# Create golden set
python scripts/workflow_cli.py golden create --manifest data/manifests/full_manifest.json --name "baseline"

# Test against golden set
python scripts/workflow_cli.py golden test --golden-set "baseline"
```

### 7. Release Playbook (`scripts/release_playbook.py`)

- **Purpose**: Manages complete release process
- **Features**:
  - Automated release pipeline
  - Quality gates
  - Compliance validation
  - Notification system

**Usage**:
```bash
# Create release
python scripts/release_playbook.py create --model-name "coupon_detector" --version "v1.0.0"

# List releases
python scripts/release_playbook.py list

# Get release status
python scripts/release_playbook.py status <release_id>
```

## 🔄 End-to-End Workflow

### Automatic Workflow

The system runs continuously with these steps:

1. **Feedback Collection**: Collects failures from Android app
2. **Active Learning**: Identifies uncertain samples for annotation
3. **Continuous Learning**: Retrains model when enough samples available
4. **Model Comparison**: Compares new model with current model
5. **Release Management**: Creates releases when significant improvements found

### Manual Workflow

You can also trigger steps manually:

```bash
# Start workflow
python scripts/workflow_cli.py start

# Trigger retrain
python scripts/workflow_cli.py retrain

# Trigger release
python scripts/workflow_cli.py release v1.1.0 --description "Bug fixes"

# Stop workflow
python scripts/workflow_cli.py stop
```

## 📊 Monitoring

### Web Dashboard

Access the web dashboard at `http://localhost:8080` to:

- View training jobs and status
- Monitor feedback queue
- Check model performance
- Manage releases
- View workflow health

### CLI Monitoring

```bash
# Workflow status
python scripts/workflow_cli.py status

# Queue status
python scripts/workflow_cli.py queue

# Release status
python scripts/workflow_cli.py release-status
```

### API Endpoints

- `GET /api/workflow/status` - Workflow status
- `GET /api/workflow/metrics` - Detailed metrics
- `POST /api/workflow/start` - Start workflow
- `POST /api/workflow/stop` - Stop workflow
- `POST /api/workflow/retrain` - Manual retrain
- `POST /api/workflow/release` - Manual release
- `GET /api/feedback/queue` - Feedback queue
- `POST /api/feedback/upload` - Upload feedback

## 🛠️ Configuration

### Workflow Configuration

```python
from ml.workflow import WorkflowConfig

config = WorkflowConfig(
    auto_collect_feedback=True,
    enable_active_learning=True,
    enable_continuous_learning=True,
    auto_release=False,  # Manual release by default
    retrain_interval_hours=24,
    improvement_threshold=0.02,
)
```

### Environment Variables

```bash
export MLFLOW_TRACKING_URI=http://127.0.0.1:5000
export MLFLOW_EXPERIMENT_NAME=coupon_training
export WORKFLOW_ALERT_WEBHOOK=https://hooks.slack.com/...
```

## 🚨 Troubleshooting

### Common Issues

1. **Workflow not starting**
   ```bash
   # Check logs
   tail -f logs/workflow.log
   
   # Restart workflow
   python scripts/workflow_cli.py stop
   python scripts/workflow_cli.py start
   ```

2. **Training jobs failing**
   ```bash
   # Check orchestrator logs
   tail -f runs/orchestrator/logs/*.log
   
   # Check job status
   curl http://localhost:8001/jobs
   ```

3. **Feedback not uploading**
   ```bash
   # Check feedback queue
   curl http://localhost:8080/api/feedback/queue
   
   # Check upload endpoint
   curl -X POST http://localhost:8080/api/feedback/upload
   ```

### Health Checks

```bash
# Check all services
curl http://localhost:8080/api/workflow/status
curl http://localhost:8001/health
curl http://localhost:5000/health  # MLflow
```

## 📈 Performance

### Metrics Tracked

- **Detection**: mAP, mAP50, precision, recall
- **OCR**: Text accuracy, field extraction
- **Parsing**: Field parsing accuracy
- **Performance**: Inference time, model size
- **Workflow**: Queue size, retrain frequency, release cadence

### Optimization

- **Model Size**: Use INT8 quantization
- **Inference Speed**: Optimize preprocessing
- **Training Speed**: Use smaller batch sizes for continuous learning
- **Storage**: Clean up old artifacts regularly

## 🔒 Security

### Best Practices

- Store API keys in environment variables
- Use HTTPS in production
- Validate all inputs
- Monitor for suspicious activity
- Regular security updates

### Data Privacy

- No PII stored in feedback
- Anonymize device information
- Secure data transmission
- Regular data cleanup

## 🚀 Production Deployment

### Prerequisites

1. **Infrastructure**:
   - Kubernetes cluster or Docker Swarm
   - Persistent storage for data
   - Load balancer for API
   - Monitoring and logging

2. **Services**:
   - MLflow server
   - Database (PostgreSQL)
   - Message queue (Redis/RabbitMQ)
   - Object storage (S3/GCS)

### Deployment Steps

1. **Build Images**:
   ```bash
   docker build -t coupon-training:latest .
   ```

2. **Deploy Services**:
   ```bash
   kubectl apply -f k8s/
   ```

3. **Configure Monitoring**:
   ```bash
   # Set up Prometheus/Grafana
   # Configure alerting
   # Set up log aggregation
   ```

4. **Initialize Workflow**:
   ```bash
   # Create initial dataset
   # Set up golden sets
   # Start continuous learning
   ```

## 📚 API Reference

### Workflow API

- `POST /api/workflow/start` - Start workflow
- `POST /api/workflow/stop` - Stop workflow
- `GET /api/workflow/status` - Get status
- `GET /api/workflow/metrics` - Get metrics
- `POST /api/workflow/retrain` - Manual retrain
- `POST /api/workflow/release` - Manual release

### Feedback API

- `POST /api/feedback/upload` - Upload feedback
- `GET /api/feedback/queue` - Get queue status
- `POST /api/feedback/process/<path>` - Mark processed

### Training API

- `GET /api/orchestrator/jobs` - List jobs
- `POST /api/orchestrator/jobs` - Submit job
- `GET /api/orchestrator/jobs/<id>` - Get job status

### Packaging API

- `GET /api/packages` - List packages
- `POST /api/packages` - Create package
- `POST /api/packages/<id>/promote` - Promote package

## 🤝 Contributing

### Development Setup

1. **Clone Repository**:
   ```bash
   git clone <repository>
   cd coupon-training
   ```

2. **Install Dependencies**:
   ```bash
   pip install -r requirements.txt
   pip install -r requirements-ml.txt
   pip install -r requirements-orchestrator.txt
   ```

3. **Run Tests**:
   ```bash
   python -m pytest tests/
   ```

4. **Start Development**:
   ```bash
   python scripts/workflow_cli.py start
   ```

### Code Style

- Follow PEP 8
- Use type hints
- Write docstrings
- Add unit tests
- Update documentation

## 📄 License

This project is licensed under the MIT License - see the LICENSE file for details.

## 🙏 Acknowledgments

- Ultralytics for YOLOv8
- MLflow for experiment tracking
- FastAPI for web services
- Flask for web UI
- All contributors and users

---

**Status**: ✅ Complete - All phases implemented and integrated

**Last Updated**: 2024-01-20

**Version**: 1.0.0
