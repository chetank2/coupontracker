#!/usr/bin/env python3
"""
Direct Coupon Image Downloader

This script downloads coupon images from direct URLs.
"""

import os
import requests
import json
import time
from datetime import datetime
import random

# Direct image URLs of coupons
COUPON_URLS = [
    # GPay coupons
    "https://i.redd.it/gpay-coupons-v0-624tbeivxywe1.jpg",
    "https://i.redd.it/gpay-coupon-code-v0-zmowm1rvxywe1.jpg",
    "https://i.redd.it/sharing-gpay-coupons-for-dotkey-aqualogica-v0-ixvnvnxnvxb81.jpg",
    
    # Zomato coupons
    "https://i.redd.it/zomato-coupons-ajio-puma-etc-v0-if9ulnzxvxb81.jpg",
    "https://i.redd.it/zomato-coupons-v0-f31irgzxvxb81.jpg",
    
    # PhonePe coupons
    "https://i.redd.it/phonepe-coupons-v0-hhbf63zxvxb81.jpg",
    
    # Myntra coupons
    "https://i.redd.it/myntra-coupons-v0-1k7ivn0zxvxb81.jpg"
]

# Base directories
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW_IMAGES_DIR = os.path.join(BASE_DIR, 'raw_images')
COLLECTION_LOG = os.path.join(BASE_DIR, 'collection_log.json')

def ensure_directories_exist():
    """Ensure all necessary directories exist."""
    for source in ['zomato', 'gpay', 'phonepe', 'myntra', 'other']:
        os.makedirs(os.path.join(RAW_IMAGES_DIR, source), exist_ok=True)
    print("Directory structure verified")

def determine_source(url):
    """Determine the source of the coupon based on URL."""
    url_lower = url.lower()
    
    # Check for known sources
    if 'zomato' in url_lower:
        return 'zomato'
    elif 'gpay' in url_lower or 'google pay' in url_lower:
        return 'gpay'
    elif 'phonepe' in url_lower or 'phone pe' in url_lower:
        return 'phonepe'
    elif 'myntra' in url_lower:
        return 'myntra'
    
    # Default to 'other' if no match
    return 'other'

def download_image(url):
    """Download an image from a URL and save it to the appropriate directory."""
    try:
        response = requests.get(url, stream=True, timeout=10)
        response.raise_for_status()
        
        # Determine source
        source = determine_source(url)
        
        # Extract file extension
        file_ext = os.path.splitext(url)[1]
        if not file_ext or file_ext.lower() not in ['.jpg', '.jpeg', '.png', '.gif']:
            file_ext = '.jpg'  # Default to jpg if no extension or unrecognized
        
        # Create a unique filename
        date_str = datetime.now().strftime('%Y%m%d')
        random_id = ''.join(random.choices('abcdefghijklmnopqrstuvwxyz0123456789', k=8))
        filename = f"{source}_{date_str}_{random_id}{file_ext}"
        
        # Save the image
        file_path = os.path.join(RAW_IMAGES_DIR, source, filename)
        with open(file_path, 'wb') as f:
            for chunk in response.iter_content(chunk_size=8192):
                f.write(chunk)
        
        print(f"Downloaded image: {filename}")
        
        # Log the collection
        log_collection(url, source, file_path)
        
        return file_path
    except Exception as e:
        print(f"Error downloading image from {url}: {e}")
        return None

def log_collection(url, source, file_path):
    """Log the collection details to a JSON file."""
    log_entry = {
        'url': url,
        'source': source,
        'file_path': file_path,
        'collection_date': datetime.now().isoformat()
    }
    
    # Load existing log if it exists
    if os.path.exists(COLLECTION_LOG):
        with open(COLLECTION_LOG, 'r') as f:
            try:
                log_data = json.load(f)
            except json.JSONDecodeError:
                log_data = []
    else:
        log_data = []
    
    # Add new entry and save
    log_data.append(log_entry)
    with open(COLLECTION_LOG, 'w') as f:
        json.dump(log_data, f, indent=2)

def main():
    """Main function to download coupon images."""
    ensure_directories_exist()
    
    results = []
    
    for i, url in enumerate(COUPON_URLS):
        print(f"Processing image {i+1}/{len(COUPON_URLS)}: {url}")
        
        # Download the image
        file_path = download_image(url)
        
        if file_path:
            # Add to results
            results.append({
                'url': url,
                'file_path': file_path,
                'source': determine_source(url)
            })
        
        # Be nice to servers
        time.sleep(random.uniform(1.0, 2.0))
    
    # Save results summary
    results_path = os.path.join(BASE_DIR, 'download_results.json')
    with open(results_path, 'w') as f:
        json.dump(results, f, indent=2)
    
    print(f"Download complete. Collected {len(results)} images.")
    return results

if __name__ == "__main__":
    main()
