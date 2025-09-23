#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Simple data augmentation for coupon images.
This script implements basic domain-specific augmentations to improve model robustness.
"""

import os
import sys
import argparse
import cv2
import numpy as np
import random
from PIL import Image, ImageDraw, ImageFilter, ImageEnhance
import json

def apply_shadow(image, max_shadow_size=0.4):
    """Apply random shadow to the image"""
    width, height = image.size
    shadow_img = image.copy()
    draw = ImageDraw.Draw(shadow_img, 'RGBA')
    
    # Random shadow position and size
    shadow_width = int(width * random.uniform(0.1, max_shadow_size))
    shadow_height = int(height * random.uniform(0.1, max_shadow_size))
    
    x = random.randint(0, width - shadow_width)
    y = random.randint(0, height - shadow_height)
    
    # Random shadow opacity
    opacity = random.randint(40, 100)
    
    # Draw shadow
    draw.rectangle([x, y, x + shadow_width, y + shadow_height], 
                  fill=(0, 0, 0, opacity))
    
    # Blend shadow with original image
    return Image.blend(image, shadow_img, 0.5)

def apply_glare(image, max_glare_size=0.3):
    """Apply random glare spot to the image"""
    width, height = image.size
    glare_img = image.copy()
    draw = ImageDraw.Draw(glare_img, 'RGBA')
    
    # Random glare position and size
    glare_size = int(min(width, height) * random.uniform(0.05, max_glare_size))
    
    x = random.randint(0, width - glare_size)
    y = random.randint(0, height - glare_size)
    
    # Random glare opacity
    opacity = random.randint(100, 200)
    
    # Draw glare (ellipse)
    draw.ellipse([x, y, x + glare_size, y + glare_size], 
                 fill=(255, 255, 255, opacity))
    
    # Apply blur to the glare
    glare_img = glare_img.filter(ImageFilter.GaussianBlur(radius=3))
    
    # Blend glare with original image
    return Image.blend(image, glare_img, 0.7)

def apply_perspective(image, annotation=None):
    """Apply random perspective transformation to simulate phone camera angles"""
    width, height = image.size
    
    # Convert to OpenCV format
    img_cv = np.array(image)
    img_cv = cv2.cvtColor(img_cv, cv2.COLOR_RGB2BGR)
    
    # Define perspective transformation points
    # Source points (original image corners)
    src_points = np.float32([
        [0, 0],  # Top-left
        [width, 0],  # Top-right
        [0, height],  # Bottom-left
        [width, height]  # Bottom-right
    ])
    
    # Define random distortion range
    distortion_range = min(width, height) * 0.15
    
    # Target points (distorted corners)
    dst_points = np.float32([
        [random.uniform(0, distortion_range), random.uniform(0, distortion_range)],  # Top-left
        [width - random.uniform(0, distortion_range), random.uniform(0, distortion_range)],  # Top-right
        [random.uniform(0, distortion_range), height - random.uniform(0, distortion_range)],  # Bottom-left
        [width - random.uniform(0, distortion_range), height - random.uniform(0, distortion_range)]  # Bottom-right
    ])
    
    # Calculate perspective transform matrix
    transform_matrix = cv2.getPerspectiveTransform(src_points, dst_points)
    
    # Apply transformation to image
    transformed_img = cv2.warpPerspective(img_cv, transform_matrix, (width, height))
    transformed_img = cv2.cvtColor(transformed_img, cv2.COLOR_BGR2RGB)
    transformed_img = Image.fromarray(transformed_img)
    
    # Transform annotation if provided
    transformed_annotation = None
    if annotation:
        transformed_annotation = annotation.copy()
        
        # Transform each region in the annotation
        for region_type, regions in annotation.items():
            transformed_regions = []
            
            for region in regions:
                # Get region coordinates
                left = region.get('left', 0)
                top = region.get('top', 0)
                right = region.get('right', 0)
                bottom = region.get('bottom', 0)
                
                # Transform the four corners of the region
                corners = np.float32([
                    [left, top],
                    [right, top],
                    [left, bottom],
                    [right, bottom]
                ])
                
                # Apply perspective transform to each corner
                transformed_corners = cv2.perspectiveTransform(corners.reshape(-1, 1, 2), transform_matrix)
                transformed_corners = transformed_corners.reshape(-1, 2)
                
                # Get new bounding box
                new_left = max(0, int(min(transformed_corners[:, 0])))
                new_top = max(0, int(min(transformed_corners[:, 1])))
                new_right = min(width, int(max(transformed_corners[:, 0])))
                new_bottom = min(height, int(max(transformed_corners[:, 1])))
                
                # Add transformed region
                transformed_regions.append({
                    'left': new_left,
                    'top': new_top,
                    'right': new_right,
                    'bottom': new_bottom
                })
            
            transformed_annotation[region_type] = transformed_regions
    
    return transformed_img, transformed_annotation

def apply_lighting(image, brightness_range=(0.7, 1.3), contrast_range=(0.7, 1.3)):
    """Apply random lighting conditions"""
    # Random brightness
    brightness_factor = random.uniform(brightness_range[0], brightness_range[1])
    brightness_enhancer = ImageEnhance.Brightness(image)
    image = brightness_enhancer.enhance(brightness_factor)
    
    # Random contrast
    contrast_factor = random.uniform(contrast_range[0], contrast_range[1])
    contrast_enhancer = ImageEnhance.Contrast(image)
    image = contrast_enhancer.enhance(contrast_factor)
    
    return image

def augment_image(image_path, annotation_path=None, output_dir=None, output_prefix=None):
    """Apply augmentation to an image"""
    # Load image
    try:
        image = Image.open(image_path).convert('RGB')
    except Exception as e:
        print(f"Error loading image {image_path}: {e}")
        return []
    
    # Load annotation if available
    annotation = None
    if annotation_path and os.path.exists(annotation_path):
        try:
            with open(annotation_path, 'r') as f:
                annotation = json.load(f)
        except Exception as e:
            print(f"Error loading annotation {annotation_path}: {e}")
    
    # Set output directory
    if not output_dir:
        output_dir = os.path.dirname(image_path)
    os.makedirs(output_dir, exist_ok=True)
    
    # Create output annotation directory if needed
    output_annotation_dir = os.path.join(output_dir, 'annotations')
    if annotation:
        os.makedirs(output_annotation_dir, exist_ok=True)
    
    # Generate output prefix if not provided
    if not output_prefix:
        base_name = os.path.splitext(os.path.basename(image_path))[0]
        output_prefix = f"{base_name}_aug"
    
    # List to store paths to augmented images
    augmented_paths = []
    
    # Apply different augmentations
    augmentations = [
        # Shadow effect
        {
            'name': 'shadow',
            'func': lambda img, ann: (apply_shadow(img), ann)
        },
        # Glare effect
        {
            'name': 'glare',
            'func': lambda img, ann: (apply_glare(img), ann)
        },
        # Perspective transformation
        {
            'name': 'perspective',
            'func': lambda img, ann: apply_perspective(img, ann)
        },
        # Lighting conditions
        {
            'name': 'lighting',
            'func': lambda img, ann: (apply_lighting(img), ann)
        }
    ]
    
    # Apply each augmentation
    for aug in augmentations:
        try:
            # Apply augmentation
            aug_image, aug_annotation = aug['func'](image, annotation)
            
            # Save augmented image
            output_image_path = os.path.join(
                output_dir, 
                f"{output_prefix}_{aug['name']}.jpg"
            )
            aug_image.save(output_image_path, quality=95)
            augmented_paths.append(output_image_path)
            
            # Save annotation if available
            if aug_annotation and annotation:
                output_annotation_path = os.path.join(
                    output_annotation_dir,
                    f"{output_prefix}_{aug['name']}_annotations.json"
                )
                with open(output_annotation_path, 'w') as f:
                    json.dump(aug_annotation, f, indent=2)
        
        except Exception as e:
            print(f"Error applying {aug['name']} augmentation: {e}")
    
    return augmented_paths

def main():
    parser = argparse.ArgumentParser(description="Apply simple augmentation to coupon images")
    parser.add_argument("--image", required=True, help="Path to the input image")
    parser.add_argument("--annotation", help="Path to the annotation file (optional)")
    parser.add_argument("--output-dir", default="data/augmented", help="Directory to save augmented images")
    
    args = parser.parse_args()
    
    # Create output directory if it doesn't exist
    os.makedirs(args.output_dir, exist_ok=True)
    
    # Apply augmentation
    augmented_paths = augment_image(args.image, args.annotation, args.output_dir)
    
    print(f"Created {len(augmented_paths)} augmented images:")
    for path in augmented_paths:
        print(f"  {path}")

if __name__ == "__main__":
    main()
