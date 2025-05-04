#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import uuid
import shutil

class ImageProcessor:
    """Utility class for processing images"""
    
    def __init__(self):
        """Initialize the image processor"""
        self.base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        self.uploads_dir = os.path.join(self.base_dir, 'static', 'uploads')
        self.processed_dir = os.path.join(self.uploads_dir, 'processed')
        
        # Create directories if they don't exist
        os.makedirs(self.uploads_dir, exist_ok=True)
        os.makedirs(self.processed_dir, exist_ok=True)
    
    def preprocess_image(self, image_path):
        """Preprocess an image for better OCR
        
        Args:
            image_path (str): Path to the image
            
        Returns:
            str: Path to the processed image
        """
        try:
            # Generate output filename
            filename = os.path.basename(image_path)
            base_name, ext = os.path.splitext(filename)
            output_path = os.path.join(self.processed_dir, f"{base_name}_processed{ext}")
            
            # For demo purposes, just copy the image
            shutil.copy(image_path, output_path)
            
            return output_path
        except Exception as e:
            print(f"Error preprocessing image {image_path}: {e}")
            return image_path
