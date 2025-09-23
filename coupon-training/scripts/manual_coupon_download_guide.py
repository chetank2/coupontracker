#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Guide for manually downloading coupon images from Reddit.
This script provides instructions for manually downloading coupon images.
"""

import os
import sys
import argparse
import json
from PIL import Image

# Add parent directory to path
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

def create_annotation(image_path, output_path):
    """
    Create a basic annotation file for the image
    
    Args:
        image_path: Path to the image
        output_path: Path to save the annotation file
        
    Returns:
        bool: True if annotation was created successfully, False otherwise
    """
    try:
        # Open the image to get dimensions
        img = Image.open(image_path)
        width, height = img.size
        
        # Create basic annotation with estimated regions
        annotation = {
            'store': [
                {
                    'left': int(width * 0.1),
                    'top': int(height * 0.05),
                    'right': int(width * 0.9),
                    'bottom': int(height * 0.2)
                }
            ],
            'code': [
                {
                    'left': int(width * 0.2),
                    'top': int(height * 0.4),
                    'right': int(width * 0.8),
                    'bottom': int(height * 0.6)
                }
            ],
            'amount': [
                {
                    'left': int(width * 0.3),
                    'top': int(height * 0.25),
                    'right': int(width * 0.7),
                    'bottom': int(height * 0.35)
                }
            ],
            'expiry': [
                {
                    'left': int(width * 0.2),
                    'top': int(height * 0.7),
                    'right': int(width * 0.8),
                    'bottom': int(height * 0.8)
                }
            ],
            'description': [
                {
                    'left': int(width * 0.1),
                    'top': int(height * 0.3),
                    'right': int(width * 0.9),
                    'bottom': int(height * 0.4)
                }
            ]
        }
        
        # Save the annotation
        with open(output_path, 'w') as f:
            json.dump(annotation, f, indent=2)
        
        return True
    
    except Exception as e:
        print(f"Error creating annotation for {image_path}: {e}")
        return False

def process_images(input_dir, annotation_dir):
    """
    Process images in the input directory and create annotation files
    
    Args:
        input_dir: Directory containing input images
        annotation_dir: Directory to save annotation files
        
    Returns:
        int: Number of images processed
    """
    # Create annotation directory if it doesn't exist
    os.makedirs(annotation_dir, exist_ok=True)
    
    # Get list of image files
    image_files = [f for f in os.listdir(input_dir) 
                  if f.lower().endswith(('.jpg', '.jpeg', '.png'))]
    
    if not image_files:
        print(f"No image files found in {input_dir}")
        return 0
    
    print(f"Found {len(image_files)} images to process")
    
    # Process each image
    processed_count = 0
    
    for image_file in image_files:
        image_path = os.path.join(input_dir, image_file)
        
        # Create annotation file
        annotation_path = os.path.join(annotation_dir, f"{os.path.splitext(image_file)[0]}_annotations.json")
        
        if create_annotation(image_path, annotation_path):
            processed_count += 1
            print(f"Created annotation for {image_file}")
    
    return processed_count

def main():
    parser = argparse.ArgumentParser(description="Guide for manually downloading coupon images")
    parser.add_argument("--input-dir", default="../data/raw", help="Directory containing manually downloaded images")
    parser.add_argument("--annotation-dir", default="../data/annotated", help="Directory to save annotation files")
    
    args = parser.parse_args()
    
    # Print instructions
    print("\n" + "="*80)
    print("MANUAL COUPON IMAGE DOWNLOAD GUIDE")
    print("="*80)
    print("\nSince direct downloading from Reddit is restricted, please follow these steps:")
    print("\n1. Visit the Reddit search page for CRED coupons:")
    print("   https://www.reddit.com/search/?q=cred+coupons&type=media")
    print("\n2. For each coupon image:")
    print("   a. Right-click on the image and select 'Save Image As...'")
    print("   b. Save the image to the following directory:")
    print(f"      {os.path.abspath(args.input_dir)}")
    print("\n3. After downloading the images, run this script again to process them:")
    print(f"   python3 {os.path.basename(__file__)}")
    print("\n4. The script will create annotation files for each image in:")
    print(f"   {os.path.abspath(args.annotation_dir)}")
    print("\n5. You can then use these images and annotations for training.")
    print("\nRecommended coupon images to download:")
    print("- Citizen watch coupons")
    print("- Myntra coupons")
    print("- Swiggy coupons")
    print("- Beauty product coupons (Dove, Renee, etc.)")
    print("- CRED coupon collections")
    print("="*80 + "\n")
    
    # Check if input directory exists and has images
    if os.path.exists(args.input_dir):
        # Process images
        processed_count = process_images(args.input_dir, args.annotation_dir)
        
        if processed_count > 0:
            print(f"\nSuccessfully processed {processed_count} images")
            print("\nNext steps:")
            print("1. Review and refine the annotation files if needed")
            print("2. Use these images for training your model")
            print("3. Run the evaluation script to see if the model performance improves")
    else:
        print(f"\nInput directory {args.input_dir} does not exist")
        print("Please create it and download images there first")

if __name__ == "__main__":
    main()
