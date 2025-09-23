#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import cv2
import numpy as np
import random
import json
from pathlib import Path
from tqdm import tqdm

def add_noise(image, noise_type="gaussian", amount=0.05):
    """Add noise to an image
    
    Args:
        image (numpy.ndarray): Input image
        noise_type (str): Type of noise ('gaussian', 'salt_pepper', 'speckle')
        amount (float): Amount of noise to add
        
    Returns:
        numpy.ndarray: Image with added noise
    """
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
        s_vs_p = 0.5
        amount = amount
        noisy = np.copy(image)
        
        # Salt mode
        num_salt = np.ceil(amount * image.size * s_vs_p)
        coords = [np.random.randint(0, i - 1, int(num_salt)) for i in image.shape]
        noisy[coords[0], coords[1], :] = 255
        
        # Pepper mode
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
    """Adjust brightness and contrast of an image
    
    Args:
        image (numpy.ndarray): Input image
        brightness (float): Brightness adjustment (-1.0 to 1.0)
        contrast (float): Contrast adjustment (-1.0 to 1.0)
        
    Returns:
        numpy.ndarray: Adjusted image
    """
    if brightness != 0:
        if brightness > 0:
            shadow = brightness
            highlight = 255
        else:
            shadow = 0
            highlight = 255 + brightness
        alpha_b = (highlight - shadow) / 255
        gamma_b = shadow
        
        buf = cv2.addWeighted(image, alpha_b, image, 0, gamma_b)
    else:
        buf = image.copy()
    
    if contrast != 0:
        f = 131 * (contrast + 127) / (127 * (131 - contrast))
        alpha_c = f
        gamma_c = 127 * (1 - f)
        
        buf = cv2.addWeighted(buf, alpha_c, buf, 0, gamma_c)
    
    return buf

def apply_blur(image, kernel_size=5):
    """Apply blur to an image
    
    Args:
        image (numpy.ndarray): Input image
        kernel_size (int): Size of the blur kernel
        
    Returns:
        numpy.ndarray: Blurred image
    """
    return cv2.GaussianBlur(image, (kernel_size, kernel_size), 0)

