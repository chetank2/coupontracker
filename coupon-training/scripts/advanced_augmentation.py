#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Advanced augmentation techniques for coupon images.
This script implements domain-specific augmentations to improve model robustness.
"""

import os
import sys
import argparse
import cv2
import numpy as np
import random
from tqdm import tqdm
import json
import shutil
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageEnhance

# Add parent directory to path
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

class AdvancedAugmentation:
    """
    Advanced augmentation techniques for coupon images
    """
    
    def __init__(self, input_dir, output_dir, annotation_dir=None):
        """
        Initialize the augmentation
        
        Args:
            input_dir: Directory containing input images
            output_dir: Directory to save augmented images
            annotation_dir: Directory containing annotation files (optional)
        """
        self.input_dir = input_dir
        self.output_dir = output_dir
        self.annotation_dir = annotation_dir
        
        # Create output directory if it doesn't exist
        os.makedirs(output_dir, exist_ok=True)
        
        # Create output annotation directory if needed
        if annotation_dir:
            self.output_annotation_dir = os.path.join(output_dir, 'annotations')
            os.makedirs(self.output_annotation_dir, exist_ok=True)
        else:
            self.output_annotation_dir = None
    
    def apply_shadow(self, image, max_shadow_size=0.4, count=3):
        """
        Apply random shadows to the image
        
        Args:
            image: PIL Image
            max_shadow_size: Maximum shadow size as a fraction of image size
            count: Number of shadows to apply
            
        Returns:
            PIL Image with shadows
        """
        width, height = image.size
        shadow_img = image.copy()
        draw = ImageDraw.Draw(shadow_img, 'RGBA')
        
        for _ in range(count):
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
    
    def apply_glare(self, image, max_glare_size=0.3, count=2):
        """
        Apply random glare spots to the image
        
        Args:
            image: PIL Image
            max_glare_size: Maximum glare size as a fraction of image size
            count: Number of glare spots to apply
            
        Returns:
            PIL Image with glare
        """
        width, height = image.size
        glare_img = image.copy()
        draw = ImageDraw.Draw(glare_img, 'RGBA')
        
        for _ in range(count):
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
    
    def apply_crumple(self, image, intensity=0.3):
        """
        Apply crumpled paper effect to the image
        
        Args:
            image: PIL Image
            intensity: Intensity of the crumple effect
            
        Returns:
            PIL Image with crumpled effect
        """
        # Convert to OpenCV format
        img_cv = np.array(image)
        img_cv = cv2.cvtColor(img_cv, cv2.COLOR_RGB2BGR)
        
        # Create displacement map
        height, width = img_cv.shape[:2]
        displacement_map = np.zeros((height, width, 2), dtype=np.float32)
        
        # Generate random displacement
        for y in range(height):
            for x in range(width):
                displacement_map[y, x, 0] = (random.random() - 0.5) * 2 * intensity * 10
                displacement_map[y, x, 1] = (random.random() - 0.5) * 2 * intensity * 10
        
        # Apply displacement map
        displacement_map = cv2.GaussianBlur(displacement_map, (25, 25), 0)
        
        # Create grid for remapping
        grid_x, grid_y = np.meshgrid(np.arange(width), np.arange(height))
        map_x = (grid_x + displacement_map[:, :, 0]).astype(np.float32)
        map_y = (grid_y + displacement_map[:, :, 1]).astype(np.float32)
        
        # Apply remapping
        crumpled = cv2.remap(img_cv, map_x, map_y, cv2.INTER_LINEAR)
        
        # Convert back to PIL
        crumpled = cv2.cvtColor(crumpled, cv2.COLOR_BGR2RGB)
        return Image.fromarray(crumpled)
    
    def apply_perspective(self, image, annotation=None):
        """
        Apply random perspective transformation to simulate phone camera angles
        
        Args:
            image: PIL Image
            annotation: Annotation data (optional)
            
        Returns:
            Tuple of (transformed image, transformed annotation)
        """
        width, height = image.size
        
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
        img_cv = np.array(image)
        img_cv = cv2.cvtColor(img_cv, cv2.COLOR_RGB2BGR)
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
    
    def apply_lighting(self, image, brightness_range=(0.7, 1.3), contrast_range=(0.7, 1.3)):
        """
        Apply random lighting conditions
        
        Args:
            image: PIL Image
            brightness_range: Range for brightness adjustment
            contrast_range: Range for contrast adjustment
            
        Returns:
            PIL Image with adjusted lighting
        """
        # Random brightness
        brightness_factor = random.uniform(brightness_range[0], brightness_range[1])
        brightness_enhancer = ImageEnhance.Brightness(image)
        image = brightness_enhancer.enhance(brightness_factor)
        
        # Random contrast
        contrast_factor = random.uniform(contrast_range[0], contrast_range[1])
        contrast_enhancer = ImageEnhance.Contrast(image)
        image = contrast_enhancer.enhance(contrast_factor)
        
        return image
    
    def generate_synthetic_coupon(self, width=720, height=1600, elements=None):
        """
        Generate a synthetic coupon image
        
        Args:
            width: Image width
            height: Image height
            elements: Dictionary of elements to include (store, code, amount, expiry, description)
            
        Returns:
            Tuple of (synthetic image, annotation)
        """
        # Create blank image with random background color
        bg_color = (
            random.randint(230, 255),
            random.randint(230, 255),
            random.randint(230, 255)
        )
        image = Image.new('RGB', (width, height), bg_color)
        draw = ImageDraw.Draw(image)
        
        # Add some background texture
        for _ in range(100):
            x = random.randint(0, width)
            y = random.randint(0, height)
            radius = random.randint(1, 5)
            color = (
                bg_color[0] - random.randint(0, 20),
                bg_color[1] - random.randint(0, 20),
                bg_color[2] - random.randint(0, 20)
            )
            draw.ellipse([x-radius, y-radius, x+radius, y+radius], fill=color)
        
        # Initialize annotation
        annotation = {
            'store': [],
            'code': [],
            'amount': [],
            'expiry': [],
            'description': []
        }
        
        # Default elements if none provided
        if not elements:
            elements = {
                'store': 'Sample Store',
                'code': 'COUPON123',
                'amount': '50% OFF',
                'expiry': '31/12/2023',
                'description': 'Get 50% off on all products'
            }
        
        # Try to load fonts
        try:
            store_font = ImageFont.truetype('Arial Bold.ttf', 40)
            code_font = ImageFont.truetype('Courier New.ttf', 36)
            normal_font = ImageFont.truetype('Arial.ttf', 24)
        except:
            # Fallback to default font
            store_font = ImageFont.load_default()
            code_font = ImageFont.load_default()
            normal_font = ImageFont.load_default()
        
        # Add store name at the top
        if 'store' in elements:
            store_text = elements['store']
            text_width = draw.textlength(store_text, font=store_font)
            x = (width - text_width) // 2
            y = height // 10
            draw.text((x, y), store_text, font=store_font, fill=(30, 30, 30))
            
            # Add to annotation
            annotation['store'].append({
                'left': int(x),
                'top': int(y),
                'right': int(x + text_width),
                'bottom': int(y + 50)
            })
        
        # Add coupon code
        if 'code' in elements:
            code_text = elements['code']
            text_width = draw.textlength(code_text, font=code_font)
            x = (width - text_width) // 2
            y = height // 2
            
            # Draw a box around the code
            box_padding = 20
            draw.rectangle([
                x - box_padding, 
                y - box_padding, 
                x + text_width + box_padding, 
                y + 40 + box_padding
            ], outline=(100, 100, 100), width=2)
            
            draw.text((x, y), code_text, font=code_font, fill=(30, 30, 30))
            
            # Add to annotation
            annotation['code'].append({
                'left': int(x - box_padding),
                'top': int(y - box_padding),
                'right': int(x + text_width + box_padding),
                'bottom': int(y + 40 + box_padding)
            })
        
        # Add amount/discount
        if 'amount' in elements:
            amount_text = elements['amount']
            text_width = draw.textlength(amount_text, font=store_font)
            x = (width - text_width) // 2
            y = height // 4
            
            # Use a highlight color for the amount
            draw.text((x, y), amount_text, font=store_font, fill=(200, 50, 50))
            
            # Add to annotation
            annotation['amount'].append({
                'left': int(x),
                'top': int(y),
                'right': int(x + text_width),
                'bottom': int(y + 50)
            })
        
        # Add description
        if 'description' in elements:
            desc_text = elements['description']
            text_width = draw.textlength(desc_text, font=normal_font)
            x = (width - text_width) // 2
            y = height // 3
            draw.text((x, y), desc_text, font=normal_font, fill=(50, 50, 50))
            
            # Add to annotation
            annotation['description'].append({
                'left': int(x),
                'top': int(y),
                'right': int(x + text_width),
                'bottom': int(y + 30)
            })
        
        # Add expiry date
        if 'expiry' in elements:
            expiry_text = f"Valid till: {elements['expiry']}"
            text_width = draw.textlength(expiry_text, font=normal_font)
            x = (width - text_width) // 2
            y = height * 3 // 4
            draw.text((x, y), expiry_text, font=normal_font, fill=(100, 100, 100))
            
            # Add to annotation
            annotation['expiry'].append({
                'left': int(x),
                'top': int(y),
                'right': int(x + text_width),
                'bottom': int(y + 30)
            })
        
        return image, annotation
    
    def augment_image(self, image_path, annotation_path=None, output_prefix=None):
        """
        Apply augmentation to an image
        
        Args:
            image_path: Path to the input image
            annotation_path: Path to the annotation file (optional)
            output_prefix: Prefix for output files
            
        Returns:
            List of paths to augmented images
        """
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
                'func': lambda img, ann: (self.apply_shadow(img), ann)
            },
            # Glare effect
            {
                'name': 'glare',
                'func': lambda img, ann: (self.apply_glare(img), ann)
            },
            # Crumpled paper effect
            {
                'name': 'crumple',
                'func': lambda img, ann: (self.apply_crumple(img), ann)
            },
            # Perspective transformation
            {
                'name': 'perspective',
                'func': lambda img, ann: self.apply_perspective(img, ann)
            },
            # Lighting conditions
            {
                'name': 'lighting',
                'func': lambda img, ann: (self.apply_lighting(img), ann)
            },
            # Combined effects
            {
                'name': 'combined1',
                'func': lambda img, ann: (
                    self.apply_lighting(
                        self.apply_shadow(img)
                    ), 
                    ann
                )
            },
            {
                'name': 'combined2',
                'func': lambda img, ann: (
                    self.apply_glare(
                        self.apply_perspective(img, ann)[0]
                    ), 
                    self.apply_perspective(img, ann)[1]
                )
            }
        ]
        
        # Apply each augmentation
        for aug in augmentations:
            try:
                # Apply augmentation
                aug_image, aug_annotation = aug['func'](image, annotation)
                
                # Save augmented image
                output_image_path = os.path.join(
                    self.output_dir, 
                    f"{output_prefix}_{aug['name']}.jpg"
                )
                aug_image.save(output_image_path, quality=95)
                augmented_paths.append(output_image_path)
                
                # Save annotation if available
                if aug_annotation and self.output_annotation_dir:
                    output_annotation_path = os.path.join(
                        self.output_annotation_dir,
                        f"{output_prefix}_{aug['name']}_annotations.json"
                    )
                    with open(output_annotation_path, 'w') as f:
                        json.dump(aug_annotation, f, indent=2)
            
            except Exception as e:
                print(f"Error applying {aug['name']} augmentation: {e}")
        
        return augmented_paths
    
    def generate_synthetic_dataset(self, count=10):
        """
        Generate a synthetic dataset of coupon images
        
        Args:
            count: Number of synthetic images to generate
            
        Returns:
            List of paths to generated images
        """
        generated_paths = []
        
        # Sample store names
        store_names = [
            "SuperMart", "FashionHub", "TechZone", "FoodDelight",
            "SportsPro", "HomeEssentials", "BeautySpot", "BookHaven",
            "GadgetWorld", "HealthPlus"
        ]
        
        # Sample coupon codes
        coupon_codes = [
            "SAVE50", "EXTRA20", "WELCOME10", "FLASH30",
            "SPECIAL25", "NEWUSER15", "HOLIDAY40", "WEEKEND35",
            "SUMMER20", "WINTER30"
        ]
        
        # Sample amounts/discounts
        amounts = [
            "50% OFF", "₹500 OFF", "30% CASHBACK", "BUY 1 GET 1",
            "FLAT 40% OFF", "UPTO 60% OFF", "₹1000 OFF", "25% DISCOUNT",
            "70% OFF", "EXTRA 15% OFF"
        ]
        
        # Sample descriptions
        descriptions = [
            "Valid on all products", "Minimum purchase ₹1000",
            "Not valid on sale items", "First time users only",
            "Limited time offer", "Terms and conditions apply",
            "Valid for online purchases", "One coupon per user",
            "Cannot be combined with other offers", "Selected items only"
        ]
        
        # Sample expiry dates
        expiry_dates = [
            "31/12/2023", "15/01/2024", "28/02/2024", "31/03/2024",
            "30/04/2024", "31/05/2024", "30/06/2024", "31/07/2024",
            "31/08/2024", "30/09/2024"
        ]
        
        for i in range(count):
            # Generate random elements
            elements = {
                'store': random.choice(store_names),
                'code': random.choice(coupon_codes),
                'amount': random.choice(amounts),
                'description': random.choice(descriptions),
                'expiry': random.choice(expiry_dates)
            }
            
            # Generate synthetic coupon
            image, annotation = self.generate_synthetic_coupon(elements=elements)
            
            # Save image
            output_image_path = os.path.join(
                self.output_dir, 
                f"synthetic_coupon_{i+1}.jpg"
            )
            image.save(output_image_path, quality=95)
            generated_paths.append(output_image_path)
            
            # Save annotation
            if self.output_annotation_dir:
                output_annotation_path = os.path.join(
                    self.output_annotation_dir,
                    f"synthetic_coupon_{i+1}_annotations.json"
                )
                with open(output_annotation_path, 'w') as f:
                    json.dump(annotation, f, indent=2)
            
            # Apply augmentations to the synthetic image
            self.augment_image(
                output_image_path, 
                output_annotation_path if self.output_annotation_dir else None,
                f"synthetic_coupon_{i+1}"
            )
        
        return generated_paths
    
    def process_directory(self):
        """
        Process all images in the input directory
        
        Returns:
            Number of processed images
        """
        # Get list of image files
        image_files = [f for f in os.listdir(self.input_dir) 
                      if f.lower().endswith(('.jpg', '.jpeg', '.png'))]
        
        if not image_files:
            print(f"No image files found in {self.input_dir}")
            return 0
        
        print(f"Found {len(image_files)} images to process")
        
        # Process each image
        for image_file in tqdm(image_files, desc="Augmenting images"):
            image_path = os.path.join(self.input_dir, image_file)
            
            # Check for annotation file
            base_name = os.path.splitext(image_file)[0]
            annotation_path = None
            
            if self.annotation_dir:
                # Try different annotation file patterns
                for ext in ['_annotations.json', '_processed_annotations.json', '.json']:
                    potential_path = os.path.join(self.annotation_dir, f"{base_name}{ext}")
                    if os.path.exists(potential_path):
                        annotation_path = potential_path
                        break
            
            # Apply augmentation
            self.augment_image(image_path, annotation_path, base_name)
        
        return len(image_files)

def main():
    parser = argparse.ArgumentParser(description="Apply advanced augmentation to coupon images")
    parser.add_argument("--input-dir", default="../data/raw", help="Directory containing input images")
    parser.add_argument("--output-dir", default="../data/augmented", help="Directory to save augmented images")
    parser.add_argument("--annotation-dir", default="../data/annotated", help="Directory containing annotation files")
    parser.add_argument("--synthetic", action="store_true", help="Generate synthetic coupon images")
    parser.add_argument("--synthetic-count", type=int, default=10, help="Number of synthetic images to generate")
    
    args = parser.parse_args()
    
    # Create augmentation object
    augmentation = AdvancedAugmentation(
        args.input_dir, 
        args.output_dir, 
        args.annotation_dir
    )
    
    # Generate synthetic dataset if requested
    if args.synthetic:
        print(f"Generating {args.synthetic_count} synthetic coupon images")
        generated_paths = augmentation.generate_synthetic_dataset(args.synthetic_count)
        print(f"Generated {len(generated_paths)} synthetic images")
    
    # Process real images
    processed_count = augmentation.process_directory()
    print(f"Processed {processed_count} real images")

if __name__ == "__main__":
    main()
