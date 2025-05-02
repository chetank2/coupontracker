#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import sys
import json
import uuid
from datetime import datetime
from flask import Flask, render_template, request, jsonify, send_from_directory, redirect, url_for

# Add parent directory to path to import from scripts
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# Import utility modules
from utils.model_manager import ModelManager
from utils.image_processor import ImageProcessor

# Initialize Flask app
app = Flask(__name__)
app.config['UPLOAD_FOLDER'] = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'static', 'uploads')
app.config['MAX_CONTENT_LENGTH'] = 16 * 1024 * 1024  # 16MB max upload size

# Ensure upload directory exists
os.makedirs(app.config['UPLOAD_FOLDER'], exist_ok=True)

# Initialize utility classes
model_manager = ModelManager()
image_processor = ImageProcessor()

@app.route('/')
def index():
    """Render the main page"""
    return render_template('index.html')

@app.route('/training')
def training():
    """Render the training interface"""
    return render_template('training.html')

@app.route('/testing')
def testing():
    """Render the testing interface"""
    return render_template('testing.html')

@app.route('/api/upload/training', methods=['POST'])
def upload_training_images():
    """Handle training image uploads"""
    if 'files[]' not in request.files:
        return jsonify({'error': 'No files provided'}), 400
    
    files = request.files.getlist('files[]')
    uploaded_files = []
    
    for file in files:
        if file and allowed_file(file.filename):
            # Generate a unique filename
            filename = str(uuid.uuid4()) + os.path.splitext(file.filename)[1]
            filepath = os.path.join(app.config['UPLOAD_FOLDER'], filename)
            file.save(filepath)
            
            # Process the image (resize, normalize)
            processed_path = image_processor.preprocess_image(filepath)
            
            uploaded_files.append({
                'original_name': file.filename,
                'saved_name': filename,
                'path': processed_path,
                'url': url_for('static', filename=f'uploads/{filename}')
            })
    
    return jsonify({'files': uploaded_files})

@app.route('/api/upload/testing', methods=['POST'])
def upload_testing_images():
    """Handle testing image uploads"""
    if 'file' not in request.files:
        return jsonify({'error': 'No file provided'}), 400
    
    file = request.files['file']
    if file and allowed_file(file.filename):
        # Generate a unique filename
        filename = str(uuid.uuid4()) + os.path.splitext(file.filename)[1]
        filepath = os.path.join(app.config['UPLOAD_FOLDER'], filename)
        file.save(filepath)
        
        # Process the image
        processed_path = image_processor.preprocess_image(filepath)
        
        # Run pattern recognition
        results = model_manager.test_image(processed_path)
        
        return jsonify({
            'file': {
                'original_name': file.filename,
                'saved_name': filename,
                'url': url_for('static', filename=f'uploads/{filename}')
            },
            'results': results
        })
    
    return jsonify({'error': 'Invalid file'}), 400

@app.route('/api/annotate', methods=['POST'])
def save_annotation():
    """Save annotation data for an image"""
    data = request.json
    if not data or 'image' not in data or 'annotations' not in data:
        return jsonify({'error': 'Invalid annotation data'}), 400
    
    image_path = data['image']
    annotations = data['annotations']
    
    # Save annotations
    success = model_manager.save_annotations(image_path, annotations)
    
    if success:
        return jsonify({'status': 'success'})
    else:
        return jsonify({'error': 'Failed to save annotations'}), 500

@app.route('/api/train', methods=['POST'])
def train_model():
    """Train the model using annotated images"""
    # Start training process
    result = model_manager.train_model()
    
    if result['success']:
        return jsonify({
            'status': 'success',
            'message': 'Model trained successfully',
            'model_info': result['model_info']
        })
    else:
        return jsonify({
            'status': 'error',
            'message': result['error']
        }), 500

@app.route('/api/update-app', methods=['POST'])
def update_app_model():
    """Update the model in the Android app"""
    result = model_manager.update_app_model()
    
    if result['success']:
        return jsonify({
            'status': 'success',
            'message': 'App model updated successfully',
            'details': result['details']
        })
    else:
        return jsonify({
            'status': 'error',
            'message': result['error']
        }), 500

@app.route('/api/models', methods=['GET'])
def get_models():
    """Get list of available models"""
    models = model_manager.get_models()
    return jsonify({'models': models})

def allowed_file(filename):
    """Check if the file has an allowed extension"""
    ALLOWED_EXTENSIONS = {'png', 'jpg', 'jpeg', 'gif'}
    return '.' in filename and filename.rsplit('.', 1)[1].lower() in ALLOWED_EXTENSIONS

if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0', port=5000)
