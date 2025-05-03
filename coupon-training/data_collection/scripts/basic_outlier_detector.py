#!/usr/bin/env python3
"""
Basic Outlier Detector for Coupon Images

This script identifies simple outliers in the coupon dataset based on visual features.
"""

import os
import cv2
import numpy as np
import json
import logging
import argparse
from pathlib import Path

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("outlier_detection.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("outlier_detector")

# Base directories
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROCESSED_IMAGES_DIR = os.path.join(BASE_DIR, 'processed_images')
OUTLIERS_DIR = os.path.join(BASE_DIR, 'outliers')
OUTLIER_METADATA = os.path.join(BASE_DIR, 'outlier_metadata.json')

def ensure_directories_exist():
    """Ensure all necessary directories exist."""
    for subdir in ['visual_outliers', 'content_outliers', 'mixed_outliers']:
        os.makedirs(os.path.join(OUTLIERS_DIR, subdir), exist_ok=True)
    logger.info("Directory structure verified")

def extract_visual_features(image_path):
    """Extract basic visual features from an image."""
    try:
        # Load image
        image = cv2.imread(image_path)
        if image is None:
            logger.error(f"Failed to load image: {image_path}")
            return None
        
        # Convert to grayscale
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        
        # Calculate edge density
        edges = cv2.Canny(gray, 50, 150)
        edge_density = np.sum(edges > 0) / (edges.shape[0] * edges.shape[1])
        
        # Calculate aspect ratio
        aspect_ratio = image.shape[1] / image.shape[0]
        
        # Calculate brightness
        brightness = np.mean(gray)
        
        # Calculate contrast
        contrast = np.std(gray)
        
        return {
            "edge_density": float(edge_density),
            "aspect_ratio": float(aspect_ratio),
            "brightness": float(brightness),
            "contrast": float(contrast),
            "size": image.shape[0] * image.shape[1]
        }
    
    except Exception as e:
        logger.error(f"Error extracting visual features from {image_path}: {e}")
        return None

def detect_outliers(features, threshold=2.0):
    """Detect outliers using z-score method."""
    outliers = {}
    
    # Calculate z-scores for each feature
    for feature_name in features[0].keys():
        feature_values = [item[feature_name] for item in features]
        mean = np.mean(feature_values)
        std = np.std(feature_values)
        
        if std == 0:  # Skip if standard deviation is zero
            continue
        
        # Calculate z-scores
        z_scores = [(x - mean) / std for x in feature_values]
        
        # Find outliers
        for i, z in enumerate(z_scores):
            if abs(z) > threshold:
                if i not in outliers:
                    outliers[i] = []
                outliers[i].append({
                    "feature": feature_name,
                    "value": feature_values[i],
                    "z_score": z,
                    "mean": mean,
                    "std": std
                })
    
    return outliers

def analyze_outliers(image_paths):
    """Analyze images and detect basic outliers."""
    ensure_directories_exist()
    
    logger.info(f"Analyzing {len(image_paths)} images for outliers")
    
    # Extract features
    all_features = []
    valid_paths = []
    
    for i, image_path in enumerate(image_paths):
        logger.info(f"Extracting features from image {i+1}/{len(image_paths)}: {image_path}")
        
        # Extract visual features
        features = extract_visual_features(image_path)
        
        if features:
            all_features.append(features)
            valid_paths.append(image_path)
    
    # Detect outliers
    outliers = detect_outliers(all_features)
    logger.info(f"Detected {len(outliers)} outliers")
    
    # Prepare outlier metadata
    outlier_data = []
    
    for idx, outlier_features in outliers.items():
        image_path = valid_paths[idx]
        
        # Determine outlier type (simplified)
        outlier_type = "visual"  # Only detecting visual outliers in this basic version
        
        # Determine destination directory
        dest_dir = os.path.join(OUTLIERS_DIR, 'visual_outliers')
        
        # Copy the image to the outlier directory
        filename = os.path.basename(image_path)
        dest_path = os.path.join(dest_dir, filename)
        
        try:
            # Read the image and write to destination
            img = cv2.imread(image_path)
            cv2.imwrite(dest_path, img)
            
            # Add to outlier data
            outlier_data.append({
                "original_path": image_path,
                "outlier_path": dest_path,
                "outlier_type": outlier_type,
                "features": all_features[idx],
                "outlier_details": outlier_features
            })
        except Exception as e:
            logger.error(f"Error copying outlier image {image_path}: {e}")
    
    # Save outlier metadata
    with open(OUTLIER_METADATA, 'w') as f:
        json.dump(outlier_data, f, indent=2)
    
    logger.info(f"Outlier analysis complete. Identified {len(outlier_data)} outliers.")
    return outlier_data

def main():
    """Main function to detect outliers in coupon images."""
    parser = argparse.ArgumentParser(description='Detect outliers in coupon images.')
    parser.add_argument('--input-dir', default=PROCESSED_IMAGES_DIR, help='Input directory containing processed images')
    
    args = parser.parse_args()
    
    # Get all image files
    image_extensions = ['.jpg', '.jpeg', '.png', '.gif']
    image_paths = []
    
    for root, _, files in os.walk(args.input_dir):
        for file in files:
            if any(file.lower().endswith(ext) for ext in image_extensions):
                image_paths.append(os.path.join(root, file))
    
    logger.info(f"Found {len(image_paths)} images to analyze")
    
    analyze_outliers(image_paths)

if __name__ == "__main__":
    main()
