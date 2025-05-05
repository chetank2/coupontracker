#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Multi-Coupon Workflow

This script runs the entire workflow for multi-coupon support:
1. Segment coupons from raw images
2. Train the multi-coupon detection model
3. Enhance the model for multi-coupon support
4. Integrate the model with the Android app
"""

import os
import sys
import logging
import argparse
import subprocess
from pathlib import Path

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("multi_coupon_workflow.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("multi_coupon_workflow")

def run_script(script_path, args):
    """
    Run a Python script with the given arguments

    Args:
        script_path (str): Path to the script
        args (list): List of arguments

    Returns:
        int: Return code
    """
    cmd = [sys.executable, script_path] + args
    logger.info(f"Running command: {' '.join(cmd)}")

    process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    stdout, stderr = process.communicate()

    if stdout:
        logger.info(stdout.decode())

    if stderr:
        logger.error(stderr.decode())

    return process.returncode

def main():
    """Main function"""
    parser = argparse.ArgumentParser(description="Run multi-coupon workflow")
    parser.add_argument("--data-dir", default="data",
                       help="Directory containing the training data")
    parser.add_argument("--output-dir", default="models/multi_coupon",
                       help="Directory to save the trained model")
    parser.add_argument("--app-dir", default="../app",
                       help="Directory containing the Android app")
    parser.add_argument("--skip-segmentation", action="store_true",
                       help="Skip coupon segmentation step")
    parser.add_argument("--skip-training", action="store_true",
                       help="Skip model training step")
    parser.add_argument("--skip-integration", action="store_true",
                       help="Skip app integration step")

    args = parser.parse_args()

    # Get the current directory
    current_dir = os.path.dirname(os.path.abspath(__file__))

    # Ensure directories exist
    os.makedirs(os.path.join(current_dir, args.data_dir), exist_ok=True)
    os.makedirs(os.path.join(current_dir, args.data_dir, "raw"), exist_ok=True)
    os.makedirs(os.path.join(current_dir, args.data_dir, "segmented"), exist_ok=True)
    os.makedirs(os.path.join(current_dir, args.data_dir, "processed"), exist_ok=True)
    os.makedirs(os.path.join(current_dir, args.data_dir, "annotated"), exist_ok=True)
    os.makedirs(os.path.join(current_dir, args.output_dir), exist_ok=True)

    # Step 1: Segment coupons
    if not args.skip_segmentation:
        logger.info("Step 1: Segmenting coupons...")
        script_path = os.path.join(current_dir, "scripts", "detect_multiple_coupons.py")
        script_args = [
            "--input-dir", os.path.join(current_dir, args.data_dir, "raw"),
            "--output-dir", os.path.join(current_dir, args.data_dir, "segmented"),
            "--visualize"
        ]

        if run_script(script_path, script_args) != 0:
            logger.error("Error segmenting coupons")
            return 1
    else:
        logger.info("Skipping coupon segmentation step")

    # Step 2: Train multi-coupon model
    if not args.skip_training:
        logger.info("Step 2: Training multi-coupon model...")
        script_path = os.path.join(current_dir, "scripts", "train_multi_coupon_model.py")
        script_args = [
            "--data-dir", os.path.join(current_dir, args.data_dir),
            "--output-dir", os.path.join(current_dir, args.output_dir)
        ]

        if run_script(script_path, script_args) != 0:
            logger.error("Error training multi-coupon model")
            return 1
    else:
        logger.info("Skipping model training step")

    # Step 3: Integrate with Android app
    if not args.skip_integration:
        logger.info("Step 3: Integrating with Android app...")
        script_path = os.path.join(current_dir, "scripts", "integrate_multi_coupon_model.py")
        script_args = [
            "--model-dir", os.path.join(current_dir, args.output_dir),
            "--app-dir", os.path.join(current_dir, args.app_dir)
        ]

        if run_script(script_path, script_args) != 0:
            logger.error("Error integrating with Android app")
            return 1
    else:
        logger.info("Skipping app integration step")

    logger.info("Multi-coupon workflow complete!")
    print("\nMulti-coupon workflow complete!")
    print(f"Segmented coupons: {os.path.join(current_dir, args.data_dir, 'segmented')}")
    print(f"Trained model: {os.path.join(current_dir, args.output_dir)}")
    print(f"Android app integration: {os.path.join(current_dir, args.app_dir)}")

    return 0

if __name__ == "__main__":
    sys.exit(main())
