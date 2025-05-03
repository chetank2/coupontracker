#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Download and process coupon images from Reddit for training.
This script downloads coupon images from specified URLs and prepares them for training.
"""

import os
import sys
import argparse
import requests
import json
from PIL import Image
from io import BytesIO
import time
import random
from tqdm import tqdm

# Add parent directory to path
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# List of Reddit coupon image URLs
COUPON_URLS = [
    # Citizen watch coupons
    "https://preview.redd.it/citizen-cred-coupon-v0-oz9bi3gs3a9e1.jpeg",
    "https://preview.redd.it/cred-is-giving-40-off-coupons-on-these-citizen-watches-v0-qi5smhgtjybd1.jpg",
    "https://preview.redd.it/new-collection-added-in-cred-x-citizen-40-off-coupon-v0-vr21qwf35v0e1.jpg",
    "https://preview.redd.it/citizen-cred-coupons-v0-glwg9pac7c9e1.jpg",
    
    # Myntra coupon
    "https://preview.redd.it/can-anyone-please-pm-me-this-myntra-coupon-from-cred-app-if-v0-71q0bcz1iz6e1.jpeg",
    
    # Swiggy coupon
    "https://preview.redd.it/coupon-credswiggyupi-v0-ers2etec5nre1.png",
    
    # Beauty product coupons
    "https://preview.redd.it/renee-perfumes-at-210-on-cred-app-v0-151o8yg3y8me1.jpg",
    "https://preview.redd.it/dove-body-scrub-at-347-on-zepto-cred-coupon-v0-7s2pyre25rwe1.jpeg",
    "https://preview.redd.it/pilgrim-buy-3-cred-coupon-v0-8tsytz6rk4gb1.jpg",
    
    # Cred coupon collections
    "https://preview.redd.it/cred-coupons-23-05-2023-v0-dg5ay4qsui1b1.jpg",
    "https://preview.redd.it/cred-discount-coupons-dt-16-04-2022-v0-fs5f626et6ua1.jpg",
    "https://preview.redd.it/cred-coins-rush-coupons-giveaway-v0-amz5h2a6aqge1.jpg",
    "https://preview.redd.it/cred-coupons-giveaway-v0-z2946hekk9he1.jpg",
    
    # Ajio coupon
    "https://preview.redd.it/cred-ajio-coupon-v0-kasr2mwteqqc1.png"
]

def download_image(url, output_path):
    """
    Download an image from a URL and save it to the specified path
    
    Args:
        url: URL of the image
        output_path: Path to save the image
        
    Returns:
        bool: True if download was successful, False otherwise
    """
    try:
        # Add a random user agent to avoid being blocked
        headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36'
        }
        
        # Download the image
        response = requests.get(url, headers=headers, timeout=10)
        response.raise_for_status()
        
        # Open the image to verify it's valid
        img = Image.open(BytesIO(response.content))
        
        # Save the image
        img.save(output_path)
        
        return True
    
    except Exception as e:
        print(f"Error downloading {url}: {e}")
        return False

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

def main():
    parser = argparse.ArgumentParser(description="Download and process coupon images from Reddit")
    parser.add_argument("--output-dir", default="../data/raw", help="Directory to save downloaded images")
    parser.add_argument("--annotation-dir", default="../data/annotated", help="Directory to save annotation files")
    
    args = parser.parse_args()
    
    # Create output directories if they don't exist
    os.makedirs(args.output_dir, exist_ok=True)
    os.makedirs(args.annotation_dir, exist_ok=True)
    
    # Download and process each image
    successful_downloads = 0
    
    for i, url in enumerate(tqdm(COUPON_URLS, desc="Downloading images")):
        # Generate a filename based on the URL
        filename = f"reddit_coupon_{i+1}_{int(time.time())}.jpg"
        output_path = os.path.join(args.output_dir, filename)
        
        # Download the image
        if download_image(url, output_path):
            # Create annotation file
            annotation_path = os.path.join(args.annotation_dir, f"{os.path.splitext(filename)[0]}_annotations.json")
            if create_annotation(output_path, annotation_path):
                successful_downloads += 1
            
            # Add a small delay to avoid rate limiting
            time.sleep(random.uniform(0.5, 1.5))
    
    print(f"Successfully downloaded and processed {successful_downloads} out of {len(COUPON_URLS)} images")

if __name__ == "__main__":
    main()
