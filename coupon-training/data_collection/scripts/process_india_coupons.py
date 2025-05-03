#!/usr/bin/env python3
"""
Process India Coupons

This script processes the downloaded coupon images from r/CouponsIndia.
"""

import os
import json
import shutil
from datetime import datetime

# Base directories
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW_IMAGES_DIR = os.path.join(BASE_DIR, 'raw_images')
PROCESSED_IMAGES_DIR = os.path.join(BASE_DIR, 'processed_images')
ANNOTATIONS_DIR = os.path.join(BASE_DIR, 'annotations')

def ensure_directories_exist():
    """Ensure all necessary directories exist."""
    os.makedirs(PROCESSED_IMAGES_DIR, exist_ok=True)
    os.makedirs(ANNOTATIONS_DIR, exist_ok=True)
    print("Directory structure verified")

def process_images():
    """Process the downloaded coupon images."""
    ensure_directories_exist()
    
    # Load scraping results if available
    results_path = os.path.join(BASE_DIR, 'india_scraping_results.json')
    if os.path.exists(results_path):
        with open(results_path, 'r') as f:
            results = json.load(f)
    else:
        results = []
        print("No scraping results found. Scanning raw images directory...")
    
    # If no results, scan the raw images directory
    if not results:
        for source_dir in os.listdir(RAW_IMAGES_DIR):
            source_path = os.path.join(RAW_IMAGES_DIR, source_dir)
            if os.path.isdir(source_path):
                for filename in os.listdir(source_path):
                    if filename.endswith(('.jpg', '.jpeg', '.png', '.gif')):
                        file_path = os.path.join(source_path, filename)
                        results.append({
                            'title': 'Unknown',
                            'source': source_dir,
                            'file_path': file_path,
                            'subreddit': 'CouponsIndia'
                        })
    
    print(f"Found {len(results)} images to process")
    
    processed_count = 0
    
    for i, result in enumerate(results):
        try:
            # Get file path and source
            file_path = result.get('file_path')
            source = result.get('source', 'other')
            title = result.get('title', 'Unknown')
            
            if not os.path.exists(file_path):
                print(f"File not found: {file_path}")
                continue
            
            # Create processed filename
            filename = os.path.basename(file_path)
            processed_filename = f"processed_{filename}"
            processed_path = os.path.join(PROCESSED_IMAGES_DIR, processed_filename)
            
            # Copy file to processed directory
            shutil.copy2(file_path, processed_path)
            
            # Create annotation
            annotation = {
                "image_id": os.path.splitext(processed_filename)[0],
                "source": source,
                "title": title,
                "annotation_date": datetime.now().isoformat(),
                "annotator_id": "auto_processor",
                "fields": {
                    "store_name": {
                        "text": source.capitalize() if source != 'other' else "Unknown",
                        "confidence": 0.8
                    }
                },
                "verification_status": "unverified"
            }
            
            # Extract potential coupon code from title
            if 'code' in title.lower():
                words = title.split()
                for word in words:
                    if word.isupper() and len(word) >= 4:
                        annotation["fields"]["coupon_code"] = {
                            "text": word,
                            "confidence": 0.6
                        }
                        break
            
            # Save annotation
            annotation_path = os.path.join(ANNOTATIONS_DIR, f"{os.path.splitext(processed_filename)[0]}.json")
            with open(annotation_path, 'w') as f:
                json.dump(annotation, f, indent=2)
            
            processed_count += 1
            print(f"Processed image {i+1}/{len(results)}: {filename}")
        
        except Exception as e:
            print(f"Error processing image {i+1}/{len(results)}: {e}")
    
    print(f"Processing complete. Processed {processed_count} images.")

if __name__ == "__main__":
    process_images()
