#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import cv2
import numpy as np
from PIL import Image, ImageEnhance

class ImageProcessor:
    """Utility class for processing images"""
    
    def __init__(self):
        """Initialize the image processor"""
        self.processed_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'static', 'uploads', 'processed')
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
            
            # Load the image with PIL for enhancement
            pil_img = Image.open(image_path)
            
            # Resize if too large
            max_size = 1200
            if pil_img.width > max_size or pil_img.height > max_size:
                pil_img.thumbnail((max_size, max_size), Image.LANCZOS)
            
            # Convert to RGB if needed
            if pil_img.mode != 'RGB':
                pil_img = pil_img.convert('RGB')
            
            # Enhance the image
            pil_img = self._enhance_image(pil_img)
            
            # Save the enhanced image
            pil_img.save(output_path)
            
            # Load with OpenCV for further processing
            img = cv2.imread(output_path)
            
            # Detect and crop to coupon boundaries
            cropped = self._detect_and_crop_coupon(img)
            if cropped is not None:
                cv2.imwrite(output_path, cropped)
            
            # Deskew the image
            deskewed = self._deskew_image(cv2.imread(output_path))
            if deskewed is not None:
                cv2.imwrite(output_path, deskewed)
            
            return output_path
        
        except Exception as e:
            print(f"Error preprocessing image {image_path}: {e}")
            return image_path
    
    def _enhance_image(self, img):
        """Enhance an image for better OCR
        
        Args:
            img (PIL.Image): Input image
            
        Returns:
            PIL.Image: Enhanced image
        """
        # Increase contrast
        enhancer = ImageEnhance.Contrast(img)
        img = enhancer.enhance(1.5)
        
        # Increase sharpness
        enhancer = ImageEnhance.Sharpness(img)
        img = enhancer.enhance(1.5)
        
        return img
    
    def _detect_and_crop_coupon(self, img):
        """Detect and crop to coupon boundaries
        
        Args:
            img (numpy.ndarray): Input image
            
        Returns:
            numpy.ndarray: Cropped image, or None if no coupon detected
        """
        try:
            # Convert to grayscale
            gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
            
            # Apply Gaussian blur
            blurred = cv2.GaussianBlur(gray, (5, 5), 0)
            
            # Apply Canny edge detection
            edges = cv2.Canny(blurred, 50, 150)
            
            # Dilate the edges to connect them
            kernel = np.ones((3, 3), np.uint8)
            dilated = cv2.dilate(edges, kernel, iterations=2)
            
            # Find contours
            contours, _ = cv2.findContours(dilated, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            
            # Find the largest contour
            if contours:
                largest_contour = max(contours, key=cv2.contourArea)
                
                # Get the bounding rectangle
                x, y, w, h = cv2.boundingRect(largest_contour)
                
                # Check if the rectangle is large enough
                if w > img.shape[1] * 0.5 and h > img.shape[0] * 0.5:
                    # Add a small margin
                    margin = 10
                    x = max(0, x - margin)
                    y = max(0, y - margin)
                    w = min(img.shape[1] - x, w + 2 * margin)
                    h = min(img.shape[0] - y, h + 2 * margin)
                    
                    # Crop the image
                    return img[y:y+h, x:x+w]
            
            # If no suitable contour found, return the original image
            return img
        
        except Exception as e:
            print(f"Error detecting and cropping coupon: {e}")
            return None
    
    def _deskew_image(self, img):
        """Deskew an image
        
        Args:
            img (numpy.ndarray): Input image
            
        Returns:
            numpy.ndarray: Deskewed image, or None if deskewing failed
        """
        try:
            # Convert to grayscale
            gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
            
            # Apply threshold
            _, thresh = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
            
            # Find all contours
            contours, _ = cv2.findContours(thresh, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
            
            # Find the largest contour
            if not contours:
                return img
            
            largest_contour = max(contours, key=cv2.contourArea)
            
            # Get the minimum area rectangle
            rect = cv2.minAreaRect(largest_contour)
            angle = rect[2]
            
            # Adjust the angle
            if angle < -45:
                angle = 90 + angle
            
            # Ignore small angles
            if abs(angle) < 1:
                return img
            
            # Rotate the image
            (h, w) = img.shape[:2]
            center = (w // 2, h // 2)
            M = cv2.getRotationMatrix2D(center, angle, 1.0)
            rotated = cv2.warpAffine(img, M, (w, h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
            
            return rotated
        
        except Exception as e:
            print(f"Error deskewing image: {e}")
            return None
