#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Multiple Coupon Detection Script

This script detects and segments multiple coupons from a single image.
It uses contour detection and rectangle identification to find coupon-like shapes.
"""

import os
import cv2
import numpy as np
import argparse
import logging
from pathlib import Path
from tqdm import tqdm

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("coupon_detection.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("coupon_detection")

class MultiCouponDetector:
    """Detects and segments multiple coupons from a single image"""
    
    def __init__(self, min_coupon_area=10000, aspect_ratio_range=(1.5, 5.0)):
        """
        Initialize the coupon detector
        
        Args:
            min_coupon_area (int): Minimum area (in pixels) for a region to be considered a coupon
            aspect_ratio_range (tuple): Valid range for width/height ratio of coupon regions
        """
        self.min_coupon_area = min_coupon_area
        self.min_aspect_ratio, self.max_aspect_ratio = aspect_ratio_range
        logger.info(f"Initialized MultiCouponDetector with min_area={min_coupon_area}, "
                   f"aspect_ratio_range={aspect_ratio_range}")
    
    def detect_coupons(self, image_path, output_dir=None, visualize=False):
        """
        Detect and segment coupons from an image
        
        Args:
            image_path (str): Path to the input image
            output_dir (str, optional): Directory to save segmented coupons
            visualize (bool): Whether to save visualization of detected coupons
            
        Returns:
            list: List of paths to segmented coupon images
        """
        try:
            # Read the image
            image = cv2.imread(image_path)
            if image is None:
                logger.error(f"Failed to read image: {image_path}")
                return []
            
            # Create output directory if needed
            if output_dir:
                os.makedirs(output_dir, exist_ok=True)
                
            # Get base filename without extension
            base_name = os.path.splitext(os.path.basename(image_path))[0]
            
            # Detect coupon regions
            coupon_regions = self._detect_coupon_regions(image)
            logger.info(f"Detected {len(coupon_regions)} potential coupon regions in {image_path}")
            
            # Extract and save each coupon
            coupon_paths = []
            for i, region in enumerate(coupon_regions):
                x, y, w, h = region
                
                # Add a small margin
                margin = 10
                x = max(0, x - margin)
                y = max(0, y - margin)
                w = min(image.shape[1] - x, w + 2 * margin)
                h = min(image.shape[0] - y, h + 2 * margin)
                
                # Extract the coupon
                coupon_image = image[y:y+h, x:x+w]
                
                if output_dir:
                    # Save the coupon
                    coupon_path = os.path.join(output_dir, f"{base_name}_coupon_{i+1}.png")
                    cv2.imwrite(coupon_path, coupon_image)
                    coupon_paths.append(coupon_path)
                    logger.info(f"Saved coupon {i+1} to {coupon_path}")
            
            # Create visualization if requested
            if visualize and output_dir:
                self._visualize_detection(image, coupon_regions, 
                                         os.path.join(output_dir, f"{base_name}_detection.png"))
            
            return coupon_paths
        
        except Exception as e:
            logger.error(f"Error detecting coupons in {image_path}: {e}")
            return []
    
    def _detect_coupon_regions(self, image):
        """
        Detect regions that are likely to be coupons
        
        Args:
            image (numpy.ndarray): Input image
            
        Returns:
            list: List of (x, y, w, h) tuples for detected coupon regions
        """
        # Convert to grayscale
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        
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
        
        # If still no regions found, use a more aggressive approach
        if not coupon_regions:
            # Try to find rectangular shapes using Hough lines
            edges = cv2.Canny(blurred, 50, 150)
            lines = cv2.HoughLinesP(edges, 1, np.pi/180, threshold=100, minLineLength=100, maxLineGap=10)
            
            if lines is not None:
                # Create a blank mask
                mask = np.zeros_like(gray)
                
                # Draw lines on the mask
                for line in lines:
                    x1, y1, x2, y2 = line[0]
                    cv2.line(mask, (x1, y1), (x2, y2), 255, 2)
                
                # Dilate to connect lines
                kernel = np.ones((5, 5), np.uint8)
                dilated = cv2.dilate(mask, kernel, iterations=2)
                
                # Find contours
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
    
    def _remove_overlapping_regions(self, regions, overlap_threshold=0.5):
        """
        Remove overlapping regions
        
        Args:
            regions (list): List of (x, y, w, h) tuples
            overlap_threshold (float): Maximum allowed overlap ratio
            
        Returns:
            list: Filtered list of regions
        """
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
                
                if overlap_ratio > overlap_threshold:
                    overlap = True
                    break
            
            if not overlap:
                filtered_regions.append(region)
        
        return filtered_regions
    
    def _visualize_detection(self, image, regions, output_path):
        """
        Create a visualization of detected coupon regions
        
        Args:
            image (numpy.ndarray): Input image
            regions (list): List of (x, y, w, h) tuples for detected regions
            output_path (str): Path to save the visualization
        """
        # Create a copy of the image
        vis_image = image.copy()
        
        # Draw rectangles around detected regions
        for i, (x, y, w, h) in enumerate(regions):
            cv2.rectangle(vis_image, (x, y), (x + w, y + h), (0, 255, 0), 2)
            cv2.putText(vis_image, f"Coupon {i+1}", (x, y - 10),
                       cv2.FONT_HERSHEY_SIMPLEX, 0.9, (0, 255, 0), 2)
        
        # Save the visualization
        cv2.imwrite(output_path, vis_image)
        logger.info(f"Saved detection visualization to {output_path}")

def process_directory(input_dir, output_dir, visualize=False):
    """
    Process all images in a directory
    
    Args:
        input_dir (str): Input directory containing images
        output_dir (str): Output directory to save segmented coupons
        visualize (bool): Whether to save visualization of detected coupons
        
    Returns:
        int: Number of coupons detected
    """
    # Create detector
    detector = MultiCouponDetector()
    
    # Create output directory
    os.makedirs(output_dir, exist_ok=True)
    
    # Get all image files
    image_extensions = ['.jpg', '.jpeg', '.png', '.bmp', '.webp']
    image_files = [f for f in os.listdir(input_dir) 
                  if os.path.splitext(f.lower())[1] in image_extensions]
    
    logger.info(f"Found {len(image_files)} images in {input_dir}")
    
    # Process each image
    total_coupons = 0
    for image_file in tqdm(image_files, desc="Processing images"):
        image_path = os.path.join(input_dir, image_file)
        
        # Detect coupons
        coupon_paths = detector.detect_coupons(image_path, output_dir, visualize)
        
        total_coupons += len(coupon_paths)
    
    logger.info(f"Detected a total of {total_coupons} coupons from {len(image_files)} images")
    return total_coupons

def main():
    """Main function"""
    parser = argparse.ArgumentParser(description="Detect and segment multiple coupons from images")
    parser.add_argument("--input-dir", default="../data/raw", 
                       help="Directory containing input images")
    parser.add_argument("--output-dir", default="../data/segmented", 
                       help="Directory to save segmented coupons")
    parser.add_argument("--visualize", action="store_true", 
                       help="Save visualization of detected coupons")
    
    args = parser.parse_args()
    
    # Process the directory
    total_coupons = process_directory(args.input_dir, args.output_dir, args.visualize)
    
    print(f"\nDetection complete. Found {total_coupons} coupons in {args.input_dir}")
    print(f"Segmented coupons saved to {args.output_dir}")

if __name__ == "__main__":
    main()
