# CouponTracker Web UI

This is the web interface for the CouponTracker standardized process. It provides a user-friendly way to train and test the coupon recognition model.

## Features

- **Home Page**: Displays model metrics and quick links to other features
- **Training Page**: Upload and annotate coupon images for training
- **Testing Page**: Test the model with new coupon images
- **Train from URL**: Train the model using coupon images from a URL

## Setup

1. Make sure you have Python 3.6+ installed
2. Install the required packages:
   ```
   pip install flask
   ```
3. Run the web UI:
   ```
   cd web_ui
   python simple_web_ui.py
   ```
4. Open your browser and navigate to http://127.0.0.1:8080

## Usage

### Home Page

The home page displays the current model metrics and provides quick links to the other features.

### Training Page

The training page allows you to:
1. Upload coupon images by dragging and dropping or clicking the upload area
2. Annotate coupon fields (store name, coupon code, discount, expiry date)
3. Train the model with the annotated images
4. Optionally update the app with the new model

### Testing Page

The testing page allows you to:
1. Upload a coupon image
2. Test the model's recognition capabilities
3. View the extracted coupon fields with confidence scores

### Train from URL Page

The train from URL page allows you to:
1. Enter a URL containing coupon images (e.g., Reddit post, blog, etc.)
2. Configure training options (filtering, augmentation, app update)
3. Start the training process
4. Monitor the training progress

## Integration with Standardized Process

The web UI integrates with the standardized process for collecting coupon data from URLs and training the model. It provides a user-friendly interface for the following components:

- `coupon_scraper.py`: Extracts coupon images and metadata from URLs
- `image_processor.py`: Cleans, normalizes, and enhances coupon images
- `coupon_annotator.py`: Automatically detects and annotates coupon fields
- `train_model.py`: Trains the model using the annotated images
- `update_app.py`: Updates the Android app with the trained model
