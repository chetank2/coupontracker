#!/usr/bin/env python3
"""
Outlier Detector for Coupon Images

This script identifies outliers in the coupon dataset using various detection methods:
- Visual outlier detection (unusual formats, visual complexity)
- Content outlier detection (unusual text patterns)
- Statistical outlier detection (based on feature distributions)
"""

import os
import cv2
import numpy as np
import json
import logging
import argparse
from pathlib import Path
from sklearn.ensemble import IsolationForest
import pytesseract
from collections import Counter
import tensorflow as tf
import matplotlib.pyplot as plt
from sklearn.manifold import TSNE

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
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PROCESSED_IMAGES_DIR = os.path.join(BASE_DIR, 'processed_images')
OUTLIERS_DIR = os.path.join(BASE_DIR, 'outliers')
OUTLIER_METADATA = os.path.join(BASE_DIR, 'outlier_metadata.json')

def ensure_directories_exist():
    """Ensure all necessary directories exist."""
    for subdir in ['visual_outliers', 'content_outliers', 'mixed_outliers']:
        os.makedirs(os.path.join(OUTLIERS_DIR, subdir), exist_ok=True)
    logger.info("Directory structure verified")

def extract_visual_features(image_path):
    """Extract visual features from an image."""
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
        
        # Calculate color histogram variance
        color_hist = cv2.calcHist([image], [0, 1, 2], None, [8, 8, 8], [0, 256, 0, 256, 0, 256])
        color_hist = cv2.normalize(color_hist, color_hist).flatten()
        color_variance = np.var(color_hist)
        
        # Calculate text-to-background ratio using Otsu's thresholding
        _, thresh = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
        text_ratio = np.sum(thresh > 0) / (thresh.shape[0] * thresh.shape[1])
        
        # Calculate image complexity using DCT coefficients
        dct = cv2.dct(np.float32(gray))
        dct_energy = np.sum(np.abs(dct)) / (dct.shape[0] * dct.shape[1])
        
        return {
            "edge_density": float(edge_density),
            "color_variance": float(color_variance),
            "text_ratio": float(text_ratio),
            "dct_energy": float(dct_energy),
            "aspect_ratio": float(image.shape[1] / image.shape[0]),
            "size": image.shape[0] * image.shape[1]
        }
    
    except Exception as e:
        logger.error(f"Error extracting visual features from {image_path}: {e}")
        return None

def extract_text_features(image_path):
    """Extract text features from an image using OCR."""
    try:
        # Load image
        image = cv2.imread(image_path)
        if image is None:
            logger.error(f"Failed to load image: {image_path}")
            return None
        
        # Perform OCR
        text = pytesseract.image_to_string(image)
        
        # Calculate text length
        text_length = len(text)
        
        # Calculate word count
        words = [word for word in text.split() if word.strip()]
        word_count = len(words)
        
        # Calculate character distribution
        char_counts = Counter(text.lower())
        alpha_ratio = sum(char_counts.get(c, 0) for c in 'abcdefghijklmnopqrstuvwxyz') / max(text_length, 1)
        digit_ratio = sum(char_counts.get(c, 0) for c in '0123456789') / max(text_length, 1)
        special_ratio = sum(char_counts.get(c, 0) for c in '!@#$%^&*()_+-=[]{}|;:,.<>?/') / max(text_length, 1)
        
        # Check for coupon-related keywords
        coupon_keywords = ['coupon', 'code', 'offer', 'discount', 'off', 'save', 'free', 'deal', 'valid', 'expires']
        keyword_count = sum(1 for word in words if word.lower() in coupon_keywords)
        keyword_ratio = keyword_count / max(word_count, 1)
        
        return {
            "text_length": text_length,
            "word_count": word_count,
            "alpha_ratio": float(alpha_ratio),
            "digit_ratio": float(digit_ratio),
            "special_ratio": float(special_ratio),
            "keyword_ratio": float(keyword_ratio),
            "extracted_text": text[:500]  # Limit text length for storage
        }
    
    except Exception as e:
        logger.error(f"Error extracting text features from {image_path}: {e}")
        return None

def extract_deep_features(image_path, model=None):
    """Extract deep features using a pre-trained CNN."""
    try:
        # Load and preprocess image
        img = tf.keras.preprocessing.image.load_img(image_path, target_size=(224, 224))
        img_array = tf.keras.preprocessing.image.img_to_array(img)
        img_array = np.expand_dims(img_array, axis=0)
        img_array = tf.keras.applications.resnet50.preprocess_input(img_array)
        
        # Load model if not provided
        if model is None:
            model = tf.keras.applications.ResNet50(include_top=False, pooling='avg')
        
        # Extract features
        features = model.predict(img_array)
        
        return features[0].tolist()  # Convert to list for JSON serialization
    
    except Exception as e:
        logger.error(f"Error extracting deep features from {image_path}: {e}")
        return None

def detect_numerical_outliers(feature_values, threshold=3.0):
    """Detect outliers using z-score method."""
    mean = np.mean(feature_values)
    std = np.std(feature_values)
    z_scores = [(x - mean) / max(std, 1e-10) for x in feature_values]  # Avoid division by zero
    return [i for i, z in enumerate(z_scores) if abs(z) > threshold]

def detect_multivariate_outliers(features, contamination=0.05):
    """Detect outliers using Isolation Forest."""
    model = IsolationForest(contamination=contamination, random_state=42)
    preds = model.fit_predict(features)
    return [i for i, p in enumerate(preds) if p == -1]  # -1 indicates outlier

