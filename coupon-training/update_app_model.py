#!/usr/bin/env python3
"""
Script to update the Android app with the new CRED coupon model.
"""

import os
import json
import shutil
import datetime

# Directories
MODELS_DIR = "models"
APP_DIR = "../app/src/main/assets"
PATTERN_FILE = os.path.join(MODELS_DIR, "patterns.json")
APP_PATTERN_FILE = os.path.join(APP_DIR, "patterns.json")

def update_app_model():
    """Update the Android app with the new model."""
    # Check if the pattern file exists
    if not os.path.exists(PATTERN_FILE):
        print(f"Pattern file not found: {PATTERN_FILE}")
        return False
    
    # Check if the app directory exists
    if not os.path.exists(APP_DIR):
        os.makedirs(APP_DIR, exist_ok=True)
    
    # Copy the pattern file to the app directory
    shutil.copy(PATTERN_FILE, APP_PATTERN_FILE)
    
    # Get the model version
    history_file = os.path.join(MODELS_DIR, "history.json")
    if os.path.exists(history_file):
        with open(history_file, 'r') as f:
            history = json.load(f)
        
        if history:
            latest_model = history[0]
            model_version = latest_model.get("version", "unknown")
        else:
            model_version = "unknown"
    else:
        model_version = "unknown"
    
    # Create a metadata file in the app directory
    metadata = {
        "model_version": model_version,
        "update_date": datetime.datetime.now().isoformat(),
        "num_patterns": 0
    }
    
    # Count the number of patterns
    with open(PATTERN_FILE, 'r') as f:
        patterns = json.load(f)
        metadata["num_patterns"] = len(patterns)
    
    # Save the metadata
    metadata_file = os.path.join(APP_DIR, "model_metadata.json")
    with open(metadata_file, 'w') as f:
        json.dump(metadata, f, indent=2)
    
    print(f"Updated app model to version {model_version}")
    print(f"Number of patterns: {metadata['num_patterns']}")
    print(f"Pattern file: {APP_PATTERN_FILE}")
    print(f"Metadata file: {metadata_file}")
    
    return True

def main():
    """Main function."""
    print("Updating Android app with new CRED coupon model...")
    success = update_app_model()
    
    if success:
        print("Update completed successfully.")
    else:
        print("Update failed.")

if __name__ == "__main__":
    main()
