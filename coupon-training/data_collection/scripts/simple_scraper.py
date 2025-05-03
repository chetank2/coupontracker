#!/usr/bin/env python3
"""
Simple Reddit Coupon Scraper

This script downloads images from Reddit posts containing coupons.
"""

import os
import requests
import re
import json
import time
from datetime import datetime
import random

# Reddit links to test
REDDIT_LINKS = [
    "https://www.reddit.com/r/IndianBeautyDeals/comments/1k7ivn0/gpay_coupons/",
    "https://www.reddit.com/r/IndianBeautyDeals/comments/1jm1kuy/gpay_coupon_code/",
    "https://www.reddit.com/r/IndianBeautyDeals/comments/1cxpbf8/sharing_gpay_coupons_for_dotkey_aqualogica/",
    "https://www.reddit.com/r/IndianBeautyDeals/comments/1if9uln/zomato_coupons_ajio_puma_etc/",
    "https://www.reddit.com/r/IndianBeautyDeals/comments/1f31irg/zomato_coupons/",
    "https://www.reddit.com/r/CouponsIndia/comments/1hhbf63/gpay_coupons/"
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
        response = requests.get(url, stream=True, timeout=10)
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

def extract_image_urls_from_html(html_content):
    """Extract image URLs from HTML content using regex."""
    # Look for image URLs in the HTML
    img_pattern = r'https://[^"\s]+\.(jpg|jpeg|png|gif)'
    image_urls = re.findall(img_pattern, html_content, re.IGNORECASE)
    
    # Filter out thumbnails and icons
    filtered_urls = []
    for url in image_urls:
        if 'thumb' not in url.lower() and 'icon' not in url.lower() and 'avatar' not in url.lower():
            filtered_urls.append(url)
    
    return filtered_urls

def scrape_reddit_post(url):
    """Scrape a Reddit post for images."""
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36'
    }
    
    try:
        # Add .json to the URL to get the JSON version of the post
        json_url = url + '.json'
        response = requests.get(json_url, headers=headers)
        response.raise_for_status()
        
        data = response.json()
        
        # Extract post title and subreddit
        post_data = data[0]['data']['children'][0]['data']
        title = post_data.get('title', 'Unknown Title')
        subreddit = post_data.get('subreddit', 'unknown')
        
        # Extract image URLs
        image_urls = []
        
        # Check for direct image URL
        url = post_data.get('url')
        if url and any(url.endswith(ext) for ext in ['.jpg', '.jpeg', '.png', '.gif']):
            image_urls.append(url)
        
        # Check for gallery
        if 'gallery_data' in post_data:
            gallery_items = post_data.get('gallery_data', {}).get('items', [])
            media_metadata = post_data.get('media_metadata', {})
            
            for item in gallery_items:
                media_id = item.get('media_id')
                if media_id and media_id in media_metadata:
                    media_item = media_metadata[media_id]
                    if media_item.get('status') == 'valid':
                        for source in media_item.get('s', {}).get('u', ''):
                            image_urls.append(source)
        
        # If no images found, try to extract from HTML
        if not image_urls:
            html_response = requests.get(url, headers=headers)
            html_response.raise_for_status()
            image_urls = extract_image_urls_from_html(html_response.text)
        
        print(f"Found {len(image_urls)} images in post: {title}")
        
        return {
            'title': title,
            'subreddit': subreddit,
            'image_urls': image_urls
        }
    
    except Exception as e:
        print(f"Error scraping Reddit post {url}: {e}")
        return None

def main():
    """Main function to scrape Reddit posts for coupon images."""
    ensure_directories_exist()
    
    results = []
    
    for i, link in enumerate(REDDIT_LINKS):
        print(f"Processing link {i+1}/{len(REDDIT_LINKS)}: {link}")
        
        # Scrape the Reddit post
        post_data = scrape_reddit_post(link)
        
        if not post_data or not post_data['image_urls']:
            print(f"No images found in post: {link}")
            continue
        
        # Process each image
        for j, image_url in enumerate(post_data['image_urls']):
            print(f"Processing image {j+1}/{len(post_data['image_urls'])}: {image_url}")
            
            # Determine source
            source = determine_source(post_data['title'], image_url)
            
            # Download the image
            file_path = download_image(image_url, source, post_data['title'])
            
            if file_path:
                # Log the collection
                log_collection(image_url, source, file_path, post_data['title'], post_data['subreddit'])
                
                # Add to results
                results.append({
                    'reddit_link': link,
                    'image_url': image_url,
                    'source': source,
                    'file_path': file_path,
                    'title': post_data['title'],
                    'subreddit': post_data['subreddit']
                })
            
            # Be nice to servers
            time.sleep(random.uniform(1.0, 2.0))
    
    # Save results summary
    results_path = os.path.join(BASE_DIR, 'scraping_results.json')
    with open(results_path, 'w') as f:
        json.dump(results, f, indent=2)
    
    print(f"Scraping complete. Collected {len(results)} images.")
    return results

if __name__ == "__main__":
    main()
