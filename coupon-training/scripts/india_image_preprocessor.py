#!/usr/bin/env python3
"""
Image Preprocessor for Indian Coupon Images

This script preprocesses raw Indian coupon images to prepare them for annotation and training:
- Resizes images while preserving aspect ratio
- Enhances contrast and readability
- Attempts to detect and crop to coupon boundaries
- Performs basic text enhancement
- Optimizes for Indian currency symbols and text formats
"""

import os
import cv2
import numpy as np
import logging
import argparse
from pathlib import Path
import json
from tqdm import tqdm

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("india_preprocessing.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("india_image_preprocessor")

# Base directories
BASE_DIR = os.path.join('coupon-training', 'data', 'reddit_india')
RAW_DIR = os.path.join(BASE_DIR, 'raw')
PROCESSED_DIR = os.path.join(BASE_DIR, 'processed')

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

def detect_coupon_boundaries(image):
    """Attempt to detect and crop to coupon boundaries."""
    # Convert to grayscale
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # Apply Gaussian blur to reduce noise
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    
    # Apply Canny edge detection
    edges = cv2.Canny(blurred, 50, 150)
    
    # Find contours
    contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    
    if not contours:
        return image
    
    # Find the largest contour by area
    largest_contour = max(contours, key=cv2.contourArea)
    
    # Get the bounding rectangle
    x, y, w, h = cv2.boundingRect(largest_contour)
    
    # Only crop if the detected area is significant
    image_area = image.shape[0] * image.shape[1]
    contour_area = w * h
    
    if contour_area > 0.3 * image_area and contour_area < 0.95 * image_area:
        # Add a small margin
        margin = 10
        x = max(0, x - margin)
        y = max(0, y - margin)
        w = min(image.shape[1] - x, w + 2 * margin)
        h = min(image.shape[0] - y, h + 2 * margin)
        
        # Crop the image
        cropped = image[y:y+h, x:x+w]
        return cropped
    
    return image

def enhance_contrast(image):
    """Enhance image contrast using CLAHE."""
    # Convert to LAB color space
    lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB)
    
    # Split the LAB image into L, A, and B channels
    l, a, b = cv2.split(lab)
    
    # Apply CLAHE to the L channel
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    cl = clahe.apply(l)
    
    # Merge the enhanced L channel with the original A and B channels
    merged = cv2.merge((cl, a, b))
    
    # Convert back to BGR color space
    enhanced = cv2.cvtColor(merged, cv2.COLOR_LAB2BGR)
    
    return enhanced

def enhance_text(image):
    """Enhance text in the image for better OCR."""
    # Convert to grayscale
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # Apply adaptive thresholding
    # This helps with text that might be in different lighting conditions
    thresh = cv2.adaptiveThreshold(
        gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 11, 2
    )
    
    # Create a mask of the thresholded image
    mask = cv2.bitwise_not(thresh)
    
    # Apply the mask to the original image
    result = image.copy()
    result = cv2.addWeighted(result, 0.8, cv2.cvtColor(mask, cv2.COLOR_GRAY2BGR), 0.2, 0)
    
    return result

def enhance_indian_currency_symbol(image):
    """Enhance Indian Rupee symbol (₹) in the image."""
    # Convert to HSV color space
    hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    
    # Define range for black/dark colors (typical for currency symbols)
    lower_black = np.array([0, 0, 0])
    upper_black = np.array([180, 255, 50])
    
    # Create a mask for dark colors
    mask = cv2.inRange(hsv, lower_black, upper_black)
    
    # Dilate the mask to make symbols more prominent
    kernel = np.ones((3, 3), np.uint8)
    dilated_mask = cv2.dilate(mask, kernel, iterations=1)
    
    # Apply the mask to the original image
    result = image.copy()
    result[dilated_mask > 0] = [0, 0, 255]  # Highlight in red
    
    return result

def calculate_image_quality(image):
    """Calculate basic image quality metrics."""
    # Convert to grayscale
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # Calculate metrics
    mean_val = np.mean(gray)
    std_val = np.std(gray)
    
    # Calculate sharpness using Laplacian
    laplacian = cv2.Laplacian(gray, cv2.CV_64F)
    sharpness = np.var(laplacian)
    
    return {
        "brightness": mean_val,
        "contrast": std_val,
        "sharpness": sharpness
    }

def preprocess_image(image_path, output_dir):
    """Preprocess a single image."""
    try:
        # Read the image
        image = cv2.imread(image_path)
        if image is None:
            logger.error(f"Failed to read image: {image_path}")
            return None
        
        # Calculate original quality metrics
        original_quality = calculate_image_quality(image)
        
        # Resize while preserving aspect ratio
        resized = resize_preserve_aspect(image)
        
        # Try to detect and crop to coupon boundaries
        cropped = detect_coupon_boundaries(resized)
        
        # Enhance contrast
        contrast_enhanced = enhance_contrast(cropped)
        
        # Enhance text
        text_enhanced = enhance_text(contrast_enhanced)
        
        # Enhance Indian currency symbols
        currency_enhanced = enhance_indian_currency_symbol(text_enhanced)
        
        # Calculate processed quality metrics
        processed_quality = calculate_image_quality(currency_enhanced)
        
        # Create output filename
        input_filename = os.path.basename(image_path)
        output_filename = f"processed_{input_filename}"
        output_path = os.path.join(output_dir, output_filename)
        
        # Save processed image
        cv2.imwrite(output_path, currency_enhanced)
        
        # Return metadata
        return {
            "input_path": image_path,
            "output_path": output_path,
            "original_size": image.shape[:2],
            "processed_size": currency_enhanced.shape[:2],
            "original_quality": original_quality,
            "processed_quality": processed_quality,
            "preprocessing_steps": ["resize", "crop_boundaries", "enhance_contrast", "enhance_text", "enhance_currency"]
        }
    
    except Exception as e:
        logger.error(f"Error preprocessing image {image_path}: {e}")
        return None

def preprocess_images(raw_dir, processed_dir):
    """Preprocess all images in the raw directory."""
    # Create output directory if it doesn't exist
    os.makedirs(processed_dir, exist_ok=True)
    
    # Get all image files recursively
    image_paths = []
    for root, _, files in os.walk(raw_dir):
        for file in files:
            if file.lower().endswith(('.png', '.jpg', '.jpeg', '.gif')):
                image_paths.append(os.path.join(root, file))
    
    logger.info(f"Found {len(image_paths)} images to process")
    
    # Process each image
    metadata = []
    for i, image_path in tqdm(enumerate(image_paths), total=len(image_paths), desc="Processing images"):
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
    metadata_path = os.path.join(processed_dir, 'preprocessing_metadata.json')
    with open(metadata_path, 'w') as f:
        json.dump(metadata, f, indent=2)
    
    logger.info(f"Preprocessing complete. Processed {len(metadata)} images.")
    return len(metadata)

def main():
    """Main function to preprocess images."""
    parser = argparse.ArgumentParser(description='Preprocess Indian coupon images.')
    parser.add_argument('--input-dir', default=RAW_DIR, help='Directory containing raw images')
    parser.add_argument('--output-dir', default=PROCESSED_DIR, help='Directory to save processed images')
    args = parser.parse_args()
    
    preprocess_images(args.input_dir, args.output_dir)

if __name__ == "__main__":
    main()
