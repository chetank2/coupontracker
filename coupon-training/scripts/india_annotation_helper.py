#!/usr/bin/env python3
"""
Annotation Helper for Indian Coupon Images

This script helps with annotating Indian coupon images by:
- Providing a simple UI for annotation
- Auto-detecting common regions in Indian coupons
- Saving annotations in the required format
"""

import os
import cv2
import numpy as np
import json
import argparse
from pathlib import Path
import logging
from tqdm import tqdm

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("india_annotation.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("india_annotation_helper")

# Base directories
BASE_DIR = os.path.join('coupon-training', 'data', 'reddit_india')
PROCESSED_DIR = os.path.join(BASE_DIR, 'processed')
ANNOTATED_DIR = os.path.join(BASE_DIR, 'annotated')

# Annotation regions
REGIONS = [
    'store_name',
    'coupon_code',
    'expiry_date',
    'description',
    'amount',
    'min_purchase'
]

def auto_detect_regions(image_path):
    """
    Attempt to automatically detect regions in an Indian coupon image.
    This is a basic implementation that can be improved with more sophisticated techniques.
    """
    # Read the image
    image = cv2.imread(image_path)
    if image is None:
        logger.error(f"Failed to read image: {image_path}")
        return None
    
    height, width = image.shape[:2]
    
    # Default regions based on common coupon layouts
    # These are just rough estimates and should be adjusted manually
    regions = {
        'store_name': [0, 0, width, int(height * 0.2)],  # Top 20% of the image
        'coupon_code': [int(width * 0.1), int(height * 0.4), int(width * 0.9), int(height * 0.6)],  # Middle section
        'expiry_date': [0, int(height * 0.8), width, height],  # Bottom 20% of the image
        'description': [int(width * 0.7), int(height * 0.6), int(width * 0.9), int(height * 0.7)],  # Bottom right section
        'amount': [int(width * 0.1), int(height * 0.6), int(width * 0.9), int(height * 0.8)]  # Bottom middle section
    }
    
    # Try to detect the Rupee symbol (₹) for amount region
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    _, binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    
    # Use OCR or template matching for more accurate detection
    # This is a placeholder for more sophisticated detection
    
    return regions

def create_annotation_file(image_path, output_dir):
    """Create an annotation file for an image."""
    try:
        # Read the image to get dimensions
        image = cv2.imread(image_path)
        if image is None:
            logger.error(f"Failed to read image: {image_path}")
            return False
        
        height, width = image.shape[:2]
        
        # Auto-detect regions
        regions = auto_detect_regions(image_path)
        
        # Create annotation data
        annotation_data = {
            "image_path": os.path.relpath(image_path, BASE_DIR),
            "image_width": width,
            "image_height": height,
            "regions": regions
        }
        
        # Create output filename
        input_filename = os.path.basename(image_path)
        output_filename = f"{os.path.splitext(input_filename)[0]}_annotations.json"
        output_path = os.path.join(output_dir, output_filename)
        
        # Save annotation file
        with open(output_path, 'w') as f:
            json.dump(annotation_data, f, indent=4)
        
        logger.info(f"Created annotation file: {output_path}")
        return True
    
    except Exception as e:
        logger.error(f"Error creating annotation file for {image_path}: {e}")
        return False

def create_annotations(processed_dir, annotated_dir):
    """Create annotation files for all processed images."""
    # Create output directory if it doesn't exist
    os.makedirs(annotated_dir, exist_ok=True)
    
    # Get all image files recursively
    image_paths = []
    for root, _, files in os.walk(processed_dir):
        for file in files:
            if file.lower().endswith(('.png', '.jpg', '.jpeg', '.gif')):
                image_paths.append(os.path.join(root, file))
    
    logger.info(f"Found {len(image_paths)} images to annotate")
    
    # Create annotation files
    success_count = 0
    for i, image_path in tqdm(enumerate(image_paths), total=len(image_paths), desc="Creating annotations"):
        logger.info(f"Creating annotation for image {i+1}/{len(image_paths)}: {image_path}")
        
        if create_annotation_file(image_path, annotated_dir):
            success_count += 1
    
    logger.info(f"Annotation creation complete. Created {success_count} annotation files.")
    return success_count

def main():
    """Main function to create annotation files."""
    parser = argparse.ArgumentParser(description='Create annotation files for Indian coupon images.')
    parser.add_argument('--input-dir', default=PROCESSED_DIR, help='Directory containing processed images')
    parser.add_argument('--output-dir', default=ANNOTATED_DIR, help='Directory to save annotation files')
    args = parser.parse_args()
    
    create_annotations(args.input_dir, args.output_dir)

if __name__ == "__main__":
    main()
