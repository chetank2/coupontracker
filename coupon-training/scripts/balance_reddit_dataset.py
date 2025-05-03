#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Balance Reddit Coupon Dataset
This script balances the Reddit coupon dataset by adding more examples of underrepresented coupon types.
"""

import os
import sys
import json
import argparse
import shutil
from PIL import Image, ImageDraw, ImageFont
import random
import datetime
import numpy as np

# Add parent directory to path
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# List of Reddit coupon image URLs from download_reddit_coupons.py
COUPON_URLS = [
    # Citizen watch coupons
    "https://preview.redd.it/citizen-cred-coupon-v0-oz9bi3gs3a9e1.jpeg",
    "https://preview.redd.it/cred-is-giving-40-off-coupons-on-these-citizen-watches-v0-qi5smhgtjybd1.jpg",
    "https://preview.redd.it/new-collection-added-in-cred-x-citizen-40-off-coupon-v0-vr21qwf35v0e1.jpg",
    "https://preview.redd.it/citizen-cred-coupons-v0-glwg9pac7c9e1.jpg",
    
    # Myntra coupon
    # Removing the request link that might confuse the model
    # "https://preview.redd.it/can-anyone-please-pm-me-this-myntra-coupon-from-cred-app-if-v0-71q0bcz1iz6e1.jpeg",
    
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

# Define coupon types
COUPON_TYPES = {
    "standard": [0, 1, 2, 3, 5, 11, 12, 13],  # Normal coupon links
    "price_specific": [6, 7, 8],  # Price-specific deals
    "dated": [9, 10]  # Dated collections
}

def create_synthetic_coupon(coupon_type, output_path, annotation_path):
    """
    Create a synthetic coupon image based on the specified type
    
    Args:
        coupon_type: Type of coupon to create (standard, price_specific, dated)
        output_path: Path to save the synthetic image
        annotation_path: Path to save the annotation file
        
    Returns:
        bool: True if successful, False otherwise
    """
    try:
        # Create a blank image
        width, height = 800, 1200
        image = Image.new('RGB', (width, height), color=(255, 255, 255))
        draw = ImageDraw.Draw(image)
        
        # Try to load a font, use default if not available
        try:
            font_large = ImageFont.truetype("Arial.ttf", 48)
            font_medium = ImageFont.truetype("Arial.ttf", 36)
            font_small = ImageFont.truetype("Arial.ttf", 24)
        except IOError:
            font_large = ImageFont.load_default()
            font_medium = ImageFont.load_default()
            font_small = ImageFont.load_default()
        
        # Define brands and coupon codes
        brands = ["Amazon", "Flipkart", "Myntra", "Swiggy", "Zomato", "PhonePe", "Paytm", "MakeMyTrip", "Uber", "Ola"]
        coupon_codes = ["SAVE20", "EXTRA10", "FLAT50", "NEWUSER", "WELCOME", "SPECIAL25", "DISCOUNT30", "OFFER40", "DEAL15", "PROMO35"]
        
        # Select a random brand
        brand = random.choice(brands)
        
        # Draw brand name
        brand_text = f"{brand} CRED Coupon"
        draw.text((width//2, 100), brand_text, fill=(0, 0, 0), font=font_large, anchor="mm")
        
        # Create coupon content based on type
        if coupon_type == "standard":
            # Standard coupon with discount percentage
            discount = random.randint(10, 50)
            discount_text = f"{discount}% OFF"
            draw.text((width//2, 250), discount_text, fill=(255, 0, 0), font=font_large, anchor="mm")
            
            # Coupon code
            code = random.choice(coupon_codes)
            draw.text((width//2, 400), f"Use code: {code}", fill=(0, 0, 0), font=font_medium, anchor="mm")
            
            # Description
            descriptions = [
                "On all products",
                "On orders above ₹999",
                "For new users only",
                "On selected items",
                "On your first purchase"
            ]
            description = random.choice(descriptions)
            draw.text((width//2, 500), description, fill=(0, 0, 0), font=font_small, anchor="mm")
            
            # Terms and conditions
            draw.text((width//2, 1000), "Terms & Conditions Apply", fill=(100, 100, 100), font=font_small, anchor="mm")
            
        elif coupon_type == "price_specific":
            # Price-specific deal
            price = random.randint(100, 1000)
            price_text = f"₹{price} OFF"
            draw.text((width//2, 250), price_text, fill=(255, 0, 0), font=font_large, anchor="mm")
            
            # Minimum order value
            min_order = price * 3
            draw.text((width//2, 350), f"On orders above ₹{min_order}", fill=(0, 0, 0), font=font_small, anchor="mm")
            
            # Coupon code
            code = random.choice(coupon_codes)
            draw.text((width//2, 450), f"Use code: {code}", fill=(0, 0, 0), font=font_medium, anchor="mm")
            
            # Product specific
            products = [
                "on Electronics",
                "on Fashion items",
                "on Beauty products",
                "on Home appliances",
                "on Grocery items"
            ]
            product = random.choice(products)
            draw.text((width//2, 550), product, fill=(0, 0, 0), font=font_small, anchor="mm")
            
            # Terms and conditions
            draw.text((width//2, 1000), "Terms & Conditions Apply", fill=(100, 100, 100), font=font_small, anchor="mm")
            
        elif coupon_type == "dated":
            # Dated collection
            # Generate a random future date
            today = datetime.date.today()
            days_ahead = random.randint(10, 60)
            future_date = today + datetime.timedelta(days=days_ahead)
            date_text = future_date.strftime("%d/%m/%Y")
            
            # Coupon title
            draw.text((width//2, 200), "Limited Time Offer", fill=(255, 0, 0), font=font_medium, anchor="mm")
            
            # Expiry date
            draw.text((width//2, 250), f"Valid till: {date_text}", fill=(0, 0, 0), font=font_small, anchor="mm")
            
            # Discount
            discount = random.randint(10, 50)
            discount_text = f"{discount}% OFF"
            draw.text((width//2, 350), discount_text, fill=(255, 0, 0), font=font_large, anchor="mm")
            
            # Coupon code
            code = random.choice(coupon_codes)
            draw.text((width//2, 450), f"Use code: {code}", fill=(0, 0, 0), font=font_medium, anchor="mm")
            
            # Collection text
            draw.text((width//2, 550), "CRED Exclusive Offer", fill=(0, 0, 0), font=font_small, anchor="mm")
            
            # Terms and conditions
            draw.text((width//2, 1000), "Terms & Conditions Apply", fill=(100, 100, 100), font=font_small, anchor="mm")
        
        # Save the image
        image.save(output_path)
        
        # Create annotation
        annotation = {
            'store': [
                {
                    'left': width // 4,
                    'top': 70,
                    'right': 3 * width // 4,
                    'bottom': 130
                }
            ],
            'code': [
                {
                    'left': width // 4,
                    'top': 370,
                    'right': 3 * width // 4,
                    'bottom': 430
                }
            ],
            'amount': [
                {
                    'left': width // 4,
                    'top': 220,
                    'right': 3 * width // 4,
                    'bottom': 280
                }
            ],
            'expiry': [
                {
                    'left': width // 4,
                    'top': 970,
                    'right': 3 * width // 4,
                    'bottom': 1030
                }
            ],
            'description': [
                {
                    'left': width // 4,
                    'top': 470,
                    'right': 3 * width // 4,
                    'bottom': 530
                }
            ]
        }
        
        # Save annotation
        with open(annotation_path, 'w') as f:
            json.dump(annotation, f, indent=2)
        
        return True
    
    except Exception as e:
        print(f"Error creating synthetic coupon: {e}")
        return False

def main():
    parser = argparse.ArgumentParser(description="Balance Reddit coupon dataset")
    parser.add_argument("--output-dir", default="../data/raw/balanced", help="Directory to save balanced dataset")
    parser.add_argument("--annotation-dir", default="../data/annotated/balanced", help="Directory to save annotation files")
    parser.add_argument("--standard-count", type=int, default=10, help="Number of standard coupons to generate")
    parser.add_argument("--price-specific-count", type=int, default=10, help="Number of price-specific coupons to generate")
    parser.add_argument("--dated-count", type=int, default=10, help="Number of dated coupons to generate")
    
    args = parser.parse_args()
    
    # Create output directories if they don't exist
    os.makedirs(args.output_dir, exist_ok=True)
    os.makedirs(args.annotation_dir, exist_ok=True)
    
    # Generate synthetic coupons
    print("Generating synthetic coupons...")
    
    # Generate standard coupons
    for i in range(args.standard_count):
        output_path = os.path.join(args.output_dir, f"synthetic_standard_{i+1}.jpg")
        annotation_path = os.path.join(args.annotation_dir, f"synthetic_standard_{i+1}_annotations.json")
        if create_synthetic_coupon("standard", output_path, annotation_path):
            print(f"Created standard coupon {i+1}/{args.standard_count}")
    
    # Generate price-specific coupons
    for i in range(args.price_specific_count):
        output_path = os.path.join(args.output_dir, f"synthetic_price_{i+1}.jpg")
        annotation_path = os.path.join(args.annotation_dir, f"synthetic_price_{i+1}_annotations.json")
        if create_synthetic_coupon("price_specific", output_path, annotation_path):
            print(f"Created price-specific coupon {i+1}/{args.price_specific_count}")
    
    # Generate dated coupons
    for i in range(args.dated_count):
        output_path = os.path.join(args.output_dir, f"synthetic_dated_{i+1}.jpg")
        annotation_path = os.path.join(args.annotation_dir, f"synthetic_dated_{i+1}_annotations.json")
        if create_synthetic_coupon("dated", output_path, annotation_path):
            print(f"Created dated coupon {i+1}/{args.dated_count}")
    
    print(f"Successfully generated {args.standard_count + args.price_specific_count + args.dated_count} synthetic coupons")

if __name__ == "__main__":
    main()
