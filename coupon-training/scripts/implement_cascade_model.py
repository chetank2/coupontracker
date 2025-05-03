#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Implement a cascade model for coupon recognition.
This script creates a two-stage pipeline:
1. First stage: Region detection to identify different coupon elements
2. Second stage: Specialized OCR for each region type
"""

import os
import sys
import json
import argparse
import cv2
import numpy as np
import pytesseract
from PIL import Image
from tqdm import tqdm
import re
import shutil

# Add parent directory to path
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

class CascadeModel:
    """
    A cascade model for coupon recognition with a two-stage pipeline:
    1. Region detection
    2. Specialized OCR
    """
    
    def __init__(self, region_detector_path, text_recognizer_path):
        """
        Initialize the cascade model
        
        Args:
            region_detector_path: Path to the region detector model
            text_recognizer_path: Path to the text recognizer model
        """
        self.region_detector_path = region_detector_path
        self.text_recognizer_path = text_recognizer_path
        
        # Load the region detector patterns
        self.region_patterns = self.load_region_patterns()
        
        # Initialize specialized OCR configurations
        self.ocr_configs = {
            'store': '--oem 3 --psm 7 -c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789&-. ',
            'code': '--oem 3 --psm 7 -c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789',
            'amount': '--oem 3 --psm 7 -c tessedit_char_whitelist=0123456789.,%₹$ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz ',
            'expiry': '--oem 3 --psm 7 -c tessedit_char_whitelist=0123456789/-.ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz ',
            'description': '--oem 3 --psm 6 -c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 .,;:!?%₹$()-_'
        }
    
    def load_region_patterns(self):
        """
        Load region patterns from the region detector model
        
        Returns:
            dict: Dictionary of region patterns by type
        """
        patterns = {
            'store': [],
            'code': [],
            'amount': [],
            'description': [],
            'expiry': []
        }
        
        pattern_file = os.path.join(self.region_detector_path, 'coupon_patterns.txt')
        
        if not os.path.exists(pattern_file):
            print(f"Error: Pattern file not found at {pattern_file}")
            return patterns
        
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
    
    def preprocess_image(self, image, region_type):
        """
        Preprocess image based on region type
        
        Args:
            image: OpenCV image
            region_type: Type of region ('store', 'code', 'amount', 'expiry', 'description')
            
        Returns:
            Preprocessed image
        """
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
    
    def detect_regions(self, image):
        """
        Detect regions in the image
        
        Args:
            image: OpenCV image
            
        Returns:
            dict: Dictionary of detected regions by type
        """
        height, width = image.shape[:2]
        
        # Scale patterns to match image dimensions
        scaled_patterns = {}
        for pattern_type, regions in self.region_patterns.items():
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
        
        return scaled_patterns
    
    def recognize_text(self, image, region, region_type):
        """
        Recognize text in a region using specialized OCR
        
        Args:
            image: OpenCV image
            region: Region coordinates
            region_type: Type of region
            
        Returns:
            str: Recognized text
        """
        # Extract region from image
        left = region['left']
        top = region['top']
        right = region['right']
        bottom = region['bottom']
        
        # Skip if region is invalid
        if left >= right or top >= bottom:
            return ""
        
        roi = image[top:bottom, left:right]
        
        # Skip if region is empty
        if roi.size == 0:
            return ""
        
        # Preprocess region based on type
        processed_roi = self.preprocess_image(roi, region_type)
        
        # Convert to PIL for OCR
        if len(processed_roi.shape) == 3:
            pil_image = Image.fromarray(cv2.cvtColor(processed_roi, cv2.COLOR_BGR2RGB))
        else:
            pil_image = Image.fromarray(processed_roi)
        
        # Apply specialized OCR configuration
        config = self.ocr_configs.get(region_type, '--oem 3 --psm 6')
        text = pytesseract.image_to_string(pil_image, config=config).strip()
        
        return text
    
    def post_process_text(self, text, region_type):
        """
        Post-process recognized text based on region type
        
        Args:
            text: Recognized text
            region_type: Type of region
            
        Returns:
            str: Post-processed text
        """
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
    
    def calculate_confidence(self, text, region_type):
        """
        Calculate confidence score for recognized text
        
        Args:
            text: Recognized text
            region_type: Type of region
            
        Returns:
            float: Confidence score (0.0 to 1.0)
        """
        if not text:
            return 0.0
        
        # Base score starts at 0.5
        score = 0.5
        
        if region_type == 'store':
            # Store names should be capitalized words
            if any(len(word) > 1 and word[0].isupper() for word in text.split()):
                score += 0.2
            
            # Store names shouldn't be too long
            if len(text) < 20:
                score += 0.1
            
            # Store names shouldn't contain too many numbers
            if sum(c.isdigit() for c in text) < len(text) / 3:
                score += 0.1
            
            # Common store name patterns
            if re.search(r'(?i).*(store|shop|mart|market|outlet).*', text):
                score += 0.1
        
        elif region_type == 'code':
            # Coupon codes are typically uppercase alphanumeric
            if all(c.isalnum() or c.isspace() for c in text):
                score += 0.2
            
            # Coupon codes usually have a minimum length
            if len(text.replace(" ", "")) >= 5:
                score += 0.1
            
            # Coupon codes often have a mix of letters and numbers
            if any(c.isalpha() for c in text) and any(c.isdigit() for c in text):
                score += 0.1
            
            # Coupon codes shouldn't contain too many special characters
            if sum(not c.isalnum() and not c.isspace() for c in text) <= 1:
                score += 0.1
        
        elif region_type == 'amount':
            # Amounts typically contain digits
            if any(c.isdigit() for c in text):
                score += 0.2
            
            # Amounts often contain currency symbols or percentage
            if re.search(r'[₹$%]', text):
                score += 0.2
            
            # Common amount patterns
            if re.search(r'(?i).*(off|discount|cashback|save).*', text):
                score += 0.1
            
            # Check for percentage pattern
            if re.search(r'.*\d+\s*%.*', text):
                score += 0.2
            
            # Check for currency pattern
            if re.search(r'.*(₹|Rs\.?)\s*\d+.*', text):
                score += 0.2
        
        elif region_type == 'expiry':
            # Expiry dates typically contain digits
            if any(c.isdigit() for c in text):
                score += 0.2
            
            # Expiry dates often contain date separators
            if re.search(r'[/.-]', text):
                score += 0.1
            
            # Common expiry date patterns
            if re.search(r'(?i).*(expir|valid|till|until).*', text):
                score += 0.2
            
            # Check for date pattern
            if re.search(r'.*\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}.*', text):
                score += 0.3
        
        elif region_type == 'description':
            # Descriptions are typically longer text
            if len(text) > 10:
                score += 0.1
            
            # Descriptions often contain multiple words
            if len(text.split()) > 3:
                score += 0.1
            
            # Descriptions shouldn't be just numbers
            if any(c.isalpha() for c in text):
                score += 0.1
            
            # Common description patterns
            if re.search(r'(?i).*(get|use|apply|offer|deal|discount).*', text):
                score += 0.1
        
        # Cap the score at 1.0
        return min(score, 1.0)
    
    def process_image(self, image_path):
        """
        Process an image through the cascade model
        
        Args:
            image_path: Path to the image
            
        Returns:
            dict: Dictionary of recognized elements with confidence scores
        """
        # Load image
        image = cv2.imread(image_path)
        if image is None:
            print(f"Error: Could not read image {image_path}")
            return {}
        
        # Stage 1: Detect regions
        regions = self.detect_regions(image)
        
        # Stage 2: Recognize text in each region
        results = {}
        confidence_scores = {}
        
        for region_type, region_list in regions.items():
            best_text = ""
            best_confidence = 0.0
            
            for region in region_list:
                # Recognize text in region
                text = self.recognize_text(image, region, region_type)
                
                # Skip if no text was found
                if not text:
                    continue
                
                # Post-process text
                processed_text = self.post_process_text(text, region_type)
                
                # Calculate confidence score
                confidence = self.calculate_confidence(processed_text, region_type)
                
                # If this is the best result so far for this type, save it
                if confidence > best_confidence:
                    best_text = processed_text
                    best_confidence = confidence
            
            # If we found a good result for this type, add it
            if best_text and best_confidence > 0.3:
                results[region_type] = best_text
                confidence_scores[region_type] = best_confidence
        
        return {
            'results': results,
            'confidence': confidence_scores
        }
    
    def save_model(self, output_path):
        """
        Save the cascade model
        
        Args:
            output_path: Path to save the model
        """
        # Create output directory if it doesn't exist
        os.makedirs(output_path, exist_ok=True)
        
        # Copy region detector patterns
        pattern_file = os.path.join(self.region_detector_path, 'coupon_patterns.txt')
        if os.path.exists(pattern_file):
            shutil.copy(pattern_file, os.path.join(output_path, 'coupon_patterns.txt'))
        
        # Save OCR configurations
        with open(os.path.join(output_path, 'ocr_configs.json'), 'w') as f:
            json.dump(self.ocr_configs, f, indent=2)
        
        # Save model metadata
        metadata = {
            'model_type': 'cascade',
            'region_detector': os.path.basename(self.region_detector_path),
            'text_recognizer': os.path.basename(self.text_recognizer_path),
            'version': '1.0.0',
            'created_at': str(datetime.datetime.now())
        }
        
        with open(os.path.join(output_path, 'model_metadata.json'), 'w') as f:
            json.dump(metadata, f, indent=2)
        
        print(f"Cascade model saved to {output_path}")

def main():
    parser = argparse.ArgumentParser(description="Implement a cascade model for coupon recognition")
    parser.add_argument("--first-stage-model", required=True, help="Path to the first stage model (region detector)")
    parser.add_argument("--second-stage-model", required=True, help="Path to the second stage model (text recognizer)")
    parser.add_argument("--output", default="../models/cascade", help="Path to save the cascade model")
    parser.add_argument("--test-image", help="Path to a test image to process")
    
    args = parser.parse_args()
    
    # Create cascade model
    cascade_model = CascadeModel(args.first_stage_model, args.second_stage_model)
    
    # Save the model
    cascade_model.save_model(args.output)
    
    # Test the model if a test image is provided
    if args.test_image:
        if os.path.exists(args.test_image):
            print(f"Testing model on {args.test_image}")
            result = cascade_model.process_image(args.test_image)
            
            print("\nRecognition Results:")
            for element_type, text in result['results'].items():
                confidence = result['confidence'].get(element_type, 0.0)
                print(f"  {element_type}: {text} (confidence: {confidence:.2f})")
        else:
            print(f"Error: Test image not found at {args.test_image}")

if __name__ == "__main__":
    import datetime
    main()
