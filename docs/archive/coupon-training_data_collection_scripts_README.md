# CouponTracker Data Collection Scripts

This directory contains scripts for collecting, preprocessing, annotating, and managing coupon data.

## Available Scripts

### 1. `reddit_scraper.py`

Collects coupon images from Reddit based on specified subreddits and search terms.

**Usage:**
```bash
python reddit_scraper.py [--subreddits SUBREDDITS [SUBREDDITS ...]] [--search-terms SEARCH_TERMS [SEARCH_TERMS ...]] [--limit LIMIT]
```

**Parameters:**
- `--subreddits`: List of subreddits to search (default: IndianBeautyDeals, CouponsIndia, DealsCouponsIndia, unusedcodes)
- `--search-terms`: List of search terms (default: zomato coupon, gpay coupon, phonepe coupon, myntra coupon)
- `--limit`: Maximum number of posts to retrieve per search (default: 25)

### 2. `image_preprocessor.py`

Preprocesses raw coupon images to prepare them for annotation and training.

**Usage:**
```bash
python image_preprocessor.py [--input-dir INPUT_DIR] [--output-dir OUTPUT_DIR]
```

**Parameters:**
- `--input-dir`: Input directory containing raw images (default: ../raw_images)
- `--output-dir`: Output directory for processed images (default: ../processed_images)

### 3. `basic_outlier_detector.py`

Identifies outliers in the coupon dataset based on visual features.

**Usage:**
```bash
python basic_outlier_detector.py [--input-dir INPUT_DIR]
```

**Parameters:**
- `--input-dir`: Input directory containing processed images (default: ../processed_images)

### 4. `annotation_server.py`

Provides a web interface for annotating coupon images.

**Usage:**
```bash
python annotation_server.py [--host HOST] [--port PORT]
```

**Parameters:**
- `--host`: Host to run the server on (default: 0.0.0.0)
- `--port`: Port to run the server on (default: 5000)

### 5. `test_real_data.py`

Tests the data collection pipeline with real Reddit links.

**Usage:**
```bash
python test_real_data.py [--links LINKS [LINKS ...]]
```

**Parameters:**
- `--links`: List of Reddit links to test (default: predefined list of Reddit links)

### 6. `enhanced_outlier_detector.py`

Provides improved outlier detection using clustering and more sophisticated feature extraction.

**Usage:**
```bash
python enhanced_outlier_detector.py [--input-dir INPUT_DIR]
```

**Parameters:**
- `--input-dir`: Input directory containing processed images (default: ../processed_images)

### 7. `auto_field_detector.py`

Automatically detects common fields in coupon images to assist with annotation.

**Usage:**
```bash
python auto_field_detector.py [--input-dir INPUT_DIR] [--no-visualize]
```

**Parameters:**
- `--input-dir`: Input directory containing processed images (default: ../processed_images)
- `--no-visualize`: Disable visualization of detections

### 8. `model_training_integration.py`

Prepares annotated coupon data for model training and integrates with the model training pipeline.

**Usage:**
```bash
python model_training_integration.py [--format {yolo,coco}] [--train-split TRAIN_SPLIT] [--val-split VAL_SPLIT] [--test-split TEST_SPLIT]
```

**Parameters:**
- `--format`: Annotation format (default: yolo)
- `--train-split`: Training data split ratio (default: 0.7)
- `--val-split`: Validation data split ratio (default: 0.15)
- `--test-split`: Test data split ratio (default: 0.15)

### 9. `data_augmentation.py`

Applies various augmentations to coupon images to increase the diversity of the training dataset.

**Usage:**
```bash
python data_augmentation.py [--input-dir INPUT_DIR] [--annotation-dir ANNOTATION_DIR]
```

**Parameters:**
- `--input-dir`: Input directory containing processed images (default: ../processed_images)
- `--annotation-dir`: Directory containing annotations (default: ../annotations)

## Workflow

The typical workflow for using these scripts is:

1. Collect coupon images from Reddit using `reddit_scraper.py` or test with real links using `test_real_data.py`
2. Preprocess the collected images using `image_preprocessor.py`
3. Identify outliers using `basic_outlier_detector.py` or the enhanced version `enhanced_outlier_detector.py`
4. Automatically detect fields using `auto_field_detector.py` to assist with annotation
5. Annotate the processed images using the web interface provided by `annotation_server.py`
6. Augment the annotated images using `data_augmentation.py`
7. Prepare the annotated data for model training using `model_training_integration.py`

You can also use the main orchestration script (`../main.py`) to run the entire process in sequence.
