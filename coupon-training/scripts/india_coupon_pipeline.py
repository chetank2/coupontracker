#!/usr/bin/env python3
"""
Master Pipeline for Indian Coupon Recognition

This script orchestrates the entire pipeline for training a model to recognize Indian coupons:
1. Data collection from Reddit
2. Image preprocessing
3. Annotation
4. Data augmentation
5. Model training preparation
"""

import os
import argparse
import logging
import subprocess
import time
from pathlib import Path

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("india_coupon_pipeline.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("india_coupon_pipeline")

# Base directories
BASE_DIR = os.path.join('coupon-training', 'data', 'reddit_india')
SCRIPTS_DIR = os.path.join('coupon-training', 'scripts')

def run_script(script_name, args=None):
    """Run a Python script with the given arguments."""
    script_path = os.path.join(SCRIPTS_DIR, script_name)

    # Build command
    command = ['python3', script_path]
    if args:
        command.extend(args)

    logger.info(f"Running script: {' '.join(command)}")

    try:
        # Run the script
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

        # Stream output
        for line in process.stdout:
            logger.info(line.strip())

        # Wait for process to complete
        process.wait()

        # Check return code
        if process.returncode != 0:
            logger.error(f"Script {script_name} failed with return code {process.returncode}")
            for line in process.stderr:
                logger.error(line.strip())
            return False

        logger.info(f"Script {script_name} completed successfully")
        return True

    except Exception as e:
        logger.error(f"Error running script {script_name}: {e}")
        return False

def collect_data(args):
    """Run the data collection process."""
    logger.info("Starting data collection process")

    script_args = []
    if args.subreddits:
        script_args.extend(['--subreddits'] + args.subreddits)
    if args.search_terms:
        script_args.extend(['--search-terms'] + args.search_terms)
    if args.limit:
        script_args.extend(['--limit', str(args.limit)])

    success = run_script('india_coupon_scraper.py', script_args)

    if success:
        logger.info("Data collection completed successfully")
    else:
        logger.error("Data collection failed")

    return success

def preprocess_images(args):
    """Run the image preprocessing process."""
    logger.info("Starting image preprocessing process")

    script_args = []
    if args.input_dir:
        script_args.extend(['--input-dir', args.input_dir])
    if args.output_dir:
        script_args.extend(['--output-dir', args.output_dir])

    success = run_script('india_image_preprocessor.py', script_args)

    if success:
        logger.info("Image preprocessing completed successfully")
    else:
        logger.error("Image preprocessing failed")

    return success

def create_annotations(args):
    """Run the annotation creation process."""
    logger.info("Starting annotation creation process")

    script_args = []
    if args.input_dir:
        script_args.extend(['--input-dir', args.input_dir])
    if args.output_dir:
        script_args.extend(['--output-dir', args.output_dir])

    success = run_script('india_annotation_helper.py', script_args)

    if success:
        logger.info("Annotation creation completed successfully")
    else:
        logger.error("Annotation creation failed")

    return success

def augment_data(args):
    """Run the data augmentation process."""
    logger.info("Starting data augmentation process")

    script_args = []
    if args.input_dir:
        script_args.extend(['--input-dir', args.input_dir])
    if args.output_dir:
        script_args.extend(['--output-dir', args.output_dir])
    if args.num_augmentations:
        script_args.extend(['--num-augmentations', str(args.num_augmentations)])

    success = run_script('india_data_augmentation.py', script_args)

    if success:
        logger.info("Data augmentation completed successfully")
    else:
        logger.error("Data augmentation failed")

    return success

def prepare_training(args):
    """Run the training preparation process."""
    logger.info("Starting training preparation process")

    script_args = []
    if args.input_dir:
        script_args.extend(['--input-dir', args.input_dir])
    if args.output_dir:
        script_args.extend(['--output-dir', args.output_dir])
    if args.train_split:
        script_args.extend(['--train-split', str(args.train_split)])
    if args.val_split:
        script_args.extend(['--val-split', str(args.val_split)])
    if args.test_split:
        script_args.extend(['--test-split', str(args.test_split)])
    if args.format:
        script_args.extend(['--format', args.format])

    success = run_script('india_model_training.py', script_args)

    if success:
        logger.info("Training preparation completed successfully")
    else:
        logger.error("Training preparation failed")

    return success

def main():
    """Main function to orchestrate the entire pipeline."""
    parser = argparse.ArgumentParser(description='Orchestrate the Indian coupon recognition pipeline.')
    parser.add_argument('--skip-collect', action='store_true', help='Skip data collection step')
    parser.add_argument('--skip-preprocess', action='store_true', help='Skip image preprocessing step')
    parser.add_argument('--skip-annotate', action='store_true', help='Skip annotation creation step')
    parser.add_argument('--skip-augment', action='store_true', help='Skip data augmentation step')
    parser.add_argument('--skip-training-prep', action='store_true', help='Skip training preparation step')

    # Data collection arguments
    parser.add_argument('--subreddits', nargs='+', help='List of subreddits to search')
    parser.add_argument('--search-terms', nargs='+', help='List of search terms')
    parser.add_argument('--limit', type=int, help='Maximum number of posts to retrieve per search')

    # Directory arguments
    parser.add_argument('--input-dir', help='Input directory for the current step')
    parser.add_argument('--output-dir', help='Output directory for the current step')

    # Augmentation arguments
    parser.add_argument('--num-augmentations', type=int, help='Number of augmented versions to create per image')

    # Training preparation arguments
    parser.add_argument('--train-split', type=float, help='Proportion of data to use for training')
    parser.add_argument('--val-split', type=float, help='Proportion of data to use for validation')
    parser.add_argument('--test-split', type=float, help='Proportion of data to use for testing')
    parser.add_argument('--format', choices=['yolo', 'original'], help='Format to convert annotations to')

    args = parser.parse_args()

    # Create base directory if it doesn't exist
    os.makedirs(BASE_DIR, exist_ok=True)

    # Run the pipeline
    start_time = time.time()

    if not args.skip_collect:
        if not collect_data(args):
            return

    if not args.skip_preprocess:
        if not preprocess_images(args):
            return

    if not args.skip_annotate:
        if not create_annotations(args):
            return

    if not args.skip_augment:
        if not augment_data(args):
            return

    if not args.skip_training_prep:
        if not prepare_training(args):
            return

    end_time = time.time()
    elapsed_time = end_time - start_time

    logger.info(f"Pipeline completed successfully in {elapsed_time:.2f} seconds")

if __name__ == "__main__":
    main()