def apply_rotation(image, angle=10):
    """Rotate an image
    
    Args:
        image (numpy.ndarray): Input image
        angle (float): Rotation angle in degrees
        
    Returns:
        numpy.ndarray: Rotated image
    """
    height, width = image.shape[:2]
    center = (width // 2, height // 2)
    
    rotation_matrix = cv2.getRotationMatrix2D(center, angle, 1.0)
    rotated = cv2.warpAffine(image, rotation_matrix, (width, height), 
                             flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
    
    return rotated

def apply_perspective_transform(image, strength=0.05):
    """Apply perspective transform to an image
    
    Args:
        image (numpy.ndarray): Input image
        strength (float): Strength of the perspective transform
        
    Returns:
        numpy.ndarray: Transformed image
    """
    height, width = image.shape[:2]
    
    # Define the 4 source points (original corners)
    pts1 = np.float32([[0, 0], [width, 0], [0, height], [width, height]])
    
    # Define the 4 destination points (perturbed corners)
    random_strength = strength * min(width, height)
    pts2 = np.float32([
        [0 + random.uniform(0, random_strength), 0 + random.uniform(0, random_strength)],
        [width - random.uniform(0, random_strength), 0 + random.uniform(0, random_strength)],
        [0 + random.uniform(0, random_strength), height - random.uniform(0, random_strength)],
        [width - random.uniform(0, random_strength), height - random.uniform(0, random_strength)]
    ])
    
    # Get the perspective transform matrix
    M = cv2.getPerspectiveTransform(pts1, pts2)
    
    # Apply the perspective transformation
    transformed = cv2.warpPerspective(image, M, (width, height), 
                                      flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
    
    return transformed

def apply_random_crop(image, max_crop_percent=0.1):
    """Apply random crop to an image
    
    Args:
        image (numpy.ndarray): Input image
        max_crop_percent (float): Maximum percentage to crop from each side
        
    Returns:
        numpy.ndarray: Cropped image
    """
    height, width = image.shape[:2]
    
    # Calculate maximum pixels to crop from each side
    max_crop_pixels_h = int(height * max_crop_percent)
    max_crop_pixels_w = int(width * max_crop_percent)
    
    # Generate random crop values
    crop_top = random.randint(0, max_crop_pixels_h)
    crop_bottom = random.randint(0, max_crop_pixels_h)
    crop_left = random.randint(0, max_crop_pixels_w)
    crop_right = random.randint(0, max_crop_pixels_w)
    
    # Apply crop
    cropped = image[crop_top:height-crop_bottom, crop_left:width-crop_right]
    
    # Resize back to original dimensions
    resized = cv2.resize(cropped, (width, height), interpolation=cv2.INTER_LINEAR)
    
    return resized

def augment_image(image, augmentation_type=None, params=None):
    """Apply augmentation to an image
    
    Args:
        image (numpy.ndarray): Input image
        augmentation_type (str): Type of augmentation to apply
        params (dict): Parameters for the augmentation
        
    Returns:
        numpy.ndarray: Augmented image
    """
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

def augment_dataset(input_dir, output_dir, num_augmentations=3, preserve_annotations=True):
    """Augment a dataset of images
    
    Args:
        input_dir (str): Directory containing original images
        output_dir (str): Directory to save augmented images
        num_augmentations (int): Number of augmented versions to create per image
        preserve_annotations (bool): Whether to copy and adjust annotations
        
    Returns:
        int: Number of augmented images created
    """
    # Create output directory if it doesn't exist
    os.makedirs(output_dir, exist_ok=True)
    
    # Get list of image files
    image_files = [f for f in os.listdir(input_dir) if f.lower().endswith(('.png', '.jpg', '.jpeg'))]
    
    count = 0
    for image_file in tqdm(image_files, desc="Augmenting images"):
        # Load image
        image_path = os.path.join(input_dir, image_file)
        image = cv2.imread(image_path)
        
        if image is None:
            print(f"Warning: Could not load image {image_path}")
            continue
        
        # Check for annotation file
        base_name = os.path.splitext(image_file)[0]
        annotation_path = os.path.join(input_dir, f"{base_name}.json")
        annotation_data = None
        
        if preserve_annotations and os.path.exists(annotation_path):
            try:
                with open(annotation_path, 'r') as f:
                    annotation_data = json.load(f)
            except Exception as e:
                print(f"Warning: Could not load annotation file {annotation_path}: {e}")
        
        # Create augmented versions
        for i in range(num_augmentations):
            # Apply random augmentation
            augmented = augment_image(image, "combined")
            
            # Save augmented image
            aug_image_file = f"{base_name}_aug{i+1}.jpg"
            aug_image_path = os.path.join(output_dir, aug_image_file)
            cv2.imwrite(aug_image_path, augmented)
            
            # Save annotation if available
            if annotation_data is not None:
                aug_annotation_path = os.path.join(output_dir, f"{base_name}_aug{i+1}.json")
                
                # Copy annotation data (for now, we're not adjusting coordinates)
                # In a real implementation, we would need to transform the annotation coordinates
                # based on the applied augmentations
                aug_annotation_data = annotation_data.copy()
                aug_annotation_data["image"] = aug_image_file
                
                with open(aug_annotation_path, 'w') as f:
                    json.dump(aug_annotation_data, f, indent=2)
            
            count += 1
    
    return count

if __name__ == "__main__":
    import argparse
    
    parser = argparse.ArgumentParser(description="Augment coupon images dataset")
    parser.add_argument("--input", type=str, default="../data/raw", help="Input directory with original images")
    parser.add_argument("--output", type=str, default="../data/augmented", help="Output directory for augmented images")
    parser.add_argument("--count", type=int, default=3, help="Number of augmented versions per image")
    parser.add_argument("--preserve-annotations", action="store_true", help="Preserve and copy annotations")
    
    args = parser.parse_args()
    
    print(f"Augmenting images from {args.input} to {args.output}")
    count = augment_dataset(args.input, args.output, args.count, args.preserve_annotations)
    print(f"Created {count} augmented images")
