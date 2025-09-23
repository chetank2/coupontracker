#!/usr/bin/env python3
"""
Model Training Integration for Indian Coupon Recognition

This script prepares the augmented data for model training:
- Splits the data into training, validation, and test sets
- Converts annotations to the required format
- Generates configuration files for training
"""

import os
import json
import random
import shutil
import argparse
import logging
from pathlib import Path
from tqdm import tqdm

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("india_model_training.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("india_model_training")

# Base directories
BASE_DIR = os.path.join('coupon-training', 'data', 'reddit_india')
AUGMENTED_DIR = os.path.join(BASE_DIR, 'augmented')
TRAINING_DIR = os.path.join(BASE_DIR, 'training')

def create_training_directories():
    """Create directories for training, validation, and test sets."""
    os.makedirs(TRAINING_DIR, exist_ok=True)
    os.makedirs(os.path.join(TRAINING_DIR, 'train', 'images'), exist_ok=True)
    os.makedirs(os.path.join(TRAINING_DIR, 'train', 'labels'), exist_ok=True)
    os.makedirs(os.path.join(TRAINING_DIR, 'val', 'images'), exist_ok=True)
    os.makedirs(os.path.join(TRAINING_DIR, 'val', 'labels'), exist_ok=True)
    os.makedirs(os.path.join(TRAINING_DIR, 'test', 'images'), exist_ok=True)
    os.makedirs(os.path.join(TRAINING_DIR, 'test', 'labels'), exist_ok=True)

def convert_annotation_to_yolo(annotation, class_mapping):
    """Convert annotation to YOLO format."""
    image_width = annotation["image_width"]
    image_height = annotation["image_height"]
    regions = annotation["regions"]
    
    yolo_annotations = []
    
    for region_name, coords in regions.items():
        if region_name in class_mapping:
            class_id = class_mapping[region_name]
            
            # Convert coordinates to YOLO format
            x_min, y_min, x_max, y_max = coords
            
            # Normalize coordinates
            x_center = (x_min + x_max) / 2 / image_width
            y_center = (y_min + y_max) / 2 / image_height
            width = (x_max - x_min) / image_width
            height = (y_max - y_min) / image_height
            
            # Add to annotations
            yolo_annotations.append(f"{class_id} {x_center} {y_center} {width} {height}")
    
    return yolo_annotations

def prepare_data_for_training(augmented_dir, training_dir, train_split=0.7, val_split=0.15, test_split=0.15, format="yolo"):
    """Prepare data for model training."""
    # Create training directories
    create_training_directories()
    
    # Get all annotation files
    annotation_files = []
    for root, _, files in os.walk(augmented_dir):
        for file in files:
            if file.lower().endswith('_annotations.json'):
                annotation_files.append(os.path.join(root, file))
    
    logger.info(f"Found {len(annotation_files)} annotation files to prepare")
    
    # Shuffle annotation files
    random.shuffle(annotation_files)
    
    # Calculate split indices
    train_end = int(len(annotation_files) * train_split)
    val_end = train_end + int(len(annotation_files) * val_split)
    
    # Split into train, val, and test sets
    train_files = annotation_files[:train_end]
    val_files = annotation_files[train_end:val_end]
    test_files = annotation_files[val_end:]
    
    logger.info(f"Split: {len(train_files)} train, {len(val_files)} val, {len(test_files)} test")
    
    # Define class mapping
    class_mapping = {
        "store_name": 0,
        "coupon_code": 1,
        "expiry_date": 2,
        "description": 3,
        "amount": 4,
        "min_purchase": 5
    }
    
    # Save class mapping
    with open(os.path.join(training_dir, 'class_mapping.json'), 'w') as f:
        json.dump(class_mapping, f, indent=4)
    
    # Process each set
    process_set(train_files, 'train', training_dir, class_mapping, format)
    process_set(val_files, 'val', training_dir, class_mapping, format)
    process_set(test_files, 'test', training_dir, class_mapping, format)
    
    # Create dataset.yaml for YOLO
    if format == "yolo":
        create_yolo_dataset_yaml(training_dir, class_mapping)
    
    logger.info("Data preparation complete.")

def process_set(annotation_files, set_name, training_dir, class_mapping, format):
    """Process a set of annotation files."""
    logger.info(f"Processing {set_name} set")
    
    for i, annotation_file in tqdm(enumerate(annotation_files), total=len(annotation_files), desc=f"Processing {set_name} set"):
        # Read annotation
        with open(annotation_file, 'r') as f:
            annotation = json.load(f)
        
        # Get image path
        image_path = os.path.join(BASE_DIR, annotation["image_path"])
        
        # Copy image to training directory
        image_filename = os.path.basename(image_path)
        dest_image_path = os.path.join(training_dir, set_name, 'images', image_filename)
        shutil.copy(image_path, dest_image_path)
        
        # Convert annotation to required format
        if format == "yolo":
            yolo_annotations = convert_annotation_to_yolo(annotation, class_mapping)
            
            # Save YOLO annotation
            base_name = os.path.splitext(image_filename)[0]
            label_filename = f"{base_name}.txt"
            label_path = os.path.join(training_dir, set_name, 'labels', label_filename)
            with open(label_path, 'w') as f:
                f.write('\n'.join(yolo_annotations))
        else:
            # Copy original annotation
            annotation_filename = os.path.basename(annotation_file)
            dest_annotation_path = os.path.join(training_dir, set_name, 'labels', annotation_filename)
            shutil.copy(annotation_file, dest_annotation_path)

def create_yolo_dataset_yaml(training_dir, class_mapping):
    """Create dataset.yaml file for YOLO training."""
    yaml_content = f"""# YOLOv5 dataset configuration
path: {os.path.abspath(training_dir)}
train: train/images
val: val/images
test: test/images

# Classes
nc: {len(class_mapping)}  # number of classes
names: {list(class_mapping.keys())}  # class names
"""
    
    with open(os.path.join(training_dir, 'dataset.yaml'), 'w') as f:
        f.write(yaml_content)

def main():
    """Main function to prepare data for model training."""
    parser = argparse.ArgumentParser(description='Prepare data for Indian coupon recognition model training.')
    parser.add_argument('--input-dir', default=AUGMENTED_DIR, help='Directory containing augmented images and annotations')
    parser.add_argument('--output-dir', default=TRAINING_DIR, help='Directory to save prepared data')
    parser.add_argument('--train-split', type=float, default=0.7, help='Proportion of data to use for training')
    parser.add_argument('--val-split', type=float, default=0.15, help='Proportion of data to use for validation')
    parser.add_argument('--test-split', type=float, default=0.15, help='Proportion of data to use for testing')
    parser.add_argument('--format', choices=['yolo', 'original'], default='yolo', help='Format to convert annotations to')
    args = parser.parse_args()
    
    prepare_data_for_training(
        args.input_dir, 
        args.output_dir, 
        args.train_split, 
        args.val_split, 
        args.test_split, 
        args.format
    )

if __name__ == "__main__":
    main()
