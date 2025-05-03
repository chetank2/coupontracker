#!/usr/bin/env python3
"""
Enhanced Outlier Detector for Coupon Images

This script provides improved outlier detection using clustering and
more sophisticated feature extraction.
"""

import os
import cv2
import numpy as np
import json
import logging
import argparse
from pathlib import Path
import matplotlib.pyplot as plt
from sklearn.cluster import DBSCAN, KMeans
from sklearn.preprocessing import StandardScaler
from sklearn.decomposition import PCA
import pytesseract
from collections import Counter
import tensorflow as tf

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("enhanced_outlier_detection.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("enhanced_outlier_detector")

# Base directories
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROCESSED_IMAGES_DIR = os.path.join(BASE_DIR, 'processed_images')
OUTLIERS_DIR = os.path.join(BASE_DIR, 'outliers')
ENHANCED_OUTLIER_METADATA = os.path.join(BASE_DIR, 'enhanced_outlier_metadata.json')
VISUALIZATION_DIR = os.path.join(OUTLIERS_DIR, 'visualizations')

def ensure_directories_exist():
    """Ensure all necessary directories exist."""
    for subdir in ['visual_outliers', 'content_outliers', 'mixed_outliers', 'visualizations']:
        os.makedirs(os.path.join(OUTLIERS_DIR, subdir), exist_ok=True)
    logger.info("Directory structure verified")

def extract_visual_features(image_path):
    """Extract enhanced visual features from an image."""
    try:
        # Load image
        image = cv2.imread(image_path)
        if image is None:
            logger.error(f"Failed to load image: {image_path}")
            return None
        
        # Convert to grayscale
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        
        # Basic features
        height, width = image.shape[:2]
        aspect_ratio = width / height
        size = width * height
        
        # Edge features
        edges = cv2.Canny(gray, 50, 150)
        edge_density = np.sum(edges > 0) / (edges.shape[0] * edges.shape[1])
        
        # Texture features using GLCM (Gray-Level Co-occurrence Matrix)
        # Simplified version - just use standard deviation as a texture measure
        texture_std = np.std(gray)
        
        # Color features
        hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
        h, s, v = cv2.split(hsv)
        color_features = {
            'hue_mean': float(np.mean(h)),
            'hue_std': float(np.std(h)),
            'saturation_mean': float(np.mean(s)),
            'saturation_std': float(np.std(s)),
            'value_mean': float(np.mean(v)),
            'value_std': float(np.std(v))
        }
        
        # Layout features - detect text regions
        # Use simple thresholding to estimate text regions
        _, thresh = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
        text_ratio = np.sum(thresh > 0) / (thresh.shape[0] * thresh.shape[1])
        
        # Find contours to analyze layout
        contours, _ = cv2.findContours(thresh, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        num_contours = len(contours)
        
        # Calculate contour density (number of contours per unit area)
        contour_density = num_contours / (width * height) * 10000  # Scaled for readability
        
        # Calculate average contour size
        if num_contours > 0:
            avg_contour_size = np.mean([cv2.contourArea(c) for c in contours])
        else:
            avg_contour_size = 0
        
        # Combine all features
        features = {
            "aspect_ratio": float(aspect_ratio),
            "size": int(size),
            "edge_density": float(edge_density),
            "texture_std": float(texture_std),
            "text_ratio": float(text_ratio),
            "num_contours": int(num_contours),
            "contour_density": float(contour_density),
            "avg_contour_size": float(avg_contour_size),
            **color_features
        }
        
        return features
    
    except Exception as e:
        logger.error(f"Error extracting visual features from {image_path}: {e}")
        return None

def extract_text_features(image_path):
    """Extract enhanced text features from an image using OCR."""
    try:
        # Load image
        image = cv2.imread(image_path)
        if image is None:
            logger.error(f"Failed to load image: {image_path}")
            return None
        
        # Perform OCR
        text = pytesseract.image_to_string(image)
        
        # Basic text features
        text_length = len(text)
        words = [word for word in text.split() if word.strip()]
        word_count = len(words)
        
        # Character distribution
        char_counts = Counter(text.lower())
        alpha_count = sum(char_counts.get(c, 0) for c in 'abcdefghijklmnopqrstuvwxyz')
        digit_count = sum(char_counts.get(c, 0) for c in '0123456789')
        special_count = sum(char_counts.get(c, 0) for c in '!@#$%^&*()_+-=[]{}|;:,.<>?/')
        
        alpha_ratio = alpha_count / max(text_length, 1)
        digit_ratio = digit_count / max(text_length, 1)
        special_ratio = special_count / max(text_length, 1)
        
        # Word length statistics
        if words:
            word_lengths = [len(word) for word in words]
            avg_word_length = np.mean(word_lengths)
            max_word_length = max(word_lengths)
        else:
            avg_word_length = 0
            max_word_length = 0
        
        # Coupon-specific keyword detection
        coupon_keywords = [
            'coupon', 'code', 'offer', 'discount', 'off', 'save', 'free', 
            'deal', 'valid', 'expires', 'cashback', 'redeem', 'promo'
        ]
        keyword_count = sum(1 for word in words if word.lower() in coupon_keywords)
        keyword_ratio = keyword_count / max(word_count, 1)
        
        # Date pattern detection
        date_patterns = [
            r'\d{1,2}[/-]\d{1,2}[/-]\d{2,4}',  # DD/MM/YYYY
            r'\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{2,4}',  # DD MMM YYYY
            r'(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{1,2},?\s+\d{2,4}'  # MMM DD, YYYY
        ]
        
        has_date = any(re.search(pattern, text, re.IGNORECASE) for pattern in date_patterns)
        
        # Currency symbol detection
        currency_symbols = ['₹', '$', '€', '£', '¥']
        has_currency = any(symbol in text for symbol in currency_symbols)
        
        # Percentage detection
        has_percentage = '%' in text or 'percent' in text.lower()
        
        # Combine all features
        features = {
            "text_length": text_length,
            "word_count": word_count,
            "alpha_ratio": float(alpha_ratio),
            "digit_ratio": float(digit_ratio),
            "special_ratio": float(special_ratio),
            "avg_word_length": float(avg_word_length),
            "max_word_length": int(max_word_length),
            "keyword_count": keyword_count,
            "keyword_ratio": float(keyword_ratio),
            "has_date": has_date,
            "has_currency": has_currency,
            "has_percentage": has_percentage,
            "extracted_text": text[:500]  # Limit text length for storage
        }
        
        return features
    
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

def detect_outliers_with_clustering(features, method='dbscan', **kwargs):
    """Detect outliers using clustering methods."""
    # Convert features to numpy array
    X = np.array(features)
    
    # Standardize features
    scaler = StandardScaler()
    X_scaled = scaler.fit_transform(X)
    
    # Apply dimensionality reduction for visualization
    pca = PCA(n_components=2)
    X_pca = pca.fit_transform(X_scaled)
    
    # Detect outliers using the specified method
    if method == 'dbscan':
        eps = kwargs.get('eps', 0.5)
        min_samples = kwargs.get('min_samples', 5)
        
        # Apply DBSCAN clustering
        dbscan = DBSCAN(eps=eps, min_samples=min_samples)
        labels = dbscan.fit_predict(X_scaled)
        
        # Points labeled as -1 are outliers
        outlier_indices = np.where(labels == -1)[0]
        
    elif method == 'kmeans':
        n_clusters = kwargs.get('n_clusters', 3)
        threshold = kwargs.get('threshold', 2.0)
        
        # Apply K-means clustering
        kmeans = KMeans(n_clusters=n_clusters, random_state=42)
        labels = kmeans.fit_predict(X_scaled)
        
        # Calculate distance to cluster centers
        distances = np.min(
            [np.linalg.norm(X_scaled - center, axis=1) for center in kmeans.cluster_centers_],
            axis=0
        )
        
        # Points with distance greater than threshold are outliers
        outlier_indices = np.where(distances > threshold)[0]
    
    else:
        raise ValueError(f"Unknown clustering method: {method}")
    
    # Create visualization
    plt.figure(figsize=(10, 8))
    
    # Plot normal points
    normal_indices = [i for i in range(len(X)) if i not in outlier_indices]
    plt.scatter(
        X_pca[normal_indices, 0],
        X_pca[normal_indices, 1],
        c='blue',
        alpha=0.5,
        label='Normal'
    )
    
    # Plot outliers
    if len(outlier_indices) > 0:
        plt.scatter(
            X_pca[outlier_indices, 0],
            X_pca[outlier_indices, 1],
            c='red',
            marker='x',
            s=100,
            label='Outlier'
        )
    
    plt.title(f'Outlier Detection using {method.upper()}')
    plt.legend()
    
    return outlier_indices, X_pca

def analyze_outliers(image_paths):
    """Analyze images and detect outliers using enhanced methods."""
    ensure_directories_exist()
    
    logger.info(f"Analyzing {len(image_paths)} images for outliers")
    
    # Extract features
    visual_features_list = []
    text_features_list = []
    deep_features_list = []
    valid_indices = []
    valid_paths = []
    
    # Load ResNet model once for efficiency
    resnet_model = tf.keras.applications.ResNet50(include_top=False, pooling='avg')
    
    for i, image_path in enumerate(image_paths):
        logger.info(f"Extracting features from image {i+1}/{len(image_paths)}: {image_path}")
        
        # Extract visual features
        visual_features = extract_visual_features(image_path)
        
        # Extract text features
        text_features = extract_text_features(image_path)
        
        # Extract deep features
        deep_features = extract_deep_features(image_path, resnet_model)
        
        # Only include images where all feature extraction succeeded
        if visual_features and text_features and deep_features:
            # Convert visual features to list
            visual_features_values = list(visual_features.values())
            
            # Convert text features to list (excluding the text itself)
            text_features_values = [
                text_features["text_length"],
                text_features["word_count"],
                text_features["alpha_ratio"],
                text_features["digit_ratio"],
                text_features["special_ratio"],
                text_features["avg_word_length"],
                text_features["max_word_length"],
                text_features["keyword_count"],
                text_features["keyword_ratio"],
                1 if text_features["has_date"] else 0,
                1 if text_features["has_currency"] else 0,
                1 if text_features["has_percentage"] else 0
            ]
            
            visual_features_list.append(visual_features_values)
            text_features_list.append(text_features_values)
            deep_features_list.append(deep_features)
            valid_indices.append(i)
            valid_paths.append(image_path)
    
    # Convert to numpy arrays
    visual_features_array = np.array(visual_features_list)
    text_features_array = np.array(text_features_list)
    deep_features_array = np.array(deep_features_list)
    
    # Detect outliers using different methods
    outlier_results = {}
    
    # 1. Visual features outliers using DBSCAN
    logger.info("Detecting visual outliers using DBSCAN")
    visual_outliers, visual_pca = detect_outliers_with_clustering(
        visual_features_array, 
        method='dbscan',
        eps=0.5,
        min_samples=3
    )
    
    # Save visualization
    plt.savefig(os.path.join(VISUALIZATION_DIR, 'visual_outliers_dbscan.png'))
    plt.close()
    
    # 2. Text features outliers using K-means
    logger.info("Detecting text outliers using K-means")
    text_outliers, text_pca = detect_outliers_with_clustering(
        text_features_array, 
        method='kmeans',
        n_clusters=3,
        threshold=2.0
    )
    
    # Save visualization
    plt.savefig(os.path.join(VISUALIZATION_DIR, 'text_outliers_kmeans.png'))
    plt.close()
    
    # 3. Deep features outliers using DBSCAN
    logger.info("Detecting deep feature outliers using DBSCAN")
    deep_outliers, deep_pca = detect_outliers_with_clustering(
        deep_features_array, 
        method='dbscan',
        eps=0.3,
        min_samples=3
    )
    
    # Save visualization
    plt.savefig(os.path.join(VISUALIZATION_DIR, 'deep_outliers_dbscan.png'))
    plt.close()
    
    # Combine outlier detection results
    all_outliers = set(visual_outliers) | set(text_outliers) | set(deep_outliers)
    logger.info(f"Detected {len(all_outliers)} total unique outliers")
    
    # Create combined visualization
    plt.figure(figsize=(12, 10))
    
    # Plot all points
    plt.scatter(
        visual_pca[:, 0],
        visual_pca[:, 1],
        c='blue',
        alpha=0.3,
        label='Normal'
    )
    
    # Plot different types of outliers
    if len(visual_outliers) > 0:
        plt.scatter(
            visual_pca[visual_outliers, 0],
            visual_pca[visual_outliers, 1],
            c='red',
            marker='x',
            s=100,
            label='Visual Outlier'
        )
    
    if len(text_outliers) > 0:
        plt.scatter(
            visual_pca[text_outliers, 0],
            visual_pca[text_outliers, 1],
            c='green',
            marker='+',
            s=100,
            label='Text Outlier'
        )
    
    if len(deep_outliers) > 0:
        plt.scatter(
            visual_pca[deep_outliers, 0],
            visual_pca[deep_outliers, 1],
            c='purple',
            marker='*',
            s=100,
            label='Deep Outlier'
        )
    
    plt.title('Combined Outlier Detection')
    plt.legend()
    plt.savefig(os.path.join(VISUALIZATION_DIR, 'combined_outliers.png'))
    plt.close()
    
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
                    list(extract_visual_features(image_path).keys()),
                    visual_features_list[idx]
                )),
                "text_features": text_features[idx] if idx < len(text_features) else None
            })
        except Exception as e:
            logger.error(f"Error copying outlier image {image_path}: {e}")
    
    # Save outlier metadata
    with open(ENHANCED_OUTLIER_METADATA, 'w') as f:
        json.dump(outlier_data, f, indent=2)
    
    logger.info(f"Enhanced outlier analysis complete. Identified {len(outlier_data)} outliers.")
    return outlier_data

def main():
    """Main function to detect outliers in coupon images."""
    parser = argparse.ArgumentParser(description='Detect outliers in coupon images using enhanced methods.')
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
