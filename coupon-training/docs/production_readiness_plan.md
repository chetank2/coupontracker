# Production-Grade Upgrade Plan

This document captures the end-to-end work required to evolve the coupon-training workspace into a production-quality computer-vision platform. Each section calls out implementation tasks, dependencies, environment considerations, and validation requirements. Items marked **[BLOCKER]** must be addressed before deploying any neural model to end users.

## 1. Data Pipeline & Storage

1. **Source Acquisition**
   - Integrate authenticated scraping for Reddit, Telegram, Instagram, and partner feeds.
   - Add support for vendor-provided coupon datasets (S3/Google Drive ingest).
   - [BLOCKER] Create `data_ingest/` package with pluggable collectors (requests + Selenium + API clients) and unit tests.
   - Maintain provenance metadata (`source`, `timestamp`, `terms`) in `data/catalog/manifest.parquet`.

2. **Data Validation**
   - Implement lightweight schema validation using `pydantic` to enforce annotation structure.
   - Add automated image sanity checks (resolution limits, color channels, corruption detection with PIL/OpenCV).
   - Fail ingestion runs if validation fails; store failures in `logs/ingest_failures.json`.

3. **Storage Strategy**
   - Promote git LFS or object storage (S3/Cloud Storage) for large datasets; only manifests stay in repo.
   - Introduce dataset versioning via DVC or LakeFS for reproducibility.

## 2. Labeling & Active Learning

1. **Web UI Enhancements**
   - Add an annotation queue fed by predicted difficulty (entropy/uncertainty from the model).
   - Record per-field confidence scores and annotate review status.
   - Enable keyboard shortcuts and bulk copy/paste of bounding boxes to speed labeling.

2. **Active Learning Loop**
   - After each training run, calculate uncertainty metrics and enqueue images needing manual review.
   - Provide `scripts/active_learning/select_uncertain.py` to export batches for annotators.

3. **Quality Assurance**
   - Add reviewer role with double-blind checks; disagreements trigger escalation.
   - Track labeler metrics (accuracy, throughput) in SQLite/PostgreSQL.

## 3. Model Architecture

1. **Detection**
   - Replace heuristics with a deep detector (YOLOv8, EfficientDet, or Detectron2). Provide training scripts under `ml/models/detectors/` with config-driven training (Hydra).
   - Provide CUDA-compatible environment files; CPU fallback for dev.

2. **Recognition**
   - Evaluate transformer-based OCR (TrOCR) or use Tesseract with fine-tuned language models.
   - For multilingual support, integrate PaddleOCR or Donut; store vocab configs in `ml/config/ocr/`.

3. **Field Parsing**
   - Replace regex-only pipeline with sequence-to-structure model (e.g., pointer-generator networks) or gradient boosting using contextual embeddings.
   - Provide fallback rules for rare cases; store them in `config/fallback_rules.yaml`.

## 4. Training Pipeline

1. **Orchestration**
   - Implement training entrypoint `ml/train.py` using PyTorch Lightning.
   - Add Hydra configs for dataset splits, augmentation, optimizer, and scheduler choices.

2. **Data Augmentation**
   - Use Albumentations for image transformations (blur, brightness, perspective) with reproducible seeds.
   - Provide synthetic coupon generator leveraging fonts and backgrounds.

3. **Evaluation Metrics**
   - Compute per-field precision/recall, F1, and calibration curves.
   - Log confusion matrices and qualitative samples (success/failure heatmaps).

4. **Experiment Tracking**
   - Integrate MLflow (server + artifact store) with auto-logging for metrics/params.
   - Provide `ml/experiments/README.md` describing how to launch tracking server locally or on cloud.

## 5. Deployment

1. **Model Packaging**
   - Export detectors/recognizers to ONNX/TFLite for Android consumption.
   - Maintain compatibility manifests describing required app-side preprocessing.

2. **CI/CD**
   - Configure GitHub Actions to run unit/integration tests, linting, and sample training smoke test (short epoch).
   - Add artifact upload (models + evaluation report) to release pipeline.

3. **Monitoring**
   - Define telemetry events in the Android app (field-level confidence, user corrections).
   - Build server dashboards (Grafana/ELK) to monitor real-world accuracy drift.

## 6. Security & Compliance

- Ensure scraping respects Terms of Service; where not possible, rely on official APIs or partner data agreements.
- Store potential PII securely; audit logs for access.
- Provide documentation for data retention policies and Right-to-Forget workflows.

## 7. Immediate Next Steps

1. Stand up dedicated `ml/` package with proper Python packaging (pyproject.toml).
2. Choose baseline detector (YOLOv8) and implement training on existing dataset.
3. Implement MLflow tracking and initial CI pipeline.
4. Refactor web UI backend to use the new experiment metadata.
5. Schedule follow-up audits to retire legacy scripts once parity is achieved.

---

This roadmap should be treated as a living document; update it as components land. Each major subsection should spawn detailed design docs before implementation.
