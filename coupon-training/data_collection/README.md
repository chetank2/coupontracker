# Coupon Data Collection and Annotation System

This directory contains tools for collecting, preprocessing, annotating, and managing coupon data for the CouponTracker project.

## Directory Structure

```
data_collection/
├── raw_images/            # Raw coupon images collected from various sources
│   ├── zomato/            # Zomato coupon images
│   ├── gpay/              # Google Pay coupon images
│   ├── phonepe/           # PhonePe coupon images
│   ├── myntra/            # Myntra coupon images
│   └── other/             # Other coupon images
├── processed_images/      # Preprocessed images ready for annotation
├── annotations/           # Annotation data in JSON format
├── augmented/             # Augmented images for training
├── outliers/              # Detected outlier images
│   ├── visual_outliers/   # Visually unusual coupons
│   ├── content_outliers/  # Content-wise unusual coupons
│   └── mixed_outliers/    # Coupons that are outliers in multiple ways
└── scripts/               # Data collection and processing scripts
```

## Components

1. **Data Collection**: Scripts to collect coupon images from Reddit and other sources
2. **Preprocessing**: Tools to clean, normalize, and enhance coupon images
3. **Annotation**: Web-based tool for annotating coupon fields
4. **Outlier Detection**: System to identify and handle unusual coupon formats
5. **Data Management**: Tools for versioning and tracking the dataset

## Getting Started

### Prerequisites

- Python 3.8+
- Required Python packages: `pip install -r requirements.txt`

### Basic Usage

1. **Collect data**:
   ```
   python reddit_scraper.py
   ```

2. **Preprocess images**:
   ```
   python image_preprocessor.py
   ```

3. **Detect outliers**:
   ```
   python outlier_detector.py
   ```

4. **Start annotation server**:
   ```
   python annotation_server.py
   ```

## Implementation Plan

1. **Phase 1**: Basic data collection from Reddit
2. **Phase 2**: Image preprocessing and quality control
3. **Phase 3**: Outlier detection and handling
4. **Phase 4**: Annotation system development
5. **Phase 5**: Dataset management and versioning

## Contributing

When contributing to this project, please follow these guidelines:
- Use descriptive commit messages
- Document new functions and components
- Add tests for new functionality
- Update the README with any necessary information
