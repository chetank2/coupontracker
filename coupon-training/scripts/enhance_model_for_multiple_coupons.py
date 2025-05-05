#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Enhance Model for Multiple Coupons

This script enhances the existing coupon recognition model to support
detecting and processing multiple coupons from a single image.
"""

import os
import sys
import json
import shutil
import logging
import argparse
from pathlib import Path

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("enhance_model.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("enhance_model")

class ModelEnhancer:
    """Enhances the coupon recognition model to support multiple coupons"""
    
    def __init__(self, model_dir, output_dir):
        """
        Initialize the model enhancer
        
        Args:
            model_dir (str): Directory containing the existing model
            output_dir (str): Directory to save the enhanced model
        """
        self.model_dir = model_dir
        self.output_dir = output_dir
        
        # Ensure output directory exists
        os.makedirs(output_dir, exist_ok=True)
        
        logger.info(f"Initialized ModelEnhancer with model_dir={model_dir}, output_dir={output_dir}")
    
    def enhance_model(self):
        """
        Enhance the model to support multiple coupons
        
        Returns:
            bool: True if successful, False otherwise
        """
        try:
            # Check if model exists
            if not os.path.exists(self.model_dir):
                logger.error(f"Model directory not found: {self.model_dir}")
                return False
            
            # Copy the existing model files
            self._copy_model_files()
            
            # Update the model configuration
            self._update_model_config()
            
            # Create the coupon detection component
            self._create_coupon_detector()
            
            # Update the model pipeline
            self._update_model_pipeline()
            
            logger.info(f"Model enhancement complete. Enhanced model saved to {self.output_dir}")
            return True
        
        except Exception as e:
            logger.error(f"Error enhancing model: {e}")
            return False
    
    def _copy_model_files(self):
        """Copy the existing model files to the output directory"""
        # Get all files in the model directory
        model_files = [f for f in os.listdir(self.model_dir) 
                      if os.path.isfile(os.path.join(self.model_dir, f))]
        
        # Copy each file
        for file_name in model_files:
            src_path = os.path.join(self.model_dir, file_name)
            dst_path = os.path.join(self.output_dir, file_name)
            
            shutil.copy2(src_path, dst_path)
            logger.info(f"Copied {src_path} to {dst_path}")
    
    def _update_model_config(self):
        """Update the model configuration to support multiple coupons"""
        # Check for config file
        config_file = os.path.join(self.output_dir, "coupon_model_config.json")
        if not os.path.exists(config_file):
            config_file = os.path.join(self.output_dir, "model_config.json")
        
        if not os.path.exists(config_file):
            # Create a new config file
            config = {
                "model_name": "enhanced_coupon_model",
                "version": "2.0.0",
                "supports_multiple_coupons": True,
                "detection_enabled": True,
                "detection_config": {
                    "min_coupon_area": 10000,
                    "min_aspect_ratio": 1.5,
                    "max_aspect_ratio": 5.0,
                    "overlap_threshold": 0.5
                },
                "field_extraction_config": {
                    "fields": ["store_name", "coupon_code", "amount", "expiry_date", "description"]
                }
            }
        else:
            # Load existing config
            with open(config_file, 'r') as f:
                config = json.load(f)
            
            # Update config
            config["supports_multiple_coupons"] = True
            config["detection_enabled"] = True
            
            # Add detection config if not present
            if "detection_config" not in config:
                config["detection_config"] = {
                    "min_coupon_area": 10000,
                    "min_aspect_ratio": 1.5,
                    "max_aspect_ratio": 5.0,
                    "overlap_threshold": 0.5
                }
            
            # Increment version
            if "version" in config:
                version_parts = config["version"].split(".")
                version_parts[-1] = str(int(version_parts[-1]) + 1)
                config["version"] = ".".join(version_parts)
            else:
                config["version"] = "2.0.0"
        
        # Save updated config
        with open(config_file, 'w') as f:
            json.dump(config, f, indent=4)
        
        logger.info(f"Updated model configuration in {config_file}")
    
    def _create_coupon_detector(self):
        """Create the coupon detection component"""
        # Create the detector script
        detector_script = os.path.join(self.output_dir, "coupon_detector.py")
        
        with open(detector_script, 'w') as f:
            f.write("""#!/usr/bin/env python3
