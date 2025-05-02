# Coupon Pattern Recognition Web UI

This web interface allows you to interact with the coupon pattern recognition model through a browser. You can upload coupon images for training, annotate them, train the model, test the model with new images, and update the model in the Android app.

## Features

1. **Training Interface**
   - Upload multiple coupon images
   - Annotate regions of interest (store name, description, expiry date, code, amount)
   - Save annotations
   - Train the model

2. **Testing Interface**
   - Upload coupon images for testing
   - View recognition results with highlighted regions
   - See extracted text and detected elements

3. **Model Management**
   - View model history
   - Update the model in the Android app

## Setup

### Prerequisites

- Python 3.7 or higher
- Flask
- OpenCV
- NumPy
- Fabric.js (included via CDN)

### Installation

1. Install the required Python packages:

```bash
pip install flask opencv-python numpy
```

2. Run the Flask application:

```bash
cd coupon-training/web_ui
python app.py
```

3. Open your browser and navigate to:

```
http://localhost:5000
```

## Usage

### Training

1. Go to the Training page
2. Upload coupon images using the drag-and-drop area or browse button
3. Select an image from the gallery
4. Choose an annotation type (store, description, expiry, code, amount)
5. Draw rectangles around the corresponding regions in the image
6. Save annotations
7. Repeat for all images
8. Click "Train Model" to generate a pattern file

### Testing

1. Go to the Testing page
2. Upload a coupon image
3. View the recognition results
4. Check the extracted text and detected elements

### Updating the App

1. Go to the Home page
2. Click "Update App Model"
3. Confirm the update
4. The model in the Android app will be updated with the latest trained model

## Directory Structure

```
web_ui/
├── app.py                  # Flask application
├── static/                 # Static assets
│   ├── css/                # CSS styles
│   ├── js/                 # JavaScript files
│   └── uploads/            # Temporary storage for uploads
├── templates/              # HTML templates
│   ├── index.html          # Main page
│   ├── training.html       # Training interface
│   └── testing.html        # Testing interface
└── utils/                  # Utility functions
    ├── model_manager.py    # Model management functions
    └── image_processor.py  # Image processing functions
```

## API Endpoints

- `/api/upload/training` - Upload training images
- `/api/upload/testing` - Upload testing images
- `/api/annotate` - Save annotation data
- `/api/train` - Train the model
- `/api/update-app` - Update the model in the app
- `/api/models` - Get list of available models
