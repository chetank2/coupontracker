#!/usr/bin/env python3
"""
Sample Coupon Generator

This script creates sample coupon images for training purposes.
"""

import os
import numpy as np
import cv2
import random
import json
from datetime import datetime, timedelta
import string

# Base directories
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW_IMAGES_DIR = os.path.join(BASE_DIR, 'raw_images')
SAMPLE_DATA_DIR = os.path.join(BASE_DIR, 'sample_data')

# Sample data
STORE_NAMES = [
    "Zomato", "Swiggy", "GPay", "PhonePe", "Myntra", "Amazon", "Flipkart", 
    "CRED", "Ajio", "Tata CLiQ", "Nykaa", "BigBasket", "Grofers", "Uber Eats",
    "Domino's", "Pizza Hut", "McDonald's", "KFC", "Burger King", "Subway"
]

DISCOUNT_TYPES = [
    "₹{} OFF", "{}% OFF", "FLAT {}% OFF", "UPTO ₹{} OFF", 
    "BUY 1 GET 1 FREE", "FREE DELIVERY", "CASHBACK ₹{}"
]

COUPON_CODES = [
    "WELCOME50", "FIRST100", "NEWUSER", "SAVE20", "EXTRA10", 
    "FESTIVE30", "WEEKEND", "SPECIAL25", "SUMMER40", "MONSOON50"
]

def ensure_directories_exist():
    """Ensure all necessary directories exist."""
    for source in ['zomato', 'gpay', 'phonepe', 'myntra', 'other']:
        os.makedirs(os.path.join(RAW_IMAGES_DIR, source), exist_ok=True)
    os.makedirs(SAMPLE_DATA_DIR, exist_ok=True)
    print("Directory structure verified")

def generate_random_code(length=8):
    """Generate a random coupon code."""
    chars = string.ascii_uppercase + string.digits
    return ''.join(random.choice(chars) for _ in range(length))

def generate_random_date():
    """Generate a random future date."""
    days_ahead = random.randint(1, 90)
    future_date = datetime.now() + timedelta(days=days_ahead)
    return future_date.strftime("%d %b %Y")

def create_coupon_image(store_name, coupon_code, discount, expiry_date, min_purchase=None):
    """Create a sample coupon image."""
    # Create a blank image
    width, height = 800, 400
    image = np.ones((height, width, 3), dtype=np.uint8) * 255
    
    # Add a colored border
    color = (random.randint(0, 255), random.randint(0, 255), random.randint(0, 255))
    cv2.rectangle(image, (0, 0), (width-1, height-1), color, 10)
    
    # Add store name
    cv2.putText(image, store_name, (50, 70), cv2.FONT_HERSHEY_SIMPLEX, 2, (0, 0, 0), 3)
    
    # Add discount
    cv2.putText(image, discount, (50, 150), cv2.FONT_HERSHEY_SIMPLEX, 1.5, (0, 0, 200), 2)
    
    # Add coupon code
    cv2.putText(image, f"CODE: {coupon_code}", (50, 220), cv2.FONT_HERSHEY_SIMPLEX, 1, (200, 0, 0), 2)
    
    # Add expiry date
    cv2.putText(image, f"Valid till: {expiry_date}", (50, 280), cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 0, 0), 2)
    
    # Add minimum purchase if provided
    if min_purchase:
        cv2.putText(image, f"Min. order: ₹{min_purchase}", (50, 340), cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 0, 0), 2)
    
    return image

def determine_source(store_name):
    """Determine the source category based on store name."""
    store_lower = store_name.lower()
    
    if 'zomato' in store_lower:
        return 'zomato'
    elif 'gpay' in store_lower or 'google pay' in store_lower:
        return 'gpay'
    elif 'phonepe' in store_lower:
        return 'phonepe'
    elif 'myntra' in store_lower:
        return 'myntra'
    
    return 'other'

def create_sample_coupons(num_coupons=20):
    """Create sample coupon images."""
    ensure_directories_exist()
    
    coupon_data = []
    
    for i in range(num_coupons):
        # Generate coupon data
        store_name = random.choice(STORE_NAMES)
        coupon_code = random.choice(COUPON_CODES) if random.random() < 0.5 else generate_random_code()
        
        discount_template = random.choice(DISCOUNT_TYPES)
        if '{}%' in discount_template:
            discount_value = random.randint(5, 70)
        elif '₹{}' in discount_template:
            discount_value = random.choice([50, 100, 150, 200, 250, 300, 500])
        else:
            discount_value = None
        
        discount = discount_template.format(discount_value) if discount_value else discount_template
        
        expiry_date = generate_random_date()
        min_purchase = random.choice([None, 99, 199, 299, 499, 999])
        
        # Create coupon image
        image = create_coupon_image(store_name, coupon_code, discount, expiry_date, min_purchase)
        
        # Determine source category
        source = determine_source(store_name)
        
        # Save image
        filename = f"{source}_{i+1}.jpg"
        file_path = os.path.join(RAW_IMAGES_DIR, source, filename)
        cv2.imwrite(file_path, image)
        
        # Save annotation
        annotation = {
            "image_id": f"{source}_{i+1}",
            "source": "sample_generator",
            "annotation_date": datetime.now().isoformat(),
            "annotator_id": "sample_generator",
            "fields": {
                "store_name": {
                    "text": store_name,
                    "bounding_box": [50, 40, 500, 80],
                    "confidence": 1.0
                },
                "coupon_code": {
                    "text": coupon_code,
                    "bounding_box": [150, 190, 500, 230],
                    "confidence": 1.0
                },
                "expiry_date": {
                    "text": expiry_date,
                    "bounding_box": [150, 250, 500, 290],
                    "confidence": 1.0,
                    "normalized_date": datetime.strptime(expiry_date, "%d %b %Y").strftime("%Y-%m-%d")
                },
                "discount_amount": {
                    "text": discount,
                    "bounding_box": [50, 120, 500, 160],
                    "confidence": 1.0
                }
            },
            "verification_status": "verified"
        }
        
        # Add minimum purchase if available
        if min_purchase:
            annotation["fields"]["min_purchase"] = {
                "text": f"Min. order: ₹{min_purchase}",
                "bounding_box": [50, 310, 500, 350],
                "confidence": 1.0,
                "value": min_purchase
            }
        
        # Save annotation
        annotation_path = os.path.join(SAMPLE_DATA_DIR, f"{source}_{i+1}.json")
        with open(annotation_path, 'w') as f:
            json.dump(annotation, f, indent=2)
        
        # Add to coupon data
        coupon_data.append({
            "store_name": store_name,
            "coupon_code": coupon_code,
            "discount": discount,
            "expiry_date": expiry_date,
            "min_purchase": min_purchase,
            "image_path": file_path,
            "annotation_path": annotation_path,
            "source": source
        })
        
        print(f"Created sample coupon {i+1}/{num_coupons}: {store_name} - {coupon_code}")
    
    # Save coupon data summary
    summary_path = os.path.join(SAMPLE_DATA_DIR, 'sample_coupons.json')
    with open(summary_path, 'w') as f:
        json.dump(coupon_data, f, indent=2)
    
    print(f"Created {num_coupons} sample coupons")
    return coupon_data

if __name__ == "__main__":
    create_sample_coupons(20)
