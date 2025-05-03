#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Testable Coupon Recognizer
This script implements a testable code structure with dependency injection for the coupon recognizer.
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
import abc
import logging
from typing import Dict, List, Tuple, Optional, Any, Union

# Add parent directory to path
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("coupon_tracker_test.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("TestableCouponRecognizer")

# Define interfaces for dependency injection
class ImageLoader(abc.ABC):
    """Interface for image loading"""
    
    @abc.abstractmethod
    def load_image(self, image_path: str) -> np.ndarray:
        """
        Load an image from a path
        
        Args:
            image_path: Path to the image
            
        Returns:
            np.ndarray: Loaded image
        """
        pass

class TextExtractor(abc.ABC):
    """Interface for text extraction"""
    
    @abc.abstractmethod
    def extract_text(self, image: Union[np.ndarray, Image.Image], config: Optional[str] = None) -> str:
        """
        Extract text from an image
        
        Args:
            image: Image to extract text from
            config: Configuration for text extraction
            
        Returns:
            str: Extracted text
        """
        pass

class ImagePreprocessor(abc.ABC):
    """Interface for image preprocessing"""
    
    @abc.abstractmethod
    def preprocess(self, image: np.ndarray, region_type: Optional[str] = None) -> np.ndarray:
        """
        Preprocess an image
        
        Args:
            image: Image to preprocess
            region_type: Type of region
            
        Returns:
            np.ndarray: Preprocessed image
        """
        pass

class TextPostprocessor(abc.ABC):
    """Interface for text postprocessing"""
    
    @abc.abstractmethod
    def postprocess(self, text: str, region_type: Optional[str] = None) -> str:
        """
        Postprocess extracted text
        
        Args:
            text: Text to postprocess
            region_type: Type of region
            
        Returns:
            str: Postprocessed text
        """
        pass

class ConfidenceCalculator(abc.ABC):
    """Interface for confidence calculation"""
    
    @abc.abstractmethod
    def calculate_confidence(self, text: str, region_type: Optional[str] = None) -> float:
        """
        Calculate confidence score for text
        
        Args:
            text: Text to calculate confidence for
            region_type: Type of region
            
        Returns:
            float: Confidence score (0.0 to 1.0)
        """
        pass

# Concrete implementations
class OpenCVImageLoader(ImageLoader):
    """OpenCV implementation of image loader"""
    
    def load_image(self, image_path: str) -> np.ndarray:
        """
        Load an image using OpenCV
        
        Args:
            image_path: Path to the image
            
        Returns:
            np.ndarray: Loaded image
        """
        image = cv2.imread(image_path)
        if image is None:
            raise ValueError(f"Could not read image {image_path}")
        return image

class TesseractTextExtractor(TextExtractor):
    """Tesseract implementation of text extractor"""
    
    def extract_text(self, image: Union[np.ndarray, Image.Image], config: Optional[str] = None) -> str:
        """
        Extract text using Tesseract OCR
        
        Args:
            image: Image to extract text from
            config: Tesseract configuration
            
        Returns:
            str: Extracted text
        """
        # Convert OpenCV image to PIL if needed
        if isinstance(image, np.ndarray):
            pil_image = Image.fromarray(cv2.cvtColor(image, cv2.COLOR_BGR2RGB))
        else:
            pil_image = image
        
        # Extract text
        text = pytesseract.image_to_string(pil_image, config=config).strip()
        
        return text

class DefaultImagePreprocessor(ImagePreprocessor):
    """Default implementation of image preprocessor"""
    
    def preprocess(self, image: np.ndarray, region_type: Optional[str] = None) -> np.ndarray:
        """
        Preprocess an image based on region type
        
        Args:
            image: Image to preprocess
            region_type: Type of region
            
        Returns:
            np.ndarray: Preprocessed image
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

class DefaultTextPostprocessor(TextPostprocessor):
    """Default implementation of text postprocessor"""
    
    def postprocess(self, text: str, region_type: Optional[str] = None) -> str:
        """
        Postprocess text based on region type
        
        Args:
            text: Text to postprocess
            region_type: Type of region
            
        Returns:
            str: Postprocessed text
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

class DefaultConfidenceCalculator(ConfidenceCalculator):
    """Default implementation of confidence calculator"""
    
    def calculate_confidence(self, text: str, region_type: Optional[str] = None) -> float:
        """
        Calculate confidence score for text
        
        Args:
            text: Text to calculate confidence for
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

# Main recognizer class
class CouponRecognizer:
    """
    Testable coupon recognizer with dependency injection
    """
    
    def __init__(
        self,
        image_loader: ImageLoader = None,
        text_extractor: TextExtractor = None,
        image_preprocessor: ImagePreprocessor = None,
        text_postprocessor: TextPostprocessor = None,
        confidence_calculator: ConfidenceCalculator = None,
        config_path: Optional[str] = None
    ):
        """
        Initialize the coupon recognizer
        
        Args:
            image_loader: Image loader implementation
            text_extractor: Text extractor implementation
            image_preprocessor: Image preprocessor implementation
            text_postprocessor: Text postprocessor implementation
            confidence_calculator: Confidence calculator implementation
            config_path: Path to configuration file (optional)
        """
        # Set dependencies (with defaults if not provided)
        self.image_loader = image_loader or OpenCVImageLoader()
        self.text_extractor = text_extractor or TesseractTextExtractor()
        self.image_preprocessor = image_preprocessor or DefaultImagePreprocessor()
        self.text_postprocessor = text_postprocessor or DefaultTextPostprocessor()
        self.confidence_calculator = confidence_calculator or DefaultConfidenceCalculator()
        
        # Default configuration
        self.config = {
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
                logger.error(f"Error loading configuration: {e}")
    
    def process_region(
        self, 
        image: np.ndarray, 
        region: Dict[str, int], 
        region_type: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Process a region to extract text
        
        Args:
            image: Image to process
            region: Region coordinates (left, top, right, bottom)
            region_type: Type of region
            
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
                    'confidence': 0.0
                }
            
            roi = image[top:bottom, left:right]
            
            # Skip if region is empty
            if roi.size == 0:
                logger.warning(f"Empty region: {region}")
                return {
                    'text': "",
                    'confidence': 0.0
                }
            
            # Preprocess region
            processed_roi = self.image_preprocessor.preprocess(roi, region_type)
            
            # Get OCR config for region type
            config = None
            if region_type and region_type in self.config['ocr_configs']:
                config = self.config['ocr_configs'][region_type]
            
            # Extract text
            text = self.text_extractor.extract_text(processed_roi, config)
            
            # Postprocess text
            processed_text = self.text_postprocessor.postprocess(text, region_type)
            
            # Calculate confidence
            confidence = self.confidence_calculator.calculate_confidence(processed_text, region_type)
            
            return {
                'text': processed_text,
                'confidence': confidence
            }
            
        except Exception as e:
            logger.error(f"Error processing region: {e}")
            return {
                'text': "",
                'confidence': 0.0
            }
    
    def process_image(self, image_path: str, regions: Dict[str, List[Dict[str, int]]]) -> Dict[str, Any]:
        """
        Process an image to extract text from regions
        
        Args:
            image_path: Path to the image
            regions: Dictionary of regions by element type
            
        Returns:
            dict: Dictionary of results by element type
        """
        try:
            # Load image
            image = self.image_loader.load_image(image_path)
            
            # Process each region type
            results = {}
            confidence_scores = {}
            
            for region_type, region_list in regions.items():
                best_result = {
                    'text': "",
                    'confidence': 0.0
                }
                
                for region in region_list:
                    # Process region
                    result = self.process_region(image, region, region_type)
                    
                    # If better than current best, update
                    if result['confidence'] > best_result['confidence']:
                        best_result = result
                
                # If we found a good result, add it
                if best_result['text'] and best_result['confidence'] > 0.3:
                    results[region_type] = best_result['text']
                    confidence_scores[region_type] = best_result['confidence']
            
            return {
                'results': results,
                'confidence': confidence_scores
            }
            
        except Exception as e:
            logger.error(f"Error processing image: {e}")
            return {
                'results': {},
                'confidence': {}
            }

def main():
    parser = argparse.ArgumentParser(description="Testable Coupon Recognizer")
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
    
    # Create recognizer
    recognizer = CouponRecognizer(config_path=args.config)
    
    # Process image
    results = recognizer.process_image(args.image, regions)
    
    # Print results
    print("\nRecognition Results:")
    for element_type, text in results['results'].items():
        confidence = results['confidence'].get(element_type, 0.0)
        print(f"  {element_type}: {text} (confidence: {confidence:.2f})")
    
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
