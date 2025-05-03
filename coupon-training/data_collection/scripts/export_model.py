#!/usr/bin/env python3
"""
Export Model for Android App

This script exports the trained model for use in the Android app.
"""

import os
import json
import shutil
from datetime import datetime

# Base directories
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL_DIR = os.path.join(BASE_DIR, 'model')
EXPORT_DIR = os.path.join(BASE_DIR, 'export')
APP_ASSETS_DIR = os.path.join(os.path.dirname(os.path.dirname(BASE_DIR)), 'app/src/main/assets')

def ensure_directories_exist():
    """Ensure all necessary directories exist."""
    os.makedirs(EXPORT_DIR, exist_ok=True)
    os.makedirs(APP_ASSETS_DIR, exist_ok=True)
    print("Directory structure verified")

def export_model():
    """Export the trained model for use in the Android app."""
    ensure_directories_exist()
    
    # Load model metadata
    metadata_path = os.path.join(MODEL_DIR, 'model_metadata.json')
    with open(metadata_path, 'r') as f:
        metadata = json.load(f)
    
    # Load model
    model_path = os.path.join(MODEL_DIR, 'coupon_model.json')
    with open(model_path, 'r') as f:
        model = json.load(f)
    
    # Create export package
    export_package = {
        "model": model,
        "metadata": metadata,
        "export_date": datetime.now().isoformat(),
        "version": "1.0.0",
        "format": "json"
    }
    
    # Save export package
    export_path = os.path.join(EXPORT_DIR, 'coupon_model_export.json')
    with open(export_path, 'w') as f:
        json.dump(export_package, f, indent=2)
    
    # Copy to app assets
    app_model_path = os.path.join(APP_ASSETS_DIR, 'coupon_model.json')
    shutil.copy2(export_path, app_model_path)
    
    print(f"Model exported to {export_path}")
    print(f"Model copied to app assets: {app_model_path}")

if __name__ == "__main__":
    export_model()
