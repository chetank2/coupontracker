#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import sys
import cv2
import numpy as np
from pathlib import Path

# Add parent directory to path to import from scripts
sys.path.append(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

# Try to import preprocessing script
try:
    from scripts.preprocess_images import preprocess_image as script_preprocess
except ImportError as e:
    print(f"Error importing preprocessing script: {e}")
    
    # Define a fallback preprocessing function
    def script_preprocess(input_path, output_path):
        """Fallback preprocessing function"""
        img = cv2.imread(input_path)
        if img is None:
            return False
        
        # Resize to standard dimensions
        img = cv2.resize(img, (1080, 1920))
        
        # Save the processed image
        cv2.imwrite(output_path, img)
        return True

class ImageProcessor:
    """Processes images for training and testing"""
    
    def __init__(self):
        # Base paths
        self.base_dir = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        self.data_dir = os.path.join(self.base_dir, 'data')
        self.processed_dir = os.path.join(self.data_dir, 'processed')
        
        # Ensure directories exist
        os.makedirs(self.processed_dir, exist_ok=True)
    
    def preprocess_image(self, image_path):
        """Preprocess an image for training or testing
        
        Args:
            image_path (str): Path to the image
            
        Returns:
            str: Path to the processed image
        """
        try:
            # Extract image filename
            image_filename = os.path.basename(image_path)
            
            # Create processed image path
            processed_path = os.path.join(self.processed_dir, image_filename)
            
            # Preprocess the image using the script function
            success = script_preprocess(image_path, processed_path)
            
            if success:
                return processed_path
            else:
                # Fallback to basic preprocessing
                return self._basic_preprocess(image_path, processed_path)
        except Exception as e:
            print(f"Error preprocessing image: {e}")
            # Fallback to basic preprocessing
            return self._basic_preprocess(image_path, processed_path)
    
    def _basic_preprocess(self, input_path, output_path):
        """Basic image preprocessing
        
        Args:
            input_path (str): Path to the input image
            output_path (str): Path to save the processed image
            
        Returns:
            str: Path to the processed image
        """
        try:
            # Read the image
            img = cv2.imread(input_path)
            
            if img is None:
                return input_path
            
            # Resize to standard dimensions
            img = cv2.resize(img, (1080, 1920))
            
            # Save the processed image
            cv2.imwrite(output_path, img)
            
            return output_path
        except Exception as e:
            print(f"Error in basic preprocessing: {e}")
            return input_path
