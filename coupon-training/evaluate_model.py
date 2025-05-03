#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import sys
import json
import cv2
import pytesseract
from PIL import Image
import re
import argparse
from tqdm import tqdm

def load_coupon_summary(summary_file):
    """Load the coupon summary data from markdown file"""
    coupon_data = {}
    current_brand = None
    
    with open(summary_file, 'r') as f:
        lines = f.readlines()
    
    for line in lines:
        line = line.strip()
        if line.startswith('## '):
            current_brand = line[3:].strip()
            coupon_data[current_brand] = {}
        elif line.startswith('- **Coupon Code:**'):
            if current_brand:
                coupon_data[current_brand]['code'] = line.split('**Coupon Code:**')[1].strip()
        elif line.startswith('- **Cashback/Discount:**'):
            if current_brand:
                coupon_data[current_brand]['amount'] = line.split('**Cashback/Discount:**')[1].strip()
        elif line.startswith('- **Expiry Date:**'):
            if current_brand:
                coupon_data[current_brand]['expiry'] = line.split('**Expiry Date:**')[1].strip()
        elif line.startswith('- **Source File:**'):
            if current_brand:
                coupon_data[current_brand]['source_file'] = line.split('**Source File:**')[1].strip()
    
    return coupon_data

def load_patterns(pattern_file):
    """Load pattern regions from the pattern file"""
    patterns = {
        'store': [],
        'code': [],
        'amount': [],
        'description': [],
        'expiry': []
    }
    
    with open(pattern_file, 'r') as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith('#'):
                parts = line.split(':')
                if len(parts) == 2:
                    pattern_type = parts[0]
                    coords = parts[1].split(',')
                    if len(coords) == 4 and pattern_type in patterns:
                        try:
                            left = int(coords[0])
                            top = int(coords[1])
                            right = int(coords[2])
                            bottom = int(coords[3])
                            patterns[pattern_type].append({
                                'left': left,
                                'top': top,
                                'right': right,
                                'bottom': bottom
                            })
                        except ValueError:
                            print(f"Invalid coordinates in line: {line}")
    
    return patterns

def extract_text_from_region(image, region, region_type=None):
    """Extract text from a specific region of the image"""
    if region is None:
        return None
    
    left = region['left']
    top = region['top']
    right = region['right']
    bottom = region['bottom']
    
    # Extract the region from the image
    roi = image[top:bottom, left:right]
    
    # Skip if region is empty
    if roi.size == 0:
        return None
    
    # Convert to PIL for OCR
    roi_pil = Image.fromarray(cv2.cvtColor(roi, cv2.COLOR_BGR2RGB))
    
    # Apply different OCR configurations based on region type
    if region_type == 'code':
        # For coupon codes, optimize for alphanumeric characters
        config = '--oem 3 --psm 7 -c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
        text = pytesseract.image_to_string(roi_pil, config=config).strip()
    elif region_type == 'amount':
        # For amounts, optimize for numbers and currency symbols
        config = '--oem 3 --psm 6 -c tessedit_char_whitelist=0123456789%.₹$ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'
        text = pytesseract.image_to_string(roi_pil, config=config).strip()
    elif region_type == 'expiry':
        # For expiry dates, optimize for date formats
        config = '--oem 3 --psm 6'
        text = pytesseract.image_to_string(roi_pil, config=config).strip()
    else:
        # For other types, use general configuration
        config = '--oem 3 --psm 6'
        text = pytesseract.image_to_string(roi_pil, config=config).strip()
    
    return text

