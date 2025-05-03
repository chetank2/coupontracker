#!/usr/bin/env python3
"""
Main Orchestration Script for Coupon Data Collection and Annotation

This script coordinates the entire process of collecting, preprocessing,
detecting outliers, and setting up annotation for coupon images.
"""

import os
import argparse
import logging
import subprocess
import sys
import json
from pathlib import Path

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("main_process.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("main_process")

# Base directory
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
SCRIPTS_DIR = os.path.join(BASE_DIR, 'scripts')

def run_script(script_name, args=None, cwd=None):
    """Run a Python script with the given arguments."""
    script_path = os.path.join(SCRIPTS_DIR, script_name)

    if not os.path.exists(script_path):
        logger.error(f"Script not found: {script_path}")
        return False

    cmd = [sys.executable, script_path]
    if args:
        cmd.extend(args)

    logger.info(f"Running: {' '.join(cmd)}")

    try:
        process = subprocess.Popen(
            cmd,
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            universal_newlines=True
        )

        # Stream output in real-time
        for line in process.stdout:
            logger.info(line.strip())

        # Wait for process to complete
        process.wait()

        # Check for errors
        if process.returncode != 0:
            for line in process.stderr:
                logger.error(line.strip())
            logger.error(f"Script {script_name} failed with return code {process.returncode}")
            return False

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

    success = run_script('reddit_scraper.py', script_args)

    if success:
        logger.info("Data collection completed successfully")
    else:
        logger.error("Data collection failed")

    return success

def preprocess_images(args):
    """Run the image preprocessing process."""
    logger.info("Starting image preprocessing")

    script_args = []
    if args.input_dir:
        script_args.extend(['--input-dir', args.input_dir])
    if args.output_dir:
        script_args.extend(['--output-dir', args.output_dir])

    success = run_script('image_preprocessor.py', script_args)

    if success:
        logger.info("Image preprocessing completed successfully")
    else:
        logger.error("Image preprocessing failed")

    return success

def detect_outliers(args):
    """Run the outlier detection process."""
    logger.info("Starting outlier detection")

    script_args = []
    if args.input_dir:
        script_args.extend(['--input-dir', args.input_dir])

    success = run_script('basic_outlier_detector.py', script_args)

    if success:
        logger.info("Outlier detection completed successfully")
    else:
        logger.error("Outlier detection failed")

    return success

def start_annotation_server(args):
    """Start the annotation server."""
    logger.info("Starting annotation server")

    script_args = []
    if args.host:
        script_args.extend(['--host', args.host])
    if args.port:
        script_args.extend(['--port', str(args.port)])

    # This will block until the server is stopped
    success = run_script('annotation_server.py', script_args)

    if success:
        logger.info("Annotation server stopped")
    else:
        logger.error("Annotation server failed")

    return success

def generate_dataset_stats():
    """Generate statistics about the collected dataset."""
    logger.info("Generating dataset statistics")

    stats = {
        'raw_images': {},
        'processed_images': {},
        'annotations': {},
        'outliers': {}
    }

    # Count raw images by source
    raw_dir = os.path.join(BASE_DIR, 'raw_images')
    for source in os.listdir(raw_dir):
        source_dir = os.path.join(raw_dir, source)
        if os.path.isdir(source_dir):
            count = len([f for f in os.listdir(source_dir) if os.path.isfile(os.path.join(source_dir, f))])
            stats['raw_images'][source] = count

    # Count processed images
    processed_dir = os.path.join(BASE_DIR, 'processed_images')
    if os.path.exists(processed_dir):
        count = 0
        for root, _, files in os.walk(processed_dir):
            count += len([f for f in files if os.path.isfile(os.path.join(root, f))])
        stats['processed_images']['total'] = count

    # Count annotations by status
    annotations_dir = os.path.join(BASE_DIR, 'annotations')
    if os.path.exists(annotations_dir):
        status_counts = {'unverified': 0, 'verified': 0, 'rejected': 0, 'needs_review': 0}

        for f in os.listdir(annotations_dir):
            if f.endswith('.json'):
                try:
                    with open(os.path.join(annotations_dir, f), 'r') as file:
                        data = json.load(file)
                        status = data.get('verification_status', 'unverified')
                        status_counts[status] = status_counts.get(status, 0) + 1
                except Exception as e:
                    logger.error(f"Error reading annotation file {f}: {e}")

        stats['annotations'] = status_counts

    # Count outliers by type
    outliers_dir = os.path.join(BASE_DIR, 'outliers')
    if os.path.exists(outliers_dir):
        for outlier_type in ['visual_outliers', 'content_outliers', 'mixed_outliers']:
            type_dir = os.path.join(outliers_dir, outlier_type)
            if os.path.exists(type_dir):
                count = len([f for f in os.listdir(type_dir) if os.path.isfile(os.path.join(type_dir, f))])
                stats['outliers'][outlier_type] = count

    # Save statistics
    stats_file = os.path.join(BASE_DIR, 'dataset_stats.json')
    with open(stats_file, 'w') as f:
        json.dump(stats, f, indent=2)

    logger.info(f"Dataset statistics saved to {stats_file}")

    return stats

def main():
    """Main function to orchestrate the entire process."""
    parser = argparse.ArgumentParser(description='Orchestrate coupon data collection and annotation.')
    subparsers = parser.add_subparsers(dest='command', help='Command to run')

    # Data collection command
    collect_parser = subparsers.add_parser('collect', help='Collect coupon images')
    collect_parser.add_argument('--subreddits', nargs='+', help='List of subreddits to search')
    collect_parser.add_argument('--search-terms', nargs='+', help='List of search terms')
    collect_parser.add_argument('--limit', type=int, help='Maximum number of posts to retrieve per search')

    # Preprocessing command
    preprocess_parser = subparsers.add_parser('preprocess', help='Preprocess coupon images')
    preprocess_parser.add_argument('--input-dir', help='Input directory containing raw images')
    preprocess_parser.add_argument('--output-dir', help='Output directory for processed images')

    # Outlier detection command
    outlier_parser = subparsers.add_parser('outliers', help='Detect outliers in coupon images')
    outlier_parser.add_argument('--input-dir', help='Input directory containing processed images')

    # Annotation server command
    annotate_parser = subparsers.add_parser('annotate', help='Start the annotation server')
    annotate_parser.add_argument('--host', help='Host to run the server on')
    annotate_parser.add_argument('--port', type=int, help='Port to run the server on')

    # Test real data command
    test_parser = subparsers.add_parser('test-real-data', help='Test with real Reddit data')
    test_parser.add_argument('--links', nargs='+', help='List of Reddit links to test')

    # Enhanced outlier detection command
    enhanced_outlier_parser = subparsers.add_parser('enhanced-outliers', help='Run enhanced outlier detection')
    enhanced_outlier_parser.add_argument('--input-dir', help='Input directory containing processed images')

    # Auto field detection command
    auto_detect_parser = subparsers.add_parser('auto-detect', help='Automatically detect fields in coupon images')
    auto_detect_parser.add_argument('--input-dir', help='Input directory containing processed images')
    auto_detect_parser.add_argument('--no-visualize', action='store_true', help='Disable visualization of detections')

    # Model training integration command
    model_parser = subparsers.add_parser('prepare-training', help='Prepare data for model training')
    model_parser.add_argument('--format', choices=['yolo', 'coco'], default='yolo', help='Annotation format')
    model_parser.add_argument('--train-split', type=float, default=0.7, help='Training data split ratio')
    model_parser.add_argument('--val-split', type=float, default=0.15, help='Validation data split ratio')
    model_parser.add_argument('--test-split', type=float, default=0.15, help='Test data split ratio')

    # Stats command
    subparsers.add_parser('stats', help='Generate dataset statistics')

    # All command
    all_parser = subparsers.add_parser('all', help='Run the entire process')
    all_parser.add_argument('--skip-collect', action='store_true', help='Skip data collection')
    all_parser.add_argument('--skip-preprocess', action='store_true', help='Skip preprocessing')
    all_parser.add_argument('--skip-outliers', action='store_true', help='Skip outlier detection')
    all_parser.add_argument('--skip-auto-detect', action='store_true', help='Skip automatic field detection')
    all_parser.add_argument('--skip-training-prep', action='store_true', help='Skip training data preparation')

    args = parser.parse_args()

    if args.command == 'collect':
        collect_data(args)
    elif args.command == 'preprocess':
        preprocess_images(args)
    elif args.command == 'outliers':
        detect_outliers(args)
    elif args.command == 'annotate':
        start_annotation_server(args)
    elif args.command == 'test-real-data':
        script_args = []
        if args.links:
            script_args.extend(['--links'] + args.links)
        run_script('test_real_data.py', script_args)
    elif args.command == 'enhanced-outliers':
        script_args = []
        if args.input_dir:
            script_args.extend(['--input-dir', args.input_dir])
        run_script('enhanced_outlier_detector.py', script_args)
    elif args.command == 'auto-detect':
        script_args = []
        if args.input_dir:
            script_args.extend(['--input-dir', args.input_dir])
        if args.no_visualize:
            script_args.append('--no-visualize')
        run_script('auto_field_detector.py', script_args)
    elif args.command == 'prepare-training':
        script_args = ['--format', args.format]
        script_args.extend(['--train-split', str(args.train_split)])
        script_args.extend(['--val-split', str(args.val_split)])
        script_args.extend(['--test-split', str(args.test_split)])
        run_script('model_training_integration.py', script_args)
    elif args.command == 'stats':
        generate_dataset_stats()
    elif args.command == 'all':
        if not args.skip_collect:
            if not collect_data(args):
                return

        if not args.skip_preprocess:
            if not preprocess_images(args):
                return

        if not args.skip_outliers:
            if not detect_outliers(args):
                return

        if not args.skip_auto_detect:
            logger.info("Running automatic field detection...")
            run_script('auto_field_detector.py')

        if not args.skip_training_prep:
            logger.info("Preparing data for model training...")
            run_script('model_training_integration.py', ['--format', 'yolo'])

        generate_dataset_stats()

        logger.info("All processing completed. Starting annotation server...")
        start_annotation_server(args)
    else:
        parser.print_help()

if __name__ == "__main__":
    main()
