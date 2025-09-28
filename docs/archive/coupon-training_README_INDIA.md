# Indian Coupon Recognition Training Pipeline

This directory contains scripts and data for training a model to recognize Indian coupons from r/CouponsIndia and other Indian coupon sources.

## Directory Structure

```
coupon-training/
├── data/
│   └── reddit_india/
│       ├── raw/             # Raw images downloaded from Reddit
│       ├── processed/       # Preprocessed images
│       ├── annotated/       # Annotated images
│       ├── augmented/       # Augmented images
│       └── training/        # Data prepared for model training
├── scripts/
│   ├── india_coupon_scraper.py       # Script to download coupon images from Reddit
│   ├── india_image_preprocessor.py   # Script to preprocess images
│   ├── india_annotation_helper.py    # Script to help with annotation
│   ├── india_data_augmentation.py    # Script to augment data
│   ├── india_model_training.py       # Script to prepare data for model training
│   └── india_coupon_pipeline.py      # Master script to run the entire pipeline
└── README_INDIA.md                   # This file
```

## Pipeline Steps

The pipeline consists of the following steps:

1. **Data Collection**: Download coupon images from r/CouponsIndia and other Indian coupon subreddits.
2. **Image Preprocessing**: Preprocess the images to enhance quality and readability.
3. **Annotation**: Annotate the images to identify key regions (store name, coupon code, expiry date, etc.).
4. **Data Augmentation**: Augment the annotated images to increase the dataset size.
5. **Model Training Preparation**: Prepare the data for model training by splitting into train/val/test sets and converting annotations to the required format.

## Installation

Before running the pipeline, you need to install the required dependencies:

```bash
# Create a virtual environment (optional but recommended)
python3 -m venv india_coupon_env
source india_coupon_env/bin/activate  # On Windows: india_coupon_env\Scripts\activate

# Install dependencies
pip install -r coupon-training/requirements.txt
```

## Running the Pipeline

You can run the entire pipeline using the master script:

```bash
python3 coupon-training/scripts/india_coupon_pipeline.py
```

Or you can run individual steps:

```bash
# Data Collection
python coupon-training/scripts/india_coupon_scraper.py --subreddits CouponsIndia --limit 100

# Image Preprocessing
python coupon-training/scripts/india_image_preprocessor.py

# Annotation
python coupon-training/scripts/india_annotation_helper.py

# Data Augmentation
python coupon-training/scripts/india_data_augmentation.py --num-augmentations 3

# Model Training Preparation
python coupon-training/scripts/india_model_training.py --format yolo
```

## Command-Line Arguments

### Master Pipeline Script

```
usage: india_coupon_pipeline.py [-h] [--skip-collect] [--skip-preprocess] [--skip-annotate] [--skip-augment] [--skip-training-prep]
                               [--subreddits SUBREDDITS [SUBREDDITS ...]] [--search-terms SEARCH_TERMS [SEARCH_TERMS ...]] [--limit LIMIT]
                               [--input-dir INPUT_DIR] [--output-dir OUTPUT_DIR] [--num-augmentations NUM_AUGMENTATIONS]
                               [--train-split TRAIN_SPLIT] [--val-split VAL_SPLIT] [--test-split TEST_SPLIT] [--format {yolo,original}]

Orchestrate the Indian coupon recognition pipeline.

optional arguments:
  -h, --help            show this help message and exit
  --skip-collect        Skip data collection step
  --skip-preprocess     Skip image preprocessing step
  --skip-annotate       Skip annotation creation step
  --skip-augment        Skip data augmentation step
  --skip-training-prep  Skip training preparation step
  --subreddits SUBREDDITS [SUBREDDITS ...]
                        List of subreddits to search
  --search-terms SEARCH_TERMS [SEARCH_TERMS ...]
                        List of search terms
  --limit LIMIT         Maximum number of posts to retrieve per search
  --input-dir INPUT_DIR
                        Input directory for the current step
  --output-dir OUTPUT_DIR
                        Output directory for the current step
  --num-augmentations NUM_AUGMENTATIONS
                        Number of augmented versions to create per image
  --train-split TRAIN_SPLIT
                        Proportion of data to use for training
  --val-split VAL_SPLIT
                        Proportion of data to use for validation
  --test-split TEST_SPLIT
                        Proportion of data to use for testing
  --format {yolo,original}
                        Format to convert annotations to
```

## Annotation Guidelines for Indian Coupons

When annotating Indian coupon images, pay attention to the following regions:

1. **Store Name**: The name of the store or brand (e.g., Zomato, PhonePe, Myntra)
2. **Coupon Code**: The actual code to be used (e.g., WELCOME50, FIRST100)
3. **Expiry Date**: When the coupon expires (e.g., 30 Jun 2025)
4. **Amount/Discount**: The discount amount or percentage (e.g., ₹100 OFF, 50% OFF)
5. **Description**: Additional details about the coupon
6. **Minimum Purchase**: If applicable, the minimum purchase amount required

## Special Considerations for Indian Coupons

1. **Currency Symbol**: Look for the ₹ symbol to identify amount fields
2. **Date Formats**: Indian date formats may vary (DD/MM/YYYY or DD-MM-YYYY)
3. **Regional Terms**: Terms like "cashback" or "wallet" are common in Indian coupons
4. **Platform-Specific**: Some coupons are specific to Indian platforms (Swiggy, Zomato, PhonePe)

## Model Training

After preparing the data, you can train the model using YOLOv5 or another object detection framework:

```bash
# Clone YOLOv5 repository
git clone https://github.com/ultralytics/yolov5.git
cd yolov5

# Install requirements
pip install -r requirements.txt

# Train the model
python train.py --img 640 --batch 16 --epochs 100 --data ../coupon-training/data/reddit_india/training/dataset.yaml --weights yolov5s.pt
```

## Model Integration

After training, you can integrate the model into the CouponTracker app:

1. Export the model to ONNX or TFLite format
2. Copy the model file to the app's assets directory
3. Update the app code to use the new model

## Troubleshooting

If you encounter issues with the pipeline, check the log files:

- `india_coupon_collection.log`: Log file for data collection
- `india_preprocessing.log`: Log file for image preprocessing
- `india_annotation.log`: Log file for annotation
- `india_augmentation.log`: Log file for data augmentation
- `india_model_training.log`: Log file for model training preparation
- `india_coupon_pipeline.log`: Log file for the master pipeline
