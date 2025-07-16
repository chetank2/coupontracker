#!/usr/bin/env python3
"""
Enhanced Image Processor - Advanced preprocessing for coupon images.

This module provides enhanced image processing capabilities specifically
optimized for coupon text extraction and OCR performance.
"""

import os
import cv2
import numpy as np
import logging
from PIL import Image, ImageEnhance, ImageFilter
from sklearn.cluster import KMeans
from scipy import ndimage
import matplotlib.pyplot as plt

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("enhanced_image_processor.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("enhanced_image_processor")

class EnhancedCouponImageProcessor:
    """Enhanced processor for coupon images with advanced preprocessing."""
    
    def __init__(self, input_dir="data/scraped_coupons", output_dir="data/enhanced_processed"):
        """Initialize the enhanced processor.
        
        Args:
            input_dir (str): Directory containing input images
            output_dir (str): Directory to save processed images
        """
        self.input_dir = input_dir
        self.output_dir = output_dir
        
        # Create output directory
        os.makedirs(output_dir, exist_ok=True)
        os.makedirs(os.path.join(output_dir, "variants"), exist_ok=True)
        
        # Initialize counters
        self.images_processed = 0
        self.variants_created = 0
        
        logger.info(f"Enhanced processor initialized: {input_dir} -> {output_dir}")
    
    def create_processing_variants(self, image_path):
        """Create multiple processing variants for better OCR results.
        
        Args:
            image_path (str): Path to input image
            
        Returns:
            list: List of processed image variants with metadata
        """
        try:
            # Load image
            img = cv2.imread(image_path)
            if img is None:
                logger.error(f"Could not load image: {image_path}")
                return []
            
            variants = []
            base_name = os.path.splitext(os.path.basename(image_path))[0]
            
            # 1. Original cleaned version
            cleaned = self._basic_cleanup(img.copy())
            variant_path = os.path.join(self.output_dir, "variants", f"{base_name}_cleaned.jpg")
            cv2.imwrite(variant_path, cleaned)
            variants.append({
                'path': variant_path,
                'type': 'cleaned',
                'description': 'Basic noise reduction and contrast enhancement'
            })
            
            # 2. High contrast version for bold text
            high_contrast = self._enhance_contrast(img.copy(), factor=2.0)
            variant_path = os.path.join(self.output_dir, "variants", f"{base_name}_high_contrast.jpg")
            cv2.imwrite(variant_path, high_contrast)
            variants.append({
                'path': variant_path,
                'type': 'high_contrast',
                'description': 'Enhanced contrast for bold text extraction'
            })
            
            # 3. Edge enhanced version for outlined text
            edge_enhanced = self._enhance_edges(img.copy())
            variant_path = os.path.join(self.output_dir, "variants", f"{base_name}_edge_enhanced.jpg")
            cv2.imwrite(variant_path, edge_enhanced)
            variants.append({
                'path': variant_path,
                'type': 'edge_enhanced',
                'description': 'Edge enhancement for outlined or embossed text'
            })
            
            # 4. Adaptive threshold version for varied lighting
            adaptive = self._adaptive_threshold(img.copy())
            variant_path = os.path.join(self.output_dir, "variants", f"{base_name}_adaptive.jpg")
            cv2.imwrite(variant_path, adaptive)
            variants.append({
                'path': variant_path,
                'type': 'adaptive',
                'description': 'Adaptive thresholding for varied lighting conditions'
            })
            
            # 5. Background subtracted version for busy backgrounds
            bg_subtracted = self._subtract_background(img.copy())
            variant_path = os.path.join(self.output_dir, "variants", f"{base_name}_bg_subtracted.jpg")
            cv2.imwrite(variant_path, bg_subtracted)
            variants.append({
                'path': variant_path,
                'type': 'background_subtracted',
                'description': 'Background removal for busy coupon designs'
            })
            
            # 6. Perspective corrected version
            perspective_corrected = self._correct_perspective(img.copy())
            if perspective_corrected is not None:
                variant_path = os.path.join(self.output_dir, "variants", f"{base_name}_perspective.jpg")
                cv2.imwrite(variant_path, perspective_corrected)
                variants.append({
                    'path': variant_path,
                    'type': 'perspective_corrected',
                    'description': 'Perspective correction for skewed coupons'
                })
            
            self.variants_created += len(variants)
            logger.info(f"Created {len(variants)} variants for {os.path.basename(image_path)}")
            
            return variants
            
        except Exception as e:
            logger.error(f"Error creating variants for {image_path}: {e}")
            return []
    
    def _basic_cleanup(self, img):
        """Basic image cleanup operations.
        
        Args:
            img: OpenCV image
            
        Returns:
            Cleaned image
        """
        # Convert to grayscale if needed
        if len(img.shape) == 3:
            gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        else:
            gray = img.copy()
        
        # Noise reduction
        denoised = cv2.bilateralFilter(gray, 9, 75, 75)
        
        # Contrast enhancement
        clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8,8))
        enhanced = clahe.apply(denoised)
        
        return enhanced
    
    def _enhance_contrast(self, img, factor=1.5):
        """Enhance image contrast for better text visibility.
        
        Args:
            img: OpenCV image
            factor: Contrast enhancement factor
            
        Returns:
            Contrast enhanced image
        """
        # Convert to LAB color space
        lab = cv2.cvtColor(img, cv2.COLOR_BGR2LAB)
        l, a, b = cv2.split(lab)
        
        # Apply CLAHE to L channel
        clahe = cv2.createCLAHE(clipLimit=factor, tileGridSize=(8,8))
        l = clahe.apply(l)
        
        # Merge channels and convert back
        enhanced = cv2.merge([l, a, b])
        enhanced = cv2.cvtColor(enhanced, cv2.COLOR_LAB2BGR)
        
        return enhanced
    
    def _enhance_edges(self, img):
        """Enhance edges for better text detection.
        
        Args:
            img: OpenCV image
            
        Returns:
            Edge enhanced image
        """
        # Convert to grayscale
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        
        # Apply Gaussian blur
        blurred = cv2.GaussianBlur(gray, (3, 3), 0)
        
        # Unsharp masking for edge enhancement
        sharpened = cv2.addWeighted(gray, 1.5, blurred, -0.5, 0)
        
        # Morphological operations to clean up
        kernel = np.ones((2,2), np.uint8)
        cleaned = cv2.morphologyEx(sharpened, cv2.MORPH_CLOSE, kernel)
        
        return cleaned
    
    def _adaptive_threshold(self, img):
        """Apply adaptive thresholding for varied lighting.
        
        Args:
            img: OpenCV image
            
        Returns:
            Adaptively thresholded image
        """
        # Convert to grayscale
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        
        # Apply bilateral filter for noise reduction
        filtered = cv2.bilateralFilter(gray, 9, 75, 75)
        
        # Adaptive threshold
        adaptive = cv2.adaptiveThreshold(
            filtered, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, 
            cv2.THRESH_BINARY, 11, 2
        )
        
        return adaptive
    
    def _subtract_background(self, img):
        """Remove background for text isolation.
        
        Args:
            img: OpenCV image
            
        Returns:
            Background subtracted image
        """
        # Convert to grayscale
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        
        # Morphological opening to estimate background
        kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (15, 15))
        background = cv2.morphologyEx(gray, cv2.MORPH_OPEN, kernel)
        
        # Subtract background
        result = cv2.subtract(gray, background)
        
        # Enhance the result
        enhanced = cv2.convertScaleAbs(result, alpha=2.0, beta=10)
        
        return enhanced
    
    def _correct_perspective(self, img):
        """Correct perspective distortion in coupon images.
        
        Args:
            img: OpenCV image
            
        Returns:
            Perspective corrected image or None if correction not possible
        """
        try:
            # Convert to grayscale
            gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
            
            # Edge detection
            edges = cv2.Canny(gray, 50, 150, apertureSize=3)
            
            # Find contours
            contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            
            # Find the largest rectangular contour
            largest_contour = None
            max_area = 0
            
            for contour in contours:
                area = cv2.contourArea(contour)
                if area > max_area:
                    # Approximate contour to check if it's rectangular
                    epsilon = 0.02 * cv2.arcLength(contour, True)
                    approx = cv2.approxPolyDP(contour, epsilon, True)
                    
                    if len(approx) == 4 and area > max_area:
                        largest_contour = approx
                        max_area = area
            
            if largest_contour is not None and max_area > 1000:  # Minimum area threshold
                # Order points for perspective transform
                points = largest_contour.reshape(4, 2)
                rect = self._order_points(points)
                
                # Calculate dimensions of the corrected image
                (tl, tr, br, bl) = rect
                width_a = np.sqrt(((br[0] - bl[0]) ** 2) + ((br[1] - bl[1]) ** 2))
                width_b = np.sqrt(((tr[0] - tl[0]) ** 2) + ((tr[1] - tl[1]) ** 2))
                max_width = max(int(width_a), int(width_b))
                
                height_a = np.sqrt(((tr[0] - br[0]) ** 2) + ((tr[1] - br[1]) ** 2))
                height_b = np.sqrt(((tl[0] - bl[0]) ** 2) + ((tl[1] - bl[1]) ** 2))
                max_height = max(int(height_a), int(height_b))
                
                # Define destination points
                dst = np.array([
                    [0, 0],
                    [max_width - 1, 0],
                    [max_width - 1, max_height - 1],
                    [0, max_height - 1]
                ], dtype="float32")
                
                # Calculate perspective transform matrix
                matrix = cv2.getPerspectiveTransform(rect, dst)
                
                # Apply perspective transform
                corrected = cv2.warpPerspective(img, matrix, (max_width, max_height))
                
                return corrected
            
            return None
            
        except Exception as e:
            logger.warning(f"Could not correct perspective: {e}")
            return None
    
    def _order_points(self, pts):
        """Order points for perspective transformation.
        
        Args:
            pts: Array of 4 points
            
        Returns:
            Ordered points [top-left, top-right, bottom-right, bottom-left]
        """
        rect = np.zeros((4, 2), dtype="float32")
        
        # Sum and difference of coordinates
        s = pts.sum(axis=1)
        diff = np.diff(pts, axis=1)
        
        # Top-left point has smallest sum
        rect[0] = pts[np.argmin(s)]
        
        # Bottom-right point has largest sum
        rect[2] = pts[np.argmax(s)]
        
        # Top-right point has smallest difference
        rect[1] = pts[np.argmin(diff)]
        
        # Bottom-left point has largest difference
        rect[3] = pts[np.argmax(diff)]
        
        return rect
    
    def assess_image_quality(self, image_path):
        """Assess image quality for OCR suitability.
        
        Args:
            image_path (str): Path to image
            
        Returns:
            dict: Quality assessment metrics
        """
        try:
            img = cv2.imread(image_path)
            gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
            
            # Calculate various quality metrics
            metrics = {}
            
            # 1. Contrast measure (Michelson contrast)
            max_val = np.max(gray)
            min_val = np.min(gray)
            if max_val + min_val > 0:
                metrics['contrast'] = (max_val - min_val) / (max_val + min_val)
            else:
                metrics['contrast'] = 0.0
            
            # 2. Sharpness measure (variance of Laplacian)
            metrics['sharpness'] = cv2.Laplacian(gray, cv2.CV_64F).var()
            
            # 3. Brightness measure
            metrics['brightness'] = np.mean(gray) / 255.0
            
            # 4. Noise estimate (using bilateral filter difference)
            filtered = cv2.bilateralFilter(gray, 9, 75, 75)
            noise_map = cv2.absdiff(gray, filtered)
            metrics['noise_level'] = np.mean(noise_map) / 255.0
            
            # 5. Overall quality score (0-100)
            quality_score = (
                metrics['contrast'] * 30 +
                min(metrics['sharpness'] / 100, 1.0) * 25 +
                (1 - abs(metrics['brightness'] - 0.5) * 2) * 25 +
                (1 - metrics['noise_level']) * 20
            )
            metrics['overall_quality'] = max(0, min(100, quality_score))
            
            return metrics
            
        except Exception as e:
            logger.error(f"Error assessing image quality for {image_path}: {e}")
            return {
                'contrast': 0.0,
                'sharpness': 0.0,
                'brightness': 0.0,
                'noise_level': 1.0,
                'overall_quality': 0.0
            }
    
    def process_image_intelligently(self, image_path):
        """Process image using intelligent variant selection.
        
        Args:
            image_path (str): Path to input image
            
        Returns:
            dict: Best processing result with metadata
        """
        # Assess original image quality
        quality_metrics = self.assess_image_quality(image_path)
        
        # Create all variants
        variants = self.create_processing_variants(image_path)
        
        if not variants:
            return None
        
        # Assess quality of each variant
        best_variant = None
        best_score = 0
        
        for variant in variants:
            variant_quality = self.assess_image_quality(variant['path'])
            
            # Weight score based on original image characteristics
            score = variant_quality['overall_quality']
            
            # Boost scores for specific conditions
            if quality_metrics['contrast'] < 0.3:  # Low contrast original
                if variant['type'] in ['high_contrast', 'adaptive']:
                    score *= 1.2
            
            if quality_metrics['noise_level'] > 0.3:  # Noisy original
                if variant['type'] in ['cleaned', 'background_subtracted']:
                    score *= 1.2
            
            if quality_metrics['sharpness'] < 50:  # Blurry original
                if variant['type'] in ['edge_enhanced', 'cleaned']:
                    score *= 1.1
            
            variant['quality_score'] = score
            variant['quality_metrics'] = variant_quality
            
            if score > best_score:
                best_score = score
                best_variant = variant
        
        # Log the selection decision
        logger.info(f"Selected {best_variant['type']} variant for {os.path.basename(image_path)} (score: {best_score:.2f})")
        
        return {
            'best_variant': best_variant,
            'all_variants': variants,
            'original_quality': quality_metrics,
            'improvement': best_score - quality_metrics['overall_quality']
        }