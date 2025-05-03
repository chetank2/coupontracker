#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Enhanced Data Augmentation for Coupon Images
This script implements advanced augmentation techniques for coupon images.
"""

import os
import sys
import argparse
import random
import json
import numpy as np
import cv2
from PIL import Image, ImageEnhance, ImageFilter
from tqdm import tqdm

# Add parent directory to path
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

def load_image(image_path):
    """
    Load an image from a path

    Args:
        image_path: Path to the image

    Returns:
        PIL.Image: Loaded image
    """
    try:
        return Image.open(image_path)
    except Exception as e:
        print(f"Error loading image {image_path}: {e}")
        return None

def save_image(image, output_path):
    """
    Save an image to a path

    Args:
        image: PIL.Image to save
        output_path: Path to save the image

    Returns:
        bool: True if successful, False otherwise
    """
    try:
        image.save(output_path)
        return True
    except Exception as e:
        print(f"Error saving image to {output_path}: {e}")
        return False

def adjust_annotation(annotation, transform_params):
    """
    Adjust annotation coordinates based on transformation parameters

    Args:
        annotation: Original annotation dictionary
        transform_params: Dictionary of transformation parameters

    Returns:
        dict: Adjusted annotation dictionary
    """
    # Create a copy of the annotation
    adjusted = {}

    # Get transformation parameters
    scale_x = transform_params.get('scale_x', 1.0)
    scale_y = transform_params.get('scale_y', 1.0)
    translate_x = transform_params.get('translate_x', 0)
    translate_y = transform_params.get('translate_y', 0)

    # Adjust each region
    for region_type, regions in annotation.items():
        adjusted[region_type] = []

        for region in regions:
            # Apply scaling and translation
            adjusted_region = {
                'left': int(region['left'] * scale_x + translate_x),
                'top': int(region['top'] * scale_y + translate_y),
                'right': int(region['right'] * scale_x + translate_x),
                'bottom': int(region['bottom'] * scale_y + translate_y)
            }

            # Ensure coordinates are valid
            adjusted_region['left'] = max(0, adjusted_region['left'])
            adjusted_region['top'] = max(0, adjusted_region['top'])
            adjusted_region['right'] = max(adjusted_region['left'] + 1, adjusted_region['right'])
            adjusted_region['bottom'] = max(adjusted_region['top'] + 1, adjusted_region['bottom'])

            adjusted[region_type].append(adjusted_region)

    return adjusted

def apply_basic_augmentations(image):
    """
    Apply basic augmentations to an image

    Args:
        image: PIL.Image to augment

    Returns:
        list: List of (augmented_image, transform_params, augmentation_name) tuples
    """
    augmented_images = []

    # 1. Brightness adjustment
    brightness_factor = random.uniform(0.8, 1.2)
    brightness_image = ImageEnhance.Brightness(image).enhance(brightness_factor)
    augmented_images.append((
        brightness_image,
        {'scale_x': 1.0, 'scale_y': 1.0, 'translate_x': 0, 'translate_y': 0},
        'brightness'
    ))

    # 2. Contrast adjustment
    contrast_factor = random.uniform(0.8, 1.2)
    contrast_image = ImageEnhance.Contrast(image).enhance(contrast_factor)
    augmented_images.append((
        contrast_image,
        {'scale_x': 1.0, 'scale_y': 1.0, 'translate_x': 0, 'translate_y': 0},
        'contrast'
    ))

    # 3. Slight rotation
    angle = random.uniform(-5, 5)
    rotated_image = image.rotate(angle, resample=Image.BICUBIC, expand=False)
    augmented_images.append((
        rotated_image,
        {'scale_x': 1.0, 'scale_y': 1.0, 'translate_x': 0, 'translate_y': 0},
        'rotation'
    ))

    # 4. Slight blur
    blurred_image = image.filter(ImageFilter.GaussianBlur(radius=random.uniform(0.5, 1.0)))
    augmented_images.append((
        blurred_image,
        {'scale_x': 1.0, 'scale_y': 1.0, 'translate_x': 0, 'translate_y': 0},
        'blur'
    ))

    return augmented_images

def apply_advanced_augmentations(image):
    """
    Apply advanced augmentations to an image using OpenCV and PIL

    Args:
        image: PIL.Image to augment

    Returns:
        list: List of (augmented_image, transform_params, augmentation_name) tuples
    """
    augmented_images = []

    # Convert PIL image to numpy array for OpenCV
    np_image = np.array(image)
    cv_image = cv2.cvtColor(np_image, cv2.COLOR_RGB2BGR)

    # 1. Simulated shadow
    shadow_image = cv_image.copy()
    h, w = shadow_image.shape[:2]

    # Create a random shadow mask
    mask = np.ones((h, w), dtype=np.float32)

    # Add a random shadow polygon
    points = np.array([
        [random.randint(0, w//3), random.randint(0, h//3)],
        [random.randint(2*w//3, w), random.randint(0, h//3)],
        [random.randint(2*w//3, w), random.randint(2*h//3, h)],
        [random.randint(0, w//3), random.randint(2*h//3, h)]
    ], dtype=np.int32)

    cv2.fillPoly(mask, [points], 0.7)  # 0.7 = 30% shadow darkness

    # Apply shadow to each channel
    for i in range(3):
        shadow_image[:, :, i] = shadow_image[:, :, i] * mask

    # Convert back to PIL
    shadow_pil = Image.fromarray(cv2.cvtColor(shadow_image, cv2.COLOR_BGR2RGB))
    augmented_images.append((
        shadow_pil,
        {'scale_x': 1.0, 'scale_y': 1.0, 'translate_x': 0, 'translate_y': 0},
        'shadow'
    ))

    # 2. Perspective transform
    perspective_image = cv_image.copy()
    h, w = perspective_image.shape[:2]

    # Define source points
    src_points = np.float32([
        [0, 0],
        [w-1, 0],
        [0, h-1],
        [w-1, h-1]
    ])

    # Define destination points with random offsets
    offset = int(min(w, h) * 0.1)
    dst_points = np.float32([
        [random.randint(0, offset), random.randint(0, offset)],
        [random.randint(w-offset, w-1), random.randint(0, offset)],
        [random.randint(0, offset), random.randint(h-offset, h-1)],
        [random.randint(w-offset, w-1), random.randint(h-offset, h-1)]
    ])

    # Get perspective transform matrix
    M = cv2.getPerspectiveTransform(src_points, dst_points)

    # Apply perspective transform
    perspective_image = cv2.warpPerspective(perspective_image, M, (w, h))

    # Convert back to PIL
    perspective_pil = Image.fromarray(cv2.cvtColor(perspective_image, cv2.COLOR_BGR2RGB))
    augmented_images.append((
        perspective_pil,
        {'scale_x': 1.0, 'scale_y': 1.0, 'translate_x': 0, 'translate_y': 0},
        'perspective'
    ))

    # 3. Noise
    noise_image = cv_image.copy()
    h, w = noise_image.shape[:2]

    # Add Gaussian noise
    mean = 0
    sigma = random.uniform(5, 15)
    noise = np.random.normal(mean, sigma, (h, w, 3)).astype(np.uint8)
    noise_image = cv2.add(noise_image, noise)

    # Convert back to PIL
    noise_pil = Image.fromarray(cv2.cvtColor(noise_image, cv2.COLOR_BGR2RGB))
    augmented_images.append((
        noise_pil,
        {'scale_x': 1.0, 'scale_y': 1.0, 'translate_x': 0, 'translate_y': 0},
        'noise'
    ))

    # 4. JPEG compression simulation
    # Save to a temporary buffer with low quality
    jpeg_quality = random.randint(50, 85)
    encode_param = [int(cv2.IMWRITE_JPEG_QUALITY), jpeg_quality]
    _, buffer = cv2.imencode('.jpg', cv_image, encode_param)

    # Read back from buffer
    jpeg_image = cv2.imdecode(buffer, cv2.IMREAD_COLOR)

    # Convert back to PIL
    jpeg_pil = Image.fromarray(cv2.cvtColor(jpeg_image, cv2.COLOR_BGR2RGB))
    augmented_images.append((
        jpeg_pil,
        {'scale_x': 1.0, 'scale_y': 1.0, 'translate_x': 0, 'translate_y': 0},
        'jpeg_artifacts'
    ))

    # 5. Brightness and contrast adjustment
    bright_contrast_image = cv_image.copy()

    # Adjust brightness
    brightness = random.uniform(0.8, 1.2)
    bright_contrast_image = cv2.convertScaleAbs(bright_contrast_image, alpha=brightness, beta=0)

    # Adjust contrast
    contrast = random.uniform(0.8, 1.2)
    mean = np.mean(bright_contrast_image)
    bright_contrast_image = cv2.convertScaleAbs(bright_contrast_image, alpha=contrast, beta=(1.0 - contrast) * mean)

    # Convert back to PIL
    bright_contrast_pil = Image.fromarray(cv2.cvtColor(bright_contrast_image, cv2.COLOR_BGR2RGB))
    augmented_images.append((
        bright_contrast_pil,
        {'scale_x': 1.0, 'scale_y': 1.0, 'translate_x': 0, 'translate_y': 0},
        'bright_contrast'
    ))

    return augmented_images

def apply_realistic_augmentations(image):
    """
    Apply realistic augmentations that simulate real-world conditions

    Args:
        image: PIL.Image to augment

    Returns:
        list: List of (augmented_image, transform_params, augmentation_name) tuples
    """
    augmented_images = []

    # Convert PIL image to numpy array for OpenCV
    np_image = np.array(image)
    cv_image = cv2.cvtColor(np_image, cv2.COLOR_RGB2BGR)

    # 1. Simulated glare
    glare_image = cv_image.copy()
    h, w = glare_image.shape[:2]

    # Create a random glare spot
    center_x = random.randint(w // 4, 3 * w // 4)
    center_y = random.randint(h // 4, 3 * h // 4)
    radius = random.randint(min(w, h) // 10, min(w, h) // 5)

    # Create a mask for the glare
    mask = np.zeros((h, w), dtype=np.uint8)
    cv2.circle(mask, (center_x, center_y), radius, 255, -1)
    mask = cv2.GaussianBlur(mask, (radius // 2 * 2 + 1, radius // 2 * 2 + 1), 0)

    # Apply the glare
    glare_image = cv2.addWeighted(glare_image, 1, cv2.merge([mask, mask, mask]), 0.5, 0)

    # Convert back to PIL
    glare_pil = Image.fromarray(cv2.cvtColor(glare_image, cv2.COLOR_BGR2RGB))
    augmented_images.append((
        glare_pil,
        {'scale_x': 1.0, 'scale_y': 1.0, 'translate_x': 0, 'translate_y': 0},
        'glare'
    ))

    # 2. Simulated screen capture with moiré pattern
    moire_image = cv_image.copy()
    h, w = moire_image.shape[:2]

    # Create a grid pattern
    grid = np.zeros((h, w), dtype=np.uint8)
    grid_size = random.randint(3, 7)
    for i in range(0, h, grid_size):
        grid[i:i+1, :] = 255
    for j in range(0, w, grid_size):
        grid[:, j:j+1] = 255

    # Apply the grid with low opacity
    moire_image = cv2.addWeighted(moire_image, 0.9, cv2.merge([grid, grid, grid]), 0.1, 0)

    # Add slight blur to simulate screen capture
    moire_image = cv2.GaussianBlur(moire_image, (3, 3), 0)

    # Convert back to PIL
    moire_pil = Image.fromarray(cv2.cvtColor(moire_image, cv2.COLOR_BGR2RGB))
    augmented_images.append((
        moire_pil,
        {'scale_x': 1.0, 'scale_y': 1.0, 'translate_x': 0, 'translate_y': 0},
        'moire'
    ))

    # 3. Simulated poor lighting
    dark_image = cv_image.copy()

    # Create a vignette effect
    h, w = dark_image.shape[:2]
    mask = np.zeros((h, w), dtype=np.float32)

    # Create a radial gradient
    center_x, center_y = w // 2, h // 2
    for y in range(h):
        for x in range(w):
            # Calculate distance from center (normalized)
            distance = np.sqrt((x - center_x) ** 2 + (y - center_y) ** 2) / (np.sqrt(w * w + h * h) / 2)
            # Apply radial darkening
            mask[y, x] = 1 - min(1, distance * random.uniform(0.8, 1.2))

    # Apply the mask to each channel
    for i in range(3):
        dark_image[:, :, i] = dark_image[:, :, i] * mask

    # Convert to uint8
    dark_image = dark_image.astype(np.uint8)

    # Convert back to PIL
    dark_pil = Image.fromarray(cv2.cvtColor(dark_image, cv2.COLOR_BGR2RGB))
    augmented_images.append((
        dark_pil,
        {'scale_x': 1.0, 'scale_y': 1.0, 'translate_x': 0, 'translate_y': 0},
        'poor_lighting'
    ))

    # 4. Simulated crease or fold
    crease_image = cv_image.copy()
    h, w = crease_image.shape[:2]

    # Decide on horizontal or vertical crease
    if random.choice([True, False]):
        # Horizontal crease
        y = random.randint(h // 4, 3 * h // 4)
        thickness = random.randint(1, 3)

        # Create a line with shadow effect
        cv2.line(crease_image, (0, y), (w, y), (150, 150, 150), thickness)
        cv2.line(crease_image, (0, y+thickness), (w, y+thickness), (200, 200, 200), thickness)
    else:
        # Vertical crease
        x = random.randint(w // 4, 3 * w // 4)
        thickness = random.randint(1, 3)

        # Create a line with shadow effect
        cv2.line(crease_image, (x, 0), (x, h), (150, 150, 150), thickness)
        cv2.line(crease_image, (x+thickness, 0), (x+thickness, h), (200, 200, 200), thickness)

    # Convert back to PIL
    crease_pil = Image.fromarray(cv2.cvtColor(crease_image, cv2.COLOR_BGR2RGB))
    augmented_images.append((
        crease_pil,
        {'scale_x': 1.0, 'scale_y': 1.0, 'translate_x': 0, 'translate_y': 0},
        'crease'
    ))

    return augmented_images

def augment_image(image_path, annotation_path, output_dir, annotation_dir, augmentation_types):
    """
    Augment an image and its annotation

    Args:
        image_path: Path to the image
        annotation_path: Path to the annotation file
        output_dir: Directory to save augmented images
        annotation_dir: Directory to save augmented annotations
        augmentation_types: List of augmentation types to apply

    Returns:
        int: Number of augmentations created
    """
    # Load image
    image = load_image(image_path)
    if image is None:
        return 0

    # Load annotation
    try:
        with open(annotation_path, 'r') as f:
            annotation = json.load(f)
    except Exception as e:
        print(f"Error loading annotation {annotation_path}: {e}")
        return 0

    # Get base filename without extension
    base_name = os.path.splitext(os.path.basename(image_path))[0]

    # Apply augmentations
    augmented_images = []

    if 'basic' in augmentation_types:
        augmented_images.extend(apply_basic_augmentations(image))

    if 'advanced' in augmentation_types:
        augmented_images.extend(apply_advanced_augmentations(image))

    if 'realistic' in augmentation_types:
        augmented_images.extend(apply_realistic_augmentations(image))

    # Save augmented images and annotations
    count = 0
    for aug_image, transform_params, aug_name in augmented_images:
        # Create output paths
        output_image_path = os.path.join(output_dir, f"{base_name}_aug_{aug_name}.jpg")
        output_annotation_path = os.path.join(annotation_dir, f"{base_name}_aug_{aug_name}_annotations.json")

        # Adjust annotation
        adjusted_annotation = adjust_annotation(annotation, transform_params)

        # Save image and annotation
        if save_image(aug_image, output_image_path):
            try:
                with open(output_annotation_path, 'w') as f:
                    json.dump(adjusted_annotation, f, indent=2)
                count += 1
            except Exception as e:
                print(f"Error saving annotation {output_annotation_path}: {e}")

    return count

def main():
    parser = argparse.ArgumentParser(description="Enhanced data augmentation for coupon images")
    parser.add_argument("--input-dir", required=True, help="Directory containing input images")
    parser.add_argument("--annotation-dir", required=True, help="Directory containing annotation files")
    parser.add_argument("--output-dir", required=True, help="Directory to save augmented images")
    parser.add_argument("--output-annotation-dir", required=True, help="Directory to save augmented annotations")
    parser.add_argument("--augmentation-types", nargs='+', default=['basic', 'advanced', 'realistic'],
                        choices=['basic', 'advanced', 'realistic'], help="Types of augmentations to apply")
    parser.add_argument("--include-outliers", action='store_true', help="Include outlier images in augmentation")
    parser.add_argument("--outlier-weight", type=float, default=0.5, help="Weight for outlier augmentations (0-1)")

    args = parser.parse_args()

    # Create output directories if they don't exist
    os.makedirs(args.output_dir, exist_ok=True)
    os.makedirs(args.output_annotation_dir, exist_ok=True)

    # Get list of image files
    image_files = []
    for root, _, files in os.walk(args.input_dir):
        for file in files:
            if file.lower().endswith(('.jpg', '.jpeg', '.png')):
                image_files.append(os.path.join(root, file))

    if not image_files:
        print(f"No image files found in {args.input_dir}")
        return

    print(f"Found {len(image_files)} image files")

    # Identify outliers (for demonstration, we'll consider any image with "synthetic" in the name as an outlier)
    outliers = [f for f in image_files if "synthetic" in os.path.basename(f).lower()]
    normal_images = [f for f in image_files if f not in outliers]

    print(f"Identified {len(outliers)} outlier images and {len(normal_images)} normal images")

    # Process normal images
    total_augmentations = 0

    print("Augmenting normal images...")
    for image_path in tqdm(normal_images):
        # Get corresponding annotation path
        base_name = os.path.splitext(os.path.basename(image_path))[0]
        annotation_path = os.path.join(args.annotation_dir, f"{base_name}_annotations.json")

        # Skip if annotation doesn't exist
        if not os.path.exists(annotation_path):
            print(f"Annotation not found for {image_path}, skipping")
            continue

        # Augment image
        count = augment_image(
            image_path,
            annotation_path,
            args.output_dir,
            args.output_annotation_dir,
            args.augmentation_types
        )

        total_augmentations += count

    # Process outliers if requested
    if args.include_outliers and outliers:
        print("Augmenting outlier images...")

        # Determine how many augmentations to create for each outlier
        # based on the outlier weight
        normal_aug_count = total_augmentations
        target_outlier_count = int(normal_aug_count * args.outlier_weight / (1 - args.outlier_weight))
        augs_per_outlier = max(1, target_outlier_count // len(outliers))

        print(f"Creating approximately {augs_per_outlier} augmentations per outlier")

        outlier_augmentations = 0
        for image_path in tqdm(outliers):
            # Get corresponding annotation path
            base_name = os.path.splitext(os.path.basename(image_path))[0]
            annotation_path = os.path.join(args.annotation_dir, f"{base_name}_annotations.json")

            # Skip if annotation doesn't exist
            if not os.path.exists(annotation_path):
                print(f"Annotation not found for {image_path}, skipping")
                continue

            # Limit augmentation types for outliers to control their influence
            limited_aug_types = args.augmentation_types[:min(2, len(args.augmentation_types))]

            # Augment image
            count = augment_image(
                image_path,
                annotation_path,
                args.output_dir,
                args.output_annotation_dir,
                limited_aug_types
            )

            outlier_augmentations += count

        total_augmentations += outlier_augmentations
        print(f"Created {outlier_augmentations} augmentations from outlier images")

    print(f"Successfully created {total_augmentations} augmented images")

if __name__ == "__main__":
    main()
