# Coupon Standardized Process

This repository contains the standardized process for collecting coupon data from a URL, processing it, and training the CouponTracker model.

## Overview

The standardized process consists of the following steps:

1. **Web Scraping**: Extract coupon images and metadata from a URL
2. **Image Processing**: Clean, normalize, and enhance the images
3. **Annotation**: Automatically detect and annotate coupon fields
4. **Training**: Train the model using the annotated images
5. **App Update**: Update the Android app with the trained model

## Components

- `coupon_scraper.py`: Extracts coupon images and metadata from URLs
- `image_processor.py`: Cleans, normalizes, and enhances coupon images
- `coupon_annotator.py`: Automatically detects and annotates coupon fields
- `train_model.py`: Trains the model using the annotated images
- `update_app.py`: Updates the Android app with the trained model
- `coupon_trainer_cli.py`: Command-line interface for the entire pipeline
- `web_ui/`: Web interface for the standardized process

## Usage

See `README_STANDARDIZED_PROCESS.md` for detailed usage instructions.

## Web UI

The web UI provides a user-friendly interface for the standardized process, allowing users to:

1. Paste a URL containing coupon images
2. Configure options (filter, augment, update app)
3. Start the training process
4. Monitor the progress and view the results

## License

This project is licensed under the MIT License - see the LICENSE file for details.
