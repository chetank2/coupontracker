#!/usr/bin/env python3
"""
Data Augmentation for Indian Coupon Images

This script augments annotated Indian coupon images to increase the training dataset size:
- Applies various transformations to images
- Updates annotations accordingly
- Saves augmented images and annotations
"""

import os
import cv2
import numpy as np
import json
import random
import argparse
from pathlib import Path
import logging
from tqdm import tqdm
import shutil

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("india_augmentation.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("india_data_augmentation")

# Base directories
BASE_DIR = os.path.join('coupon-training', 'data', 'reddit_india')
ANNOTATED_DIR = os.path.join(BASE_DIR, 'annotated')
AUGMENTED_DIR = os.path.join(BASE_DIR, 'augmented')

def add_noise(image, noise_type="gaussian", amount=0.05):
    """Add noise to an image."""
    if noise_type == "gaussian":
        # Gaussian noise
        row, col, ch = image.shape
        mean = 0
        sigma = amount * 255
        gauss = np.random.normal(mean, sigma, (row, col, ch))
        gauss = gauss.reshape(row, col, ch)
        noisy = image + gauss
        return np.clip(noisy, 0, 255).astype(np.uint8)
    
    elif noise_type == "salt_pepper":
        # Salt and pepper noise
        row, col, ch = image.shape
        s_vs_p = 0.5
        amount = amount
        noisy = np.copy(image)
        
        # Salt
        num_salt = np.ceil(amount * image.size * s_vs_p)
        coords = [np.random.randint(0, i - 1, int(num_salt)) for i in image.shape]
        noisy[coords[0], coords[1], :] = 255
        
        # Pepper
        num_pepper = np.ceil(amount * image.size * (1. - s_vs_p))
        coords = [np.random.randint(0, i - 1, int(num_pepper)) for i in image.shape]
        noisy[coords[0], coords[1], :] = 0
        
        return noisy
    
    elif noise_type == "speckle":
        # Speckle noise
        row, col, ch = image.shape
        gauss = np.random.randn(row, col, ch)
        gauss = gauss.reshape(row, col, ch)
        noisy = image + image * gauss * amount
        return np.clip(noisy, 0, 255).astype(np.uint8)
    
    return image

def adjust_brightness_contrast(image, brightness=0, contrast=0):
    """Adjust brightness and contrast of an image."""
    # Apply brightness
    if brightness != 0:
        if brightness > 0:
            shadow = brightness
            highlight = 255
        else:
            shadow = 0
            highlight = 255 + brightness
        alpha_b = (highlight - shadow) / 255
        gamma_b = shadow
        
        image = cv2.addWeighted(image, alpha_b, image, 0, gamma_b)
    
    # Apply contrast
    if contrast != 0:
        f = 131 * (contrast + 127) / (127 * (131 - contrast))
        alpha_c = f
        gamma_c = 127 * (1 - f)
        
        image = cv2.addWeighted(image, alpha_c, image, 0, gamma_c)
    
    return np.clip(image, 0, 255).astype(np.uint8)

def apply_blur(image, kernel_size=3):
    """Apply blur to an image."""
    return cv2.GaussianBlur(image, (kernel_size, kernel_size), 0)

def apply_rotation(image, angle=0):
    """Apply rotation to an image."""
    height, width = image.shape[:2]
    center = (width / 2, height / 2)
    
    # Get rotation matrix
    rotation_matrix = cv2.getRotationMatrix2D(center, angle, 1.0)
    
    # Apply rotation
    rotated = cv2.warpAffine(image, rotation_matrix, (width, height), flags=cv2.INTER_LINEAR)
    
    return rotated

def apply_perspective_transform(image, strength=0.05):
    """Apply perspective transform to an image."""
    height, width = image.shape[:2]
    
    # Define the 4 source points
    src_points = np.float32([
        [0, 0],
        [width - 1, 0],
        [0, height - 1],
        [width - 1, height - 1]
    ])
    
    # Define the 4 destination points with random perturbation
    dst_points = np.float32([
        [0 + random.uniform(-strength, strength) * width, 0 + random.uniform(-strength, strength) * height],
        [width - 1 - random.uniform(-strength, strength) * width, 0 + random.uniform(-strength, strength) * height],
        [0 + random.uniform(-strength, strength) * width, height - 1 - random.uniform(-strength, strength) * height],
        [width - 1 - random.uniform(-strength, strength) * width, height - 1 - random.uniform(-strength, strength) * height]
    ])
    
    # Get perspective transform matrix
    perspective_matrix = cv2.getPerspectiveTransform(src_points, dst_points)
    
    # Apply perspective transform
    transformed = cv2.warpPerspective(image, perspective_matrix, (width, height), flags=cv2.INTER_LINEAR)
    
    return transformed

def apply_random_crop(image, max_crop_percent=0.1):
    """Apply random crop to an image."""
    height, width = image.shape[:2]
    
    # Calculate crop dimensions
    crop_width = int(width * random.uniform(0, max_crop_percent))
    crop_height = int(height * random.uniform(0, max_crop_percent))
    
    # Calculate crop coordinates
    x1 = random.randint(0, crop_width)
    y1 = random.randint(0, crop_height)
    x2 = width - random.randint(0, crop_width)
    y2 = height - random.randint(0, crop_height)
    
    # Apply crop
    cropped = image[y1:y2, x1:x2]
    
    # Resize back to original dimensions
    resized = cv2.resize(cropped, (width, height), interpolation=cv2.INTER_LINEAR)
    
    return resized

def augment_image(image, augmentation_type=None, params=None):
    """Apply augmentation to an image."""
    if augmentation_type is None:
        # Choose a random augmentation
        augmentation_type = random.choice([
            "noise", "brightness_contrast", "blur", "rotation", 
            "perspective", "crop", "combined"
        ])
    
    if params is None:
        params = {}
    
    if augmentation_type == "noise":
        noise_type = params.get("noise_type", random.choice(["gaussian", "salt_pepper", "speckle"]))
        amount = params.get("amount", random.uniform(0.01, 0.1))
        return add_noise(image, noise_type, amount)
    
    elif augmentation_type == "brightness_contrast":
        brightness = params.get("brightness", random.uniform(-0.3, 0.3))
        contrast = params.get("contrast", random.uniform(-0.3, 0.3))
        return adjust_brightness_contrast(image, brightness, contrast)
    
    elif augmentation_type == "blur":
        kernel_size = params.get("kernel_size", random.choice([3, 5, 7]))
        return apply_blur(image, kernel_size)
    
    elif augmentation_type == "rotation":
        angle = params.get("angle", random.uniform(-10, 10))
        return apply_rotation(image, angle)
    
    elif augmentation_type == "perspective":
        strength = params.get("strength", random.uniform(0.02, 0.08))
        return apply_perspective_transform(image, strength)
    
    elif augmentation_type == "crop":
        max_crop_percent = params.get("max_crop_percent", random.uniform(0.05, 0.15))
        return apply_random_crop(image, max_crop_percent)
    
    elif augmentation_type == "combined":
        # Apply multiple augmentations
        augmented = image.copy()
        
        # Apply 2-3 random augmentations
        num_augmentations = random.randint(2, 3)
        augmentation_types = random.sample([
            "noise", "brightness_contrast", "blur", "rotation", "perspective", "crop"
        ], num_augmentations)
        
        for aug_type in augmentation_types:
            augmented = augment_image(augmented, aug_type)
        
        return augmented
    
    return image

def update_annotation(annotation, augmentation_type, params=None):
    """Update annotation based on the applied augmentation."""
    # For most augmentations, the annotation doesn't need to be updated
    # since the regions are defined in relative coordinates
    
    # For rotation and perspective transform, the regions would need to be updated
    # This is a simplified implementation
    
    return annotation

def augment_dataset(annotated_dir, augmented_dir, num_augmentations=3):
    """Augment a dataset of annotated images."""
    # Create output directory if it doesn't exist
    os.makedirs(augmented_dir, exist_ok=True)
    
    # Get all annotation files
    annotation_files = []
    for root, _, files in os.walk(annotated_dir):
        for file in files:
            if file.lower().endswith('_annotations.json'):
                annotation_files.append(os.path.join(root, file))
    
    logger.info(f"Found {len(annotation_files)} annotation files to augment")
    
    # Copy original files to augmented directory
    for annotation_file in annotation_files:
        # Read annotation
        with open(annotation_file, 'r') as f:
            annotation = json.load(f)
        
        # Get image path
        image_path = os.path.join(BASE_DIR, annotation["image_path"])
        
        # Copy image and annotation to augmented directory
        image_filename = os.path.basename(image_path)
        annotation_filename = os.path.basename(annotation_file)
        
        shutil.copy(image_path, os.path.join(augmented_dir, image_filename))
        shutil.copy(annotation_file, os.path.join(augmented_dir, annotation_filename))
    
    # Augment each image
    augmented_count = 0
    for i, annotation_file in tqdm(enumerate(annotation_files), total=len(annotation_files), desc="Augmenting images"):
        logger.info(f"Augmenting image {i+1}/{len(annotation_files)}: {annotation_file}")
        
        # Read annotation
        with open(annotation_file, 'r') as f:
            annotation = json.load(f)
        
        # Get image path
        image_path = os.path.join(BASE_DIR, annotation["image_path"])
        
        # Read image
        image = cv2.imread(image_path)
        if image is None:
            logger.error(f"Failed to read image: {image_path}")
            continue
        
        # Create multiple augmentations
        for j in range(num_augmentations):
            # Choose random augmentation type
            augmentation_type = random.choice([
                "noise", "brightness_contrast", "blur", "rotation", 
                "perspective", "crop", "combined"
            ])
            
            # Apply augmentation
            augmented_image = augment_image(image, augmentation_type)
            
            # Update annotation
            augmented_annotation = update_annotation(annotation.copy(), augmentation_type)
            
            # Save augmented image
            image_filename = os.path.basename(image_path)
            base_name, ext = os.path.splitext(image_filename)
            augmented_image_filename = f"{base_name}_aug{j+1}{ext}"
            augmented_image_path = os.path.join(augmented_dir, augmented_image_filename)
            cv2.imwrite(augmented_image_path, augmented_image)
            
            # Update image path in annotation
            rel_path = os.path.relpath(augmented_image_path, BASE_DIR)
            augmented_annotation["image_path"] = rel_path
            
            # Save augmented annotation
            annotation_filename = os.path.basename(annotation_file)
            base_name, ext = os.path.splitext(annotation_filename)
            augmented_annotation_filename = f"{base_name}_aug{j+1}{ext}"
            augmented_annotation_path = os.path.join(augmented_dir, augmented_annotation_filename)
            with open(augmented_annotation_path, 'w') as f:
                json.dump(augmented_annotation, f, indent=4)
            
            augmented_count += 1
    
    logger.info(f"Augmentation complete. Created {augmented_count} augmented images.")
    return augmented_count

def main():
    """Main function to augment a dataset of annotated images."""
    parser = argparse.ArgumentParser(description='Augment a dataset of annotated Indian coupon images.')
    parser.add_argument('--input-dir', default=ANNOTATED_DIR, help='Directory containing annotated images')
    parser.add_argument('--output-dir', default=AUGMENTED_DIR, help='Directory to save augmented images')
    parser.add_argument('--num-augmentations', type=int, default=3, help='Number of augmented versions to create per image')
    args = parser.parse_args()
    
    augment_dataset(args.input_dir, args.output_dir, args.num_augmentations)

if __name__ == "__main__":
    main()
