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
3. **Outlier Detection**: System to identify and handle unusual coupon formats
4. **Automatic Field Detection**: Tools to automatically detect common fields in coupons
5. **Annotation**: Web-based tool for annotating coupon fields
6. **Data Augmentation**: Tools to increase dataset diversity through image transformations
7. **Model Training Integration**: Components to prepare data for model training
8. **Data Management**: Tools for versioning and tracking the dataset

## Getting Started

### Prerequisites

- Python 3.8+
- Required Python packages: `pip install -r requirements.txt`

### Basic Usage

1. **Collect data**:
   ```
   python main.py collect
   ```

2. **Test with real Reddit links**:
   ```
   python main.py test-real-data
   ```

3. **Preprocess images**:
   ```
   python main.py preprocess
   ```

4. **Detect outliers**:
   ```
   python main.py outliers
   ```

5. **Run enhanced outlier detection**:
   ```
   python main.py enhanced-outliers
   ```

6. **Automatically detect fields**:
   ```
   python main.py auto-detect
   ```

7. **Start annotation server**:
   ```
   python main.py annotate
   ```

8. **Prepare data for model training**:
   ```
   python main.py prepare-training
   ```

9. **Run the entire pipeline**:
   ```
   python main.py all
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
