#!/usr/bin/env python3
"""
Coupon Scraper - Extracts coupon images and metadata from URLs.

This module provides a flexible scraper that can extract coupon images
from various sources including Reddit, e-commerce sites, and coupon aggregators.
"""

import os
import re
import json
import time
import random
import argparse
import logging
import requests
import urllib.parse
from urllib.request import urlretrieve
from bs4 import BeautifulSoup
from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.common.exceptions import TimeoutException

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("coupon_scraper.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("coupon_scraper")

class CouponScraper:
    """Base class for coupon scrapers."""
    
    def __init__(self, output_dir="data/scraped_coupons", delay=1):
        """Initialize the scraper.
        
        Args:
            output_dir (str): Directory to save scraped coupons
            delay (int): Delay between requests in seconds
        """
        self.output_dir = output_dir
        self.delay = delay
        self.user_agent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Safari/537.36"
        
        # Create output directory
        os.makedirs(output_dir, exist_ok=True)
        
        # Initialize counters
        self.images_found = 0
        self.images_downloaded = 0
        
        logger.info(f"Initialized scraper with output directory: {output_dir}")
    
    def scrape(self, url):
        """Scrape coupons from the given URL.
        
        Args:
            url (str): URL to scrape
            
        Returns:
            list: List of dictionaries containing coupon data
        """
        logger.info(f"Scraping URL: {url}")
        
        # Detect the type of website and use the appropriate scraper
        if "reddit.com" in url:
            return self.scrape_reddit(url)
        elif any(site in url for site in ["amazon", "flipkart", "myntra"]):
            return self.scrape_ecommerce(url)
        else:
            return self.scrape_generic(url)
    
    def scrape_reddit(self, url):
        """Scrape coupons from Reddit.
        
        Args:
            url (str): Reddit URL to scrape
            
        Returns:
            list: List of dictionaries containing coupon data
        """
        logger.info("Using Reddit scraper")
        
        coupons = []
        
        try:
            # Get the page content
            response = requests.get(
                url,
                headers={"User-Agent": self.user_agent},
                timeout=30  # 30 second timeout to prevent hanging
            )
            
            if response.status_code != 200:
                logger.error(f"Failed to fetch URL: {url}, status code: {response.status_code}")
                return coupons
            
            # Parse the HTML
            soup = BeautifulSoup(response.text, 'html.parser')
            
            # Find all posts
            posts = soup.find_all("div", class_=re.compile("Post|post"))
            logger.info(f"Found {len(posts)} posts")
            
            for post in posts:
                # Extract post title
                title_elem = post.find("h1") or post.find("h3")
                title = title_elem.text.strip() if title_elem else "Unknown"
                
                # Extract post date
                date_elem = post.find("span", text=re.compile(r"ago|hours|days|months"))
                post_date = date_elem.text.strip() if date_elem else "Unknown"
                
                # Find images
                images = post.find_all("img")
                
                for img in images:
                    src = img.get("src")
                    if not src:
                        continue
                    
                    # Skip small images and icons
                    if any(x in src for x in ["icon", "avatar", "logo", "thumbnail"]):
                        continue
                    
                    # Clean up the URL
                    if "?" in src:
                        src = src.split("?")[0]
                    
                    # Only download image files
                    if not src.lower().endswith((".jpg", ".jpeg", ".png", ".gif")):
                        continue
                    
                    self.images_found += 1
                    
                    # Download the image
                    image_path = self._download_image(src, f"reddit_{self.images_found}")
                    
                    if image_path:
                        # Extract coupon details from title
                        coupon_data = self._extract_coupon_details(title)
                        coupon_data.update({
                            "source": "Reddit",
                            "post_title": title,
                            "post_date": post_date,
                            "image_path": image_path,
                            "url": url
                        })
                        
                        coupons.append(coupon_data)
                
                # Respect rate limits
                time.sleep(self.delay)
        
        except Exception as e:
            logger.error(f"Error scraping Reddit: {e}")
        
        logger.info(f"Scraped {len(coupons)} coupons from Reddit")
        return coupons
    
    def scrape_ecommerce(self, url):
        """Scrape coupons from e-commerce sites.
        
        Args:
            url (str): E-commerce URL to scrape
            
        Returns:
            list: List of dictionaries containing coupon data
        """
        logger.info("Using e-commerce scraper")
        
        coupons = []
        
        try:
            # Use Selenium for JavaScript-heavy sites
            chrome_options = Options()
            chrome_options.add_argument("--headless")
            chrome_options.add_argument("--disable-gpu")
            chrome_options.add_argument(f"user-agent={self.user_agent}")
            
            driver = webdriver.Chrome(options=chrome_options)
            driver.get(url)
            
            # Wait for the page to load
            WebDriverWait(driver, 10).until(
                EC.presence_of_element_located((By.TAG_NAME, "body"))
            )
            
            # Scroll down to load lazy content
            last_height = driver.execute_script("return document.body.scrollHeight")
            while True:
                driver.execute_script("window.scrollTo(0, document.body.scrollHeight);")
                time.sleep(2)
                new_height = driver.execute_script("return document.body.scrollHeight")
                if new_height == last_height:
                    break
                last_height = new_height
            
            # Find coupon elements
            coupon_elements = driver.find_elements(By.XPATH, "//div[contains(@class, 'coupon') or contains(@class, 'offer') or contains(@class, 'discount')]")
            logger.info(f"Found {len(coupon_elements)} potential coupon elements")
            
            for element in coupon_elements:
                # Extract coupon details
                coupon_code = None
                code_element = element.find_elements(By.XPATH, ".//span[contains(@class, 'code') or contains(text(), 'CODE')]")
                if code_element:
                    coupon_code = code_element[0].text.strip()
                
                # Extract description
                description = element.text.strip()
                
                # Find images
                images = element.find_elements(By.TAG_NAME, "img")
                
                if images:
                    for img in images:
                        src = img.get_attribute("src")
                        if not src:
                            continue
                        
                        # Skip small images and icons
                        if any(x in src for x in ["icon", "avatar", "logo", "thumbnail"]):
                            continue
                        
                        # Clean up the URL
                        if "?" in src:
                            src = src.split("?")[0]
                        
                        # Only download image files
                        if not src.lower().endswith((".jpg", ".jpeg", ".png", ".gif")):
                            continue
                        
                        self.images_found += 1
                        
                        # Download the image
                        image_path = self._download_image(src, f"ecommerce_{self.images_found}")
                        
                        if image_path:
                            # Extract coupon details from description
                            coupon_data = self._extract_coupon_details(description)
                            coupon_data.update({
                                "source": "E-commerce",
                                "code": coupon_code,
                                "description": description,
                                "image_path": image_path,
                                "url": url
                            })
                            
                            coupons.append(coupon_data)
                
                # Respect rate limits
                time.sleep(self.delay)
            
            driver.quit()
        
        except Exception as e:
            logger.error(f"Error scraping e-commerce site: {e}")
        
        logger.info(f"Scraped {len(coupons)} coupons from e-commerce site")
        return coupons
    
    def scrape_generic(self, url):
        """Scrape coupons from a generic website.
        
        Args:
            url (str): URL to scrape
            
        Returns:
            list: List of dictionaries containing coupon data
        """
        logger.info("Using generic scraper")
        
        coupons = []
        
        try:
            # Get the page content
            response = requests.get(
                url,
                headers={"User-Agent": self.user_agent},
                timeout=30  # 30 second timeout to prevent hanging
            )
            
            if response.status_code != 200:
                logger.error(f"Failed to fetch URL: {url}, status code: {response.status_code}")
                return coupons
            
            # Parse the HTML
            soup = BeautifulSoup(response.text, 'html.parser')
            
            # Find all images
            images = soup.find_all("img")
            logger.info(f"Found {len(images)} images")
            
            for img in images:
                src = img.get("src")
                if not src:
                    continue
                
                # Skip small images and icons
                if any(x in src for x in ["icon", "avatar", "logo", "thumbnail"]):
                    continue
                
                # Make sure the URL is absolute
                if src.startswith("/"):
                    parsed_url = urllib.parse.urlparse(url)
                    base_url = f"{parsed_url.scheme}://{parsed_url.netloc}"
                    src = base_url + src
                
                # Clean up the URL
                if "?" in src:
                    src = src.split("?")[0]
                
                # Only download image files
                if not src.lower().endswith((".jpg", ".jpeg", ".png", ".gif")):
                    continue
                
                self.images_found += 1
                
                # Download the image
                image_path = self._download_image(src, f"generic_{self.images_found}")
                
                if image_path:
                    # Try to find associated text
                    parent = img.parent
                    text = ""
                    
                    # Look for text in parent elements
                    for i in range(3):  # Check up to 3 levels up
                        if parent:
                            text = parent.text.strip()
                            if text:
                                break
                            parent = parent.parent
                    
                    # Extract coupon details from text
                    coupon_data = self._extract_coupon_details(text)
                    coupon_data.update({
                        "source": "Generic",
                        "description": text[:200] + "..." if len(text) > 200 else text,
                        "image_path": image_path,
                        "url": url
                    })
                    
                    coupons.append(coupon_data)
                
                # Respect rate limits
                time.sleep(self.delay)
        
        except Exception as e:
            logger.error(f"Error scraping generic site: {e}")
        
        logger.info(f"Scraped {len(coupons)} coupons from generic site")
        return coupons
    
    def _download_image(self, url, prefix):
        """Download an image from the given URL.
        
        Args:
            url (str): URL of the image
            prefix (str): Prefix for the filename
            
        Returns:
            str: Path to the downloaded image, or None if download failed
        """
        try:
            # Generate a filename
            ext = os.path.splitext(url)[1].lower()
            if not ext or ext not in [".jpg", ".jpeg", ".png", ".gif"]:
                ext = ".jpg"  # Default to jpg
            
            filename = f"{prefix}_{int(time.time())}_{random.randint(1000, 9999)}{ext}"
            filepath = os.path.join(self.output_dir, filename)
            
            # Download the image
            response = requests.get(url, headers={"User-Agent": self.user_agent}, stream=True, timeout=30)
            
            if response.status_code == 200:
                with open(filepath, 'wb') as f:
                    for chunk in response.iter_content(1024):
                        f.write(chunk)
                
                self.images_downloaded += 1
                logger.info(f"Downloaded image: {filename}")
                
                return filepath
            else:
                logger.warning(f"Failed to download image from {url}, status code: {response.status_code}")
                return None
        
        except Exception as e:
            logger.error(f"Error downloading image from {url}: {e}")
            return None
    
    def _extract_coupon_details(self, text):
        """Extract coupon details from text.
        
        Args:
            text (str): Text to extract details from
            
        Returns:
            dict: Dictionary containing extracted details
        """
        details = {
            "store": None,
            "discount": None,
            "code": None,
            "expiry_date": None,
            "min_order": None
        }
        
        if not text:
            return details
        
        # Extract store name
        store_patterns = [
            r"(CRED|Swiggy|Zomato|Amazon|Flipkart|Myntra|PhonePe|GPay|Google Pay|Paytm|Zepto)",
            r"from\s+([A-Z][a-zA-Z]+)"
        ]
        
        for pattern in store_patterns:
            match = re.search(pattern, text)
            if match:
                details["store"] = match.group(1)
                break
        
        # Extract discount
        discount_patterns = [
            r"(\d+%\s+off)",
            r"(\d+%\s+discount)",
            r"(₹\s*\d+\s+off)",
            r"(Rs\.\s*\d+\s+off)",
            r"(Rs\s*\d+\s+off)",
            r"(Flat\s+\d+%)",
            r"(Flat\s+₹\s*\d+)"
        ]
        
        for pattern in discount_patterns:
            match = re.search(pattern, text, re.IGNORECASE)
            if match:
                details["discount"] = match.group(1)
                break
        
        # Extract coupon code
        code_patterns = [
            r"code\s*:?\s*([A-Z0-9]+)",
            r"coupon\s*:?\s*([A-Z0-9]+)",
            r"([A-Z0-9]{5,})"  # Assume codes are at least 5 characters
        ]
        
        for pattern in code_patterns:
            match = re.search(pattern, text, re.IGNORECASE)
            if match:
                details["code"] = match.group(1)
                break
        
        # Extract expiry date
        expiry_patterns = [
            r"valid\s+till\s+(\d{1,2}\s+[a-zA-Z]+\s+\d{4})",
            r"valid\s+till\s+(\d{1,2}\s+[a-zA-Z]+)",
            r"expires\s+on\s+(\d{1,2}\s+[a-zA-Z]+\s+\d{4})",
            r"expires\s+on\s+(\d{1,2}\s+[a-zA-Z]+)",
            r"expiry\s*:?\s*(\d{1,2}\s+[a-zA-Z]+\s+\d{4})",
            r"expiry\s*:?\s*(\d{1,2}\s+[a-zA-Z]+)"
        ]
        
        for pattern in expiry_patterns:
            match = re.search(pattern, text, re.IGNORECASE)
            if match:
                details["expiry_date"] = match.group(1)
                break
        
        # Extract minimum order
        min_order_patterns = [
            r"min\s+order\s*:?\s*(₹\s*\d+)",
            r"min\s+order\s*:?\s*(Rs\.\s*\d+)",
            r"min\s+order\s*:?\s*(Rs\s*\d+)",
            r"minimum\s+order\s*:?\s*(₹\s*\d+)",
            r"minimum\s+order\s*:?\s*(Rs\.\s*\d+)",
            r"minimum\s+order\s*:?\s*(Rs\s*\d+)"
        ]
        
        for pattern in min_order_patterns:
            match = re.search(pattern, text, re.IGNORECASE)
            if match:
                details["min_order"] = match.group(1)
                break
        
        return details
    
    def save_results(self, coupons, output_file="scraped_coupons.json"):
        """Save the scraped coupons to a JSON file.
        
        Args:
            coupons (list): List of coupon dictionaries
            output_file (str): Path to the output file
        """
        output_path = os.path.join(self.output_dir, output_file)
        
        with open(output_path, 'w') as f:
            json.dump(coupons, f, indent=2)
        
        logger.info(f"Saved {len(coupons)} coupons to {output_path}")

def main():
    """Main function."""
    parser = argparse.ArgumentParser(description="Scrape coupons from a URL")
    parser.add_argument("url", help="URL to scrape")
    parser.add_argument("--output-dir", default="data/scraped_coupons", help="Directory to save scraped coupons")
    parser.add_argument("--delay", type=int, default=1, help="Delay between requests in seconds")
    
    args = parser.parse_args()
    
    scraper = CouponScraper(output_dir=args.output_dir, delay=args.delay)
    coupons = scraper.scrape(args.url)
    
    if coupons:
        scraper.save_results(coupons)
        print(f"Scraped {len(coupons)} coupons from {args.url}")
        print(f"Images found: {scraper.images_found}")
        print(f"Images downloaded: {scraper.images_downloaded}")
    else:
        print(f"No coupons found at {args.url}")

if __name__ == "__main__":
    main()
