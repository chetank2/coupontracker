#!/usr/bin/env python3
"""
Update App - Updates the Android app with the trained model.

This module provides functions to update the Android app with the trained model.
"""

import os
import json
import shutil
import logging
import argparse
import datetime

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("update_app.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("update_app")

def update_app_model(models_dir="models", app_dir="../app/src/main/assets"):
    """Update the Android app with the trained model.
    
    Args:
        models_dir (str): Directory containing the trained model
        app_dir (str): Directory to save the model in the app
        
    Returns:
        bool: True if successful, False otherwise
    """
    try:
        # Check if the models directory exists
        if not os.path.exists(models_dir):
            logger.error(f"Models directory not found: {models_dir}")
            return False
        
        # Check if the pattern file exists
        pattern_file = os.path.join(models_dir, "patterns.json")
        if not os.path.exists(pattern_file):
            logger.error(f"Pattern file not found: {pattern_file}")
            return False
        
        # Create the app directory if it doesn't exist
        os.makedirs(app_dir, exist_ok=True)
        
        # Copy the pattern file to the app
        app_pattern_file = os.path.join(app_dir, "patterns.json")
        shutil.copy(pattern_file, app_pattern_file)
        
        # Get the model version
        history_file = os.path.join(models_dir, "history.json")
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
        
        # Create a metadata file in the app
        metadata = {
            "model_version": model_version,
            "update_date": datetime.datetime.now().isoformat(),
            "num_patterns": 0
        }
        
        # Count the number of patterns
        with open(pattern_file, 'r') as f:
            patterns = json.load(f)
            metadata["num_patterns"] = len(patterns)
        
        # Save the metadata
        metadata_file = os.path.join(app_dir, "model_metadata.json")
        with open(metadata_file, 'w') as f:
            json.dump(metadata, f, indent=2)
        
        logger.info(f"Updated app model to version {model_version}")
        logger.info(f"Number of patterns: {metadata['num_patterns']}")
        logger.info(f"Pattern file: {app_pattern_file}")
        logger.info(f"Metadata file: {metadata_file}")
        
        return True
    
    except Exception as e:
        logger.error(f"Error updating app model: {e}")
        return False

def main():
    """Main function."""
    parser = argparse.ArgumentParser(description="Update the Android app with the trained model")
    parser.add_argument("--models-dir", default="models", help="Directory containing the trained model")
    parser.add_argument("--app-dir", default="../app/src/main/assets", help="Directory to save the model in the app")
    
    args = parser.parse_args()
    
    success = update_app_model(models_dir=args.models_dir, app_dir=args.app_dir)
    
    if success:
        print("App updated successfully.")
    else:
        print("Error: App update failed.")

if __name__ == "__main__":
    main()