# -*- coding: utf-8 -*-

\"\"\"
Coupon Detector

This module detects multiple coupons in a single image.
\"\"\"

import cv2
import numpy as np
import os
import logging

logger = logging.getLogger(__name__)

class CouponDetector:
    \"\"\"Detects multiple coupons in a single image\"\"\"
    
    def __init__(self, config=None):
        \"\"\"
        Initialize the coupon detector
        
        Args:
            config (dict, optional): Configuration parameters
        \"\"\"
        self.config = config or {}
        
        # Set default parameters
        self.min_coupon_area = self.config.get("min_coupon_area", 10000)
        self.min_aspect_ratio = self.config.get("min_aspect_ratio", 1.5)
        self.max_aspect_ratio = self.config.get("max_aspect_ratio", 5.0)
        self.overlap_threshold = self.config.get("overlap_threshold", 0.5)
    
    def detect(self, image):
        \"\"\"
        Detect coupons in an image
        
        Args:
            image (numpy.ndarray): Input image
            
        Returns:
            list: List of (x, y, w, h) tuples for detected coupon regions
        \"\"\"
        # Convert to grayscale if needed
        if len(image.shape) == 3:
            gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        else:
            gray = image
        
        # Apply Gaussian blur to reduce noise
        blurred = cv2.GaussianBlur(gray, (5, 5), 0)
        
        # Apply adaptive thresholding
        thresh = cv2.adaptiveThreshold(
            blurred, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY_INV, 11, 2
        )
        
        # Find contours
        contours, _ = cv2.findContours(thresh, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        
        # Filter contours to find coupon-like shapes
        coupon_regions = []
        for contour in contours:
            # Get bounding rectangle
            x, y, w, h = cv2.boundingRect(contour)
            area = w * h
            aspect_ratio = w / h if h > 0 else 0
            
            # Check if it meets the criteria for a coupon
            if (area >= self.min_coupon_area and 
                self.min_aspect_ratio <= aspect_ratio <= self.max_aspect_ratio):
                coupon_regions.append((x, y, w, h))
        
        # If no regions found, try alternative method with edge detection
        if not coupon_regions:
            # Apply Canny edge detection
            edges = cv2.Canny(blurred, 50, 150)
            
            # Dilate to connect edges
            kernel = np.ones((3, 3), np.uint8)
            dilated = cv2.dilate(edges, kernel, iterations=2)
            
            # Find contours again
            contours, _ = cv2.findContours(dilated, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            
            # Filter contours
            for contour in contours:
                # Get bounding rectangle
                x, y, w, h = cv2.boundingRect(contour)
                area = w * h
                aspect_ratio = w / h if h > 0 else 0
                
                # Check if it meets the criteria for a coupon
                if (area >= self.min_coupon_area and 
                    self.min_aspect_ratio <= aspect_ratio <= self.max_aspect_ratio):
                    coupon_regions.append((x, y, w, h))
        
        # If still no regions found, use a grid-based approach
        if not coupon_regions:
            # Divide the image into a grid and consider each cell as a potential coupon
            height, width = image.shape[:2]
            
            # Try different grid sizes
            for rows in [2, 3, 4]:
                for cols in [1, 2]:
                    cell_height = height // rows
                    cell_width = width // cols
                    
                    for row in range(rows):
                        for col in range(cols):
                            x = col * cell_width
                            y = row * cell_height
                            w = cell_width
                            h = cell_height
                            
                            # Check aspect ratio
                            aspect_ratio = w / h if h > 0 else 0
                            if self.min_aspect_ratio <= aspect_ratio <= self.max_aspect_ratio:
                                coupon_regions.append((x, y, w, h))
        
        # Remove overlapping regions
        return self._remove_overlapping_regions(coupon_regions)
    
    def _remove_overlapping_regions(self, regions):
        \"\"\"
        Remove overlapping regions
        
        Args:
            regions (list): List of (x, y, w, h) tuples
            
        Returns:
            list: Filtered list of regions
        \"\"\"
        if not regions:
            return []
        
        # Sort regions by area (largest first)
        regions = sorted(regions, key=lambda r: r[2] * r[3], reverse=True)
        
        # Filter out overlapping regions
        filtered_regions = [regions[0]]
        
        for region in regions[1:]:
            x1, y1, w1, h1 = region
            
            # Check overlap with all filtered regions
            overlap = False
            for filtered_region in filtered_regions:
                x2, y2, w2, h2 = filtered_region
                
                # Calculate intersection area
                x_overlap = max(0, min(x1 + w1, x2 + w2) - max(x1, x2))
                y_overlap = max(0, min(y1 + h1, y2 + h2) - max(y1, y2))
                intersection = x_overlap * y_overlap
                
                # Calculate union area
                area1 = w1 * h1
                area2 = w2 * h2
                union = area1 + area2 - intersection
                
                # Calculate overlap ratio
                overlap_ratio = intersection / union if union > 0 else 0
                
                if overlap_ratio > self.overlap_threshold:
                    overlap = True
                    break
            
            if not overlap:
                filtered_regions.append(region)
        
        return filtered_regions
    
    def extract_coupons(self, image, regions):
        \"\"\"
        Extract coupon images from the detected regions
        
        Args:
            image (numpy.ndarray): Input image
            regions (list): List of (x, y, w, h) tuples for detected regions
            
        Returns:
            list: List of extracted coupon images
        \"\"\"
        coupon_images = []
        
        for x, y, w, h in regions:
            # Add a small margin
            margin = 10
            x = max(0, x - margin)
            y = max(0, y - margin)
            w = min(image.shape[1] - x, w + 2 * margin)
            h = min(image.shape[0] - y, h + 2 * margin)
            
            # Extract the coupon
            coupon_image = image[y:y+h, x:x+w]
            coupon_images.append(coupon_image)
        
        return coupon_images
""")
        
        logger.info(f"Created coupon detector script: {detector_script}")
    
    def _update_model_pipeline(self):
        """Update the model pipeline to support multiple coupons"""
        # Create the enhanced pipeline script
        pipeline_script = os.path.join(self.output_dir, "multi_coupon_pipeline.py")
        
        with open(pipeline_script, 'w') as f:
            f.write("""#!/usr/bin/env python3
# -*- coding: utf-8 -*-

\"\"\"
Multi-Coupon Recognition Pipeline

This module implements a pipeline for recognizing multiple coupons in a single image.
\"\"\"

import os
import cv2
import json
import logging
import numpy as np
from .coupon_detector import CouponDetector

logger = logging.getLogger(__name__)

class MultiCouponPipeline:
    \"\"\"Pipeline for recognizing multiple coupons in a single image\"\"\"
    
    def __init__(self, model_dir, config=None):
        \"\"\"
        Initialize the pipeline
        
        Args:
            model_dir (str): Directory containing the model files
            config (dict, optional): Configuration parameters
        \"\"\"
        self.model_dir = model_dir
        self.config = config or {}
        
        # Load model configuration
        self._load_config()
        
        # Initialize coupon detector
        self.detector = CouponDetector(self.detection_config)
        
        # Initialize field extractor (placeholder for the actual implementation)
        self.field_extractor = self._initialize_field_extractor()
        
        logger.info("Initialized MultiCouponPipeline")
    
    def _load_config(self):
        \"\"\"Load model configuration\"\"\"
        # Check for config file
        config_file = os.path.join(self.model_dir, "coupon_model_config.json")
        if not os.path.exists(config_file):
            config_file = os.path.join(self.model_dir, "model_config.json")
        
        if os.path.exists(config_file):
            with open(config_file, 'r') as f:
                self.model_config = json.load(f)
        else:
            self.model_config = {}
        
        # Get detection configuration
        self.detection_config = self.model_config.get("detection_config", {})
        
        # Get field extraction configuration
        self.field_extraction_config = self.model_config.get("field_extraction_config", {})
    
    def _initialize_field_extractor(self):
        \"\"\"
        Initialize the field extractor
        
        This is a placeholder for the actual implementation.
        In a real implementation, this would load the field extraction model.
        \"\"\"
        # Placeholder for the actual field extractor
        class FieldExtractor:
            def __init__(self, config):
                self.config = config
                self.fields = config.get("fields", [])
            
            def extract_fields(self, image):
                # Placeholder for actual field extraction
                # In a real implementation, this would use OCR and pattern recognition
                # to extract fields from the coupon image
                
                # Return dummy data for now
                return {
                    field: f"Sample {field}" for field in self.fields
                }
        
        return FieldExtractor(self.field_extraction_config)
    
    def process_image(self, image_path):
        \"\"\"
        Process an image containing one or more coupons
        
        Args:
            image_path (str): Path to the input image
            
        Returns:
            list: List of dictionaries containing extracted coupon information
        \"\"\"
        try:
            # Read the image
            image = cv2.imread(image_path)
            if image is None:
                logger.error(f"Failed to read image: {image_path}")
                return []
            
            # Detect coupons
            coupon_regions = self.detector.detect(image)
            logger.info(f"Detected {len(coupon_regions)} coupons in {image_path}")
            
            # Extract coupon images
            coupon_images = self.detector.extract_coupons(image, coupon_regions)
            
            # Process each coupon
            results = []
            for i, coupon_image in enumerate(coupon_images):
                # Extract fields from the coupon
                fields = self.field_extractor.extract_fields(coupon_image)
                
                # Add coupon index and region
                fields["coupon_index"] = i + 1
                fields["region"] = coupon_regions[i]
                
                # Encode the coupon image as base64 (optional)
                # This allows sending the image along with the extracted fields
                # fields["image_data"] = self._encode_image(coupon_image)
                
                results.append(fields)
            
            return results
        
        except Exception as e:
            logger.error(f"Error processing image {image_path}: {e}")
            return []
    
    def _encode_image(self, image):
        \"\"\"
        Encode an image as base64
        
        Args:
            image (numpy.ndarray): Input image
            
        Returns:
            str: Base64-encoded image
        \"\"\"
        import base64
        
        # Encode image as JPEG
        _, buffer = cv2.imencode('.jpg', image)
        
        # Convert to base64
        return base64.b64encode(buffer).decode('utf-8')
""")
        
        logger.info(f"Created multi-coupon pipeline script: {pipeline_script}")
        
        # Create an initialization script
        init_script = os.path.join(self.output_dir, "__init__.py")
        
        with open(init_script, 'w') as f:
            f.write("""#!/usr/bin/env python3
# -*- coding: utf-8 -*-

\"\"\"
Enhanced Coupon Recognition Model

This package provides functionality for recognizing multiple coupons in a single image.
\"\"\"

from .coupon_detector import CouponDetector
from .multi_coupon_pipeline import MultiCouponPipeline

__all__ = ['CouponDetector', 'MultiCouponPipeline']
""")
        
        logger.info(f"Created initialization script: {init_script}")

def main():
    """Main function"""
    parser = argparse.ArgumentParser(description="Enhance coupon recognition model for multiple coupons")
    parser.add_argument("--model-dir", default="../models/simplified", 
                       help="Directory containing the existing model")
    parser.add_argument("--output-dir", default="../models/enhanced", 
                       help="Directory to save the enhanced model")
    
    args = parser.parse_args()
    
    # Enhance the model
    enhancer = ModelEnhancer(args.model_dir, args.output_dir)
    success = enhancer.enhance_model()
    
    if success:
        print(f"\nModel enhancement complete. Enhanced model saved to {args.output_dir}")
    else:
        print("\nError enhancing model. Check the logs for details.")

if __name__ == "__main__":
    main()
