#!/usr/bin/env python3
"""
Basic Data Augmentation for Coupon Images

This script applies various augmentations to coupon images to increase
the diversity of the training dataset.
"""

import os
import cv2
import numpy as np
import logging
import argparse
import json
import random
from pathlib import Path

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("augmentation.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("data_augmentation")

# Base directories
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROCESSED_IMAGES_DIR = os.path.join(BASE_DIR, 'processed_images')
AUGMENTED_DIR = os.path.join(BASE_DIR, 'augmented')
ANNOTATIONS_DIR = os.path.join(BASE_DIR, 'annotations')
AUGMENTATION_METADATA = os.path.join(BASE_DIR, 'augmentation_metadata.json')

def ensure_directories_exist():
    """Ensure all necessary directories exist."""
    os.makedirs(AUGMENTED_DIR, exist_ok=True)
    logger.info("Directory structure verified")

def apply_rotation(image, angle):
    """Apply rotation to an image."""
    height, width = image.shape[:2]
    center = (width // 2, height // 2)
    
    # Get rotation matrix
    rotation_matrix = cv2.getRotationMatrix2D(center, angle, 1.0)
    
    # Apply rotation
    rotated = cv2.warpAffine(image, rotation_matrix, (width, height), borderMode=cv2.BORDER_REPLICATE)
    
    return rotated

def apply_brightness_contrast(image, alpha, beta):
    """Apply brightness and contrast adjustments."""
    # alpha controls contrast, beta controls brightness
    adjusted = cv2.convertScaleAbs(image, alpha=alpha, beta=beta)
    return adjusted

def apply_perspective_transform(image):
    """Apply a random perspective transformation."""
    height, width = image.shape[:2]
    
    # Define the 4 source points (corners of the image)
    src_points = np.float32([
        [0, 0],
        [width - 1, 0],
        [width - 1, height - 1],
        [0, height - 1]
    ])
    
    # Define the 4 destination points with random offsets
    max_offset = min(width, height) * 0.1
    dst_points = np.float32([
        [random.uniform(0, max_offset), random.uniform(0, max_offset)],
        [random.uniform(width - max_offset, width - 1), random.uniform(0, max_offset)],
        [random.uniform(width - max_offset, width - 1), random.uniform(height - max_offset, height - 1)],
        [random.uniform(0, max_offset), random.uniform(height - max_offset, height - 1)]
    ])
    
    # Get the perspective transform matrix
    matrix = cv2.getPerspectiveTransform(src_points, dst_points)
    
    # Apply the perspective transformation
    transformed = cv2.warpPerspective(image, matrix, (width, height), borderMode=cv2.BORDER_REPLICATE)
    
    return transformed

def apply_noise(image, noise_type='gaussian', amount=0.05):
    """Apply noise to an image."""
    if noise_type == 'gaussian':
        # Add Gaussian noise
        mean = 0
        stddev = amount * 255
        noise = np.random.normal(mean, stddev, image.shape).astype(np.uint8)
        noisy = cv2.add(image, noise)
        return noisy
    
    elif noise_type == 'salt_pepper':
        # Add salt and pepper noise
        noisy = image.copy()
        # Salt
        num_salt = int(amount * image.size * 0.5)
        coords = [np.random.randint(0, i - 1, num_salt) for i in image.shape]
        noisy[coords[0], coords[1], :] = 255
        
        # Pepper
        num_pepper = int(amount * image.size * 0.5)
        coords = [np.random.randint(0, i - 1, num_pepper) for i in image.shape]
        noisy[coords[0], coords[1], :] = 0
        
        return noisy
    
    return image

def apply_blur(image, kernel_size=5):
    """Apply Gaussian blur to an image."""
    blurred = cv2.GaussianBlur(image, (kernel_size, kernel_size), 0)
    return blurred

def apply_augmentations(image_path, annotation_path=None):
    """Apply various augmentations to an image and update its annotation."""
    try:
        # Load image
        image = cv2.imread(image_path)
        if image is None:
            logger.error(f"Failed to load image: {image_path}")
            return None
        
        # Load annotation if available
        annotation = None
        if annotation_path and os.path.exists(annotation_path):
            with open(annotation_path, 'r') as f:
                annotation = json.load(f)
        
        # Create base filename for augmented images
        base_filename = os.path.splitext(os.path.basename(image_path))[0]
        
        augmented_images = []
        
        # 1. Rotation
        for angle in [-5, 5]:
            rotated = apply_rotation(image, angle)
            filename = f"{base_filename}_rot{angle}.jpg"
            output_path = os.path.join(AUGMENTED_DIR, filename)
            cv2.imwrite(output_path, rotated)
            
            augmented_images.append({
                "original_path": image_path,
                "augmented_path": output_path,
                "augmentation_type": "rotation",
                "parameters": {"angle": angle}
            })
        
        # 2. Brightness and contrast
        # Brighter
        bright = apply_brightness_contrast(image, 1.0, 30)
        filename = f"{base_filename}_bright.jpg"
        output_path = os.path.join(AUGMENTED_DIR, filename)
        cv2.imwrite(output_path, bright)
        
        augmented_images.append({
            "original_path": image_path,
            "augmented_path": output_path,
            "augmentation_type": "brightness",
            "parameters": {"alpha": 1.0, "beta": 30}
        })
        
        # Darker
        dark = apply_brightness_contrast(image, 1.0, -30)
        filename = f"{base_filename}_dark.jpg"
        output_path = os.path.join(AUGMENTED_DIR, filename)
        cv2.imwrite(output_path, dark)
        
        augmented_images.append({
            "original_path": image_path,
            "augmented_path": output_path,
            "augmentation_type": "brightness",
            "parameters": {"alpha": 1.0, "beta": -30}
        })
        
        # Higher contrast
        high_contrast = apply_brightness_contrast(image, 1.3, 0)
        filename = f"{base_filename}_contrast.jpg"
        output_path = os.path.join(AUGMENTED_DIR, filename)
        cv2.imwrite(output_path, high_contrast)
        
        augmented_images.append({
            "original_path": image_path,
            "augmented_path": output_path,
            "augmentation_type": "contrast",
            "parameters": {"alpha": 1.3, "beta": 0}
        })
        
        # 3. Perspective transform
        perspective = apply_perspective_transform(image)
        filename = f"{base_filename}_perspective.jpg"
        output_path = os.path.join(AUGMENTED_DIR, filename)
        cv2.imwrite(output_path, perspective)
        
        augmented_images.append({
            "original_path": image_path,
            "augmented_path": output_path,
            "augmentation_type": "perspective",
            "parameters": {}
        })
        
        # 4. Noise
        noisy = apply_noise(image, 'gaussian', 0.05)
        filename = f"{base_filename}_noise.jpg"
        output_path = os.path.join(AUGMENTED_DIR, filename)
        cv2.imwrite(output_path, noisy)
        
        augmented_images.append({
            "original_path": image_path,
            "augmented_path": output_path,
            "augmentation_type": "noise",
            "parameters": {"type": "gaussian", "amount": 0.05}
        })
        
        # 5. Blur
        blurred = apply_blur(image, 3)
        filename = f"{base_filename}_blur.jpg"
        output_path = os.path.join(AUGMENTED_DIR, filename)
        cv2.imwrite(output_path, blurred)
        
        augmented_images.append({
            "original_path": image_path,
            "augmented_path": output_path,
            "augmentation_type": "blur",
            "parameters": {"kernel_size": 3}
        })
        
        return augmented_images
    
    except Exception as e:
        logger.error(f"Error applying augmentations to {image_path}: {e}")
        return None

def augment_dataset(input_dir, annotation_dir=None):
    """Apply augmentations to all images in the input directory."""
    ensure_directories_exist()
    
    # Get all image files
    image_extensions = ['.jpg', '.jpeg', '.png', '.gif']
    image_paths = []
    
    for root, _, files in os.walk(input_dir):
        for file in files:
            if any(file.lower().endswith(ext) for ext in image_extensions):
                image_paths.append(os.path.join(root, file))
    
    logger.info(f"Found {len(image_paths)} images to augment")
    
    # Apply augmentations to each image
    all_augmentations = []
    
    for i, image_path in enumerate(image_paths):
        logger.info(f"Augmenting image {i+1}/{len(image_paths)}: {image_path}")
        
        # Find corresponding annotation if available
        annotation_path = None
        if annotation_dir:
            image_id = os.path.splitext(os.path.basename(image_path))[0]
            annotation_path = os.path.join(annotation_dir, f"{image_id}.json")
        
        # Apply augmentations
        augmentations = apply_augmentations(image_path, annotation_path)
        
        if augmentations:
            all_augmentations.extend(augmentations)
    
    # Save augmentation metadata
    with open(AUGMENTATION_METADATA, 'w') as f:
        json.dump(all_augmentations, f, indent=2)
    
    logger.info(f"Augmentation complete. Created {len(all_augmentations)} augmented images.")
    return all_augmentations

def main():
    """Main function to augment coupon images."""
    parser = argparse.ArgumentParser(description='Augment coupon images.')
    parser.add_argument('--input-dir', default=PROCESSED_IMAGES_DIR, help='Input directory containing processed images')
    parser.add_argument('--annotation-dir', default=ANNOTATIONS_DIR, help='Directory containing annotations')
    
    args = parser.parse_args()
    
    augment_dataset(args.input_dir, args.annotation_dir)

if __name__ == "__main__":
    main()
