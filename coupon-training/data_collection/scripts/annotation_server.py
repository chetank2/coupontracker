#!/usr/bin/env python3
"""
Basic Annotation Server for Coupon Images

This script provides a simple web interface for annotating coupon images.
"""

import os
import json
import logging
import argparse
from datetime import datetime
from pathlib import Path
from flask import Flask, request, jsonify, render_template, send_from_directory

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("annotation_server.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("annotation_server")

# Base directories
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROCESSED_IMAGES_DIR = os.path.join(BASE_DIR, 'processed_images')
ANNOTATIONS_DIR = os.path.join(BASE_DIR, 'annotations')
STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'static')
TEMPLATES_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'templates')

# Ensure directories exist
os.makedirs(ANNOTATIONS_DIR, exist_ok=True)
os.makedirs(STATIC_DIR, exist_ok=True)
os.makedirs(TEMPLATES_DIR, exist_ok=True)

# Create Flask app
app = Flask(__name__, 
            static_folder=STATIC_DIR,
            template_folder=TEMPLATES_DIR)

def get_image_list():
    """Get a list of all processed images."""
    image_extensions = ['.jpg', '.jpeg', '.png', '.gif']
    image_paths = []
    
    for root, _, files in os.walk(PROCESSED_IMAGES_DIR):
        for file in files:
            if any(file.lower().endswith(ext) for ext in image_extensions):
                rel_path = os.path.relpath(os.path.join(root, file), PROCESSED_IMAGES_DIR)
                image_paths.append(rel_path)
    
    return sorted(image_paths)

def get_annotation_status():
    """Get the annotation status for all images."""
    image_list = get_image_list()
    status = {}
    
    for image_path in image_list:
        # Create an image_id from the path
        image_id = os.path.splitext(image_path)[0]
        annotation_file = os.path.join(ANNOTATIONS_DIR, f"{image_id}.json")
        
        if os.path.exists(annotation_file):
            with open(annotation_file, 'r') as f:
                annotation = json.load(f)
                verification_status = annotation.get('verification_status', 'unverified')
                status[image_path] = verification_status
        else:
            status[image_path] = 'not_annotated'
    
    return status

