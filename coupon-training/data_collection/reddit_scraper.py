#!/usr/bin/env python3
"""
Reddit Coupon Image Scraper

This script collects coupon images from specified subreddits and search terms.
It downloads images, categorizes them by source, and maintains a log of collected data.
"""

import os
import json
import requests
import time
import logging
import argparse
from datetime import datetime
from urllib.parse import urlparse
import hashlib
import re

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("coupon_collection.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("reddit_scraper")

# Configuration
DEFAULT_SUBREDDITS = [
    'IndianBeautyDeals', 
    'CouponsIndia', 
    'DealsCouponsIndia',
    'unusedcodes'
]

DEFAULT_SEARCH_TERMS = [
    'zomato coupon', 
    'gpay coupon', 
    'phonepe coupon', 
    'myntra coupon',
    'swiggy coupon',
    'cred coupon'
]

# Base directory for storing images
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
RAW_IMAGES_DIR = os.path.join(BASE_DIR, 'raw_images')
COLLECTION_LOG = os.path.join(BASE_DIR, 'collection_log.json')

def ensure_directories_exist():
    """Ensure all necessary directories exist."""
    for source in ['zomato', 'gpay', 'phonepe', 'myntra', 'other']:
        os.makedirs(os.path.join(RAW_IMAGES_DIR, source), exist_ok=True)
    
    logger.info("Directory structure verified")

def determine_source(title, url, text):
    """Determine the source of the coupon based on title, URL, and text content."""
    title_lower = title.lower()
    text_lower = text.lower() if text else ""
    
    # Check for known sources
    sources = {
        'zomato': ['zomato', 'food delivery', 'restaurant'],
        'gpay': ['gpay', 'google pay', 'googlepay'],
        'phonepe': ['phonepe', 'phone pe'],
        'myntra': ['myntra', 'fashion', 'clothing'],
    }
    
    for source, keywords in sources.items():
        for keyword in keywords:
            if keyword in title_lower or keyword in text_lower:
                return source
    
    # Default to 'other' if no match
    return 'other'

def download_image(url, source, title):
    """Download an image from a URL and save it to the appropriate directory."""
    try:
        response = requests.get(url, stream=True, timeout=10)
        response.raise_for_status()
        
        # Generate a unique filename based on source, date, and content hash
        url_path = urlparse(url).path
        file_ext = os.path.splitext(url_path)[1]
        if not file_ext or file_ext.lower() not in ['.jpg', '.jpeg', '.png', '.gif']:
            file_ext = '.jpg'  # Default to jpg if no extension or unrecognized
        
        # Create a hash from the URL and title to ensure uniqueness
        content_hash = hashlib.md5((url + title).encode()).hexdigest()[:8]
        date_str = datetime.now().strftime('%Y%m%d')
        filename = f"{source}_{date_str}_{content_hash}{file_ext}"
        
        # Save the image
        file_path = os.path.join(RAW_IMAGES_DIR, source, filename)
        with open(file_path, 'wb') as f:
            for chunk in response.iter_content(chunk_size=8192):
                f.write(chunk)
        
        logger.info(f"Downloaded image: {filename}")
        return file_path
    except Exception as e:
        logger.error(f"Error downloading image from {url}: {e}")
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

def extract_image_urls_from_reddit_json(json_data):
    """Extract image URLs from Reddit JSON response."""
    image_urls = []
    
    # Process posts
    posts = json_data.get('data', {}).get('children', [])
    for post in posts:
        post_data = post.get('data', {})
        title = post_data.get('title', '')
        subreddit = post_data.get('subreddit', '')
        text = post_data.get('selftext', '')
        
        # Check for image posts
        if post_data.get('post_hint') == 'image':
            url = post_data.get('url')
            if url and any(url.endswith(ext) for ext in ['.jpg', '.jpeg', '.png', '.gif']):
                image_urls.append({
                    'url': url,
                    'title': title,
                    'subreddit': subreddit,
                    'text': text
                })
        
        # Check for gallery posts
        elif 'gallery_data' in post_data:
            gallery_items = post_data.get('gallery_data', {}).get('items', [])
            media_metadata = post_data.get('media_metadata', {})
            
            for item in gallery_items:
                media_id = item.get('media_id')
                if media_id and media_id in media_metadata:
                    media_item = media_metadata[media_id]
                    if media_item.get('status') == 'valid':
                        for source in media_item.get('s', {}).get('u', ''):
                            image_urls.append({
                                'url': source,
                                'title': title,
                                'subreddit': subreddit,
                                'text': text
                            })
        
        # Check for preview images
        elif 'preview' in post_data:
            images = post_data.get('preview', {}).get('images', [])
            for image in images:
                url = image.get('source', {}).get('url')
                if url:
                    # Reddit escapes URLs in JSON, so unescape them
                    url = url.replace('\\', '')
                    image_urls.append({
                        'url': url,
                        'title': title,
                        'subreddit': subreddit,
                        'text': text
                    })
    
    return image_urls

def search_reddit(subreddit, search_term, limit=100):
    """Search Reddit for posts matching the search term in the specified subreddit."""
    url = f"https://www.reddit.com/r/{subreddit}/search.json"
    params = {
        'q': search_term,
        'restrict_sr': 'on',
        'sort': 'new',
        'limit': limit
    }
    
    headers = {
        'User-Agent': 'CouponTracker Data Collection Script v1.0'
    }
    
    try:
        response = requests.get(url, params=params, headers=headers)
        response.raise_for_status()
        return extract_image_urls_from_reddit_json(response.json())
    except Exception as e:
        logger.error(f"Error searching Reddit for {search_term} in r/{subreddit}: {e}")
        return []

def main(subreddits=None, search_terms=None, limit=50):
    """Main function to collect coupon images from Reddit."""
    if subreddits is None:
        subreddits = DEFAULT_SUBREDDITS
    
    if search_terms is None:
        search_terms = DEFAULT_SEARCH_TERMS
    
    ensure_directories_exist()
    
    logger.info(f"Starting coupon image collection from {len(subreddits)} subreddits")
    
    total_downloaded = 0
    
    for subreddit in subreddits:
        for search_term in search_terms:
            logger.info(f"Searching for '{search_term}' in r/{subreddit}")
            
            image_data = search_reddit(subreddit, search_term, limit)
            logger.info(f"Found {len(image_data)} potential images")
            
            for item in image_data:
                url = item['url']
                title = item['title']
                text = item.get('text', '')
                
                # Determine the source category
                source = determine_source(title, url, text)
                
                # Download the image
                file_path = download_image(url, source, title)
                if file_path:
                    # Log the collection
                    log_collection(url, source, file_path, title, subreddit)
                    total_downloaded += 1
            
            # Be nice to Reddit's servers
            time.sleep(2)
    
    logger.info(f"Collection complete. Downloaded {total_downloaded} images.")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='Collect coupon images from Reddit.')
    parser.add_argument('--subreddits', nargs='+', help='List of subreddits to search')
    parser.add_argument('--search-terms', nargs='+', help='List of search terms')
    parser.add_argument('--limit', type=int, default=50, help='Maximum number of posts to retrieve per search')
    
    args = parser.parse_args()
    
    main(args.subreddits, args.search_terms, args.limit)
