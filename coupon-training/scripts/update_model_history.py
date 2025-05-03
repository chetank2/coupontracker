#!/usr/bin/env python3
import os
import json
import datetime
from pathlib import Path

def update_model_history(model_dir, pattern_file):
    """Update the model history file with a new version
    
    Args:
        model_dir (str): Directory containing the model
        pattern_file (str): Path to the pattern file
        
    Returns:
        str: Version string of the new model
    """
    # Create timestamp for version
    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    
    # Count patterns in the pattern file
    num_patterns = 0
    with open(pattern_file, 'r') as f:
        for line in f:
            if ':' in line and not line.startswith('#'):
                num_patterns += 1
    
    # Create new model entry
    new_model = {
        "version": timestamp,
        "timestamp": datetime.datetime.now().isoformat(),
        "pattern_file": os.path.abspath(pattern_file),
        "num_patterns": num_patterns
    }
    
    # Load existing history or create new one
    history_file = os.path.join(model_dir, "model_history.json")
    if os.path.exists(history_file):
        with open(history_file, 'r') as f:
            try:
                history = json.load(f)
            except json.JSONDecodeError:
                history = []
    else:
        history = []
    
    # Add new model to history
    history.append(new_model)
    
    # Save updated history
    with open(history_file, 'w') as f:
        json.dump(history, f, indent=2)
    
    print(f"Updated model history: {history_file}")
    print(f"New model version: {timestamp}")
    print(f"Number of patterns: {num_patterns}")
    
    return timestamp

if __name__ == "__main__":
    import argparse
    
    parser = argparse.ArgumentParser(description="Update model history")
    parser.add_argument("--model-dir", default="../models/simplified", help="Directory containing the model")
    parser.add_argument("--pattern-file", default="../models/simplified/coupon_patterns.txt", help="Path to the pattern file")
    
    args = parser.parse_args()
    
    update_model_history(args.model_dir, args.pattern_file)
