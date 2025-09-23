# Coupon Tracker

This repository contains the standardized process for collecting coupon data from a URL, processing it, and training the CouponTracker model.

## 🚀 Live Demo - Static Web App

**Access the live demo at: [Your Netlify URL]**

This repository also includes a static demonstration version of the Coupon Pattern Recognition web application, optimized for deployment on Netlify.

### 📱 Web App Features

- **Dashboard**: View model performance metrics with interactive charts
- **Testing**: Upload images to test pattern recognition (simulated results)
- **Training**: Upload and annotate training images (demo mode)
- **URL Training**: Train models from web URLs (simulated process)

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
- `coupon_annotator.py`: Automatically detects and annotate coupon fields
- `train_model.py`: Trains the model using the annotated images
- `update_app.py`: Updates the Android app with the trained model
- `coupon_trainer_cli.py`: Command-line interface for the entire pipeline
- `web_ui/`: Web interface for the standardized process

## 🌐 Static Web App

The static web app provides a demonstration of the system's capabilities:

### 🛠️ Technology Stack
- **Frontend**: HTML5, CSS3, Bootstrap 5
- **Charts**: Chart.js
- **Icons**: Bootstrap Icons
- **Deployment**: Netlify

### 🎯 Demo Mode
This is a static demo version with simulated functionality:
- **No Backend**: All API calls are simulated with mock data
- **No Real Processing**: Image uploads and processing are simulated
- **No Persistence**: Data is not saved between sessions
- **No Real Training**: Model training is simulated with progress indicators

### 📁 Files Structure
- `index.html` - Main dashboard with interactive charts and metrics
- `testing.html` - Image testing interface with simulated OCR results  
- `training.html` - Training data management with annotation tools
- `train-from-url.html` - URL-based training with progress simulation
- `css/style.css` - Custom styling
- `js/main.js` - Interactive JavaScript with mock API functionality
- `netlify.toml` - Netlify deployment configuration

## Usage

See `README_STANDARDIZED_PROCESS.md` for detailed usage instructions.

## Web UI

The web UI provides a user-friendly interface for the standardized process, allowing users to:

1. Paste a URL containing coupon images
2. Configure options (filter, augment, update app)
3. Start the training process
4. Monitor the progress and view the results

## 🚀 Deployment

This static site is ready for deployment on Netlify:

1. Connect your Git repository to Netlify
2. Set the publish directory to the root folder
3. Deploy!

The `netlify.toml` file configures the build settings and redirects.

## License

This project is licensed under the MIT License - see the LICENSE file for details.