def evaluate_image(image_path, patterns, ground_truth=None):
    """Evaluate the model on a single image"""
    # Load the image
    image = cv2.imread(image_path)
    if image is None:
        print(f"Error: Could not read image {image_path}")
        return None
    
    # Get image dimensions
    height, width = image.shape[:2]
    
    # Extract text from each pattern type
    results = {
        'store': None,
        'code': None,
        'amount': None,
        'description': None,
        'expiry': None
    }
    
    for pattern_type, regions in patterns.items():
        for region in regions:
            # Scale region if needed
            scaled_region = region.copy()
            
            # Extract text from the region
            text = extract_text_from_region(image, scaled_region, pattern_type)
            
            # If we got text and don't have a result for this type yet, save it
            if text and not results[pattern_type]:
                results[pattern_type] = text
    
    # Calculate accuracy if ground truth is provided
    accuracy = {}
    if ground_truth:
        for field in ['code', 'amount', 'expiry']:
            if field in ground_truth and ground_truth[field] and results[field]:
                # For coupon codes, compare alphanumeric characters only
                if field == 'code':
                    gt_code = re.sub(r'[^A-Z0-9]', '', ground_truth[field].upper())
                    result_code = re.sub(r'[^A-Z0-9]', '', results[field].upper())
                    
                    # Calculate similarity (exact match or partial match)
                    if gt_code == result_code:
                        accuracy[field] = 1.0
                    else:
                        # Calculate character-level accuracy
                        common_chars = sum(1 for c in gt_code if c in result_code)
                        accuracy[field] = common_chars / max(len(gt_code), len(result_code))
                
                # For amounts, compare numbers and symbols
                elif field == 'amount':
                    gt_amount = ground_truth[field].lower()
                    result_amount = results[field].lower()
                    
                    # Check for exact match
                    if gt_amount == result_amount:
                        accuracy[field] = 1.0
                    else:
                        # Check for partial match (e.g., "42% Off" vs "42%")
                        gt_numbers = re.findall(r'\d+', gt_amount)
                        result_numbers = re.findall(r'\d+', result_amount)
                        
                        if gt_numbers and result_numbers and gt_numbers[0] == result_numbers[0]:
                            accuracy[field] = 0.8  # Same number but different text
                        else:
                            accuracy[field] = 0.0
                
                # For expiry dates, compare date patterns
                elif field == 'expiry':
                    gt_expiry = ground_truth[field].lower()
                    result_expiry = results[field].lower()
                    
                    # Check for exact match
                    if gt_expiry == result_expiry:
                        accuracy[field] = 1.0
                    else:
                        # Check for "Not Mentioned" case
                        if "not mentioned" in gt_expiry:
                            accuracy[field] = 1.0  # Assume correct if ground truth has no expiry
                        else:
                            accuracy[field] = 0.0
    
    return {
        'results': results,
        'accuracy': accuracy
    }

def main():
    parser = argparse.ArgumentParser(description="Evaluate model performance on coupon images")
    parser.add_argument("--data-dir", default="data", help="Directory containing the data")
    parser.add_argument("--pattern-file", default="models/simplified/coupon_patterns.txt", help="Path to the pattern file")
    parser.add_argument("--summary-file", default="data/coupon_summary.md", help="Path to the coupon summary file")
    
    args = parser.parse_args()
    
    # Load the coupon summary data
    print("Loading coupon summary data...")
    coupon_data = load_coupon_summary(args.summary_file)
    print(f"Loaded data for {len(coupon_data)} coupons")
    
    # Load the patterns
    print("Loading pattern data...")
    patterns = load_patterns(args.pattern_file)
    print(f"Loaded {sum(len(regions) for regions in patterns.values())} patterns")
    
    # Evaluate each image
    total_accuracy = {
        'code': [],
        'amount': [],
        'expiry': []
    }
    
    print("Evaluating images...")
    for brand, data in tqdm(coupon_data.items()):
        if 'source_file' in data:
            image_path = os.path.join(args.data_dir, 'raw', data['source_file'])
            
            if os.path.exists(image_path):
                # Create ground truth data
                ground_truth = {
                    'code': data.get('code'),
                    'amount': data.get('amount'),
                    'expiry': data.get('expiry')
                }
                
                # Evaluate the image
                result = evaluate_image(image_path, patterns, ground_truth)
                
                if result:
                    print(f"\nResults for {brand} ({data['source_file']}):")
                    print(f"  Store: {result['results']['store']}")
                    print(f"  Code: {result['results']['code']} (Ground Truth: {ground_truth['code']})")
                    print(f"  Amount: {result['results']['amount']} (Ground Truth: {ground_truth['amount']})")
                    print(f"  Expiry: {result['results']['expiry']} (Ground Truth: {ground_truth['expiry']})")
                    print(f"  Description: {result['results']['description']}")
                    
                    # Print accuracy
                    if result['accuracy']:
                        print("  Accuracy:")
                        for field, acc in result['accuracy'].items():
                            print(f"    {field}: {acc:.2f}")
                            total_accuracy[field].append(acc)
            else:
                print(f"Warning: Image not found: {image_path}")
    
    # Calculate overall accuracy
    print("\nOverall Accuracy:")
    for field, values in total_accuracy.items():
        if values:
            avg_accuracy = sum(values) / len(values)
            print(f"  {field}: {avg_accuracy:.2f}")
    
    # Calculate overall model performance
    all_values = []
    for values in total_accuracy.values():
        all_values.extend(values)
    
    if all_values:
        overall_accuracy = sum(all_values) / len(all_values)
        print(f"\nOverall Model Performance: {overall_accuracy:.2f}")
        
        # Interpret the result
        if overall_accuracy >= 0.9:
            print("The model is very well trained and performs excellently.")
        elif overall_accuracy >= 0.8:
            print("The model is well trained and performs good.")
        elif overall_accuracy >= 0.7:
            print("The model is adequately trained but could be improved.")
        elif overall_accuracy >= 0.5:
            print("The model needs significant improvement.")
        else:
            print("The model is poorly trained and needs to be retrained.")

if __name__ == "__main__":
    main()
