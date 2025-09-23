#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Analyze Reddit coupon links for potential outliers.
This script analyzes the Reddit coupon links to identify potential outliers.
"""

import os
import sys
import re
import numpy as np
from sklearn.cluster import DBSCAN
from urllib.parse import urlparse, unquote
import matplotlib.pyplot as plt

# Add parent directory to path
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# List of Reddit coupon image URLs from download_reddit_coupons.py
COUPON_URLS = [
    # Citizen watch coupons
    "https://preview.redd.it/citizen-cred-coupon-v0-oz9bi3gs3a9e1.jpeg",
    "https://preview.redd.it/cred-is-giving-40-off-coupons-on-these-citizen-watches-v0-qi5smhgtjybd1.jpg",
    "https://preview.redd.it/new-collection-added-in-cred-x-citizen-40-off-coupon-v0-vr21qwf35v0e1.jpg",
    "https://preview.redd.it/citizen-cred-coupons-v0-glwg9pac7c9e1.jpg",
    
    # Myntra coupon
    "https://preview.redd.it/can-anyone-please-pm-me-this-myntra-coupon-from-cred-app-if-v0-71q0bcz1iz6e1.jpeg",
    
    # Swiggy coupon
    "https://preview.redd.it/coupon-credswiggyupi-v0-ers2etec5nre1.png",
    
    # Beauty product coupons
    "https://preview.redd.it/renee-perfumes-at-210-on-cred-app-v0-151o8yg3y8me1.jpg",
    "https://preview.redd.it/dove-body-scrub-at-347-on-zepto-cred-coupon-v0-7s2pyre25rwe1.jpeg",
    "https://preview.redd.it/pilgrim-buy-3-cred-coupon-v0-8tsytz6rk4gb1.jpg",
    
    # Cred coupon collections
    "https://preview.redd.it/cred-coupons-23-05-2023-v0-dg5ay4qsui1b1.jpg",
    "https://preview.redd.it/cred-discount-coupons-dt-16-04-2022-v0-fs5f626et6ua1.jpg",
    "https://preview.redd.it/cred-coins-rush-coupons-giveaway-v0-amz5h2a6aqge1.jpg",
    "https://preview.redd.it/cred-coupons-giveaway-v0-z2946hekk9he1.jpg",
    
    # Ajio coupon
    "https://preview.redd.it/cred-ajio-coupon-v0-kasr2mwteqqc1.png"
]

def extract_features_from_url(url):
    """
    Extract features from a URL for analysis
    
    Args:
        url: URL to analyze
        
    Returns:
        dict: Dictionary of features
    """
    # Parse URL
    parsed_url = urlparse(url)
    
    # Extract path
    path = unquote(parsed_url.path)
    
    # Extract filename
    filename = os.path.basename(path)
    
    # Extract extension
    _, extension = os.path.splitext(filename)
    extension = extension.lower()
    
    # Extract title (part before the v0 marker)
    title_match = re.search(r'([^/]+)-v0-', path)
    title = title_match.group(1) if title_match else ""
    
    # Extract ID (part after the v0 marker)
    id_match = re.search(r'-v0-([^/]+)', path)
    post_id = id_match.group(1) if id_match else ""
    
    # Count words in title
    words = re.findall(r'\w+', title)
    word_count = len(words)
    
    # Check for specific keywords
    has_coupon = 1 if 'coupon' in title.lower() else 0
    has_cred = 1 if 'cred' in title.lower() else 0
    has_off = 1 if 'off' in title.lower() else 0
    has_discount = 1 if 'discount' in title.lower() else 0
    has_giveaway = 1 if 'giveaway' in title.lower() else 0
    
    # Check for specific brands
    brands = ['citizen', 'myntra', 'swiggy', 'ajio', 'phonepe', 'renee', 'dove', 'pilgrim']
    brand_counts = sum(1 for brand in brands if brand in title.lower())
    
    # Check for dates
    has_date = 1 if re.search(r'\d{2}[-/]\d{2}[-/]\d{2,4}', title) or re.search(r'\d{2}[-/]\d{2}', title) else 0
    
    # Check for price
    has_price = 1 if re.search(r'\d+', title) else 0
    
    return {
        'extension': extension,
        'word_count': word_count,
        'has_coupon': has_coupon,
        'has_cred': has_cred,
        'has_off': has_off,
        'has_discount': has_discount,
        'has_giveaway': has_giveaway,
        'brand_counts': brand_counts,
        'has_date': has_date,
        'has_price': has_price,
        'title': title,
        'post_id': post_id,
        'url': url
    }

def main():
    # Extract features from URLs
    features_list = []
    for url in COUPON_URLS:
        features = extract_features_from_url(url)
        features_list.append(features)
    
    # Print basic statistics
    print(f"Total URLs: {len(features_list)}")
    
    # Count extensions
    extensions = {}
    for features in features_list:
        ext = features['extension']
        extensions[ext] = extensions.get(ext, 0) + 1
    
    print("\nExtension distribution:")
    for ext, count in extensions.items():
        print(f"  {ext}: {count} ({count/len(features_list)*100:.1f}%)")
    
    # Count brands
    brands = {}
    for features in features_list:
        title = features['title'].lower()
        for brand in ['citizen', 'myntra', 'swiggy', 'ajio', 'phonepe', 'renee', 'dove', 'pilgrim']:
            if brand in title:
                brands[brand] = brands.get(brand, 0) + 1
    
    print("\nBrand distribution:")
    for brand, count in brands.items():
        print(f"  {brand}: {count} ({count/len(features_list)*100:.1f}%)")
    
    # Extract numerical features for clustering
    numerical_features = []
    for features in features_list:
        numerical_features.append([
            1 if features['extension'] == '.jpg' else (2 if features['extension'] == '.jpeg' else 3),  # Convert extension to number
            features['word_count'],
            features['has_coupon'],
            features['has_cred'],
            features['has_off'],
            features['has_discount'],
            features['has_giveaway'],
            features['brand_counts'],
            features['has_date'],
            features['has_price']
        ])
    
    # Convert to numpy array
    X = np.array(numerical_features)
    
    # Normalize features
    X_mean = np.mean(X, axis=0)
    X_std = np.std(X, axis=0)
    X_normalized = (X - X_mean) / X_std
    
    # Apply DBSCAN clustering to identify outliers
    clustering = DBSCAN(eps=1.5, min_samples=2).fit(X_normalized)
    labels = clustering.labels_
    
    # Count outliers (label -1)
    n_outliers = list(labels).count(-1)
    print(f"\nIdentified {n_outliers} outliers out of {len(features_list)} URLs")
    
    # Print outlier details
    print("\nOutlier URLs:")
    for i, label in enumerate(labels):
        if label == -1:
            features = features_list[i]
            print(f"  {i+1}. {features['title']} ({features['extension']})")
            print(f"     Word count: {features['word_count']}")
            print(f"     Keywords: coupon={features['has_coupon']}, cred={features['has_cred']}, off={features['has_off']}, discount={features['has_discount']}, giveaway={features['has_giveaway']}")
            print(f"     Brands: {features['brand_counts']}")
            print(f"     Date: {features['has_date']}, Price: {features['has_price']}")
            print(f"     URL: {features['url']}")
            print()
    
    # Print normal URL details
    print("\nNormal URLs:")
    for i, label in enumerate(labels):
        if label != -1:
            features = features_list[i]
            print(f"  {i+1}. {features['title']} ({features['extension']})")
    
    # Calculate statistics for normal vs outlier URLs
    normal_indices = [i for i, label in enumerate(labels) if label != -1]
    outlier_indices = [i for i, label in enumerate(labels) if label == -1]
    
    normal_features = X[normal_indices]
    outlier_features = X[outlier_indices] if outlier_indices else np.array([])
    
    if len(normal_indices) > 0:
        print("\nNormal URLs statistics:")
        print(f"  Word count: {np.mean(normal_features[:, 1]):.2f} ± {np.std(normal_features[:, 1]):.2f}")
        print(f"  Has coupon: {np.mean(normal_features[:, 2]):.2f}")
        print(f"  Has cred: {np.mean(normal_features[:, 3]):.2f}")
        print(f"  Has off: {np.mean(normal_features[:, 4]):.2f}")
        print(f"  Has discount: {np.mean(normal_features[:, 5]):.2f}")
        print(f"  Has giveaway: {np.mean(normal_features[:, 6]):.2f}")
        print(f"  Brand counts: {np.mean(normal_features[:, 7]):.2f}")
        print(f"  Has date: {np.mean(normal_features[:, 8]):.2f}")
        print(f"  Has price: {np.mean(normal_features[:, 9]):.2f}")
    
    if len(outlier_indices) > 0:
        print("\nOutlier URLs statistics:")
        print(f"  Word count: {np.mean(outlier_features[:, 1]):.2f} ± {np.std(outlier_features[:, 1]):.2f}")
        print(f"  Has coupon: {np.mean(outlier_features[:, 2]):.2f}")
        print(f"  Has cred: {np.mean(outlier_features[:, 3]):.2f}")
        print(f"  Has off: {np.mean(outlier_features[:, 4]):.2f}")
        print(f"  Has discount: {np.mean(outlier_features[:, 5]):.2f}")
        print(f"  Has giveaway: {np.mean(outlier_features[:, 6]):.2f}")
        print(f"  Brand counts: {np.mean(outlier_features[:, 7]):.2f}")
        print(f"  Has date: {np.mean(outlier_features[:, 8]):.2f}")
        print(f"  Has price: {np.mean(outlier_features[:, 9]):.2f}")

if __name__ == "__main__":
    main()
