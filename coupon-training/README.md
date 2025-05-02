# Coupon Element Detection Training Environment

This is a training environment for building and training a model to detect elements in coupon images using OpenCV and Tesseract OCR. It includes both command-line tools and a web interface for training and testing the model.

## Setup

1. Install the required dependencies:
   ```
   python setup_environment.py
   ```

   For the web UI:
   ```
   pip install -r web_ui/requirements.txt
   ```

2. Make sure Tesseract OCR is installed on your system:
   - macOS: `brew install tesseract`
   - Ubuntu/Debian: `sudo apt-get install tesseract-ocr`
   - Windows: Download installer from https://github.com/UB-Mannheim/tesseract/wiki

## Directory Structure

- `data/raw`: Raw coupon images
- `data/processed`: Preprocessed images
- `data/annotated`: Annotated coupon images
- `data/box-files`: Box files for Tesseract training
- `models`: Output directory for trained models
- `scripts`: Training scripts
- `utils`: Utility functions

## Training Workflow

### Using the Web Interface (Recommended)

1. Start the web UI:
   ```
   python run_web_ui.py
   ```

2. Open your browser and navigate to:
   ```
   http://localhost:5001
   ```

3. Go to the Training page and upload coupon images.

4. Select an image and annotate it by drawing rectangles around:
   - Store name
   - Description
   - Expiry date
   - Coupon code
   - Amount/discount

5. Save the annotations.

6. Train the model using the "Train Model" button.

7. Go to the Home page and click "Update App Model" to update the model in the Android app.

### Using Command-Line Tools

1. Collect coupon images and place them in the `data/raw` directory.

2. Preprocess the images:
   ```
   cd scripts
   python preprocess_images.py
   ```

3. Annotate the coupon elements:
   ```
   cd scripts
   python annotate_coupons.py
   ```

4. Generate box files for Tesseract training:
   ```
   cd scripts
   python generate_box_files.py
   ```

5. Train the Tesseract model:
   ```
   cd scripts
   python train_tesseract.py
   ```

6. Evaluate the trained model:
   ```
   cd scripts
   python evaluate_model.py
   ```

7. Prepare the model for Android integration:
   ```
   cd scripts
   python prepare_android_model.py pattern --pattern-file ../models/simplified/coupon_patterns.txt --app-assets-dir ../../app/src/main/assets/coupon_model
   ```

## Android Integration

1. Copy the trained model to the Android app's assets directory:
   ```
   cp android_model/coupon.traineddata /path/to/CouponTracker3/app/src/main/assets/tessdata/
   ```

2. Update the Android app to use the custom model:
   ```kotlin
   tesseractOCRHelper.initialize(
       language = "coupon",
       useCustomModel = true
   )
   ```

## Scripts

- `setup_environment.py`: Set up the training environment
- `scripts/preprocess_images.py`: Preprocess coupon images
- `scripts/annotate_coupons.py`: Annotate coupon elements
- `scripts/generate_box_files.py`: Generate box files for Tesseract training
- `scripts/train_tesseract.py`: Train the Tesseract model
- `scripts/evaluate_model.py`: Evaluate the trained model
- `scripts/prepare_android_model.py`: Prepare the model for Android integration

## Utilities

- `utils/image_utils.py`: Image processing utilities
- `utils/text_utils.py`: Text extraction utilities
