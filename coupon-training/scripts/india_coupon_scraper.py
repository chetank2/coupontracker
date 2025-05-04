#!/usr/bin/env python3
"""
India Coupon Scraper

This script collects coupon images specifically from Indian coupon subreddits.
It downloads images, categorizes them, and maintains a log of collected data.
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
import random
from pathlib import Path

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("india_coupon_collection.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("india_coupon_scraper")

# Configuration
DEFAULT_SUBREDDITS = [
    'CouponsIndia', 
    'IndianBeautyDeals', 
    'DealsCouponsIndia',
    'unusedcodes'
]

DEFAULT_SEARCH_TERMS = [
    'coupon', 
    'code', 
    'discount', 
    'offer', 
    'cashback',
    'zomato',
    'swiggy',
    'phonepe',
    'paytm',
    'gpay',
    'myntra',
    'ajio',
    'flipkart',
    'amazon'
]

# Base directories
BASE_DIR = os.path.join('coupon-training', 'data', 'reddit_india')
RAW_IMAGES_DIR = os.path.join(BASE_DIR, 'raw')
COLLECTION_LOG = os.path.join(BASE_DIR, 'collection_log.json')

# Source categories
SOURCES = [
    'zomato', 'swiggy', 'phonepe', 'paytm', 'gpay', 'myntra', 
    'ajio', 'amazon', 'flipkart', 'cred', 'lenskart', 'other'
]

def ensure_directories_exist():
    """Create necessary directories if they don't exist."""
    os.makedirs(BASE_DIR, exist_ok=True)
    os.makedirs(RAW_IMAGES_DIR, exist_ok=True)
    
    for source in SOURCES:
        os.makedirs(os.path.join(RAW_IMAGES_DIR, source), exist_ok=True)
    
    # Initialize collection log if it doesn't exist
    if not os.path.exists(COLLECTION_LOG):
        with open(COLLECTION_LOG, 'w') as f:
            json.dump([], f)

def extract_image_urls_from_reddit_json(json_data):
    """Extract image URLs from Reddit API JSON response."""
    image_urls = []
    
    if not json_data or 'data' not in json_data:
        return image_urls
    
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
        elif post_data.get('is_gallery', False):
            gallery_data = post_data.get('gallery_data', {})
            media_metadata = post_data.get('media_metadata', {})
            
            if gallery_data and media_metadata:
                items = gallery_data.get('items', [])
                for item in items:
                    media_id = item.get('media_id')
                    if media_id and media_id in media_metadata:
                        media_item = media_metadata[media_id]
                        if media_item.get('status') == 'valid':
                            s = media_item.get('s', {})
                            url = s.get('u')
                            if url:
                                # Fix URL encoding
                                url = url.replace('&amp;', '&')
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
        'User-Agent': 'CouponTracker India Data Collection Script v1.0'
    }
    
    try:
        response = requests.get(url, params=params, headers=headers)
        response.raise_for_status()
        return extract_image_urls_from_reddit_json(response.json())
    except Exception as e:
        logger.error(f"Error searching Reddit for {search_term} in r/{subreddit}: {e}")
        return []

def determine_source(title, url, text=""):
    """Determine the source category of a coupon based on title and URL."""
    # Combine title and text for better matching
    content = (title + " " + text).lower()
    
    # Check for known sources
    for source in SOURCES[:-1]:  # All except 'other'
        if source.lower() in content or source.lower() in url.lower():
            return source
    
    # Check for common Indian terms
    indian_terms = ['india', 'rupee', 'rs.', '₹']
    if any(term in content for term in indian_terms):
        return 'other'
    
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
        
        logger.info(f"Downloaded image: {filename}")
        return file_path
    except Exception as e:
        logger.error(f"Error downloading image from {url}: {e}")
        return None

def log_collection(url, source, file_path, title, subreddit):
    """Log the collection of an image."""
    try:
        # Read existing log
        with open(COLLECTION_LOG, 'r') as f:
            log = json.load(f)
        
        # Add new entry
        log.append({
            'url': url,
            'source': source,
            'file_path': file_path,
            'title': title,
            'subreddit': subreddit,
            'timestamp': datetime.now().isoformat()
        })
        
        # Write updated log
        with open(COLLECTION_LOG, 'w') as f:
            json.dump(log, f, indent=2)
    except Exception as e:
        logger.error(f"Error logging collection: {e}")

def main():
    """Main function to collect coupon images from Reddit."""
    parser = argparse.ArgumentParser(description='Collect Indian coupon images from Reddit.')
    parser.add_argument('--subreddits', nargs='+', default=DEFAULT_SUBREDDITS, help='List of subreddits to search')
    parser.add_argument('--search-terms', nargs='+', default=DEFAULT_SEARCH_TERMS, help='List of search terms')
    parser.add_argument('--limit', type=int, default=100, help='Maximum number of posts to retrieve per search')
    args = parser.parse_args()
    
    subreddits = args.subreddits
    search_terms = args.search_terms
    limit = args.limit
    
    ensure_directories_exist()
    
    logger.info(f"Starting Indian coupon image collection from {len(subreddits)} subreddits")
    
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
    return total_downloaded

if __name__ == "__main__":
    main()
