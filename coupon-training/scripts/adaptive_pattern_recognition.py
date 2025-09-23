#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Adaptive Pattern Recognition for Coupon Tracker
This script implements a more flexible pattern recognition system that can adapt to different coupon layouts.
"""

import os
import sys
import json
import argparse
import cv2
import numpy as np
from PIL import Image
import pytesseract
import re
from sklearn.cluster import DBSCAN
from collections import defaultdict

# Add parent directory to path
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

class AdaptivePatternRecognizer:
    """
    A more flexible pattern recognition system that can adapt to different coupon layouts.
    Uses a combination of text detection, layout analysis, and content-based classification.
    """
    
    def __init__(self, config_path=None):
        """
        Initialize the adaptive pattern recognizer
        
        Args:
            config_path: Path to configuration file (optional)
        """
        # Default configuration
        self.config = {
            'min_text_height': 20,
            'min_text_width': 50,
            'text_padding': 10,
            'clustering_eps': 50,  # DBSCAN clustering parameter
            'clustering_min_samples': 2,  # DBSCAN clustering parameter
            'element_patterns': {
                'store': [r'(?i)myntra', r'(?i)swiggy', r'(?i)phonepe', r'(?i)amazon', r'(?i)flipkart'],
                'code': [r'(?i)code[:\s]*([A-Z0-9]{4,})', r'(?i)use[:\s]*([A-Z0-9]{4,})', r'(?i)^[A-Z0-9]{5,}$'],
                'amount': [r'(?i)(\d+)%\s*off', r'(?i)₹\s*(\d+)', r'(?i)flat\s*(\d+)', r'(?i)save\s*(\d+)'],
                'expiry': [r'(?i)valid\s*(?:till|until)[:\s]*(\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4})', 
                          r'(?i)expires?\s*(?:on)?[:\s]*(\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4})'],
                'description': [r'(?i)on\s*(?:all|select|orders)', r'(?i)minimum\s*(?:order|purchase)']
            },
            'ocr_configs': {
                'store': '--oem 3 --psm 7 -c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789&-. ',
                'code': '--oem 3 --psm 7 -c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789',
                'amount': '--oem 3 --psm 7 -c tessedit_char_whitelist=0123456789.,%₹$ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz ',
                'expiry': '--oem 3 --psm 7 -c tessedit_char_whitelist=0123456789/-.ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz ',
                'description': '--oem 3 --psm 6 -c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 .,;:!?%₹$()-_'
            }
        }
        
        # Load configuration if provided
        if config_path and os.path.exists(config_path):
            try:
                with open(config_path, 'r') as f:
                    loaded_config = json.load(f)
                    # Update default config with loaded values
                    for key, value in loaded_config.items():
                        if key in self.config:
                            if isinstance(value, dict) and isinstance(self.config[key], dict):
                                self.config[key].update(value)
                            else:
                                self.config[key] = value
            except Exception as e:
                print(f"Error loading configuration: {e}")
    
    def detect_text_regions(self, image):
        """
        Detect text regions in the image using OpenCV
        
        Args:
            image: OpenCV image
            
        Returns:
            list: List of detected text regions (x, y, w, h)
        """
        # Convert to grayscale
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        
        # Apply adaptive thresholding
        binary = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, 
                                      cv2.THRESH_BINARY_INV, 11, 2)
        
        # Find contours
        contours, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        
        # Filter contours by size
        min_text_height = self.config['min_text_height']
        min_text_width = self.config['min_text_width']
        text_regions = []
        
        for contour in contours:
            x, y, w, h = cv2.boundingRect(contour)
            if h >= min_text_height and w >= min_text_width:
                # Add padding
                padding = self.config['text_padding']
                x = max(0, x - padding)
                y = max(0, y - padding)
                w = min(image.shape[1] - x, w + 2 * padding)
                h = min(image.shape[0] - y, h + 2 * padding)
                text_regions.append((x, y, w, h))
        
        return text_regions
    
    def cluster_text_regions(self, text_regions):
        """
        Cluster text regions to identify logical groups
        
        Args:
            text_regions: List of text regions (x, y, w, h)
            
        Returns:
            dict: Dictionary of clustered regions by cluster ID
        """
        if not text_regions:
            return {}
        
        # Extract center points of regions
        centers = np.array([[x + w/2, y + h/2] for x, y, w, h in text_regions])
        
        # Perform DBSCAN clustering
        clustering = DBSCAN(
            eps=self.config['clustering_eps'], 
            min_samples=self.config['clustering_min_samples']
        ).fit(centers)
        
        # Group regions by cluster
        clusters = defaultdict(list)
        for i, label in enumerate(clustering.labels_):
            if label >= 0:  # Ignore noise points (label = -1)
                clusters[label].append(text_regions[i])
        
        return clusters
    
    def extract_text_from_region(self, image, region, element_type=None):
        """
        Extract text from a region using OCR
        
        Args:
            image: OpenCV image
            region: Region coordinates (x, y, w, h)
            element_type: Type of element (store, code, amount, expiry, description)
            
        Returns:
            str: Extracted text
        """
        x, y, w, h = region
        
        # Extract region from image
        roi = image[y:y+h, x:x+w]
        
        # Skip if region is empty
        if roi.size == 0:
            return ""
        
        # Convert to PIL for OCR
        pil_image = Image.fromarray(cv2.cvtColor(roi, cv2.COLOR_BGR2RGB))
        
        # Apply specialized OCR configuration if element type is provided
        config = '--oem 3 --psm 6'
        if element_type and element_type in self.config['ocr_configs']:
            config = self.config['ocr_configs'][element_type]
        
        # Perform OCR
        text = pytesseract.image_to_string(pil_image, config=config).strip()
        
        return text
    
    def classify_text_content(self, text):
        """
        Classify text content based on patterns
        
        Args:
            text: Text to classify
            
        Returns:
            tuple: (element_type, confidence)
        """
        if not text:
            return None, 0.0
        
        best_type = None
        best_confidence = 0.0
        
        for element_type, patterns in self.config['element_patterns'].items():
            for pattern in patterns:
                match = re.search(pattern, text)
                if match:
                    # Calculate confidence based on match length and position
                    match_len = len(match.group(0))
                    text_len = len(text)
                    position_factor = 1.0 - (match.start() / text_len) if text_len > 0 else 0.5
                    confidence = (match_len / text_len) * position_factor * 0.8 + 0.2
                    
                    if confidence > best_confidence:
                        best_type = element_type
                        best_confidence = confidence
        
        return best_type, best_confidence
    
    def analyze_layout(self, image, text_regions):
        """
        Analyze the layout of the coupon to identify regions
        
        Args:
            image: OpenCV image
            text_regions: List of text regions (x, y, w, h)
            
        Returns:
            dict: Dictionary of identified regions by element type
        """
        height, width = image.shape[:2]
        
        # Cluster text regions
        clusters = self.cluster_text_regions(text_regions)
        
        # Extract text from each region and classify
        classified_regions = defaultdict(list)
        region_texts = {}
        
        for cluster_id, regions in clusters.items():
            for region in regions:
                x, y, w, h = region
                
                # Extract text
                text = self.extract_text_from_region(image, region)
                if not text:
                    continue
                
                # Classify text
                element_type, confidence = self.classify_text_content(text)
                
                # If classification is uncertain, use position heuristics
                if not element_type or confidence < 0.5:
                    # Store name is typically at the top
                    if y < height * 0.2:
                        element_type = 'store'
                        confidence = 0.6
                    # Coupon code is typically in the middle
                    elif 0.3 * height < y < 0.7 * height and w > width * 0.3:
                        element_type = 'code'
                        confidence = 0.6
                    # Expiry date is typically at the bottom
                    elif y > height * 0.7:
                        element_type = 'expiry'
                        confidence = 0.5
                
                if element_type:
                    # Re-extract text with specialized OCR config
                    text = self.extract_text_from_region(image, region, element_type)
                    
                    # Store the region with its text and confidence
                    classified_regions[element_type].append({
                        'region': region,
                        'text': text,
                        'confidence': confidence
                    })
                    region_texts[(x, y, w, h)] = text
        
        # Post-process to select best regions for each element type
        identified_regions = {}
        
        for element_type, regions in classified_regions.items():
            if regions:
                # Sort by confidence
                regions.sort(key=lambda r: r['confidence'], reverse=True)
                
                # Select the region with highest confidence
                best_region = regions[0]
                x, y, w, h = best_region['region']
                
                identified_regions[element_type] = {
                    'left': x,
                    'top': y,
                    'right': x + w,
                    'bottom': y + h,
                    'text': best_region['text'],
                    'confidence': best_region['confidence']
                }
        
        return identified_regions
    
    def process_image(self, image_path):
        """
        Process an image to identify coupon elements
        
        Args:
            image_path: Path to the image
            
        Returns:
            dict: Dictionary of identified elements with confidence scores
        """
        try:
            # Load image
            image = cv2.imread(image_path)
            if image is None:
                print(f"Error: Could not read image {image_path}")
                return {}
            
            # Detect text regions
            text_regions = self.detect_text_regions(image)
            
            # Analyze layout
            identified_regions = self.analyze_layout(image, text_regions)
            
            # Extract results
            results = {}
            confidence_scores = {}
            
            for element_type, region_info in identified_regions.items():
                results[element_type] = region_info['text']
                confidence_scores[element_type] = region_info['confidence']
            
            return {
                'results': results,
                'confidence': confidence_scores,
                'regions': identified_regions
            }
            
        except Exception as e:
            print(f"Error processing image: {e}")
            return {}
    
    def save_results(self, results, output_path):
        """
        Save results to a JSON file
        
        Args:
            results: Results dictionary
            output_path: Path to save the results
        """
        try:
            with open(output_path, 'w') as f:
                json.dump(results, f, indent=2)
            print(f"Results saved to {output_path}")
        except Exception as e:
            print(f"Error saving results: {e}")

def main():
    parser = argparse.ArgumentParser(description="Adaptive Pattern Recognition for Coupon Tracker")
    parser.add_argument("--image", required=True, help="Path to the image to process")
    parser.add_argument("--config", help="Path to configuration file")
    parser.add_argument("--output", help="Path to save results")
    
    args = parser.parse_args()
    
    # Create recognizer
    recognizer = AdaptivePatternRecognizer(args.config)
    
    # Process image
    results = recognizer.process_image(args.image)
    
    # Print results
    print("\nRecognition Results:")
    for element_type, text in results['results'].items():
        confidence = results['confidence'].get(element_type, 0.0)
        print(f"  {element_type}: {text} (confidence: {confidence:.2f})")
    
    # Save results if output path is provided
    if args.output:
        recognizer.save_results(results, args.output)

if __name__ == "__main__":
    main()
