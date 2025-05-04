# Standardized Coupon Training Process

This document describes the standardized process for collecting coupon data from a URL, processing it, and training the CouponTracker model.

## Overview

The standardized process consists of the following steps:

1. **Web Scraping**: Extract coupon images and metadata from a URL
2. **Image Processing**: Clean, normalize, and enhance the images
3. **Annotation**: Automatically detect and annotate coupon fields
4. **Training**: Train the model using the annotated images
5. **App Update**: Update the Android app with the trained model

## Requirements

- Python 3.8 or higher
- Dependencies listed in `requirements.txt`

## Installation

1. Clone the repository:
   ```
   git clone https://github.com/chetank2/coupontracker.git
   cd coupontracker/coupon-training
   ```

2. Create a virtual environment:
   ```
   python -m venv venv
   source venv/bin/activate  # On Windows: venv\Scripts\activate
   ```

3. Install dependencies:
   ```
   pip install -r requirements.txt
   ```

4. Install Tesseract OCR:
   - On Ubuntu: `sudo apt-get install tesseract-ocr`
   - On macOS: `brew install tesseract`
   - On Windows: Download and install from [GitHub](https://github.com/UB-Mannheim/tesseract/wiki)

## Usage

### Command-Line Interface

The easiest way to use the standardized process is through the command-line interface:

```
python coupon_trainer_cli.py <URL> [options]
```

#### Options

- `--base-dir`: Base directory for data (default: `data`)
- `--models-dir`: Directory for models (default: `models`)
- `--update-app`: Update the app with the trained model
- `--filter`: Filter out non-coupon images
- `--augment`: Generate augmented versions of processed images
- `--num-augmentations`: Number of augmentations to generate per image (default: 3)
- `--delay`: Delay between requests in seconds (default: 1)
- `--test-size`: Fraction of data to use for testing (default: 0.2)
- `--val-size`: Fraction of training data to use for validation (default: 0.2)
- `--epochs`: Number of training epochs (default: 20)
- `--report`: Generate a report of the training pipeline
- `--report-file`: Path to the report file (default: `training_report.json`)

#### Example

```
python coupon_trainer_cli.py https://www.example.com/coupons --filter --augment --update-app --report
```

### Individual Components

You can also use the individual components of the pipeline:

#### 1. Web Scraper

```
python coupon_scraper.py <URL> [--output-dir <dir>] [--delay <seconds>]
```

#### 2. Image Processor

```
python image_processor.py [--input-dir <dir>] [--output-dir <dir>] [--filter] [--augment] [--num-augmentations <num>]
```

#### 3. Coupon Annotator

```
python coupon_annotator.py [--input-dir <dir>] [--output-dir <dir>] [--metadata <file>] [--pattern-file <file>]
```

#### 4. Model Trainer

```
python train_model.py [--input-dir <dir>] [--output-dir <dir>] [--test-size <fraction>] [--val-size <fraction>] [--epochs <num>]
```

#### 5. App Updater

```
python update_app.py [--models-dir <dir>] [--app-dir <dir>]
```

## Directory Structure

The standardized process creates the following directory structure:

```
data/
  ├── scraped_coupons/     # Raw coupon images and metadata
  ├── processed_coupons/   # Processed and augmented images
  ├── annotated_coupons/   # Annotation files
  └── training_report.json # Training report
models/
  ├── patterns.json        # Pattern file
  ├── training_history.json # Training history
  ├── model_metadata.json  # Model metadata
  ├── history.json         # Model version history
  └── training_data/       # Training images
```

## Troubleshooting

### Common Issues

1. **No coupons found**: The URL might not contain any coupon images, or the scraper might not be able to detect them. Try a different URL or adjust the scraper settings.

2. **OCR errors**: If the annotator is not correctly identifying coupon fields, try improving the image quality or providing metadata.

3. **Low accuracy**: If the model has low accuracy, try increasing the number of training images, augmenting the images, or adjusting the training parameters.

### Logs

The standardized process creates log files for each component:

- `coupon_scraper.log`
- `image_processor.log`
- `coupon_annotator.log`
- `train_model.log`
- `update_app.log`
- `coupon_trainer.log`

Check these logs for detailed information about any errors or issues.

## Extending the Process

### Adding Support for New Websites

To add support for a new website, modify the `coupon_scraper.py` file:

1. Add a new method to the `CouponScraper` class for the website
2. Update the `scrape` method to detect and use the new method

### Improving OCR

To improve OCR accuracy:

1. Modify the `_extract_text_regions` method in `coupon_annotator.py`
2. Add or update the regex patterns in the `patterns` dictionary

### Adding New Field Types

To add support for new coupon field types:

1. Add the field type to the `field_types` list in `coupon_annotator.py`
2. Add regex patterns for the field type to the `patterns` dictionary
3. Update the `_extract_field_value` method to handle the new field type
