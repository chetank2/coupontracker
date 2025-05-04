#!/usr/bin/env python3
"""
Script to download CRED coupon images from Reddit for training the CouponTracker model.
"""

import os
import requests
import json
import time
import urllib.parse
from urllib.request import urlretrieve
import re
from bs4 import BeautifulSoup

# Directory to save the images
SAVE_DIR = "data/cred_coupons"
os.makedirs(SAVE_DIR, exist_ok=True)

# User agent for requests
USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Safari/537.36"

def extract_image_urls_from_html(html_content):
    """Extract image URLs from Reddit HTML content."""
    soup = BeautifulSoup(html_content, 'html.parser')
    image_urls = []
    
    # Find all image elements
    img_elements = soup.find_all('img')
    
    for img in img_elements:
        src = img.get('src')
        if src and ('preview.redd.it' in src or 'i.redd.it' in src):
            # Clean up the URL
            url = src.split('?')[0]
            if url.endswith(('.jpg', '.jpeg', '.png')):
                image_urls.append(url)
    
    return list(set(image_urls))  # Remove duplicates

def download_images(image_urls):
    """Download images from the provided URLs."""
    print(f"Found {len(image_urls)} images to download.")
    
    for i, url in enumerate(image_urls):
        try:
            # Extract filename from URL
            filename = os.path.basename(url)
            # Add index to ensure unique filenames
            save_path = os.path.join(SAVE_DIR, f"cred_coupon_{i}_{filename}")
            
            # Download the image
            print(f"Downloading {url} to {save_path}")
            response = requests.get(url, headers={"User-Agent": USER_AGENT})
            
            if response.status_code == 200:
                with open(save_path, 'wb') as f:
                    f.write(response.content)
                print(f"Downloaded {save_path}")
            else:
                print(f"Failed to download {url}, status code: {response.status_code}")
                
            # Be nice to the server
            time.sleep(1)
        except Exception as e:
            print(f"Error downloading {url}: {e}")

def main():
    """Main function to download CRED coupon images from Reddit."""
    # Reddit search URL for CRED coupons
    search_url = "https://www.reddit.com/search/?q=cred+coupons&type=media"
    
    try:
        # Fetch the search results
        response = requests.get(
            search_url,
            headers={"User-Agent": USER_AGENT}
        )
        
        if response.status_code == 200:
            # Extract image URLs from the HTML
            image_urls = extract_image_urls_from_html(response.text)
            
            # Download the images
            download_images(image_urls)
            
            print(f"Downloaded {len(image_urls)} images to {SAVE_DIR}")
        else:
            print(f"Failed to fetch search results, status code: {response.status_code}")
    
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    main()