@app.route('/')
def index():
    """Render the main annotation interface."""
    # Create a simple HTML template if it doesn't exist
    template_path = os.path.join(TEMPLATES_DIR, 'index.html')
    if not os.path.exists(template_path):
        with open(template_path, 'w') as f:
            f.write("""
<!DOCTYPE html>
<html>
<head>
    <title>Coupon Annotation Tool</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 0; padding: 20px; }
        .container { display: flex; }
        .sidebar { width: 300px; padding: 10px; border-right: 1px solid #ccc; }
        .main { flex-grow: 1; padding: 10px; }
        .image-list { height: 500px; overflow-y: auto; }
        .image-item { padding: 5px; cursor: pointer; border-bottom: 1px solid #eee; }
        .image-item:hover { background-color: #f0f0f0; }
        .not_annotated { color: red; }
        .unverified { color: orange; }
        .verified { color: green; }
        .rejected { color: darkred; }
        .needs_review { color: purple; }
        .annotation-area { margin-top: 20px; }
        .field-group { margin-bottom: 15px; }
        .field-label { font-weight: bold; }
        .canvas-container { position: relative; margin-top: 10px; }
        canvas { border: 1px solid #ccc; }
    </style>
</head>
<body>
    <h1>Coupon Annotation Tool</h1>
    
    <div class="container">
        <div class="sidebar">
            <h2>Images</h2>
            <div class="image-list" id="image-list">
                <!-- Image list will be populated here -->
            </div>
        </div>
        
        <div class="main">
            <div id="image-container">
                <h2 id="current-image-title">Select an image to annotate</h2>
                <div class="canvas-container">
                    <canvas id="annotation-canvas"></canvas>
                </div>
            </div>
            
            <div class="annotation-area">
                <h3>Annotation Fields</h3>
                
                <div class="field-group">
                    <div class="field-label">Store Name</div>
                    <input type="text" id="store-name" placeholder="Store name">
                    <button onclick="startDrawing('store_name')">Draw Box</button>
                </div>
                
                <div class="field-group">
                    <div class="field-label">Coupon Code</div>
                    <input type="text" id="coupon-code" placeholder="Coupon code">
                    <button onclick="startDrawing('coupon_code')">Draw Box</button>
                </div>
                
                <div class="field-group">
                    <div class="field-label">Expiry Date</div>
                    <input type="text" id="expiry-date" placeholder="Expiry date (as shown)">
                    <input type="date" id="normalized-date" placeholder="Normalized date">
                    <button onclick="startDrawing('expiry_date')">Draw Box</button>
                </div>
                
                <div class="field-group">
                    <div class="field-label">Discount Amount</div>
                    <input type="text" id="discount-amount" placeholder="Discount amount (as shown)">
                    <input type="number" id="discount-value" placeholder="Numerical value">
                    <select id="discount-type">
                        <option value="percentage">Percentage</option>
                        <option value="fixed">Fixed Amount</option>
                        <option value="free_item">Free Item</option>
                        <option value="bogo">Buy One Get One</option>
                        <option value="other">Other</option>
                    </select>
                    <button onclick="startDrawing('discount_amount')">Draw Box</button>
                </div>
                
                <div class="field-group">
                    <div class="field-label">Minimum Purchase</div>
                    <input type="text" id="min-purchase" placeholder="Minimum purchase (as shown)">
                    <input type="number" id="min-purchase-value" placeholder="Numerical value">
                    <button onclick="startDrawing('min_purchase')">Draw Box</button>
                </div>
                
                <div class="field-group">
                    <div class="field-label">Description</div>
                    <textarea id="description" placeholder="Description or terms"></textarea>
                    <button onclick="startDrawing('description')">Draw Box</button>
                </div>
                
                <div class="field-group">
                    <div class="field-label">Notes</div>
                    <textarea id="notes" placeholder="Additional notes"></textarea>
                </div>
                
                <div class="field-group">
                    <div class="field-label">Is Outlier</div>
                    <input type="checkbox" id="is-outlier">
                    <select id="outlier-type" multiple>
                        <option value="visual">Visual</option>
                        <option value="content">Content</option>
                        <option value="mixed">Mixed</option>
                    </select>
                </div>
                
                <div class="field-group">
                    <button id="save-button" onclick="saveAnnotation()">Save Annotation</button>
                    <select id="verification-status">
                        <option value="unverified">Unverified</option>
                        <option value="verified">Verified</option>
                        <option value="rejected">Rejected</option>
                        <option value="needs_review">Needs Review</option>
                    </select>
                </div>
            </div>
        </div>
    </div>
    
    <script>
        // Global variables
        let currentImage = null;
        let currentField = null;
        let isDrawing = false;
        let startX, startY;
        let boundingBoxes = {};
        
        // Load image list
        function loadImageList() {
            fetch('/api/images')
                .then(response => response.json())
                .then(data => {
                    const imageList = document.getElementById('image-list');
                    imageList.innerHTML = '';
                    
                    data.images.forEach(image => {
                        const div = document.createElement('div');
                        div.className = `image-item ${data.status[image]}`;
                        div.textContent = image;
                        div.onclick = () => loadImage(image);
                        imageList.appendChild(div);
                    });
                });
        }
        
        // Load image and its annotation if available
        function loadImage(imagePath) {
            currentImage = imagePath;
            document.getElementById('current-image-title').textContent = imagePath;
            
            // Clear previous annotation
            clearAnnotation();
            
            // Load the image
            const canvas = document.getElementById('annotation-canvas');
            const ctx = canvas.getContext('2d');
            const img = new Image();
            img.onload = function() {
                canvas.width = img.width;
                canvas.height = img.height;
                ctx.drawImage(img, 0, 0);
                
                // Load annotation if available
                fetch(`/api/annotation/${encodeURIComponent(imagePath)}`)
                    .then(response => {
                        if (response.ok) {
                            return response.json();
                        }
                        return null;
                    })
                    .then(data => {
                        if (data) {
                            // Populate form fields
                            if (data.fields.store_name) {
                                document.getElementById('store-name').value = data.fields.store_name.text;
                                boundingBoxes.store_name = data.fields.store_name.bounding_box;
                            }
                            
                            if (data.fields.coupon_code) {
                                document.getElementById('coupon-code').value = data.fields.coupon_code.text;
                                boundingBoxes.coupon_code = data.fields.coupon_code.bounding_box;
                            }
                            
                            if (data.fields.expiry_date) {
                                document.getElementById('expiry-date').value = data.fields.expiry_date.text;
                                document.getElementById('normalized-date').value = data.fields.expiry_date.normalized_date || '';
                                boundingBoxes.expiry_date = data.fields.expiry_date.bounding_box;
                            }
                            
                            if (data.fields.discount_amount) {
                                document.getElementById('discount-amount').value = data.fields.discount_amount.text;
                                document.getElementById('discount-value').value = data.fields.discount_amount.value || '';
                                document.getElementById('discount-type').value = data.fields.discount_amount.type || 'percentage';
                                boundingBoxes.discount_amount = data.fields.discount_amount.bounding_box;
                            }
                            
                            if (data.fields.min_purchase) {
                                document.getElementById('min-purchase').value = data.fields.min_purchase.text;
                                document.getElementById('min-purchase-value').value = data.fields.min_purchase.value || '';
                                boundingBoxes.min_purchase = data.fields.min_purchase.bounding_box;
                            }
                            
                            if (data.fields.description) {
                                document.getElementById('description').value = data.fields.description.text;
                                boundingBoxes.description = data.fields.description.bounding_box;
                            }
                            
                            document.getElementById('notes').value = data.notes || '';
                            document.getElementById('is-outlier').checked = data.is_outlier || false;
                            
                            if (data.outlier_type) {
                                const outlierSelect = document.getElementById('outlier-type');
                                for (let i = 0; i < outlierSelect.options.length; i++) {
                                    outlierSelect.options[i].selected = data.outlier_type.includes(outlierSelect.options[i].value);
                                }
                            }
                            
                            document.getElementById('verification-status').value = data.verification_status || 'unverified';
                            
                            // Draw bounding boxes
                            drawAllBoxes();
                        }
                    });
            };
            img.src = `/api/image/${encodeURIComponent(imagePath)}`;
        }
        
        // Clear annotation form
        function clearAnnotation() {
            document.getElementById('store-name').value = '';
            document.getElementById('coupon-code').value = '';
            document.getElementById('expiry-date').value = '';
            document.getElementById('normalized-date').value = '';
            document.getElementById('discount-amount').value = '';
            document.getElementById('discount-value').value = '';
            document.getElementById('discount-type').value = 'percentage';
            document.getElementById('min-purchase').value = '';
            document.getElementById('min-purchase-value').value = '';
            document.getElementById('description').value = '';
            document.getElementById('notes').value = '';
            document.getElementById('is-outlier').checked = false;
            
            const outlierSelect = document.getElementById('outlier-type');
            for (let i = 0; i < outlierSelect.options.length; i++) {
                outlierSelect.options[i].selected = false;
            }
            
            document.getElementById('verification-status').value = 'unverified';
            
            boundingBoxes = {};
        }
        
        // Start drawing a bounding box
        function startDrawing(field) {
            currentField = field;
            isDrawing = true;
            
            const canvas = document.getElementById('annotation-canvas');
            canvas.onmousedown = handleMouseDown;
            canvas.onmousemove = handleMouseMove;
            canvas.onmouseup = handleMouseUp;
        }
        
        // Handle mouse down event
        function handleMouseDown(e) {
            if (!isDrawing) return;
            
            const canvas = document.getElementById('annotation-canvas');
            const rect = canvas.getBoundingClientRect();
            startX = e.clientX - rect.left;
            startY = e.clientY - rect.top;
        }
        
        // Handle mouse move event
        function handleMouseMove(e) {
            if (!isDrawing || !startX || !startY) return;
            
            const canvas = document.getElementById('annotation-canvas');
            const ctx = canvas.getContext('2d');
            const rect = canvas.getBoundingClientRect();
            const currentX = e.clientX - rect.left;
            const currentY = e.clientY - rect.top;
            
            // Redraw the image and all boxes
            const img = new Image();
            img.onload = function() {
                ctx.drawImage(img, 0, 0);
                
                // Draw all existing boxes
                drawAllBoxes();
                
                // Draw the current box
                ctx.strokeStyle = 'red';
                ctx.lineWidth = 2;
                ctx.strokeRect(
                    startX,
                    startY,
                    currentX - startX,
                    currentY - startY
                );
            };
            img.src = `/api/image/${encodeURIComponent(currentImage)}`;
        }
        
        // Handle mouse up event
        function handleMouseUp(e) {
            if (!isDrawing || !startX || !startY) return;
            
            const canvas = document.getElementById('annotation-canvas');
            const rect = canvas.getBoundingClientRect();
            const endX = e.clientX - rect.left;
            const endY = e.clientY - rect.top;
            
            // Save the bounding box
            const x1 = Math.min(startX, endX);
            const y1 = Math.min(startY, endY);
            const x2 = Math.max(startX, endX);
            const y2 = Math.max(startY, endY);
            
            boundingBoxes[currentField] = [x1, y1, x2, y2];
            
            // Reset drawing state
            isDrawing = false;
            startX = null;
            startY = null;
            
            // Remove event listeners
            canvas.onmousedown = null;
            canvas.onmousemove = null;
            canvas.onmouseup = null;
            
            // Redraw all boxes
            drawAllBoxes();
        }
        
        // Draw all bounding boxes
        function drawAllBoxes() {
            const canvas = document.getElementById('annotation-canvas');
            const ctx = canvas.getContext('2d');
            
            for (const field in boundingBoxes) {
                const box = boundingBoxes[field];
                
                // Use different colors for different fields
                switch (field) {
                    case 'store_name':
                        ctx.strokeStyle = 'blue';
                        break;
                    case 'coupon_code':
                        ctx.strokeStyle = 'green';
                        break;
                    case 'expiry_date':
                        ctx.strokeStyle = 'red';
                        break;
                    case 'discount_amount':
                        ctx.strokeStyle = 'purple';
                        break;
                    case 'min_purchase':
                        ctx.strokeStyle = 'orange';
                        break;
                    case 'description':
                        ctx.strokeStyle = 'brown';
                        break;
                    default:
                        ctx.strokeStyle = 'black';
                }
                
                ctx.lineWidth = 2;
                ctx.strokeRect(box[0], box[1], box[2] - box[0], box[3] - box[1]);
                
                // Add field label
                ctx.fillStyle = ctx.strokeStyle;
                ctx.font = '12px Arial';
                ctx.fillText(field, box[0], box[1] - 5);
            }
        }
        
        // Save annotation
        function saveAnnotation() {
            if (!currentImage) {
                alert('No image selected');
                return;
            }
            
            // Prepare annotation data
            const annotation = {
                image_id: currentImage.split('.')[0],
                source: 'manual_annotation',
                annotation_date: new Date().toISOString(),
                annotator_id: 'manual_annotator',
                fields: {},
                notes: document.getElementById('notes').value,
                is_outlier: document.getElementById('is-outlier').checked,
                outlier_type: Array.from(document.getElementById('outlier-type').selectedOptions).map(option => option.value),
                verification_status: document.getElementById('verification-status').value
            };
            
            // Add fields with bounding boxes
            if (document.getElementById('store-name').value && boundingBoxes.store_name) {
                annotation.fields.store_name = {
                    text: document.getElementById('store-name').value,
                    bounding_box: boundingBoxes.store_name,
                    confidence: 1.0
                };
            }
            
            if (document.getElementById('coupon-code').value && boundingBoxes.coupon_code) {
                annotation.fields.coupon_code = {
                    text: document.getElementById('coupon-code').value,
                    bounding_box: boundingBoxes.coupon_code,
                    confidence: 1.0
                };
            }
            
            if (document.getElementById('expiry-date').value && boundingBoxes.expiry_date) {
                annotation.fields.expiry_date = {
                    text: document.getElementById('expiry-date').value,
                    bounding_box: boundingBoxes.expiry_date,
                    confidence: 1.0
                };
                
                if (document.getElementById('normalized-date').value) {
                    annotation.fields.expiry_date.normalized_date = document.getElementById('normalized-date').value;
                }
            }
            
            if (document.getElementById('discount-amount').value && boundingBoxes.discount_amount) {
                annotation.fields.discount_amount = {
                    text: document.getElementById('discount-amount').value,
                    bounding_box: boundingBoxes.discount_amount,
                    confidence: 1.0
                };
                
                if (document.getElementById('discount-value').value) {
                    annotation.fields.discount_amount.value = parseFloat(document.getElementById('discount-value').value);
                }
                
                annotation.fields.discount_amount.type = document.getElementById('discount-type').value;
            }
            
            if (document.getElementById('min-purchase').value && boundingBoxes.min_purchase) {
                annotation.fields.min_purchase = {
                    text: document.getElementById('min-purchase').value,
                    bounding_box: boundingBoxes.min_purchase,
                    confidence: 1.0
                };
                
                if (document.getElementById('min-purchase-value').value) {
                    annotation.fields.min_purchase.value = parseFloat(document.getElementById('min-purchase-value').value);
                }
            }
            
            if (document.getElementById('description').value && boundingBoxes.description) {
                annotation.fields.description = {
                    text: document.getElementById('description').value,
                    bounding_box: boundingBoxes.description,
                    confidence: 1.0
                };
            }
            
            // Save annotation
            fetch(`/api/annotation/${encodeURIComponent(currentImage)}`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(annotation)
            })
            .then(response => {
                if (response.ok) {
                    alert('Annotation saved successfully');
                    loadImageList();  // Refresh the image list to update status
                } else {
                    alert('Error saving annotation');
                }
            });
        }
        
        // Initialize
        window.onload = loadImageList;
    </script>
</body>
</html>
            """)
    
    return render_template('index.html')

