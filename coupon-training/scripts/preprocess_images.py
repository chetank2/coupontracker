#!/usr/bin/env python3
import os
import cv2
import numpy as np
import argparse
from pathlib import Path

def resize_image(image, max_width=1600, max_height=1600):
    """Resize image while maintaining aspect ratio"""
    height, width = image.shape[:2]
    
    # Calculate new dimensions
    if width > max_width or height > max_height:
        if width > height:
            new_width = max_width
            new_height = int(height * (max_width / width))
        else:
            new_height = max_height
            new_width = int(width * (max_height / height))
        
        # Resize the image
        resized = cv2.resize(image, (new_width, new_height), interpolation=cv2.INTER_AREA)
        return resized
    
    return image

def enhance_contrast(image):
    """Enhance contrast using CLAHE"""
    # Convert to LAB color space
    lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB)
    
    # Split the LAB image into L, A, and B channels
    l, a, b = cv2.split(lab)
    
    # Apply CLAHE to the L channel
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    cl = clahe.apply(l)
    
    # Merge the CLAHE enhanced L channel with the original A and B channels
    merged = cv2.merge((cl, a, b))
    
    # Convert back to BGR color space
    enhanced = cv2.cvtColor(merged, cv2.COLOR_LAB2BGR)
    
    return enhanced

def denoise_image(image):
    """Apply denoising to the image"""
    return cv2.fastNlMeansDenoisingColored(image, None, 10, 10, 7, 21)

def adaptive_threshold(image):
    """Apply adaptive thresholding to grayscale image"""
    # Convert to grayscale
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # Apply Gaussian blur
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    
    # Apply adaptive thresholding
    thresh = cv2.adaptiveThreshold(
        blurred, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 11, 2
    )
    
    return thresh

def deskew_image(image):
    """Deskew the image to straighten text"""
    # Convert to grayscale
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # Apply threshold to get binary image
    _, binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    
    # Find all contours
    contours, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    
    # Find the largest contour
    if contours:
        largest_contour = max(contours, key=cv2.contourArea)
        
        # Get the minimum area rectangle
        rect = cv2.minAreaRect(largest_contour)
        angle = rect[2]
        
        # Adjust the angle
        if angle < -45:
            angle = 90 + angle
        
        # Rotate the image
        (h, w) = image.shape[:2]
        center = (w // 2, h // 2)
        M = cv2.getRotationMatrix2D(center, angle, 1.0)
        rotated = cv2.warpAffine(image, M, (w, h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
        
        return rotated
    
    return image

def preprocess_image(image_path, output_dir, apply_threshold=False):
    """Apply full preprocessing pipeline to an image"""
    # Read the image
    image = cv2.imread(str(image_path))
    if image is None:
        print(f"Error: Could not read image {image_path}")
        return False
    
    # Get the filename without extension
    filename = os.path.basename(image_path)
    name, ext = os.path.splitext(filename)
    
    # Create output path
    output_path = os.path.join(output_dir, f"{name}_processed{ext}")
    
    try:
        # Apply preprocessing steps
        image = resize_image(image)
        image = enhance_contrast(image)
        image = denoise_image(image)
        image = deskew_image(image)
        
        # Save the processed color image
        cv2.imwrite(output_path, image)
        
        # If thresholding is requested, create a thresholded version
        if apply_threshold:
            thresh = adaptive_threshold(image)
            thresh_path = os.path.join(output_dir, f"{name}_thresh{ext}")
            cv2.imwrite(thresh_path, thresh)
        
        return True
    except Exception as e:
        print(f"Error processing {image_path}: {e}")
        return False

def main():
    parser = argparse.ArgumentParser(description="Preprocess coupon images for training")
    parser.add_argument("--input-dir", default="../data/raw", help="Directory containing raw images")
    parser.add_argument("--output-dir", default="../data/processed", help="Directory to save processed images")
    parser.add_argument("--threshold", action="store_true", help="Also create thresholded versions")
    
    args = parser.parse_args()
    
    # Ensure output directory exists
    os.makedirs(args.output_dir, exist_ok=True)
    
    # Get all image files
    image_extensions = ['.jpg', '.jpeg', '.png', '.bmp', '.tiff']
    image_paths = []
    for ext in image_extensions:
        image_paths.extend(list(Path(args.input_dir).glob(f"*{ext}")))
        image_paths.extend(list(Path(args.input_dir).glob(f"*{ext.upper()}")))
    
    if not image_paths:
        print(f"No images found in {args.input_dir}")
        return
    
    print(f"Found {len(image_paths)} images to process")
    
    # Process each image
    success_count = 0
    for image_path in image_paths:
        print(f"Processing {image_path}...")
        if preprocess_image(image_path, args.output_dir, args.threshold):
            success_count += 1
    
    print(f"Preprocessing complete. Successfully processed {success_count}/{len(image_paths)} images.")

if __name__ == "__main__":
    main()
