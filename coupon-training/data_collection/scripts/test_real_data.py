#!/usr/bin/env python3
"""
Test Script for Real Coupon Data

This script tests the data collection pipeline with real Reddit links
and validates the results.
"""

import os
import sys
import json
import logging
import argparse
import requests
from pathlib import Path
import time
import random
from bs4 import BeautifulSoup
import re

# Add parent directory to path to import from sibling modules
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from scripts.reddit_scraper import download_image, determine_source, log_collection

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("test_real_data.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("test_real_data")

# Base directories
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW_IMAGES_DIR = os.path.join(BASE_DIR, 'raw_images')
TEST_RESULTS_DIR = os.path.join(BASE_DIR, 'test_results')
TEST_METADATA = os.path.join(TEST_RESULTS_DIR, 'test_metadata.json')

# Reddit links to test
DEFAULT_REDDIT_LINKS = [
    "https://www.reddit.com/r/IndianBeautyDeals/comments/1k7ivn0/gpay_coupons/",
    "https://www.reddit.com/r/IndianBeautyDeals/comments/1jm1kuy/gpay_coupon_code/",
    "https://www.reddit.com/r/IndianBeautyDeals/comments/1cxpbf8/sharing_gpay_coupons_for_dotkey_aqualogica/",
    "https://www.reddit.com/r/IndianBeautyDeals/comments/1if9uln/zomato_coupons_ajio_puma_etc/",
    "https://www.reddit.com/r/IndianBeautyDeals/comments/1f31irg/zomato_coupons/",
    "https://www.reddit.com/r/CouponsIndia/comments/1hhbf63/gpay_coupons/"
]

def ensure_directories_exist():
    """Ensure all necessary directories exist."""
    os.makedirs(TEST_RESULTS_DIR, exist_ok=True)
    for source in ['zomato', 'gpay', 'phonepe', 'myntra', 'other']:
        os.makedirs(os.path.join(RAW_IMAGES_DIR, source), exist_ok=True)
    
    logger.info("Directory structure verified")

def extract_image_urls_from_reddit_page(html_content):
    """Extract image URLs from Reddit HTML content."""
    soup = BeautifulSoup(html_content, 'html.parser')
    image_urls = []
    
    # Find all image elements
    img_elements = soup.find_all('img')
    
    for img in img_elements:
        src = img.get('src', '')
        if src and any(ext in src.lower() for ext in ['.jpg', '.jpeg', '.png', '.gif']):
            # Filter out small icons and thumbnails
            if 'icon' not in src.lower() and 'thumb' not in src.lower() and 'avatar' not in src.lower():
                image_urls.append(src)
    
    return image_urls

def scrape_reddit_post(url):
    """Scrape a Reddit post for images."""
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36'
    }
    
    try:
        response = requests.get(url, headers=headers)
        response.raise_for_status()
        
        # Extract post title
        soup = BeautifulSoup(response.text, 'html.parser')
        title_element = soup.find('h1')
        title = title_element.text if title_element else "Unknown Title"
        
        # Extract subreddit
        subreddit_match = re.search(r'reddit\.com/r/([^/]+)', url)
        subreddit = subreddit_match.group(1) if subreddit_match else "unknown"
        
        # Extract image URLs
        image_urls = extract_image_urls_from_reddit_page(response.text)
        
        logger.info(f"Found {len(image_urls)} images in post: {title}")
        
        return {
            'title': title,
            'subreddit': subreddit,
            'image_urls': image_urls
        }
    
    except Exception as e:
        logger.error(f"Error scraping Reddit post {url}: {e}")
        return None

def test_reddit_links(links):
    """Test the data collection pipeline with real Reddit links."""
    ensure_directories_exist()
    
    results = []
    
    for i, link in enumerate(links):
        logger.info(f"Testing link {i+1}/{len(links)}: {link}")
        
        # Scrape the Reddit post
        post_data = scrape_reddit_post(link)
        
        if not post_data or not post_data['image_urls']:
            logger.warning(f"No images found in post: {link}")
            continue
        
        # Process each image
        for j, image_url in enumerate(post_data['image_urls']):
            logger.info(f"Processing image {j+1}/{len(post_data['image_urls'])}: {image_url}")
            
            # Determine source
            source = determine_source(post_data['title'], image_url, "")
            
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
    
    # Save test results
    with open(TEST_METADATA, 'w') as f:
        json.dump(results, f, indent=2)
    
    logger.info(f"Testing complete. Collected {len(results)} images.")
    return results

def main():
    """Main function to test the data collection pipeline."""
    parser = argparse.ArgumentParser(description='Test the data collection pipeline with real Reddit links.')
    parser.add_argument('--links', nargs='+', help='List of Reddit links to test')
    
    args = parser.parse_args()
    
    links = args.links if args.links else DEFAULT_REDDIT_LINKS
    
    test_reddit_links(links)

if __name__ == "__main__":
    main()
