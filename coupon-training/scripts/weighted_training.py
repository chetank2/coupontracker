#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Weighted Training for Coupon Recognition
This script implements weighted training to handle outliers appropriately.
"""

import os
import sys
import json
import argparse
import numpy as np
import pandas as pd
from sklearn.model_selection import train_test_split
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import classification_report, confusion_matrix
import joblib
from tqdm import tqdm
import cv2
from PIL import Image
import pytesseract

# Add parent directory to path
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

def extract_features(image_path, regions=None):
    """
    Extract features from an image

    Args:
        image_path: Path to the image
        regions: Dictionary of regions (optional)

    Returns:
        dict: Dictionary of features
    """
    features = {}

    try:
        # Load image
        image = cv2.imread(image_path)
        if image is None:
            return features

        # Extract global features
        height, width, channels = image.shape
        features['image_height'] = height
        features['image_width'] = width
        features['aspect_ratio'] = width / height

        # Convert to grayscale
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)

        # Calculate histogram features
        hist = cv2.calcHist([gray], [0], None, [32], [0, 256])
        hist = cv2.normalize(hist, hist).flatten()
        for i, h in enumerate(hist):
            features[f'hist_{i}'] = float(h)

        # Calculate texture features (Haralick)
        try:
            from skimage.feature import graycomatrix, graycoprops

            # Downsample image for faster processing
            small_gray = cv2.resize(gray, (256, 256))

            # Calculate GLCM
            glcm = graycomatrix(small_gray, [5], [0], 256, symmetric=True, normed=True)

            # Calculate properties
            props = ['contrast', 'dissimilarity', 'homogeneity', 'energy', 'correlation']
            for prop in props:
                features[f'texture_{prop}'] = float(graycoprops(glcm, prop)[0, 0])
        except:
            # Fallback if skimage is not available
            for prop in ['contrast', 'dissimilarity', 'homogeneity', 'energy', 'correlation']:
                features[f'texture_{prop}'] = 0.0

        # Process regions if provided
        if regions:
            for region_type, region_list in regions.items():
                for i, region in enumerate(region_list):
                    # Extract region coordinates
                    left = region['left']
                    top = region['top']
                    right = region['right']
                    bottom = region['bottom']

                    # Ensure coordinates are within image bounds
                    left = max(0, min(left, width - 1))
                    top = max(0, min(top, height - 1))
                    right = max(left + 1, min(right, width))
                    bottom = max(top + 1, min(bottom, height))

                    # Extract region from image
                    roi = image[top:bottom, left:right]

                    # Skip if region is empty
                    if roi.size == 0:
                        continue

                    # Calculate region features
                    roi_height, roi_width = roi.shape[:2]
                    features[f'{region_type}_{i}_height'] = roi_height
                    features[f'{region_type}_{i}_width'] = roi_width
                    features[f'{region_type}_{i}_area'] = roi_height * roi_width
                    features[f'{region_type}_{i}_aspect_ratio'] = roi_width / max(1, roi_height)
                    features[f'{region_type}_{i}_rel_x'] = left / width
                    features[f'{region_type}_{i}_rel_y'] = top / height
                    features[f'{region_type}_{i}_rel_width'] = roi_width / width
                    features[f'{region_type}_{i}_rel_height'] = roi_height / height

                    # Convert to PIL for OCR
                    roi_pil = Image.fromarray(cv2.cvtColor(roi, cv2.COLOR_BGR2RGB))

                    # Extract text from region
                    roi_text = pytesseract.image_to_string(roi_pil)

                    # Text features
                    features[f'{region_type}_{i}_text_length'] = len(roi_text)
                    features[f'{region_type}_{i}_word_count'] = len(roi_text.split())
                    features[f'{region_type}_{i}_digit_count'] = sum(c.isdigit() for c in roi_text)
                    features[f'{region_type}_{i}_alpha_count'] = sum(c.isalpha() for c in roi_text)
                    features[f'{region_type}_{i}_upper_count'] = sum(c.isupper() for c in roi_text)
                    features[f'{region_type}_{i}_lower_count'] = sum(c.islower() for c in roi_text)
                    features[f'{region_type}_{i}_space_count'] = sum(c.isspace() for c in roi_text)
                    features[f'{region_type}_{i}_special_count'] = len(roi_text) - features[f'{region_type}_{i}_digit_count'] - features[f'{region_type}_{i}_alpha_count'] - features[f'{region_type}_{i}_space_count']

    except Exception as e:
        print(f"Error extracting features from {image_path}: {e}")

    return features

def load_dataset(image_dir, annotation_dir, features_path=None):
    """
    Load dataset and extract features

    Args:
        image_dir: Directory containing images
        annotation_dir: Directory containing annotation files
        features_path: Path to save/load features (optional)

    Returns:
        tuple: (features_df, labels_df)
    """
    # Check if features file exists
    if features_path and os.path.exists(features_path):
        print(f"Loading features from {features_path}")
        features_df = pd.read_csv(features_path)

        # Check if 'is_outlier' column exists
        if 'is_outlier' in features_df.columns:
            # Extract labels
            labels = features_df[['image_path', 'is_outlier']].copy()

            # Remove label columns from features
            features_df = features_df.drop(columns=['is_outlier'])
        else:
            # Create 'is_outlier' column based on filename
            features_df['is_outlier'] = features_df['image_path'].apply(
                lambda x: 1 if "synthetic" in os.path.basename(x).lower() else 0
            )

            # Extract labels
            labels = features_df[['image_path', 'is_outlier']].copy()

            # Remove label columns from features
            features_df = features_df.drop(columns=['is_outlier'])

        return features_df, labels

    # Get list of image files
    image_files = []
    for root, _, files in os.walk(image_dir):
        for file in files:
            if file.lower().endswith(('.jpg', '.jpeg', '.png')):
                image_files.append(os.path.join(root, file))

    if not image_files:
        print(f"No image files found in {image_dir}")
        return None, None

    print(f"Found {len(image_files)} image files")

    # Extract features from each image
    all_features = []

    for image_path in tqdm(image_files):
        # Get corresponding annotation path
        base_name = os.path.splitext(os.path.basename(image_path))[0]
        annotation_path = os.path.join(annotation_dir, f"{base_name}_annotations.json")

        # Skip if annotation doesn't exist
        if not os.path.exists(annotation_path):
            print(f"Annotation not found for {image_path}, skipping")
            continue

        # Load annotation
        try:
            with open(annotation_path, 'r') as f:
                regions = json.load(f)
        except Exception as e:
            print(f"Error loading annotation {annotation_path}: {e}")
            continue

        # Extract features
        features = extract_features(image_path, regions)

        # Add image path
        features['image_path'] = image_path

        # Determine if image is an outlier (for demonstration, we'll consider any image with "synthetic" in the name as an outlier)
        features['is_outlier'] = 1 if "synthetic" in os.path.basename(image_path).lower() else 0

        all_features.append(features)

    # Convert to DataFrame
    features_df = pd.DataFrame(all_features)

    # Handle missing values
    features_df = features_df.fillna(0)

    # Extract labels
    labels = features_df[['image_path', 'is_outlier']].copy()

    # Remove label columns from features
    features_df = features_df.drop(columns=['is_outlier'])

    # Save features if path is provided
    if features_path:
        # Combine features and labels for saving
        save_df = pd.concat([features_df, labels[['is_outlier']]], axis=1)
        save_df.to_csv(features_path, index=False)
        print(f"Features saved to {features_path}")

    return features_df, labels

def train_weighted_model(features, labels, outlier_weight=0.5, model_path=None):
    """
    Train a weighted model

    Args:
        features: DataFrame of features
        labels: DataFrame of labels
        outlier_weight: Weight for outlier samples (0-1)
        model_path: Path to save the model (optional)

    Returns:
        tuple: (model, accuracy)
    """
    # Remove image_path column if present
    if 'image_path' in features.columns:
        features = features.drop(columns=['image_path'])

    # Remove non-numeric columns
    numeric_features = features.select_dtypes(include=['int64', 'float64'])

    # Print info about removed columns
    removed_columns = set(features.columns) - set(numeric_features.columns)
    if removed_columns:
        print(f"Removed {len(removed_columns)} non-numeric columns: {', '.join(removed_columns)}")

    # Extract outlier labels
    y = labels['is_outlier'].values

    # Split data
    X_train, X_test, y_train, y_test = train_test_split(numeric_features, y, test_size=0.2, random_state=42)

    # Calculate sample weights
    sample_weights = np.ones(len(y_train))
    outlier_indices = np.where(y_train == 1)[0]
    sample_weights[outlier_indices] = outlier_weight

    # Train model
    model = RandomForestClassifier(n_estimators=100, random_state=42)
    model.fit(X_train, y_train, sample_weight=sample_weights)

    # Evaluate model
    y_pred = model.predict(X_test)
    accuracy = (y_pred == y_test).mean()

    print("\nModel Evaluation:")
    print(f"Accuracy: {accuracy:.4f}")
    print("\nClassification Report:")
    print(classification_report(y_test, y_pred))
    print("\nConfusion Matrix:")
    print(confusion_matrix(y_test, y_pred))

    # Save model if path is provided
    if model_path:
        joblib.dump(model, model_path)
        print(f"Model saved to {model_path}")

    return model, accuracy

def main():
    parser = argparse.ArgumentParser(description="Weighted training for coupon recognition")
    parser.add_argument("--image-dir", required=True, help="Directory containing images")
    parser.add_argument("--annotation-dir", required=True, help="Directory containing annotation files")
    parser.add_argument("--features-path", help="Path to save/load features")
    parser.add_argument("--model-path", help="Path to save the model")
    parser.add_argument("--outlier-weight", type=float, default=0.5, help="Weight for outlier samples (0-1)")

    args = parser.parse_args()

    # Load dataset
    features, labels = load_dataset(args.image_dir, args.annotation_dir, args.features_path)

    if features is None or labels is None:
        return

    # Get numeric features
    numeric_features = features.select_dtypes(include=['int64', 'float64'])

    # Train model
    model, accuracy = train_weighted_model(features, labels, args.outlier_weight, args.model_path)

    # Print feature importance
    if model is not None:
        # Get feature names from the model
        if hasattr(model, 'feature_names_in_'):
            feature_names = model.feature_names_in_
        else:
            feature_names = numeric_features.columns

        # Create feature importance DataFrame
        feature_importance = pd.DataFrame({
            'feature': feature_names,
            'importance': model.feature_importances_
        })
        feature_importance = feature_importance.sort_values('importance', ascending=False)

        print("\nTop 20 Important Features:")
        print(feature_importance.head(20))

if __name__ == "__main__":
    main()
