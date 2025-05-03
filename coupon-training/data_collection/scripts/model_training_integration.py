#!/usr/bin/env python3
"""
Model Training Integration Script

This script prepares annotated coupon data for model training and
integrates with the CouponTracker model training pipeline.
"""

import os
import sys
import json
import logging
import argparse
import cv2
import numpy as np
import pandas as pd
from pathlib import Path
import shutil
import random
from sklearn.model_selection import train_test_split

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("model_training_integration.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("model_training_integration")

# Base directories
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROCESSED_IMAGES_DIR = os.path.join(BASE_DIR, 'processed_images')
ANNOTATIONS_DIR = os.path.join(BASE_DIR, 'annotations')
AUGMENTED_DIR = os.path.join(BASE_DIR, 'augmented')
TRAINING_DATA_DIR = os.path.join(BASE_DIR, 'training_data')
DATASET_METADATA = os.path.join(TRAINING_DATA_DIR, 'dataset_metadata.json')

# Training data subdirectories
TRAIN_DIR = os.path.join(TRAINING_DATA_DIR, 'train')
VAL_DIR = os.path.join(TRAINING_DATA_DIR, 'val')
TEST_DIR = os.path.join(TRAINING_DATA_DIR, 'test')

# Field types for annotation
FIELD_TYPES = ['store_name', 'coupon_code', 'expiry_date', 'discount_amount', 'min_purchase', 'description']

def ensure_directories_exist():
    """Ensure all necessary directories exist."""
    for directory in [TRAINING_DATA_DIR, TRAIN_DIR, VAL_DIR, TEST_DIR]:
        os.makedirs(directory, exist_ok=True)
        
        # Create subdirectories for images and annotations
        os.makedirs(os.path.join(directory, 'images'), exist_ok=True)
        os.makedirs(os.path.join(directory, 'annotations'), exist_ok=True)
    
    logger.info("Directory structure verified")

def load_annotations():
    """Load all annotation files."""
    annotations = []
    
    for filename in os.listdir(ANNOTATIONS_DIR):
        if filename.endswith('.json'):
            file_path = os.path.join(ANNOTATIONS_DIR, filename)
            try:
                with open(file_path, 'r') as f:
                    annotation = json.load(f)
                    
                    # Only include verified annotations
                    if annotation.get('verification_status') == 'verified':
                        annotations.append(annotation)
            except Exception as e:
                logger.error(f"Error loading annotation file {filename}: {e}")
    
    logger.info(f"Loaded {len(annotations)} verified annotations")
    return annotations

def find_image_path(image_id):
    """Find the image path for a given image ID."""
    # Check processed images directory
    for root, _, files in os.walk(PROCESSED_IMAGES_DIR):
        for file in files:
            if file.startswith(f"processed_{image_id}") or file == f"{image_id}.jpg" or file == f"{image_id}.png":
                return os.path.join(root, file)
    
    # Check augmented images directory
    for root, _, files in os.walk(AUGMENTED_DIR):
        for file in files:
            if file.startswith(f"{image_id}_") or file == f"{image_id}.jpg" or file == f"{image_id}.png":
                return os.path.join(root, file)
    
    return None

def convert_to_yolo_format(annotation, image_path):
    """Convert annotation to YOLO format."""
    try:
        # Load image to get dimensions
        image = cv2.imread(image_path)
        if image is None:
            logger.error(f"Failed to load image: {image_path}")
            return None
        
        height, width = image.shape[:2]
        
        yolo_annotations = []
        
        # Process each field
        for field_type in FIELD_TYPES:
            if field_type in annotation.get('fields', {}):
                field = annotation['fields'][field_type]
                
                # Get bounding box
                if 'bounding_box' in field:
                    bbox = field['bounding_box']
                    
                    # Convert to YOLO format (x_center, y_center, width, height)
                    x1, y1, x2, y2 = bbox
                    
                    # Normalize coordinates
                    x_center = (x1 + x2) / 2 / width
                    y_center = (y1 + y2) / 2 / height
                    bbox_width = (x2 - x1) / width
                    bbox_height = (y2 - y1) / height
                    
                    # Get class index
                    class_idx = FIELD_TYPES.index(field_type)
                    
                    # Add to annotations
                    yolo_annotations.append(f"{class_idx} {x_center} {y_center} {bbox_width} {bbox_height}")
        
        return yolo_annotations
    
    except Exception as e:
        logger.error(f"Error converting annotation to YOLO format: {e}")
        return None

def convert_to_coco_format(annotations, image_paths):
    """Convert annotations to COCO format."""
    try:
        coco_data = {
            "info": {
                "description": "CouponTracker Dataset",
                "version": "1.0",
                "year": 2023,
                "contributor": "CouponTracker Team",
                "date_created": "2023-07-01"
            },
            "licenses": [
                {
                    "id": 1,
                    "name": "Attribution-NonCommercial",
                    "url": "http://creativecommons.org/licenses/by-nc/2.0/"
                }
            ],
            "categories": [
                {"id": i, "name": field_type, "supercategory": "coupon_field"}
                for i, field_type in enumerate(FIELD_TYPES)
            ],
            "images": [],
            "annotations": []
        }
        
        annotation_id = 1
        
        for image_id, (annotation, image_path) in enumerate(zip(annotations, image_paths), 1):
            # Load image to get dimensions
            image = cv2.imread(image_path)
            if image is None:
                logger.error(f"Failed to load image: {image_path}")
                continue
            
            height, width = image.shape[:2]
            
            # Add image info
            coco_data["images"].append({
                "id": image_id,
                "file_name": os.path.basename(image_path),
                "width": width,
                "height": height,
                "license": 1,
                "date_captured": annotation.get("annotation_date", "")
            })
            
            # Process each field
            for field_type in FIELD_TYPES:
                if field_type in annotation.get('fields', {}):
                    field = annotation['fields'][field_type]
                    
                    # Get bounding box
                    if 'bounding_box' in field:
                        bbox = field['bounding_box']
                        
                        # Convert to COCO format [x, y, width, height]
                        x1, y1, x2, y2 = bbox
                        coco_bbox = [x1, y1, x2 - x1, y2 - y1]
                        
                        # Get category id
                        category_id = FIELD_TYPES.index(field_type)
                        
                        # Add annotation
                        coco_data["annotations"].append({
                            "id": annotation_id,
                            "image_id": image_id,
                            "category_id": category_id,
                            "bbox": coco_bbox,
                            "area": (x2 - x1) * (y2 - y1),
                            "segmentation": [],
                            "iscrowd": 0
                        })
                        
                        annotation_id += 1
        
        return coco_data
    
    except Exception as e:
        logger.error(f"Error converting annotations to COCO format: {e}")
        return None

def prepare_training_data(split_ratio=(0.7, 0.15, 0.15), format='yolo'):
    """Prepare training data from annotations."""
    ensure_directories_exist()
    
    # Load annotations
    annotations = load_annotations()
    
    if not annotations:
        logger.error("No verified annotations found")
        return False
    
    # Find image paths
    image_paths = []
    valid_annotations = []
    
    for annotation in annotations:
        image_id = annotation.get('image_id')
        if not image_id:
            continue
        
        image_path = find_image_path(image_id)
        if image_path:
            image_paths.append(image_path)
            valid_annotations.append(annotation)
        else:
            logger.warning(f"Image not found for annotation: {image_id}")
    
    logger.info(f"Found {len(valid_annotations)} valid annotations with images")
    
    # Split data into train, validation, and test sets
    train_val_annotations, test_annotations = train_test_split(
        list(zip(valid_annotations, image_paths)), 
        test_size=split_ratio[2],
        random_state=42
    )
    
    train_ratio = split_ratio[0] / (split_ratio[0] + split_ratio[1])
    train_annotations, val_annotations = train_test_split(
        train_val_annotations,
        test_size=1 - train_ratio,
        random_state=42
    )
    
    logger.info(f"Split data into {len(train_annotations)} train, {len(val_annotations)} validation, and {len(test_annotations)} test samples")
    
    # Process each split
    dataset_info = {
        "train": {"count": len(train_annotations), "samples": []},
        "val": {"count": len(val_annotations), "samples": []},
        "test": {"count": len(test_annotations), "samples": []}
    }
    
    # Process training data
    process_split(train_annotations, TRAIN_DIR, 'train', dataset_info, format)
    
    # Process validation data
    process_split(val_annotations, VAL_DIR, 'val', dataset_info, format)
    
    # Process test data
    process_split(test_annotations, TEST_DIR, 'test', dataset_info, format)
    
    # Save dataset metadata
    with open(DATASET_METADATA, 'w') as f:
        json.dump(dataset_info, f, indent=2)
    
    # Create dataset configuration files
    if format == 'yolo':
        create_yolo_config(dataset_info)
    
    logger.info("Training data preparation complete")
    return True

def process_split(annotations_with_paths, output_dir, split_name, dataset_info, format):
    """Process a data split (train, val, or test)."""
    images_dir = os.path.join(output_dir, 'images')
    annotations_dir = os.path.join(output_dir, 'annotations')
    
    for i, (annotation, image_path) in enumerate(annotations_with_paths):
        try:
            # Copy image
            image_filename = f"{split_name}_{i:04d}{os.path.splitext(image_path)[1]}"
            image_output_path = os.path.join(images_dir, image_filename)
            shutil.copy(image_path, image_output_path)
            
            # Process annotation based on format
            if format == 'yolo':
                # Convert to YOLO format
                yolo_annotations = convert_to_yolo_format(annotation, image_path)
                
                if yolo_annotations:
                    # Save YOLO annotation
                    annotation_filename = f"{os.path.splitext(image_filename)[0]}.txt"
                    annotation_output_path = os.path.join(annotations_dir, annotation_filename)
                    
                    with open(annotation_output_path, 'w') as f:
                        f.write('\n'.join(yolo_annotations))
                    
                    # Add to dataset info
                    dataset_info[split_name]["samples"].append({
                        "image": image_output_path,
                        "annotation": annotation_output_path,
                        "original_image": image_path,
                        "fields": list(annotation.get('fields', {}).keys())
                    })
            
            elif format == 'coco':
                # COCO format is handled separately after all images are processed
                dataset_info[split_name]["samples"].append({
                    "image": image_output_path,
                    "original_image": image_path,
                    "fields": list(annotation.get('fields', {}).keys())
                })
        
        except Exception as e:
            logger.error(f"Error processing {image_path}: {e}")
    
    # If COCO format, create a single annotation file
    if format == 'coco':
        coco_data = convert_to_coco_format(
            [a for a, _ in annotations_with_paths],
            [p for _, p in annotations_with_paths]
        )
        
        if coco_data:
            coco_output_path = os.path.join(annotations_dir, f"{split_name}_annotations.json")
            with open(coco_output_path, 'w') as f:
                json.dump(coco_data, f, indent=2)
            
            # Update dataset info
            dataset_info[split_name]["annotation_file"] = coco_output_path

def create_yolo_config(dataset_info):
    """Create configuration files for YOLO training."""
    # Create data.yaml
    data_yaml = {
        "train": os.path.join(TRAIN_DIR, 'images'),
        "val": os.path.join(VAL_DIR, 'images'),
        "test": os.path.join(TEST_DIR, 'images'),
        "nc": len(FIELD_TYPES),
        "names": FIELD_TYPES
    }
    
    with open(os.path.join(TRAINING_DATA_DIR, 'data.yaml'), 'w') as f:
        yaml.dump(data_yaml, f, default_flow_style=False)
    
    # Create train.txt, val.txt, and test.txt
    for split in ['train', 'val', 'test']:
        with open(os.path.join(TRAINING_DATA_DIR, f'{split}.txt'), 'w') as f:
            for sample in dataset_info[split]["samples"]:
                f.write(f"{sample['image']}\n")

def export_to_app(model_path, output_dir):
    """Export the trained model for use in the CouponTracker app."""
    try:
        # Create output directory if it doesn't exist
        os.makedirs(output_dir, exist_ok=True)
        
        # Copy model file
        shutil.copy(model_path, os.path.join(output_dir, 'coupon_model.tflite'))
        
        # Create model metadata
        metadata = {
            "model_version": "1.0",
            "field_types": FIELD_TYPES,
            "training_date": datetime.now().isoformat(),
            "dataset_size": {
                "train": len(os.listdir(os.path.join(TRAIN_DIR, 'images'))),
                "val": len(os.listdir(os.path.join(VAL_DIR, 'images'))),
                "test": len(os.listdir(os.path.join(TEST_DIR, 'images')))
            }
        }
        
        # Save metadata
        with open(os.path.join(output_dir, 'model_metadata.json'), 'w') as f:
            json.dump(metadata, f, indent=2)
        
        logger.info(f"Model exported to {output_dir}")
        return True
    
    except Exception as e:
        logger.error(f"Error exporting model: {e}")
        return False

def main():
    """Main function to prepare training data and integrate with model training."""
    parser = argparse.ArgumentParser(description='Prepare training data and integrate with model training.')
    parser.add_argument('--format', choices=['yolo', 'coco'], default='yolo', help='Annotation format')
    parser.add_argument('--train-split', type=float, default=0.7, help='Training data split ratio')
    parser.add_argument('--val-split', type=float, default=0.15, help='Validation data split ratio')
    parser.add_argument('--test-split', type=float, default=0.15, help='Test data split ratio')
    parser.add_argument('--export-model', action='store_true', help='Export model for app use')
    parser.add_argument('--model-path', help='Path to trained model file')
    parser.add_argument('--output-dir', help='Output directory for exported model')
    
    args = parser.parse_args()
    
    # Validate split ratios
    total_split = args.train_split + args.val_split + args.test_split
    if abs(total_split - 1.0) > 0.001:
        logger.error(f"Split ratios must sum to 1.0, got {total_split}")
        return False
    
    # Prepare training data
    success = prepare_training_data(
        split_ratio=(args.train_split, args.val_split, args.test_split),
        format=args.format
    )
    
    if not success:
        return False
    
    # Export model if requested
    if args.export_model:
        if not args.model_path or not args.output_dir:
            logger.error("Model path and output directory must be specified for export")
            return False
        
        export_to_app(args.model_path, args.output_dir)
    
    return True

if __name__ == "__main__":
    main()
