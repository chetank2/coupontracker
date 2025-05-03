#!/usr/bin/env python3
"""
Sample Coupon Text Generator

This script creates sample coupon data in text format for training purposes.
"""

import os
import random
import json
from datetime import datetime, timedelta
import string

# Base directories
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SAMPLE_DATA_DIR = os.path.join(BASE_DIR, 'sample_data')
ANNOTATIONS_DIR = os.path.join(BASE_DIR, 'annotations')

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
    os.makedirs(SAMPLE_DATA_DIR, exist_ok=True)
    os.makedirs(ANNOTATIONS_DIR, exist_ok=True)
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
    """Create sample coupon data."""
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
        
        # Determine source category
        source = determine_source(store_name)
        
        # Create coupon text
        coupon_text = f"""
Store: {store_name}
Discount: {discount}
Code: {coupon_code}
Valid till: {expiry_date}
"""
        
        if min_purchase:
            coupon_text += f"Minimum Order: ₹{min_purchase}\n"
        
        # Save coupon text
        text_filename = f"{source}_{i+1}.txt"
        text_path = os.path.join(SAMPLE_DATA_DIR, text_filename)
        with open(text_path, 'w') as f:
            f.write(coupon_text)
        
        # Save annotation
        annotation = {
            "image_id": f"{source}_{i+1}",
            "source": "sample_generator",
            "annotation_date": datetime.now().isoformat(),
            "annotator_id": "sample_generator",
            "fields": {
                "store_name": {
                    "text": store_name,
                    "confidence": 1.0
                },
                "coupon_code": {
                    "text": coupon_code,
                    "confidence": 1.0
                },
                "expiry_date": {
                    "text": expiry_date,
                    "confidence": 1.0,
                    "normalized_date": datetime.strptime(expiry_date, "%d %b %Y").strftime("%Y-%m-%d")
                },
                "discount_amount": {
                    "text": discount,
                    "confidence": 1.0
                }
            },
            "verification_status": "verified"
        }
        
        # Add minimum purchase if available
        if min_purchase:
            annotation["fields"]["min_purchase"] = {
                "text": f"Minimum Order: ₹{min_purchase}",
                "confidence": 1.0,
                "value": min_purchase
            }
        
        # Save annotation
        annotation_path = os.path.join(ANNOTATIONS_DIR, f"{source}_{i+1}.json")
        with open(annotation_path, 'w') as f:
            json.dump(annotation, f, indent=2)
        
        # Add to coupon data
        coupon_data.append({
            "store_name": store_name,
            "coupon_code": coupon_code,
            "discount": discount,
            "expiry_date": expiry_date,
            "min_purchase": min_purchase,
            "text_path": text_path,
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
