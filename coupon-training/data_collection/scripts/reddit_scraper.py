#!/usr/bin/env python3
"""
Basic Reddit Coupon Image Scraper

This script collects coupon images from specified subreddits using the Reddit API.
"""

import os
import json
import time
import logging
import argparse
from datetime import datetime
import hashlib
from urllib.parse import urlparse

try:  # pragma: no cover - optional dependency for HTTP operations
    import requests  # type: ignore
except ImportError:  # pragma: no cover - handled gracefully during runtime
    requests = None  # type: ignore[assignment]

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
    'myntra coupon'
]

# Base directory for storing images
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW_IMAGES_DIR = os.path.join(BASE_DIR, 'raw_images')
COLLECTION_LOG = os.path.join(BASE_DIR, 'collection_log.json')

def ensure_directories_exist():
    """Ensure all necessary directories exist."""
    for source in ['zomato', 'gpay', 'phonepe', 'myntra', 'other']:
        os.makedirs(os.path.join(RAW_IMAGES_DIR, source), exist_ok=True)
    
    logger.info("Directory structure verified")

def _require_dependency(module, package_name: str):
    """Ensure an optional dependency is available before performing network operations."""

    if module is None:
        raise RuntimeError(
            f"Optional dependency '{package_name}' is required for this script. "
            f"Install it with 'pip install {package_name}'."
        )
    return module


def determine_source(title, url):
    """Determine the source of the coupon based on title and URL."""
    title_lower = title.lower()
    
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
    requests_module = _require_dependency(requests, "requests")

    try:
        response = requests_module.get(url, stream=True, timeout=10)
        response.raise_for_status()
        
        # Generate a unique filename
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

def search_reddit(subreddit, search_term, limit=25):
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
    
    requests_module = _require_dependency(requests, "requests")

    try:
        response = requests_module.get(url, params=params, headers=headers)
        response.raise_for_status()
        
        # Extract image URLs from response
        data = response.json()
        posts = data.get('data', {}).get('children', [])
        
        image_data = []
        for post in posts:
            post_data = post.get('data', {})
            title = post_data.get('title', '')
            url = post_data.get('url', '')
            
            # Only include image URLs
            if url and any(url.endswith(ext) for ext in ['.jpg', '.jpeg', '.png', '.gif']):
                image_data.append({
                    'url': url,
                    'title': title,
                    'subreddit': subreddit
                })
        
        return image_data
    except Exception as e:
        logger.error(f"Error searching Reddit for {search_term} in r/{subreddit}: {e}")
        return []

def main(subreddits=None, search_terms=None, limit=25):
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
                
                # Determine the source category
                source = determine_source(title, url)
                
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
    parser.add_argument('--limit', type=int, default=25, help='Maximum number of posts to retrieve per search')
    
    args = parser.parse_args()
    
    main(args.subreddits, args.search_terms, args.limit)