@app.route('/api/images')
def get_images():
    """API endpoint to get the list of images and their annotation status."""
    image_list = get_image_list()
    status = get_annotation_status()
    return jsonify({'images': image_list, 'status': status})

@app.route('/api/image/<path:image_path>')
def get_image(image_path):
    """API endpoint to get an image."""
    directory = os.path.dirname(os.path.join(PROCESSED_IMAGES_DIR, image_path))
    filename = os.path.basename(image_path)
    return send_from_directory(directory, filename)

@app.route('/api/annotation/<path:image_path>', methods=['GET'])
def get_annotation(image_path):
    """API endpoint to get the annotation for an image."""
    image_id = os.path.splitext(image_path)[0]
    annotation_file = os.path.join(ANNOTATIONS_DIR, f"{image_id}.json")
    
    if os.path.exists(annotation_file):
        with open(annotation_file, 'r') as f:
            return jsonify(json.load(f))
    else:
        return '', 404

@app.route('/api/annotation/<path:image_path>', methods=['POST'])
def save_annotation(image_path):
    """API endpoint to save the annotation for an image."""
    image_id = os.path.splitext(image_path)[0]
    annotation_file = os.path.join(ANNOTATIONS_DIR, f"{image_id}.json")
    
    annotation = request.json
    
    with open(annotation_file, 'w') as f:
        json.dump(annotation, f, indent=2)
    
    return jsonify({'status': 'success'})

def main():
    """Main function to run the annotation server."""
    parser = argparse.ArgumentParser(description='Run the annotation server.')
    parser.add_argument('--host', default='0.0.0.0', help='Host to run the server on')
    parser.add_argument('--port', type=int, default=5000, help='Port to run the server on')
    
    args = parser.parse_args()
    
    app.run(host=args.host, port=args.port, debug=True)

if __name__ == "__main__":
    main()
