#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Feature Engineering for Coupon Recognition
This script implements feature engineering for dates, prices, and promotion types.
"""

import os
import sys
import re
import json
import argparse
import numpy as np
import pandas as pd
from datetime import datetime
import pytesseract
from PIL import Image
import cv2
from tqdm import tqdm

# Add parent directory to path
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# Regular expressions for feature extraction
PATTERNS = {
    'date': [
        r'(\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4})',  # DD/MM/YYYY, DD-MM-YYYY, DD.MM.YYYY
        r'(\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{2,4})',  # DD Month YYYY
        r'(?:valid|expires?|till|until)\s+(\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4})',  # Valid/Expires till DD/MM/YYYY
        r'(?:valid|expires?|till|until)\s+(\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*)',  # Valid/Expires till DD Month
    ],
    'price': [
        r'(?:₹|Rs\.?|INR)\s*(\d+(?:[,.]\d+)?)',  # ₹100, Rs.100, Rs 100, INR 100
        r'(\d+(?:[,.]\d+)?)\s*(?:₹|Rs\.?|INR)',  # 100₹, 100 Rs., 100 Rs, 100 INR
        r'(\d+)%\s*(?:off|discount|cashback)',  # 20% off, 20% discount, 20% cashback
        r'(?:off|discount|cashback)\s*(\d+)%',  # off 20%, discount 20%, cashback 20%
        r'(?:flat|save|get)\s*(?:₹|Rs\.?|INR)?\s*(\d+(?:[,.]\d+)?)',  # Flat ₹100, Save Rs.100, Get 100
    ],
    'promotion_type': [
        r'(buy\s+\d+\s+get\s+\d+)',  # Buy 1 Get 1, Buy 2 Get 1
        r'(first\s+(?:order|purchase|time))',  # First order, First purchase, First time
        r'(new\s+user)',  # New user
        r'(limited\s+(?:time|period|offer))',  # Limited time, Limited period, Limited offer
        r'(exclusive)',  # Exclusive
        r'(flash\s+sale)',  # Flash sale
        r'(clearance)',  # Clearance
        r'(season(?:al)?\s+(?:sale|discount|offer))',  # Seasonal sale, Season discount, Seasonal offer
        r'(festive\s+(?:sale|discount|offer))',  # Festive sale, Festive discount, Festive offer
        r'(combo\s+(?:offer|deal))',  # Combo offer, Combo deal
    ]
}

def extract_features_from_text(text):
    """
    Extract features from text using regular expressions
    
    Args:
        text: Text to extract features from
        
    Returns:
        dict: Dictionary of extracted features
    """
    features = {
        'date': None,
        'price': None,
        'promotion_type': None,
        'has_date': 0,
        'has_price': 0,
        'has_promotion_type': 0,
        'date_confidence': 0.0,
        'price_confidence': 0.0,
        'promotion_confidence': 0.0
    }
    
    # Normalize text
    normalized_text = text.lower().strip()
    
    # Extract date
    for pattern in PATTERNS['date']:
        match = re.search(pattern, normalized_text, re.IGNORECASE)
        if match:
            features['date'] = match.group(1)
            features['has_date'] = 1
            # Higher confidence for more specific patterns
            if 'valid' in pattern or 'expires' in pattern:
                features['date_confidence'] = 0.9
            else:
                features['date_confidence'] = 0.7
            break
    
    # Extract price
    for pattern in PATTERNS['price']:
        match = re.search(pattern, normalized_text, re.IGNORECASE)
        if match:
            features['price'] = match.group(1)
            features['has_price'] = 1
            # Higher confidence for currency symbols
            if '₹' in pattern or 'Rs' in pattern or 'INR' in pattern:
                features['price_confidence'] = 0.9
            else:
                features['price_confidence'] = 0.7
            break
    
    # Extract promotion type
    for pattern in PATTERNS['promotion_type']:
        match = re.search(pattern, normalized_text, re.IGNORECASE)
        if match:
            features['promotion_type'] = match.group(1)
            features['has_promotion_type'] = 1
            features['promotion_confidence'] = 0.8
            break
    
    return features

def extract_features_from_image(image_path, annotation_path=None):
    """
    Extract features from an image and its annotation
    
    Args:
        image_path: Path to the image
        annotation_path: Path to the annotation file (optional)
        
    Returns:
        dict: Dictionary of extracted features
    """
    features = {
        'image_path': image_path,
        'date': None,
        'price': None,
        'promotion_type': None,
        'has_date': 0,
        'has_price': 0,
        'has_promotion_type': 0,
        'date_confidence': 0.0,
        'price_confidence': 0.0,
        'promotion_confidence': 0.0,
        'store': None,
        'code': None,
        'amount': None,
        'expiry': None,
        'description': None
    }
    
    try:
        # Load image
        image = Image.open(image_path)
        
        # Extract text from the entire image
        full_text = pytesseract.image_to_string(image)
        
        # Extract features from full text
        text_features = extract_features_from_text(full_text)
        features.update(text_features)
        
        # If annotation is provided, extract text from specific regions
        if annotation_path and os.path.exists(annotation_path):
            try:
                with open(annotation_path, 'r') as f:
                    annotation = json.load(f)
                
                # Convert PIL image to OpenCV format
                cv_image = cv2.cvtColor(np.array(image), cv2.COLOR_RGB2BGR)
                
                # Extract text from each region
                for region_type, regions in annotation.items():
                    region_text = ""
                    
                    for region in regions:
                        # Extract region coordinates
                        left = region['left']
                        top = region['top']
                        right = region['right']
                        bottom = region['bottom']
                        
                        # Extract region from image
                        roi = cv_image[top:bottom, left:right]
                        
                        # Skip if region is empty
                        if roi.size == 0:
                            continue
                        
                        # Convert to PIL for OCR
                        roi_pil = Image.fromarray(cv2.cvtColor(roi, cv2.COLOR_BGR2RGB))
                        
                        # Extract text from region
                        roi_text = pytesseract.image_to_string(roi_pil)
                        
                        # Add to region text
                        if roi_text.strip():
                            region_text += roi_text + " "
                    
                    # Store region text
                    features[region_type] = region_text.strip()
                    
                    # Extract features from region text
                    if region_type in ['expiry', 'description']:
                        region_features = extract_features_from_text(region_text)
                        
                        # Update features if confidence is higher
                        if region_features['date_confidence'] > features['date_confidence']:
                            features['date'] = region_features['date']
                            features['has_date'] = region_features['has_date']
                            features['date_confidence'] = region_features['date_confidence']
                        
                        if region_features['price_confidence'] > features['price_confidence']:
                            features['price'] = region_features['price']
                            features['has_price'] = region_features['has_price']
                            features['price_confidence'] = region_features['price_confidence']
                        
                        if region_features['promotion_confidence'] > features['promotion_confidence']:
                            features['promotion_type'] = region_features['promotion_type']
                            features['has_promotion_type'] = region_features['has_promotion_type']
                            features['promotion_confidence'] = region_features['promotion_confidence']
            
            except Exception as e:
                print(f"Error processing annotation {annotation_path}: {e}")
    
    except Exception as e:
        print(f"Error processing image {image_path}: {e}")
    
    return features

def process_images(image_dir, annotation_dir=None, output_path=None):
    """
    Process images and extract features
    
    Args:
        image_dir: Directory containing images
        annotation_dir: Directory containing annotation files (optional)
        output_path: Path to save features (optional)
        
    Returns:
        pd.DataFrame: DataFrame of extracted features
    """
    # Get list of image files
    image_files = []
    for root, _, files in os.walk(image_dir):
        for file in files:
            if file.lower().endswith(('.jpg', '.jpeg', '.png')):
                image_files.append(os.path.join(root, file))
    
    if not image_files:
        print(f"No image files found in {image_dir}")
        return None
    
    print(f"Found {len(image_files)} image files")
    
    # Extract features from each image
    all_features = []
    
    for image_path in tqdm(image_files):
        # Get corresponding annotation path if annotation directory is provided
        annotation_path = None
        if annotation_dir:
            base_name = os.path.splitext(os.path.basename(image_path))[0]
            annotation_path = os.path.join(annotation_dir, f"{base_name}_annotations.json")
        
        # Extract features
        features = extract_features_from_image(image_path, annotation_path)
        all_features.append(features)
    
    # Convert to DataFrame
    df = pd.DataFrame(all_features)
    
    # Save features if output path is provided
    if output_path:
        df.to_csv(output_path, index=False)
        print(f"Features saved to {output_path}")
    
    return df

def analyze_features(features_df):
    """
    Analyze extracted features
    
    Args:
        features_df: DataFrame of extracted features
        
    Returns:
        dict: Dictionary of analysis results
    """
    analysis = {}
    
    # Count occurrences of each feature
    analysis['total_images'] = len(features_df)
    analysis['images_with_date'] = features_df['has_date'].sum()
    analysis['images_with_price'] = features_df['has_price'].sum()
    analysis['images_with_promotion'] = features_df['has_promotion_type'].sum()
    
    # Calculate percentages
    analysis['date_percentage'] = analysis['images_with_date'] / analysis['total_images'] * 100
    analysis['price_percentage'] = analysis['images_with_price'] / analysis['total_images'] * 100
    analysis['promotion_percentage'] = analysis['images_with_promotion'] / analysis['total_images'] * 100
    
    # Calculate average confidence
    analysis['avg_date_confidence'] = features_df[features_df['has_date'] == 1]['date_confidence'].mean()
    analysis['avg_price_confidence'] = features_df[features_df['has_price'] == 1]['price_confidence'].mean()
    analysis['avg_promotion_confidence'] = features_df[features_df['has_promotion_type'] == 1]['promotion_confidence'].mean()
    
    # Count images with store, code, amount, expiry, description
    for field in ['store', 'code', 'amount', 'expiry', 'description']:
        analysis[f'images_with_{field}'] = features_df[field].notna().sum()
        analysis[f'{field}_percentage'] = analysis[f'images_with_{field}'] / analysis['total_images'] * 100
    
    return analysis

def main():
    parser = argparse.ArgumentParser(description="Feature engineering for coupon recognition")
    parser.add_argument("--image-dir", required=True, help="Directory containing images")
    parser.add_argument("--annotation-dir", help="Directory containing annotation files")
    parser.add_argument("--output-path", help="Path to save features")
    parser.add_argument("--analyze", action="store_true", help="Analyze extracted features")
    
    args = parser.parse_args()
    
    # Process images
    features_df = process_images(args.image_dir, args.annotation_dir, args.output_path)
    
    if features_df is None:
        return
    
    # Analyze features if requested
    if args.analyze:
        analysis = analyze_features(features_df)
        
        print("\nFeature Analysis:")
        print(f"Total images: {analysis['total_images']}")
        print(f"Images with date: {analysis['images_with_date']} ({analysis['date_percentage']:.1f}%)")
        print(f"Images with price: {analysis['images_with_price']} ({analysis['price_percentage']:.1f}%)")
        print(f"Images with promotion type: {analysis['images_with_promotion']} ({analysis['promotion_percentage']:.1f}%)")
        
        print("\nAverage confidence:")
        print(f"Date: {analysis['avg_date_confidence']:.2f}")
        print(f"Price: {analysis['avg_price_confidence']:.2f}")
        print(f"Promotion type: {analysis['avg_promotion_confidence']:.2f}")
        
        print("\nAnnotation fields:")
        for field in ['store', 'code', 'amount', 'expiry', 'description']:
            print(f"Images with {field}: {analysis[f'images_with_{field}']} ({analysis[f'{field}_percentage']:.1f}%)")

if __name__ == "__main__":
    main()
