#!/usr/bin/env bash
# Sync Android extraction assets to models/extraction/android-mirror/.
# Must be run from the repository root.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
exec "$SCRIPT_DIR/.venv/bin/python" -m extraction_eval.sync_mirror \
    --project-root "$REPO_ROOT" \
    "$@"
