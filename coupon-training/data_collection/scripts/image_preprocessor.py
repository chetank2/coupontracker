#!/usr/bin/env python3
"""
Basic Image Preprocessor for Coupon Images

This script preprocesses raw coupon images to prepare them for annotation.
"""

import os
import cv2
import numpy as np
import logging
import argparse
import json
from pathlib import Path

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("preprocessing.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("image_preprocessor")

# Base directories
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW_IMAGES_DIR = os.path.join(BASE_DIR, 'raw_images')
PROCESSED_IMAGES_DIR = os.path.join(BASE_DIR, 'processed_images')
PREPROCESSING_METADATA = os.path.join(BASE_DIR, 'preprocessing_metadata.json')

def ensure_directories_exist():
    """Ensure all necessary directories exist."""
    os.makedirs(PROCESSED_IMAGES_DIR, exist_ok=True)
    logger.info("Directory structure verified")

def resize_preserve_aspect(image, max_dim=1024):
    """Resize image while preserving aspect ratio."""
    height, width = image.shape[:2]
    
    # Calculate the ratio of the max dimension to the larger dimension
    if height > width:
        ratio = max_dim / height
    else:
        ratio = max_dim / width
    
    # Only resize if the image is larger than max_dim
    if ratio < 1:
        new_width = int(width * ratio)
        new_height = int(height * ratio)
        resized = cv2.resize(image, (new_width, new_height), interpolation=cv2.INTER_AREA)
        return resized
    
    return image

def enhance_contrast(image):
    """Enhance image contrast for better text readability."""
    # Convert to LAB color space
    lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB)
    
    # Split the LAB image into L, A, and B channels
    l, a, b = cv2.split(lab)
    
    # Apply CLAHE to L channel
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    cl = clahe.apply(l)
    
    # Merge the CLAHE enhanced L channel with the original A and B channels
    merged = cv2.merge((cl, a, b))
    
    # Convert back to BGR color space
    enhanced = cv2.cvtColor(merged, cv2.COLOR_LAB2BGR)
    
    return enhanced

def preprocess_image(image_path, output_dir):
    """Preprocess a single image and save the result."""
    try:
        # Load image
        image = cv2.imread(image_path)
        if image is None:
            logger.error(f"Failed to load image: {image_path}")
            return None
        
        # Resize while preserving aspect ratio
        resized = resize_preserve_aspect(image)
        
        # Enhance contrast
        enhanced = enhance_contrast(resized)
        
        # Create output filename
        input_filename = os.path.basename(image_path)
        output_filename = f"processed_{input_filename}"
        output_path = os.path.join(output_dir, output_filename)
        
        # Save processed image
        cv2.imwrite(output_path, enhanced)
        
        # Return metadata
        return {
            "input_path": image_path,
            "output_path": output_path,
            "original_size": image.shape[:2],
            "processed_size": enhanced.shape[:2],
            "preprocessing_steps": ["resize", "enhance_contrast"]
        }
    
    except Exception as e:
        logger.error(f"Error preprocessing image {image_path}: {e}")
        return None

def process_all_images(raw_dir, processed_dir):
    """Process all images in the raw directory and its subdirectories."""
    ensure_directories_exist()
    
    # Get all image files
    image_extensions = ['.jpg', '.jpeg', '.png', '.gif']
    image_paths = []
    
    for root, _, files in os.walk(raw_dir):
        for file in files:
            if any(file.lower().endswith(ext) for ext in image_extensions):
                image_paths.append(os.path.join(root, file))
    
    logger.info(f"Found {len(image_paths)} images to process")
    
    # Process each image
    metadata = []
    for i, image_path in enumerate(image_paths):
        logger.info(f"Processing image {i+1}/{len(image_paths)}: {image_path}")
        
        # Determine output directory structure to mirror input
        rel_path = os.path.relpath(os.path.dirname(image_path), raw_dir)
        output_dir = os.path.join(processed_dir, rel_path)
        os.makedirs(output_dir, exist_ok=True)
        
        # Process the image
        result = preprocess_image(image_path, output_dir)
        if result:
            metadata.append(result)
    
    # Save metadata
    with open(PREPROCESSING_METADATA, 'w') as f:
        json.dump(metadata, f, indent=2)
    
    logger.info(f"Preprocessing complete. Processed {len(metadata)} images successfully.")
    return metadata

def main():
    """Main function to preprocess coupon images."""
    parser = argparse.ArgumentParser(description='Preprocess coupon images.')
    parser.add_argument('--input-dir', default=RAW_IMAGES_DIR, help='Input directory containing raw images')
    parser.add_argument('--output-dir', default=PROCESSED_IMAGES_DIR, help='Output directory for processed images')
    
    args = parser.parse_args()
    
    process_all_images(args.input_dir, args.output_dir)

if __name__ == "__main__":
    main()
