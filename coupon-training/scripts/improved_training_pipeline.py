#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Improved Training Pipeline for Coupon Recognition
This script integrates all the improvements for training the coupon recognition model.
"""

import os
import sys
import argparse
import subprocess
import json
import shutil
from datetime import datetime

# Add parent directory to path
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

def run_command(command):
    """
    Run a shell command and print output

    Args:
        command: Command to run

    Returns:
        int: Return code
    """
    print(f"Running: {command}")
    process = subprocess.Popen(command, shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True)

    # Print output in real-time
    for line in process.stdout:
        print(line.strip())

    # Wait for process to complete
    process.wait()

    return process.returncode

def create_directory_structure(base_dir):
    """
    Create directory structure for the pipeline

    Args:
        base_dir: Base directory

    Returns:
        dict: Dictionary of directories
    """
    # Create timestamp for unique directory names
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    # Define directories
    dirs = {
        'base': base_dir,
        'raw': os.path.join(base_dir, 'raw'),
        'balanced': os.path.join(base_dir, 'balanced'),
        'augmented': os.path.join(base_dir, 'augmented'),
        'annotated': os.path.join(base_dir, 'annotated'),
        'balanced_annotated': os.path.join(base_dir, 'balanced_annotated'),
        'augmented_annotated': os.path.join(base_dir, 'augmented_annotated'),
        'features': os.path.join(base_dir, 'features'),
        'models': os.path.join(base_dir, 'models'),
        'results': os.path.join(base_dir, 'results'),
        'logs': os.path.join(base_dir, 'logs'),
        'temp': os.path.join(base_dir, 'temp', timestamp)
    }

    # Create directories
    for dir_path in dirs.values():
        os.makedirs(dir_path, exist_ok=True)

    return dirs

def main():
    parser = argparse.ArgumentParser(description="Improved training pipeline for coupon recognition")
    parser.add_argument("--base-dir", default="../data/improved_pipeline", help="Base directory for the pipeline")
    parser.add_argument("--input-dir", required=True, help="Directory containing input images")
    parser.add_argument("--annotation-dir", required=True, help="Directory containing annotation files")
    parser.add_argument("--skip-balance", action="store_true", help="Skip dataset balancing")
    parser.add_argument("--skip-augmentation", action="store_true", help="Skip data augmentation")
    parser.add_argument("--skip-feature-engineering", action="store_true", help="Skip feature engineering")
    parser.add_argument("--skip-training", action="store_true", help="Skip model training")
    parser.add_argument("--outlier-weight", type=float, default=0.5, help="Weight for outlier samples (0-1)")
    parser.add_argument("--standard-count", type=int, default=10, help="Number of standard coupons to generate")
    parser.add_argument("--price-specific-count", type=int, default=10, help="Number of price-specific coupons to generate")
    parser.add_argument("--dated-count", type=int, default=10, help="Number of dated coupons to generate")

    args = parser.parse_args()

    # Create directory structure
    dirs = create_directory_structure(args.base_dir)

    # Copy input data to raw directory
    print("\n=== Copying Input Data ===")
    for root, _, files in os.walk(args.input_dir):
        for file in files:
            if file.lower().endswith(('.jpg', '.jpeg', '.png')):
                src_path = os.path.join(root, file)
                dst_path = os.path.join(dirs['raw'], file)
                shutil.copy2(src_path, dst_path)

    # Copy annotations to annotated directory
    for root, _, files in os.walk(args.annotation_dir):
        for file in files:
            if file.lower().endswith('.json'):
                src_path = os.path.join(root, file)
                dst_path = os.path.join(dirs['annotated'], file)
                shutil.copy2(src_path, dst_path)

    print(f"Copied {len(os.listdir(dirs['raw']))} images and {len(os.listdir(dirs['annotated']))} annotations")

    # Step 1: Balance dataset
    if not args.skip_balance:
        print("\n=== Step 1: Balancing Dataset ===")
        balance_cmd = (
            f"python3 coupon-training/scripts/balance_reddit_dataset.py "
            f"--output-dir {dirs['balanced']} "
            f"--annotation-dir {dirs['balanced_annotated']} "
            f"--standard-count {args.standard_count} "
            f"--price-specific-count {args.price_specific_count} "
            f"--dated-count {args.dated_count}"
        )

        if run_command(balance_cmd) != 0:
            print("Error balancing dataset")
            return

    # Step 2: Data augmentation
    if not args.skip_augmentation:
        print("\n=== Step 2: Data Augmentation ===")

        # Determine input directories based on whether balancing was skipped
        input_dir = dirs['balanced'] if not args.skip_balance else dirs['raw']
        annotation_dir = dirs['balanced_annotated'] if not args.skip_balance else dirs['annotated']

        augmentation_cmd = (
            f"python3 coupon-training/scripts/enhanced_augmentation.py "
            f"--input-dir {input_dir} "
            f"--annotation-dir {annotation_dir} "
            f"--output-dir {dirs['augmented']} "
            f"--output-annotation-dir {dirs['augmented_annotated']} "
            f"--augmentation-types basic advanced realistic "
            f"--include-outliers "
            f"--outlier-weight {args.outlier_weight}"
        )

        if run_command(augmentation_cmd) != 0:
            print("Error performing data augmentation")
            return

    # Step 3: Feature engineering
    if not args.skip_feature_engineering:
        print("\n=== Step 3: Feature Engineering ===")

        # Determine input directories based on previous steps
        if not args.skip_augmentation:
            input_dir = dirs['augmented']
            annotation_dir = dirs['augmented_annotated']
        elif not args.skip_balance:
            input_dir = dirs['balanced']
            annotation_dir = dirs['balanced_annotated']
        else:
            input_dir = dirs['raw']
            annotation_dir = dirs['annotated']

        features_path = os.path.join(dirs['features'], 'coupon_features.csv')

        feature_cmd = (
            f"python3 coupon-training/scripts/feature_engineering.py "
            f"--image-dir {input_dir} "
            f"--annotation-dir {annotation_dir} "
            f"--output-path {features_path} "
            f"--analyze"
        )

        if run_command(feature_cmd) != 0:
            print("Error performing feature engineering")
            return

    # Step 4: Weighted training
    if not args.skip_training:
        print("\n=== Step 4: Weighted Training ===")

        # Determine input directories based on previous steps
        if not args.skip_augmentation:
            input_dir = dirs['augmented']
            annotation_dir = dirs['augmented_annotated']
        elif not args.skip_balance:
            input_dir = dirs['balanced']
            annotation_dir = dirs['balanced_annotated']
        else:
            input_dir = dirs['raw']
            annotation_dir = dirs['annotated']

        features_path = os.path.join(dirs['features'], 'coupon_features.csv')
        model_path = os.path.join(dirs['models'], 'coupon_classifier.joblib')

        training_cmd = (
            f"python3 coupon-training/scripts/weighted_training.py "
            f"--image-dir {input_dir} "
            f"--annotation-dir {annotation_dir} "
            f"--features-path {features_path} "
            f"--model-path {model_path} "
            f"--outlier-weight {args.outlier_weight}"
        )

        if run_command(training_cmd) != 0:
            print("Error training model")
            return

    # Create summary report
    print("\n=== Creating Summary Report ===")

    report = {
        'timestamp': datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        'pipeline_config': vars(args),
        'directories': dirs,
        'stats': {
            'raw_images': len([f for f in os.listdir(dirs['raw']) if f.lower().endswith(('.jpg', '.jpeg', '.png'))]),
            'raw_annotations': len([f for f in os.listdir(dirs['annotated']) if f.lower().endswith('.json')])
        }
    }

    # Add balanced stats if available
    if not args.skip_balance and os.path.exists(dirs['balanced']):
        report['stats']['balanced_images'] = len([f for f in os.listdir(dirs['balanced']) if f.lower().endswith(('.jpg', '.jpeg', '.png'))])
        report['stats']['balanced_annotations'] = len([f for f in os.listdir(dirs['balanced_annotated']) if f.lower().endswith('.json')])

    # Add augmented stats if available
    if not args.skip_augmentation and os.path.exists(dirs['augmented']):
        report['stats']['augmented_images'] = len([f for f in os.listdir(dirs['augmented']) if f.lower().endswith(('.jpg', '.jpeg', '.png'))])
        report['stats']['augmented_annotations'] = len([f for f in os.listdir(dirs['augmented_annotated']) if f.lower().endswith('.json')])

    # Save report
    report_path = os.path.join(dirs['results'], 'pipeline_report.json')
    with open(report_path, 'w') as f:
        json.dump(report, f, indent=2)

    print(f"Pipeline completed successfully. Report saved to {report_path}")

    # Print summary
    print("\n=== Pipeline Summary ===")
    print(f"Raw images: {report['stats']['raw_images']}")
    print(f"Raw annotations: {report['stats']['raw_annotations']}")

    if 'balanced_images' in report['stats']:
        print(f"Balanced images: {report['stats']['balanced_images']}")
        print(f"Balanced annotations: {report['stats']['balanced_annotations']}")

    if 'augmented_images' in report['stats']:
        print(f"Augmented images: {report['stats']['augmented_images']}")
        print(f"Augmented annotations: {report['stats']['augmented_annotations']}")

    print(f"\nAll output files are in: {args.base_dir}")

if __name__ == "__main__":
    main()
