#!/usr/bin/env python3
"""
Reddit India Coupon Scraper

This script scrapes coupon images from r/CouponsIndia.
"""

import os
import requests
import json
import time
import random
from datetime import datetime
import re

# Base directories
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW_IMAGES_DIR = os.path.join(BASE_DIR, 'raw_images')
COLLECTION_LOG = os.path.join(BASE_DIR, 'collection_log.json')

# Reddit API configuration
REDDIT_URL = "https://www.reddit.com/r/CouponsIndia/.json"
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

def ensure_directories_exist():
    """Ensure all necessary directories exist."""
    for source in ['zomato', 'gpay', 'phonepe', 'myntra', 'other']:
        os.makedirs(os.path.join(RAW_IMAGES_DIR, source), exist_ok=True)
    print("Directory structure verified")

def determine_source(title, url):
    """Determine the source of the coupon based on title and URL."""
    title_lower = title.lower() if title else ""
    
    # Check for known sources
    if 'zomato' in title_lower:
        return 'zomato'
    elif 'gpay' in title_lower or 'google pay' in title_lower:
        return 'gpay'
    elif 'phonepe' in title_lower or 'phone pe' in title_lower:
        return 'phonepe'
    elif 'myntra' in title_lower:
        return 'myntra'
    
    # Default to 'other' if no match
    return 'other'

def download_image(url, source, title):
    """Download an image from a URL and save it to the appropriate directory."""
    try:
        headers = {
            'User-Agent': USER_AGENT
        }
        response = requests.get(url, headers=headers, stream=True, timeout=10)
        response.raise_for_status()
        
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
        return file_path
    except Exception as e:
        print(f"Error downloading image from {url}: {e}")
        return None

def log_collection(url, source, file_path, title, subreddit):
    """Log the collection details to a JSON file."""
    log_entry = {
        'url': url,
        'source': source,
        'file_path': file_path,
        'title': title,
        'subreddit': subreddit,
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

def extract_image_urls(post_data):
    """Extract image URLs from a Reddit post."""
    image_urls = []
    
    # Check if the post has a direct image URL
    url = post_data.get('url', '')
    if url.endswith(('.jpg', '.jpeg', '.png', '.gif')):
        image_urls.append(url)
    
    # Check for gallery
    if 'gallery_data' in post_data and 'media_metadata' in post_data:
        gallery_items = post_data.get('gallery_data', {}).get('items', [])
        media_metadata = post_data.get('media_metadata', {})
        
        for item in gallery_items:
            media_id = item.get('media_id')
            if media_id and media_id in media_metadata:
                media_item = media_metadata[media_id]
                if media_item.get('status') == 'valid':
                    for s in media_item.get('s', {}):
                        if 'u' in s:
                            image_urls.append(s['u'])
    
    # Check for preview images
    if 'preview' in post_data and 'images' in post_data['preview']:
        for image in post_data['preview']['images']:
            if 'source' in image and 'url' in image['source']:
                image_urls.append(image['source']['url'])
    
    # Check for thumbnail
    if 'thumbnail' in post_data and post_data['thumbnail'].startswith('http'):
        image_urls.append(post_data['thumbnail'])
    
    return image_urls

def scrape_reddit_india_coupons(limit=25):
    """Scrape coupon images from r/CouponsIndia."""
    ensure_directories_exist()
    
    headers = {
        'User-Agent': USER_AGENT
    }
    
    params = {
        'limit': limit
    }
    
    try:
        response = requests.get(REDDIT_URL, headers=headers, params=params)
        response.raise_for_status()
        
        data = response.json()
        posts = data.get('data', {}).get('children', [])
        
        results = []
        
        for post in posts:
            post_data = post.get('data', {})
            title = post_data.get('title', '')
            subreddit = post_data.get('subreddit', 'CouponsIndia')
            
            # Skip non-coupon posts
            if not any(keyword in title.lower() for keyword in ['coupon', 'code', 'discount', 'offer', 'deal']):
                continue
            
            # Extract image URLs
            image_urls = extract_image_urls(post_data)
            
            if not image_urls:
                print(f"No images found in post: {title}")
                continue
            
            print(f"Found {len(image_urls)} images in post: {title}")
            
            # Process each image
            for image_url in image_urls:
                # Determine source
                source = determine_source(title, image_url)
                
                # Download the image
                file_path = download_image(image_url, source, title)
                
                if file_path:
                    # Log the collection
                    log_collection(image_url, source, file_path, title, subreddit)
                    
                    # Add to results
                    results.append({
                        'title': title,
                        'image_url': image_url,
                        'source': source,
                        'file_path': file_path,
                        'subreddit': subreddit
                    })
                
                # Be nice to servers
                time.sleep(random.uniform(1.0, 2.0))
        
        # Save results summary
        results_path = os.path.join(BASE_DIR, 'india_scraping_results.json')
        with open(results_path, 'w') as f:
            json.dump(results, f, indent=2)
        
        print(f"Scraping complete. Collected {len(results)} images.")
        return results
    
    except Exception as e:
        print(f"Error scraping Reddit: {e}")
        return []

if __name__ == "__main__":
    scrape_reddit_india_coupons()
