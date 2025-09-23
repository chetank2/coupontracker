# Experiment Tracking Quickstart

## 1. Local MLflow server

```
python3 -m pip install mlflow[extras]
./scripts/run_mlflow_server.sh
```

Set environment variables before launching `ml/train.py` once implemented:

```
export MLFLOW_TRACKING_URI=http://127.0.0.1:5000
export MLFLOW_EXPERIMENT_NAME=coupon_unified_model
```

## 2. Remote Tracking (AWS example)

1. Create S3 bucket `coupon-ml-artifacts` and DynamoDB table `mlflow-runs`.
2. Run MLflow server on EC2/ECS:
   ```
   mlflow server \
     --backend-store-uri dynamodb://mlflow-runs \
     --default-artifact-root s3://coupon-ml-artifacts
   ```
3. Use IAM roles to secure access; rotate credentials via AWS Secrets Manager.

## 3. Metadata

Log at minimum:
- Model version/tag
- Dataset manifest digest
- Training/validation/test split details
- Hyperparameters (batch size, LR, augmentation profile)
- Evaluation metrics (per-field precision/recall/F1)
- Exported artifact checksums

## 4. CI Integration

Add a GitHub Action that spins up an ephemeral MLflow server using Docker and runs `pytest` + a smoke training job.
