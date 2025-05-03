#!/usr/bin/env python3
"""
Simple Model Trainer

This script prepares the sample coupon data for model training.
"""

import os
import json
import random
import shutil
from datetime import datetime

# Base directories
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SAMPLE_DATA_DIR = os.path.join(BASE_DIR, 'sample_data')
ANNOTATIONS_DIR = os.path.join(BASE_DIR, 'annotations')
TRAINING_DIR = os.path.join(BASE_DIR, 'training')

def ensure_directories_exist():
    """Ensure all necessary directories exist."""
    os.makedirs(TRAINING_DIR, exist_ok=True)
    os.makedirs(os.path.join(TRAINING_DIR, 'train'), exist_ok=True)
    os.makedirs(os.path.join(TRAINING_DIR, 'val'), exist_ok=True)
    os.makedirs(os.path.join(TRAINING_DIR, 'test'), exist_ok=True)
    print("Directory structure verified")

def prepare_training_data(train_split=0.7, val_split=0.15, test_split=0.15):
    """Prepare the sample coupon data for model training."""
    ensure_directories_exist()

    # Load sample coupon data
    summary_path = os.path.join(SAMPLE_DATA_DIR, 'sample_coupons.json')
    with open(summary_path, 'r') as f:
        coupons = json.load(f)

    # Shuffle the coupons
    random.shuffle(coupons)

    # Split the coupons
    num_coupons = len(coupons)
    num_train = int(num_coupons * train_split)
    num_val = int(num_coupons * val_split)

    train_coupons = coupons[:num_train]
    val_coupons = coupons[num_train:num_train+num_val]
    test_coupons = coupons[num_train+num_val:]

    # Process each split
    process_split(train_coupons, 'train')
    process_split(val_coupons, 'val')
    process_split(test_coupons, 'test')

    # Create training configuration
    config = {
        "training_date": datetime.now().isoformat(),
        "train_split": train_split,
        "val_split": val_split,
        "test_split": test_split,
        "num_train": len(train_coupons),
        "num_val": len(val_coupons),
        "num_test": len(test_coupons),
        "total_samples": num_coupons,
        "classes": ["store_name", "coupon_code", "expiry_date", "discount_amount", "min_purchase"]
    }

    # Save training configuration
    config_path = os.path.join(TRAINING_DIR, 'training_config.json')
    with open(config_path, 'w') as f:
        json.dump(config, f, indent=2)

    print(f"Prepared training data: {len(train_coupons)} train, {len(val_coupons)} val, {len(test_coupons)} test")

def process_split(coupons, split_name):
    """Process a split of coupons."""
    split_dir = os.path.join(TRAINING_DIR, split_name)

    # List all files in sample_data directory
    sample_files = os.listdir(SAMPLE_DATA_DIR)
    annotation_files = os.listdir(ANNOTATIONS_DIR)

    for i, coupon in enumerate(coupons):
        # Get source from the coupon
        source = coupon['source']

        # Find matching files
        matching_sample_files = [f for f in sample_files if f.startswith(source) and f.endswith('.txt')]
        matching_annotation_files = [f for f in annotation_files if f.startswith(source) and f.endswith('.json')]

        if matching_sample_files and matching_annotation_files:
            # Use the first matching file
            sample_file = matching_sample_files[0]
            annotation_file = matching_annotation_files[0]

            # Copy text file
            src_text_path = os.path.join(SAMPLE_DATA_DIR, sample_file)
            dst_text_path = os.path.join(split_dir, f"{split_name}_{source}_{i+1}.txt")
            shutil.copy2(src_text_path, dst_text_path)

            # Copy annotation file
            src_annotation_path = os.path.join(ANNOTATIONS_DIR, annotation_file)
            dst_annotation_path = os.path.join(split_dir, f"{split_name}_{source}_{i+1}.json")
            shutil.copy2(src_annotation_path, dst_annotation_path)

        print(f"Processed {split_name} sample {i+1}/{len(coupons)}: {coupon['store_name']} - {coupon['coupon_code']}")

if __name__ == "__main__":
    prepare_training_data()
