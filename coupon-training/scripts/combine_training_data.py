#!/usr/bin/env python3
"""
Combine Training Data Script

This script combines the existing training data with the Indian coupon data
to create a unified training dataset for the coupon recognizer model.
"""

import os
import shutil
import json
import argparse
import logging
from pathlib import Path
from tqdm import tqdm

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("combine_training_data.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("combine_training_data")

def combine_training_data(existing_data_dir, india_data_dir, output_dir):
    """
    Combine existing training data with Indian coupon data.
    
    Args:
        existing_data_dir: Directory containing existing training data
        india_data_dir: Directory containing Indian coupon data
        output_dir: Directory to save combined data
    """
    # Create output directory
    os.makedirs(output_dir, exist_ok=True)
    
    # Create subdirectories
    train_dir = os.path.join(output_dir, 'train')
    val_dir = os.path.join(output_dir, 'val')
    test_dir = os.path.join(output_dir, 'test')
    
    os.makedirs(os.path.join(train_dir, 'images'), exist_ok=True)
    os.makedirs(os.path.join(train_dir, 'labels'), exist_ok=True)
    os.makedirs(os.path.join(val_dir, 'images'), exist_ok=True)
    os.makedirs(os.path.join(val_dir, 'labels'), exist_ok=True)
    os.makedirs(os.path.join(test_dir, 'images'), exist_ok=True)
    os.makedirs(os.path.join(test_dir, 'labels'), exist_ok=True)
    
    # Copy existing data
    logger.info("Copying existing training data...")
    copy_data(
        os.path.join(existing_data_dir, 'train'), 
        os.path.join(output_dir, 'train')
    )
    copy_data(
        os.path.join(existing_data_dir, 'val'), 
        os.path.join(output_dir, 'val')
    )
    copy_data(
        os.path.join(existing_data_dir, 'test'), 
        os.path.join(output_dir, 'test')
    )
    
    # Copy Indian coupon data
    logger.info("Copying Indian coupon data...")
    copy_data(
        os.path.join(india_data_dir, 'train'), 
        os.path.join(output_dir, 'train')
    )
    copy_data(
        os.path.join(india_data_dir, 'val'), 
        os.path.join(output_dir, 'val')
    )
    copy_data(
        os.path.join(india_data_dir, 'test'), 
        os.path.join(output_dir, 'test')
    )
    
    # Copy and update dataset.yaml
    update_dataset_yaml(
        os.path.join(existing_data_dir, 'dataset.yaml'),
        os.path.join(output_dir, 'dataset.yaml'),
        output_dir
    )
    
    # Copy class mapping
    if os.path.exists(os.path.join(existing_data_dir, 'class_mapping.json')):
        shutil.copy(
            os.path.join(existing_data_dir, 'class_mapping.json'),
            os.path.join(output_dir, 'class_mapping.json')
        )
    
    logger.info(f"Combined data saved to {output_dir}")

def copy_data(src_dir, dst_dir):
    """
    Copy data from source directory to destination directory.
    
    Args:
        src_dir: Source directory
        dst_dir: Destination directory
    """
    # Copy images
    src_images_dir = os.path.join(src_dir, 'images')
    dst_images_dir = os.path.join(dst_dir, 'images')
    
    if os.path.exists(src_images_dir):
        for filename in os.listdir(src_images_dir):
            if filename.lower().endswith(('.png', '.jpg', '.jpeg')):
                src_path = os.path.join(src_images_dir, filename)
                dst_path = os.path.join(dst_images_dir, filename)
                
                if not os.path.exists(dst_path):
                    shutil.copy(src_path, dst_path)
    
    # Copy labels
    src_labels_dir = os.path.join(src_dir, 'labels')
    dst_labels_dir = os.path.join(dst_dir, 'labels')
    
    if os.path.exists(src_labels_dir):
        for filename in os.listdir(src_labels_dir):
            if filename.lower().endswith('.txt'):
                src_path = os.path.join(src_labels_dir, filename)
                dst_path = os.path.join(dst_labels_dir, filename)
                
                if not os.path.exists(dst_path):
                    shutil.copy(src_path, dst_path)

def update_dataset_yaml(src_path, dst_path, output_dir):
    """
    Update dataset.yaml file with combined data information.
    
    Args:
        src_path: Source dataset.yaml path
        dst_path: Destination dataset.yaml path
        output_dir: Output directory path
    """
    if not os.path.exists(src_path):
        # Create a new dataset.yaml file
        yaml_content = f"""# YOLOv5 dataset configuration
path: {os.path.abspath(output_dir)}
train: train/images
val: val/images
test: test/images

# Classes
nc: 6  # number of classes
names: ['store_name', 'coupon_code', 'expiry_date', 'description', 'amount', 'min_purchase']  # class names
"""
        with open(dst_path, 'w') as f:
            f.write(yaml_content)
        return
    
    # Read source dataset.yaml
    with open(src_path, 'r') as f:
        yaml_content = f.read()
    
    # Update path
    yaml_content = yaml_content.replace(
        f"path: {os.path.abspath(os.path.dirname(src_path))}",
        f"path: {os.path.abspath(output_dir)}"
    )
    
    # Update description
    yaml_content += "\n# This dataset combines standard coupon data with Indian coupon data\n"
    
    # Write updated dataset.yaml
    with open(dst_path, 'w') as f:
        f.write(yaml_content)

def main():
    """Main function."""
    parser = argparse.ArgumentParser(description='Combine existing training data with Indian coupon data.')
    parser.add_argument('--existing-data', required=True, help='Directory containing existing training data')
    parser.add_argument('--india-data', required=True, help='Directory containing Indian coupon data')
    parser.add_argument('--output-dir', required=True, help='Directory to save combined data')
    args = parser.parse_args()
    
    combine_training_data(args.existing_data, args.india_data, args.output_dir)

if __name__ == '__main__':
    main()
