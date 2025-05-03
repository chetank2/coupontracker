#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Improved Coupon Recognizer
This script integrates all the improvements for the coupon recognizer.
"""

import os
import sys
import json
import argparse
import cv2
import numpy as np
from PIL import Image
import pytesseract
import logging
import time
import gc
import contextlib
import tempfile
from concurrent.futures import ThreadPoolExecutor

# Add parent directory to path
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# Import improved modules
from scripts.adaptive_pattern_recognition import AdaptivePatternRecognizer
from scripts.error_recovery import ErrorRecoveryHandler
from scripts.resource_management import ResourceManager, managed_image, managed_tesseract
from scripts.testable_coupon_recognizer import CouponRecognizer

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("improved_coupon_recognizer.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("ImprovedCouponRecognizer")

class ImprovedCouponRecognizer:
    """
    Improved coupon recognizer that integrates all the improvements
    """
    
    def __init__(self, config_path=None):
        """
        Initialize the improved coupon recognizer
        
        Args:
            config_path: Path to configuration file (optional)
        """
        # Default configuration file
        if config_path is None:
            config_path = os.path.join(
                os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                'config',
                'coupon_recognizer_config.json'
            )
        
        # Load configuration
        try:
            with open(config_path, 'r') as f:
                self.config = json.load(f)
            logger.info(f"Loaded configuration from {config_path}")
        except Exception as e:
            logger.error(f"Error loading configuration: {e}")
            self.config = {}
        
        # Initialize components
        self.pattern_recognizer = AdaptivePatternRecognizer(config_path)
        self.error_handler = ErrorRecoveryHandler(config_path)
        self.resource_manager = ResourceManager(config_path)
        self.testable_recognizer = CouponRecognizer(config_path=config_path)
    
    def process_image(self, image_path, use_fixed_patterns=False):
        """
        Process an image to extract coupon information
        
        Args:
            image_path: Path to the image
            use_fixed_patterns: Whether to use fixed patterns or adaptive recognition
            
        Returns:
            dict: Dictionary of results
        """
        start_time = time.time()
        
        try:
            # Use resource management for image loading
            with managed_image(image_path) as (cv_image, pil_image):
                # Stage 1: Detect regions
                if use_fixed_patterns:
                    # Use fixed pattern regions from config
                    regions = self.config.get('pattern_regions', {})
                    logger.info("Using fixed pattern regions")
                else:
                    # Use adaptive pattern recognition
                    adaptive_results = self.pattern_recognizer.process_image(image_path)
                    regions = {}
                    
                    # Convert adaptive results to region format
                    for element_type, region_info in adaptive_results.get('regions', {}).items():
                        regions[element_type] = [{
                            'left': region_info['left'],
                            'top': region_info['top'],
                            'right': region_info['right'],
                            'bottom': region_info['bottom']
                        }]
                    
                    logger.info("Used adaptive pattern recognition")
                
                # Stage 2: Process regions with error recovery
                error_results = self.error_handler.process_image(image_path, regions)
                
                # Stage 3: Process with testable recognizer as fallback
                testable_results = self.testable_recognizer.process_image(image_path, regions)
                
                # Combine results, preferring higher confidence
                combined_results = {}
                combined_confidence = {}
                
                # Get all element types from both results
                all_element_types = set(
                    list(error_results.get('results', {}).keys()) + 
                    list(testable_results.get('results', {}).keys())
                )
                
                for element_type in all_element_types:
                    error_confidence = error_results.get('confidence', {}).get(element_type, 0.0)
                    testable_confidence = testable_results.get('confidence', {}).get(element_type, 0.0)
                    
                    # Choose result with higher confidence
                    if error_confidence >= testable_confidence:
                        combined_results[element_type] = error_results.get('results', {}).get(element_type, "")
                        combined_confidence[element_type] = error_confidence
                        method = error_results.get('methods', {}).get(element_type, "error_recovery")
                    else:
                        combined_results[element_type] = testable_results.get('results', {}).get(element_type, "")
                        combined_confidence[element_type] = testable_confidence
                        method = "testable"
                    
                    logger.info(f"Selected {element_type} from {method} with confidence {combined_confidence[element_type]:.2f}")
                
                # Calculate processing time
                processing_time = time.time() - start_time
                
                return {
                    'results': combined_results,
                    'confidence': combined_confidence,
                    'processing_time': processing_time
                }
                
        except Exception as e:
            logger.error(f"Error processing image: {e}")
            return {
                'results': {},
                'confidence': {},
                'processing_time': time.time() - start_time,
                'error': str(e)
            }
        finally:
            # Clean up resources
            self.resource_manager.cleanup()
            gc.collect()
    
    def batch_process(self, image_paths, output_dir=None, use_fixed_patterns=False):
        """
        Process multiple images in batch
        
        Args:
            image_paths: List of image paths
            output_dir: Directory to save results (optional)
            use_fixed_patterns: Whether to use fixed patterns or adaptive recognition
            
        Returns:
            dict: Dictionary of results by image path
        """
        if output_dir:
            os.makedirs(output_dir, exist_ok=True)
        
        batch_results = {}
        
        for image_path in image_paths:
            logger.info(f"Processing {image_path}")
            
            # Process image
            result = self.process_image(image_path, use_fixed_patterns)
            batch_results[image_path] = result
            
            # Save result if output directory is provided
            if output_dir:
                output_path = os.path.join(
                    output_dir,
                    f"{os.path.splitext(os.path.basename(image_path))[0]}_result.json"
                )
                
                try:
                    with open(output_path, 'w') as f:
                        json.dump(result, f, indent=2)
                    logger.info(f"Saved result to {output_path}")
                except Exception as e:
                    logger.error(f"Error saving result: {e}")
            
            # Clean up after each image
            self.resource_manager.cleanup()
        
        return batch_results

def main():
    parser = argparse.ArgumentParser(description="Improved Coupon Recognizer")
    parser.add_argument("--image", help="Path to the image to process")
    parser.add_argument("--batch", help="Path to a directory containing multiple images")
    parser.add_argument("--output", help="Path to save results")
    parser.add_argument("--config", help="Path to configuration file")
    parser.add_argument("--fixed-patterns", action="store_true", help="Use fixed patterns instead of adaptive recognition")
    
    args = parser.parse_args()
    
    if not args.image and not args.batch:
        parser.error("Either --image or --batch must be provided")
    
    # Create recognizer
    recognizer = ImprovedCouponRecognizer(args.config)
    
    if args.image:
        # Process single image
        result = recognizer.process_image(args.image, args.fixed_patterns)
        
        # Print results
        print("\nRecognition Results:")
        for element_type, text in result['results'].items():
            confidence = result['confidence'].get(element_type, 0.0)
            print(f"  {element_type}: {text} (confidence: {confidence:.2f})")
        
        print(f"\nProcessing time: {result['processing_time']:.2f} seconds")
        
        # Save result if output path is provided
        if args.output:
            try:
                with open(args.output, 'w') as f:
                    json.dump(result, f, indent=2)
                print(f"Result saved to {args.output}")
            except Exception as e:
                logger.error(f"Error saving result: {e}")
    
    elif args.batch:
        # Process batch of images
        if os.path.isdir(args.batch):
            # Get all image files in directory
            image_paths = [
                os.path.join(args.batch, f) for f in os.listdir(args.batch)
                if f.lower().endswith(('.jpg', '.jpeg', '.png'))
            ]
            
            if not image_paths:
                print(f"No image files found in {args.batch}")
                return
            
            print(f"Processing {len(image_paths)} images...")
            
            # Process images
            output_dir = args.output if args.output else None
            batch_results = recognizer.batch_process(image_paths, output_dir, args.fixed_patterns)
            
            # Print summary
            print("\nBatch Processing Summary:")
            for image_path, result in batch_results.items():
                print(f"\n{os.path.basename(image_path)}:")
                for element_type, text in result['results'].items():
                    confidence = result['confidence'].get(element_type, 0.0)
                    print(f"  {element_type}: {text} (confidence: {confidence:.2f})")
                print(f"  Processing time: {result['processing_time']:.2f} seconds")
            
            if output_dir:
                print(f"\nResults saved to {output_dir}")
        else:
            print(f"Error: {args.batch} is not a directory")

if __name__ == "__main__":
    main()
