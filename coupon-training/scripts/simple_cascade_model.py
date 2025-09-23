#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Simple cascade model for coupon recognition.
This script implements a basic two-stage pipeline:
1. First stage: Region detection
2. Second stage: Specialized OCR
"""

import os
import sys
import json
import argparse
import cv2
import numpy as np
import pytesseract
from PIL import Image
import re

# Add parent directory to path
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

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

def preprocess_image(image, region_type):
    """Preprocess image based on region type"""
    if region_type == 'store':
        # For store names, enhance contrast
        lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB)
        l, a, b = cv2.split(lab)
        clahe = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(8, 8))
        cl = clahe.apply(l)
        limg = cv2.merge((cl, a, b))
        enhanced = cv2.cvtColor(limg, cv2.COLOR_LAB2BGR)
        return enhanced
    
    elif region_type == 'code':
        # For coupon codes, binarize
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        _, binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        return binary
    
    elif region_type == 'amount':
        # For amounts, adaptive threshold
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        binary = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 11, 2)
        return binary
    
    elif region_type == 'expiry':
        # For expiry dates, sharpen
        kernel = np.array([[-1, -1, -1], [-1, 9, -1], [-1, -1, -1]])
        sharpened = cv2.filter2D(image, -1, kernel)
        return sharpened
    
    elif region_type == 'description':
        # For descriptions, mild enhancement
        lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB)
        l, a, b = cv2.split(lab)
        clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
        cl = clahe.apply(l)
        limg = cv2.merge((cl, a, b))
        enhanced = cv2.cvtColor(limg, cv2.COLOR_LAB2BGR)
        return enhanced
    
    # Default: return original image
    return image

def get_ocr_config(region_type):
    """Get specialized OCR configuration for region type"""
    configs = {
        'store': '--oem 3 --psm 7 -c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789&-. ',
        'code': '--oem 3 --psm 7 -c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789',
        'amount': '--oem 3 --psm 7 -c tessedit_char_whitelist=0123456789.,%₹$ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz ',
        'expiry': '--oem 3 --psm 7 -c tessedit_char_whitelist=0123456789/-.ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz ',
        'description': '--oem 3 --psm 6 -c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 .,;:!?%₹$()-_'
    }
    return configs.get(region_type, '--oem 3 --psm 6')

def post_process_text(text, region_type):
    """Post-process recognized text based on region type"""
    if not text:
        return ""
    
    if region_type == 'store':
        # Clean up store name
        text = text.strip()
        text = re.sub(r'\s+', ' ', text)  # Normalize whitespace
        words = text.split()
        words = [word for word in words if word]
        words = [word[0].upper() + word[1:].lower() if len(word) > 1 else word.upper() for word in words]
        return ' '.join(words)
    
    elif region_type == 'code':
        # Clean up coupon code
        text = text.strip()
        text = re.sub(r'[^A-Z0-9]', '', text.upper())  # Remove non-alphanumeric characters
        return text
    
    elif region_type == 'amount':
        # Clean up amount
        text = text.strip()
        text = re.sub(r'\s+', ' ', text)  # Normalize whitespace
        
        # Ensure proper spacing around percentage symbol
        text = re.sub(r'(\d)%', r'\1 %', text)
        
        # Ensure proper spacing around currency symbols
        text = re.sub(r'(₹|Rs\.?)(\d)', r'\1 \2', text)
        
        return text
    
    elif region_type == 'expiry':
        # Clean up expiry date
        text = text.strip()
        text = re.sub(r'\s+', ' ', text)  # Normalize whitespace
        
        # Try to extract date if it contains a date pattern
        date_match = re.search(r'(\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4})', text)
        if date_match:
            return date_match.group(1)
        
        return text
    
    elif region_type == 'description':
        # Clean up description
        text = text.strip()
        text = re.sub(r'\s+', ' ', text)  # Normalize whitespace
        if text and len(text) > 0:
            text = text[0].upper() + text[1:]  # Capitalize first letter
        
        return text
    
    return text

def process_image(image_path, pattern_file):
    """Process an image with the cascade model"""
    # Load image
    image = cv2.imread(image_path)
    if image is None:
        print(f"Error: Could not read image {image_path}")
        return {}
    
    # Get image dimensions
    height, width = image.shape[:2]
    
    # Load patterns
    patterns = load_patterns(pattern_file)
    
    # Scale patterns to match image dimensions
    scaled_patterns = {}
    for pattern_type, regions in patterns.items():
        scaled_patterns[pattern_type] = []
        for region in regions:
            # Scale region coordinates
            left = int(region['left'] * width / 720)
            top = int(region['top'] * height / 1600)
            right = int(region['right'] * width / 720)
            bottom = int(region['bottom'] * height / 1600)
            
            # Ensure coordinates are within image bounds
            left = max(0, min(left, width - 1))
            top = max(0, min(top, height - 1))
            right = max(0, min(right, width - 1))
            bottom = max(0, min(bottom, height - 1))
            
            # Skip if region is too small
            if right - left < 10 or bottom - top < 10:
                continue
            
            scaled_patterns[pattern_type].append({
                'left': left,
                'top': top,
                'right': right,
                'bottom': bottom
            })
    
    # Process each region type
    results = {}
    confidence_scores = {}
    
    for region_type, regions in scaled_patterns.items():
        best_text = ""
        best_confidence = 0.0
        
        for region in regions:
            # Extract region from image
            left = region['left']
            top = region['top']
            right = region['right']
            bottom = region['bottom']
            
            # Skip if region is invalid
            if left >= right or top >= bottom:
                continue
            
            roi = image[top:bottom, left:right]
            
            # Skip if region is empty
            if roi.size == 0:
                continue
            
            # Preprocess region based on type
            processed_roi = preprocess_image(roi, region_type)
            
            # Convert to PIL for OCR
            if len(processed_roi.shape) == 3:
                pil_image = Image.fromarray(cv2.cvtColor(processed_roi, cv2.COLOR_BGR2RGB))
            else:
                pil_image = Image.fromarray(processed_roi)
            
            # Apply specialized OCR configuration
            config = get_ocr_config(region_type)
            text = pytesseract.image_to_string(pil_image, config=config).strip()
            
            # Skip if no text was found
            if not text:
                continue
            
            # Post-process text
            processed_text = post_process_text(text, region_type)
            
            # Calculate simple confidence score (length-based)
            confidence = min(len(processed_text) / 20, 1.0)
            
            # If this is the best result so far for this type, save it
            if confidence > best_confidence:
                best_text = processed_text
                best_confidence = confidence
        
        # If we found a good result for this type, add it
        if best_text and best_confidence > 0.1:
            results[region_type] = best_text
            confidence_scores[region_type] = best_confidence
    
    return {
        'results': results,
        'confidence': confidence_scores
    }

def main():
    parser = argparse.ArgumentParser(description="Simple cascade model for coupon recognition")
    parser.add_argument("--image", required=True, help="Path to the image to process")
    parser.add_argument("--pattern-file", default="../models/simplified/coupon_patterns.txt", help="Path to the pattern file")
    
    args = parser.parse_args()
    
    # Process the image
    result = process_image(args.image, args.pattern_file)
    
    # Print results
    print("\nRecognition Results:")
    for element_type, text in result['results'].items():
        confidence = result['confidence'].get(element_type, 0.0)
        print(f"  {element_type}: {text} (confidence: {confidence:.2f})")

if __name__ == "__main__":
    main()
