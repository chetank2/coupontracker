#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Error Recovery and Retry Logic for Coupon Tracker
This script implements robust error handling and retry mechanisms for coupon recognition.
"""

import os
import sys
import json
import argparse
import cv2
import numpy as np
from PIL import Image
import pytesseract
import time
import logging
import re
from functools import wraps

# Add parent directory to path
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("coupon_tracker.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("CouponTracker")

def retry(max_attempts=3, delay=1, backoff=2, exceptions=(Exception,)):
    """
    Retry decorator with exponential backoff

    Args:
        max_attempts: Maximum number of retry attempts
        delay: Initial delay between retries in seconds
        backoff: Backoff multiplier
        exceptions: Tuple of exceptions to catch and retry

    Returns:
        Decorated function
    """
    def decorator(func):
        @wraps(func)
        def wrapper(*args, **kwargs):
            attempt = 0
            current_delay = delay

            while attempt < max_attempts:
                try:
                    return func(*args, **kwargs)
                except exceptions as e:
                    attempt += 1
                    if attempt >= max_attempts:
                        logger.error(f"Failed after {max_attempts} attempts: {e}")
                        raise

                    logger.warning(f"Attempt {attempt} failed: {e}. Retrying in {current_delay}s...")
                    time.sleep(current_delay)
                    current_delay *= backoff

            return None  # Should never reach here

        return wrapper

    return decorator

class ErrorRecoveryHandler:
    """
    Error recovery handler for coupon recognition
    """

    def __init__(self, config_path=None):
        """
        Initialize the error recovery handler

        Args:
            config_path: Path to configuration file (optional)
        """
        # Default configuration
        self.config = {
            'max_retry_attempts': 3,
            'retry_delay': 1,
            'retry_backoff': 2,
            'fallback_methods': ['adaptive_threshold', 'gaussian_blur', 'sharpen', 'histogram_equalization'],
            'ocr_configs': {
                'default': '--oem 3 --psm 6',
                'code': '--oem 3 --psm 7 -c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789',
                'amount': '--oem 3 --psm 7 -c tessedit_char_whitelist=0123456789.,%₹$ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz ',
                'expiry': '--oem 3 --psm 7 -c tessedit_char_whitelist=0123456789/-.ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz '
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
                logger.error(f"Error loading configuration: {e}")

    @retry(max_attempts=3, delay=1, backoff=2)
    def extract_text_with_retry(self, image, config=None):
        """
        Extract text from an image with retry logic

        Args:
            image: PIL Image
            config: Tesseract configuration string

        Returns:
            str: Extracted text
        """
        try:
            if config is None:
                config = self.config['ocr_configs']['default']

            text = pytesseract.image_to_string(image, config=config).strip()
            return text
        except Exception as e:
            logger.error(f"OCR error: {e}")
            raise

    def apply_preprocessing(self, image, method):
        """
        Apply preprocessing method to image

        Args:
            image: OpenCV image
            method: Preprocessing method name

        Returns:
            OpenCV image: Preprocessed image
        """
        if method == 'adaptive_threshold':
            # Convert to grayscale
            gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
            # Apply adaptive thresholding
            return cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
                                        cv2.THRESH_BINARY, 11, 2)

        elif method == 'gaussian_blur':
            # Apply Gaussian blur
            return cv2.GaussianBlur(image, (5, 5), 0)

        elif method == 'sharpen':
            # Apply sharpening
            kernel = np.array([[-1, -1, -1], [-1, 9, -1], [-1, -1, -1]])
            return cv2.filter2D(image, -1, kernel)

        elif method == 'histogram_equalization':
            # Convert to LAB color space
            lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB)
            # Split channels
            l, a, b = cv2.split(lab)
            # Apply CLAHE to L channel
            clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
            cl = clahe.apply(l)
            # Merge channels
            merged = cv2.merge((cl, a, b))
            # Convert back to BGR
            return cv2.cvtColor(merged, cv2.COLOR_LAB2BGR)

        # Default: return original image
        return image

    def extract_text_with_fallbacks(self, image, region_type=None):
        """
        Extract text with fallback methods if initial attempt fails

        Args:
            image: OpenCV image
            region_type: Type of region (store, code, amount, expiry, description)

        Returns:
            dict: Dictionary with text and confidence score
        """
        # Convert to PIL for OCR
        pil_image = Image.fromarray(cv2.cvtColor(image, cv2.COLOR_BGR2RGB))

        # Get OCR config for region type
        config = self.config['ocr_configs'].get(
            region_type,
            self.config['ocr_configs']['default']
        )

        # Initial attempt
        try:
            text = self.extract_text_with_retry(pil_image, config)
            if text and len(text) > 1:
                return {
                    'text': text,
                    'confidence': 0.8,
                    'method': 'default'
                }
        except Exception as e:
            logger.warning(f"Initial OCR attempt failed: {e}")

        # Try fallback methods
        results = []

        for method in self.config['fallback_methods']:
            try:
                # Apply preprocessing
                processed_image = self.apply_preprocessing(image, method)

                # Convert to PIL for OCR
                if len(processed_image.shape) == 3:
                    pil_processed = Image.fromarray(cv2.cvtColor(processed_image, cv2.COLOR_BGR2RGB))
                else:
                    pil_processed = Image.fromarray(processed_image)

                # Extract text
                text = self.extract_text_with_retry(pil_processed, config)

                # If text was extracted, add to results
                if text and len(text) > 1:
                    # Calculate confidence based on text length and content
                    confidence = min(0.7, 0.3 + (len(text) / 50))

                    # Adjust confidence based on content
                    if region_type == 'code' and re.search(r'^[A-Z0-9]{4,}$', text):
                        confidence += 0.2
                    elif region_type == 'amount' and re.search(r'(?i)(\d+%|₹\s*\d+|\d+\s*off)', text):
                        confidence += 0.2
                    elif region_type == 'expiry' and re.search(r'(?i)(\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4})', text):
                        confidence += 0.2

                    results.append({
                        'text': text,
                        'confidence': min(confidence, 0.9),
                        'method': method
                    })
            except Exception as e:
                logger.warning(f"Fallback method '{method}' failed: {e}")

        # If we have results, return the one with highest confidence
        if results:
            results.sort(key=lambda x: x['confidence'], reverse=True)
            return results[0]

        # If all methods failed, return empty result
        return {
            'text': "",
            'confidence': 0.0,
            'method': 'none'
        }

    def process_region(self, image, region, region_type=None):
        """
        Process a region with error recovery

        Args:
            image: OpenCV image
            region: Region coordinates (left, top, right, bottom)
            region_type: Type of region (store, code, amount, expiry, description)

        Returns:
            dict: Dictionary with text and confidence score
        """
        try:
            # Extract region from image
            left, top, right, bottom = region['left'], region['top'], region['right'], region['bottom']

            # Skip if region is invalid
            if left >= right or top >= bottom:
                logger.warning(f"Invalid region: {region}")
                return {
                    'text': "",
                    'confidence': 0.0,
                    'method': 'none'
                }

            roi = image[top:bottom, left:right]

            # Skip if region is empty
            if roi.size == 0:
                logger.warning(f"Empty region: {region}")
                return {
                    'text': "",
                    'confidence': 0.0,
                    'method': 'none'
                }

            # Extract text with fallbacks
            result = self.extract_text_with_fallbacks(roi, region_type)

            return result

        except Exception as e:
            logger.error(f"Error processing region: {e}")
            return {
                'text': "",
                'confidence': 0.0,
                'method': 'error'
            }

    def process_image(self, image_path, regions):
        """
        Process an image with error recovery

        Args:
            image_path: Path to the image
            regions: Dictionary of regions by element type

        Returns:
            dict: Dictionary of results by element type
        """
        try:
            # Load image
            image = cv2.imread(image_path)
            if image is None:
                logger.error(f"Error: Could not read image {image_path}")
                return {}

            # Process each region
            results = {}
            confidence_scores = {}
            methods_used = {}

            for region_type, region_list in regions.items():
                best_result = {
                    'text': "",
                    'confidence': 0.0,
                    'method': 'none'
                }

                for region in region_list:
                    # Process region
                    result = self.process_region(image, region, region_type)

                    # If better than current best, update
                    if result['confidence'] > best_result['confidence']:
                        best_result = result

                # Store results
                if best_result['text']:
                    results[region_type] = best_result['text']
                    confidence_scores[region_type] = best_result['confidence']
                    methods_used[region_type] = best_result['method']

            return {
                'results': results,
                'confidence': confidence_scores,
                'methods': methods_used
            }

        except Exception as e:
            logger.error(f"Error processing image: {e}")
            return {}

def main():
    parser = argparse.ArgumentParser(description="Error Recovery for Coupon Tracker")
    parser.add_argument("--image", required=True, help="Path to the image to process")
    parser.add_argument("--regions", required=True, help="Path to regions JSON file")
    parser.add_argument("--config", help="Path to configuration file")
    parser.add_argument("--output", help="Path to save results")

    args = parser.parse_args()

    # Load regions
    try:
        with open(args.regions, 'r') as f:
            regions = json.load(f)
    except Exception as e:
        logger.error(f"Error loading regions: {e}")
        return

    # Create error recovery handler
    handler = ErrorRecoveryHandler(args.config)

    # Process image
    results = handler.process_image(args.image, regions)

    # Print results
    print("\nRecognition Results:")
    for element_type, text in results['results'].items():
        confidence = results['confidence'].get(element_type, 0.0)
        method = results['methods'].get(element_type, 'unknown')
        print(f"  {element_type}: {text} (confidence: {confidence:.2f}, method: {method})")

    # Save results if output path is provided
    if args.output:
        try:
            with open(args.output, 'w') as f:
                json.dump(results, f, indent=2)
            print(f"Results saved to {args.output}")
        except Exception as e:
            logger.error(f"Error saving results: {e}")

if __name__ == "__main__":
    main()
