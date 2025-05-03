#!/usr/bin/env python3
"""
Simple Coupon Generator

This script creates a few sample coupon files for training purposes.
"""

import os
import json
from datetime import datetime

# Base directories
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SAMPLE_DATA_DIR = os.path.join(BASE_DIR, 'sample_data')
ANNOTATIONS_DIR = os.path.join(BASE_DIR, 'annotations')

# Sample coupons
SAMPLE_COUPONS = [
    {
        "store_name": "Zomato",
        "coupon_code": "WELCOME50",
        "discount": "50% OFF",
        "expiry_date": "30 Jun 2025",
        "min_purchase": 199,
        "source": "zomato"
    },
    {
        "store_name": "GPay",
        "coupon_code": "GPAY100",
        "discount": "₹100 OFF",
        "expiry_date": "15 Jul 2025",
        "min_purchase": None,
        "source": "gpay"
    },
    {
        "store_name": "PhonePe",
        "coupon_code": "PHONEPE20",
        "discount": "20% OFF",
        "expiry_date": "31 Aug 2025",
        "min_purchase": 299,
        "source": "phonepe"
    },
    {
        "store_name": "Myntra",
        "coupon_code": "MYNTRA500",
        "discount": "FLAT ₹500 OFF",
        "expiry_date": "30 Sep 2025",
        "min_purchase": 1999,
        "source": "myntra"
    },
    {
        "store_name": "Swiggy",
        "coupon_code": "SWIGGY150",
        "discount": "₹150 OFF",
        "expiry_date": "31 Oct 2025",
        "min_purchase": 399,
        "source": "other"
    }
]

def ensure_directories_exist():
    """Ensure all necessary directories exist."""
    os.makedirs(SAMPLE_DATA_DIR, exist_ok=True)
    os.makedirs(ANNOTATIONS_DIR, exist_ok=True)
    print("Directory structure verified")

def create_sample_coupons():
    """Create sample coupon files."""
    ensure_directories_exist()
    
    for i, coupon in enumerate(SAMPLE_COUPONS):
        # Create coupon text
        coupon_text = f"""
Store: {coupon['store_name']}
Discount: {coupon['discount']}
Code: {coupon['coupon_code']}
Valid till: {coupon['expiry_date']}
"""
        
        if coupon['min_purchase']:
            coupon_text += f"Minimum Order: ₹{coupon['min_purchase']}\n"
        
        # Save coupon text
        text_filename = f"{coupon['source']}_{i+1}.txt"
        text_path = os.path.join(SAMPLE_DATA_DIR, text_filename)
        with open(text_path, 'w') as f:
            f.write(coupon_text)
        
        # Save annotation
        annotation = {
            "image_id": f"{coupon['source']}_{i+1}",
            "source": "sample_generator",
            "annotation_date": datetime.now().isoformat(),
            "annotator_id": "sample_generator",
            "fields": {
                "store_name": {
                    "text": coupon['store_name'],
                    "confidence": 1.0
                },
                "coupon_code": {
                    "text": coupon['coupon_code'],
                    "confidence": 1.0
                },
                "expiry_date": {
                    "text": coupon['expiry_date'],
                    "confidence": 1.0
                },
                "discount_amount": {
                    "text": coupon['discount'],
                    "confidence": 1.0
                }
            },
            "verification_status": "verified"
        }
        
        # Add minimum purchase if available
        if coupon['min_purchase']:
            annotation["fields"]["min_purchase"] = {
                "text": f"Minimum Order: ₹{coupon['min_purchase']}",
                "confidence": 1.0,
                "value": coupon['min_purchase']
            }
        
        # Save annotation
        annotation_path = os.path.join(ANNOTATIONS_DIR, f"{coupon['source']}_{i+1}.json")
        with open(annotation_path, 'w') as f:
            json.dump(annotation, f, indent=2)
        
        print(f"Created sample coupon {i+1}/{len(SAMPLE_COUPONS)}: {coupon['store_name']} - {coupon['coupon_code']}")
    
    # Save coupon data summary
    summary_path = os.path.join(SAMPLE_DATA_DIR, 'sample_coupons.json')
    with open(summary_path, 'w') as f:
        json.dump(SAMPLE_COUPONS, f, indent=2)
    
    print(f"Created {len(SAMPLE_COUPONS)} sample coupons")

if __name__ == "__main__":
    create_sample_coupons()
