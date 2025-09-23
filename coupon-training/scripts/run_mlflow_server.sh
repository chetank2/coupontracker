#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")"/.. && pwd)"
RUN_DIR="$PROJECT_ROOT/runs/mlflow"
BACKEND_URI="sqlite:///$RUN_DIR/mlflow.db"
ARTIFACT_ROOT="$RUN_DIR/artifacts"

mkdir -p "$RUN_DIR" "$ARTIFACT_ROOT"

source "$PROJECT_ROOT/.venv/bin/activate"

exec mlflow server \
  --backend-store-uri "$BACKEND_URI" \
  --default-artifact-root "file://$ARTIFACT_ROOT" \
  --host 127.0.0.1 \
  --port 5000