def visualize_feature_space(features, outlier_indices, output_path):
    """Visualize feature space using t-SNE and highlight outliers."""
    # Apply t-SNE for dimensionality reduction
    tsne = TSNE(n_components=2, random_state=42)
    embedded = tsne.fit_transform(features)
    
    # Create plot
    plt.figure(figsize=(10, 8))
    
    # Plot normal points
    normal_indices = [i for i in range(len(features)) if i not in outlier_indices]
    plt.scatter(
        embedded[normal_indices, 0],
        embedded[normal_indices, 1],
        c='blue',
        alpha=0.5,
        label='Normal'
    )
    
    # Plot outliers
    plt.scatter(
        embedded[outlier_indices, 0],
        embedded[outlier_indices, 1],
        c='red',
        marker='x',
        s=100,
        label='Outlier'
    )
    
    plt.title('t-SNE Visualization of Coupon Features')
    plt.legend()
    plt.tight_layout()
    plt.savefig(output_path)
    plt.close()

def analyze_outliers(image_paths):
    """Analyze images and detect outliers."""
    ensure_directories_exist()
    
    logger.info(f"Analyzing {len(image_paths)} images for outliers")
    
    # Extract features
    visual_features = []
    text_features = []
    deep_features = []
    valid_indices = []
    valid_paths = []
    
    # Load ResNet model once for efficiency
    resnet_model = tf.keras.applications.ResNet50(include_top=False, pooling='avg')
    
    for i, image_path in enumerate(image_paths):
        logger.info(f"Extracting features from image {i+1}/{len(image_paths)}: {image_path}")
        
        # Extract visual features
        vf = extract_visual_features(image_path)
        
        # Extract text features
        tf_features = extract_text_features(image_path)
        
        # Extract deep features
        df = extract_deep_features(image_path, resnet_model)
        
        # Only include images where all feature extraction succeeded
        if vf and tf_features and df:
            visual_features.append(list(vf.values()))
            
            # Extract numerical features from text features
            tf_numerical = [
                tf_features["text_length"],
                tf_features["word_count"],
                tf_features["alpha_ratio"],
                tf_features["digit_ratio"],
                tf_features["special_ratio"],
                tf_features["keyword_ratio"]
            ]
            text_features.append(tf_numerical)
            
            deep_features.append(df)
            valid_indices.append(i)
            valid_paths.append(image_path)
    
    # Convert to numpy arrays
    visual_features = np.array(visual_features)
    text_features = np.array(text_features)
    deep_features = np.array(deep_features)
    
    # Detect outliers in visual features
    visual_outliers = detect_multivariate_outliers(visual_features, contamination=0.1)
    logger.info(f"Detected {len(visual_outliers)} visual outliers")
    
    # Detect outliers in text features
    text_outliers = detect_multivariate_outliers(text_features, contamination=0.1)
    logger.info(f"Detected {len(text_outliers)} text content outliers")
    
    # Detect outliers in deep features
    deep_outliers = detect_multivariate_outliers(deep_features, contamination=0.05)
    logger.info(f"Detected {len(deep_outliers)} deep feature outliers")
    
    # Combine outlier detection results
    all_outliers = set(visual_outliers) | set(text_outliers) | set(deep_outliers)
    logger.info(f"Detected {len(all_outliers)} total unique outliers")
    
    # Visualize feature spaces
    os.makedirs(os.path.join(OUTLIERS_DIR, 'visualizations'), exist_ok=True)
    visualize_feature_space(
        visual_features, 
        visual_outliers, 
        os.path.join(OUTLIERS_DIR, 'visualizations', 'visual_features_tsne.png')
    )
    visualize_feature_space(
        text_features, 
        text_outliers, 
        os.path.join(OUTLIERS_DIR, 'visualizations', 'text_features_tsne.png')
    )
    
    # Prepare outlier metadata
    outlier_data = []
    
    for idx in all_outliers:
        real_idx = valid_indices[idx]
        image_path = valid_paths[idx]
        
        # Determine outlier type
        outlier_type = []
        if idx in visual_outliers:
            outlier_type.append("visual")
        if idx in text_outliers:
            outlier_type.append("content")
        if idx in deep_outliers:
            outlier_type.append("deep")
        
        # Determine destination directory
        if len(outlier_type) > 1:
            dest_dir = os.path.join(OUTLIERS_DIR, 'mixed_outliers')
            outlier_category = "mixed"
        elif "visual" in outlier_type:
            dest_dir = os.path.join(OUTLIERS_DIR, 'visual_outliers')
            outlier_category = "visual"
        elif "content" in outlier_type:
            dest_dir = os.path.join(OUTLIERS_DIR, 'content_outliers')
            outlier_category = "content"
        else:
            dest_dir = os.path.join(OUTLIERS_DIR, 'mixed_outliers')
            outlier_category = "deep"
        
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
                "outlier_types": outlier_type,
                "outlier_category": outlier_category,
                "visual_features": dict(zip(
                    ["edge_density", "color_variance", "text_ratio", "dct_energy", "aspect_ratio", "size"],
                    visual_features[idx].tolist()
                )),
                "text_features": {
                    "text_length": int(text_features[idx][0]),
                    "word_count": int(text_features[idx][1]),
                    "alpha_ratio": float(text_features[idx][2]),
                    "digit_ratio": float(text_features[idx][3]),
                    "special_ratio": float(text_features[idx][4]),
                    "keyword_ratio": float(text_features[idx][5])
                }
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
