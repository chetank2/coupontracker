#!/usr/bin/env python3
"""
Image Processor - Processes coupon images for training.

This module provides functions to clean, normalize, and enhance coupon images
for better OCR and model training.
"""

import os
import cv2
import numpy as np
import logging
import argparse
from PIL import Image, ImageEnhance, ImageFilter
from sklearn.cluster import KMeans

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("image_processor.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("image_processor")

class CouponImageProcessor:
    """Processes coupon images for training."""
    
    def __init__(self, input_dir="data/scraped_coupons", output_dir="data/processed_coupons"):
        """Initialize the processor.
        
        Args:
            input_dir (str): Directory containing input images
            output_dir (str): Directory to save processed images
        """
        self.input_dir = input_dir
        self.output_dir = output_dir
        
        # Create output directory
        os.makedirs(output_dir, exist_ok=True)
        
        # Initialize counters
        self.images_processed = 0
        self.images_filtered = 0
        
        logger.info(f"Initialized processor with input directory: {input_dir}, output directory: {output_dir}")
    
    def process_images(self, filter_non_coupons=True):
        """Process all images in the input directory.
        
        Args:
            filter_non_coupons (bool): Whether to filter out non-coupon images
            
        Returns:
            list: List of paths to processed images
        """
        # Get all image files
        image_files = []
        for ext in [".jpg", ".jpeg", ".png", ".gif"]:
            image_files.extend([os.path.join(self.input_dir, f) for f in os.listdir(self.input_dir) if f.lower().endswith(ext)])
        
        logger.info(f"Found {len(image_files)} images to process")
        
        processed_images = []
        
        for image_path in image_files:
            try:
                # Check if it's a coupon image
                if filter_non_coupons and not self._is_coupon_image(image_path):
                    logger.info(f"Filtered out non-coupon image: {os.path.basename(image_path)}")
                    self.images_filtered += 1
                    continue
                
                # Process the image
                processed_path = self._process_image(image_path)
                
                if processed_path:
                    processed_images.append(processed_path)
                    self.images_processed += 1
            
            except Exception as e:
                logger.error(f"Error processing image {image_path}: {e}")
        
        logger.info(f"Processed {self.images_processed} images, filtered out {self.images_filtered} non-coupon images")
        return processed_images
    
    def _is_coupon_image(self, image_path):
        """Check if an image is likely to be a coupon.
        
        Args:
            image_path (str): Path to the image
            
        Returns:
            bool: True if the image is likely to be a coupon, False otherwise
        """
        try:
            # Load the image
            img = cv2.imread(image_path)
            if img is None:
                return False
            
            # Convert to grayscale
            gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
            
            # Check image dimensions
            height, width = gray.shape
            if height < 100 or width < 200:
                # Too small to be a coupon
                return False
            
            # Check aspect ratio
            aspect_ratio = width / height
            if aspect_ratio < 1.5 or aspect_ratio > 5:
                # Coupon images typically have a rectangular shape
                return False
            
            # Check for text content using edge detection
            edges = cv2.Canny(gray, 50, 150)
            edge_density = np.count_nonzero(edges) / (height * width)
            
            if edge_density < 0.01:
                # Very few edges, likely not a coupon
                return False
            
            # Check for rectangular shapes
            contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            
            has_rectangles = False
            for contour in contours:
                # Approximate the contour
                epsilon = 0.02 * cv2.arcLength(contour, True)
                approx = cv2.approxPolyDP(contour, epsilon, True)
                
                # Check if it's a rectangle (4 points)
                if len(approx) == 4:
                    # Check if it's large enough
                    area = cv2.contourArea(approx)
                    if area > (height * width * 0.1):
                        has_rectangles = True
                        break
            
            if not has_rectangles:
                # No significant rectangular shapes found
                return False
            
            # Perform OCR-like check: look for text-like patterns
            # We'll use a simple heuristic: check for horizontal lines of edges
            horizontal_projection = np.sum(edges, axis=1)
            text_lines = 0
            
            for i in range(1, len(horizontal_projection) - 1):
                if horizontal_projection[i] > 0 and horizontal_projection[i-1] == 0 and horizontal_projection[i+1] == 0:
                    text_lines += 1
            
            if text_lines < 3:
                # Too few text-like patterns
                return False
            
            # If we've made it this far, it's likely a coupon
            return True
        
        except Exception as e:
            logger.error(f"Error checking if image is a coupon: {e}")
            return False
    
    def _process_image(self, image_path):
        """Process a single image.
        
        Args:
            image_path (str): Path to the image
            
        Returns:
            str: Path to the processed image, or None if processing failed
        """
        try:
            # Generate output filename
            filename = os.path.basename(image_path)
            base_name, ext = os.path.splitext(filename)
            output_path = os.path.join(self.output_dir, f"{base_name}_processed{ext}")
            
            # Load the image with PIL for enhancement
            with Image.open(image_path) as pil_img:
                # Resize if too large
                max_size = 1200
                if pil_img.width > max_size or pil_img.height > max_size:
                    pil_img.thumbnail((max_size, max_size), Image.LANCZOS)
                
                # Convert to RGB if needed
                if pil_img.mode != 'RGB':
                    pil_img = pil_img.convert('RGB')
                
                # Enhance the image
                enhanced_img = self._enhance_image(pil_img)
                
                # Save the enhanced image
                enhanced_img.save(output_path)
            img = cv2.imread(output_path)
            
            # Detect and crop to coupon boundaries
            cropped = self._detect_and_crop_coupon(img)
            if cropped is not None:
                cv2.imwrite(output_path, cropped)
            
            # Deskew the image
            deskewed = self._deskew_image(cv2.imread(output_path))
            if deskewed is not None:
                cv2.imwrite(output_path, deskewed)
            
            logger.info(f"Processed image: {filename} -> {os.path.basename(output_path)}")
            return output_path
        
        except Exception as e:
            logger.error(f"Error processing image {image_path}: {e}")
            return None
    
    def _enhance_image(self, img):
        """Enhance an image for better OCR.
        
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
        
        # Apply slight blur to reduce noise
        img = img.filter(ImageFilter.UnsharpMask(radius=1.5, percent=150, threshold=3))
        
        return img
    
    def _detect_and_crop_coupon(self, img):
        """Detect and crop to coupon boundaries.
        
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
            logger.error(f"Error detecting and cropping coupon: {e}")
            return None
    
    def _deskew_image(self, img):
        """Deskew an image.
        
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
            logger.error(f"Error deskewing image: {e}")
            return None
    
    def generate_augmented_images(self, processed_images, num_augmentations=3):
        """Generate augmented versions of processed images.
        
        Args:
            processed_images (list): List of paths to processed images
            num_augmentations (int): Number of augmentations to generate per image
            
        Returns:
            list: List of paths to all images (original + augmented)
        """
        all_images = processed_images.copy()
        
        for image_path in processed_images:
            try:
                # Load the image
                img = cv2.imread(image_path)
                
                for i in range(num_augmentations):
                    # Generate output filename
                    filename = os.path.basename(image_path)
                    base_name, ext = os.path.splitext(filename)
                    output_path = os.path.join(self.output_dir, f"{base_name}_aug{i+1}{ext}")
                    
                    # Apply random augmentations
                    augmented = self._apply_augmentations(img)
                    
                    # Save the augmented image
                    cv2.imwrite(output_path, augmented)
                    
                    all_images.append(output_path)
                    logger.info(f"Generated augmented image: {os.path.basename(output_path)}")
            
            except Exception as e:
                logger.error(f"Error generating augmented images for {image_path}: {e}")
        
        logger.info(f"Generated {len(all_images) - len(processed_images)} augmented images")
        return all_images
    
    def _apply_augmentations(self, img):
        """Apply random augmentations to an image.
        
        Args:
            img (numpy.ndarray): Input image
            
        Returns:
            numpy.ndarray: Augmented image
        """
        # Make a copy of the image
        augmented = img.copy()
        
        # Random brightness and contrast adjustment
        alpha = np.random.uniform(0.8, 1.2)  # Contrast
        beta = np.random.uniform(-30, 30)    # Brightness
        augmented = cv2.convertScaleAbs(augmented, alpha=alpha, beta=beta)
        
        # Random rotation
        angle = np.random.uniform(-5, 5)
        h, w = augmented.shape[:2]
        center = (w // 2, h // 2)
        M = cv2.getRotationMatrix2D(center, angle, 1.0)
        augmented = cv2.warpAffine(augmented, M, (w, h), borderMode=cv2.BORDER_REPLICATE)
        
        # Random perspective transform
        if np.random.random() < 0.5:
            # Define the 4 source points
            src_pts = np.float32([
                [0, 0],
                [w - 1, 0],
                [0, h - 1],
                [w - 1, h - 1]
            ])
            
            # Define the 4 destination points with small random offsets
            max_offset = min(w, h) * 0.05
            dst_pts = np.float32([
                [np.random.uniform(0, max_offset), np.random.uniform(0, max_offset)],
                [np.random.uniform(w - 1 - max_offset, w - 1), np.random.uniform(0, max_offset)],
                [np.random.uniform(0, max_offset), np.random.uniform(h - 1 - max_offset, h - 1)],
                [np.random.uniform(w - 1 - max_offset, w - 1), np.random.uniform(h - 1 - max_offset, h - 1)]
            ])
            
            # Get the perspective transform matrix
            M = cv2.getPerspectiveTransform(src_pts, dst_pts)
            
            # Apply the perspective transform
            augmented = cv2.warpPerspective(augmented, M, (w, h), borderMode=cv2.BORDER_REPLICATE)
        
        # Random noise
        if np.random.random() < 0.3:
            noise = np.random.normal(0, 5, augmented.shape).astype(np.uint8)
            augmented = cv2.add(augmented, noise)
        
        return augmented

def main():
    """Main function."""
    parser = argparse.ArgumentParser(description="Process coupon images for training")
    parser.add_argument("--input-dir", default="data/scraped_coupons", help="Directory containing input images")
    parser.add_argument("--output-dir", default="data/processed_coupons", help="Directory to save processed images")
    parser.add_argument("--filter", action="store_true", help="Filter out non-coupon images")
    parser.add_argument("--augment", action="store_true", help="Generate augmented versions of processed images")
    parser.add_argument("--num-augmentations", type=int, default=3, help="Number of augmentations to generate per image")
    
    args = parser.parse_args()
    
    processor = CouponImageProcessor(input_dir=args.input_dir, output_dir=args.output_dir)
    processed_images = processor.process_images(filter_non_coupons=args.filter)
    
    if args.augment and processed_images:
        all_images = processor.generate_augmented_images(processed_images, num_augmentations=args.num_augmentations)
        print(f"Processed {processor.images_processed} images, filtered out {processor.images_filtered} non-coupon images")
        print(f"Generated {len(all_images) - len(processed_images)} augmented images")
        print(f"Total images: {len(all_images)}")
    else:
        print(f"Processed {processor.images_processed} images, filtered out {processor.images_filtered} non-coupon images")

if __name__ == "__main__":
    main()
