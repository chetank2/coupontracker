#!/usr/bin/env python3
"""
Automatic Field Detector for Coupon Images

This script uses computer vision and OCR techniques to automatically detect
common fields in coupon images to assist with annotation.
"""

import os
import cv2
import numpy as np
import json
import logging
import argparse
import pytesseract
import re
from pathlib import Path
from datetime import datetime
import dateutil.parser

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("auto_field_detector.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("auto_field_detector")

# Base directories
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROCESSED_IMAGES_DIR = os.path.join(BASE_DIR, 'processed_images')
ANNOTATIONS_DIR = os.path.join(BASE_DIR, 'annotations')
AUTO_DETECTION_DIR = os.path.join(BASE_DIR, 'auto_detection')

# Field types
FIELD_TYPES = ['store_name', 'coupon_code', 'expiry_date', 'discount_amount', 'min_purchase', 'description']

# Regular expressions for field detection
PATTERNS = {
    'coupon_code': [
        r'\b[A-Z0-9]{5,12}\b',  # Common coupon code format
        r'CODE:?\s*([A-Z0-9]{4,12})',  # CODE: followed by alphanumeric
        r'COUPON:?\s*([A-Z0-9]{4,12})',  # COUPON: followed by alphanumeric
        r'USE\s+CODE:?\s*([A-Z0-9]{4,12})',  # USE CODE: followed by alphanumeric
    ],
    'expiry_date': [
        r'(?:VALID|EXPIRES?|EXPIRY)\s+(?:TILL|UNTIL|ON|BY)?\s*:?\s*(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})',  # Valid till: DD/MM/YYYY
        r'(?:VALID|EXPIRES?|EXPIRY)\s+(?:TILL|UNTIL|ON|BY)?\s*:?\s*(\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{2,4})',  # Valid till: DD MMM YYYY
        r'(?:VALID|EXPIRES?|EXPIRY)\s+(?:TILL|UNTIL|ON|BY)?\s*:?\s*((?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{1,2},?\s+\d{2,4})',  # Valid till: MMM DD, YYYY
        r'(?:VALID|EXPIRES?|EXPIRY)\s+(?:TILL|UNTIL|ON|BY)?\s*:?\s*(\d{1,2}\s+(?:January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{2,4})',  # Valid till: DD Month YYYY
    ],
    'discount_amount': [
        r'(\d+%)\s+(?:OFF|DISCOUNT)',  # 20% OFF
        r'(?:GET|FLAT)\s+(\d+%)\s+(?:OFF|DISCOUNT)',  # GET 20% OFF
        r'(?:GET|FLAT)\s+(?:Rs\.?|₹)\s*(\d+(?:\.\d+)?)\s+(?:OFF|DISCOUNT|CASHBACK)',  # GET Rs. 100 OFF
        r'(?:Rs\.?|₹)\s*(\d+(?:\.\d+)?)\s+(?:OFF|DISCOUNT|CASHBACK)',  # Rs. 100 OFF
        r'(?:SAVE|UPTO)\s+(?:Rs\.?|₹)\s*(\d+(?:\.\d+)?)',  # SAVE Rs. 100
        r'(?:SAVE|UPTO)\s+(\d+%)',  # SAVE 20%
    ],
    'min_purchase': [
        r'(?:MIN|MINIMUM)\s+(?:ORDER|PURCHASE)\s+(?:OF|VALUE|WORTH)?\s*(?:Rs\.?|₹)\s*(\d+(?:\.\d+)?)',  # MIN ORDER Rs. 100
        r'(?:ON|FOR)\s+(?:ORDERS?|PURCHASES?)\s+(?:ABOVE|OVER|OF|WORTH)\s*(?:Rs\.?|₹)\s*(\d+(?:\.\d+)?)',  # ON ORDERS ABOVE Rs. 100
        r'(?:SPEND|BUY)\s+(?:ABOVE|OVER|FOR)?\s*(?:Rs\.?|₹)\s*(\d+(?:\.\d+)?)',  # SPEND ABOVE Rs. 100
    ],
    'store_name': [
        r'^([A-Z][A-Za-z0-9\s]{2,20})(?:\s+COUPON|\s+OFFER|\s+DISCOUNT)',  # Store name at beginning followed by COUPON/OFFER/DISCOUNT
        r'(?:ON|AT|FROM|BY)\s+([A-Z][A-Za-z0-9\s]{2,20})(?:\s+ONLY|\s+STORE|\s+SHOP)?$',  # ON/AT/FROM/BY store name at end
    ]
}

def ensure_directories_exist():
    """Ensure all necessary directories exist."""
    os.makedirs(AUTO_DETECTION_DIR, exist_ok=True)
    os.makedirs(ANNOTATIONS_DIR, exist_ok=True)
    logger.info("Directory structure verified")

def preprocess_for_text_detection(image):
    """Preprocess image for better text detection."""
    # Convert to grayscale
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # Apply adaptive thresholding
    thresh = cv2.adaptiveThreshold(
        gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY_INV, 11, 2
    )
    
    # Apply morphological operations to enhance text regions
    kernel = np.ones((3, 3), np.uint8)
    dilated = cv2.dilate(thresh, kernel, iterations=1)
    
    return gray, thresh, dilated

def detect_text_regions(image, dilated):
    """Detect text regions in the image."""
    # Find contours
    contours, _ = cv2.findContours(dilated, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    
    # Filter contours by size
    height, width = image.shape[:2]
    min_area = height * width * 0.0005  # Minimum area threshold
    max_area = height * width * 0.5  # Maximum area threshold
    
    text_regions = []
    
    for contour in contours:
        area = cv2.contourArea(contour)
        if min_area < area < max_area:
            x, y, w, h = cv2.boundingRect(contour)
            
            # Filter out very thin regions (likely not text)
            aspect_ratio = w / h
            if 0.1 < aspect_ratio < 10:
                text_regions.append((x, y, x + w, y + h))
    
    # Merge overlapping regions
    merged_regions = merge_overlapping_regions(text_regions)
    
    return merged_regions

def merge_overlapping_regions(regions, overlap_threshold=0.5):
    """Merge overlapping text regions."""
    if not regions:
        return []
    
    # Sort regions by x-coordinate
    sorted_regions = sorted(regions, key=lambda r: r[0])
    
    merged = [sorted_regions[0]]
    
    for current in sorted_regions[1:]:
        previous = merged[-1]
        
        # Calculate overlap
        x_overlap = max(0, min(previous[2], current[2]) - max(previous[0], current[0]))
        y_overlap = max(0, min(previous[3], current[3]) - max(previous[1], current[1]))
        
        overlap_area = x_overlap * y_overlap
        current_area = (current[2] - current[0]) * (current[3] - current[1])
        previous_area = (previous[2] - previous[0]) * (previous[3] - previous[1])
        
        # If significant overlap, merge regions
        if overlap_area > overlap_threshold * min(current_area, previous_area):
            merged[-1] = (
                min(previous[0], current[0]),
                min(previous[1], current[1]),
                max(previous[2], current[2]),
                max(previous[3], current[3])
            )
        else:
            merged.append(current)
    
    return merged

def extract_text_from_region(image, region):
    """Extract text from a region using OCR."""
    x1, y1, x2, y2 = region
    roi = image[y1:y2, x1:x2]
    
    # Use pytesseract to extract text
    text = pytesseract.image_to_string(roi, config='--psm 6')
    
    return text.strip()

def detect_field_type(text, region_size):
    """Detect the type of field based on text content and region size."""
    text_upper = text.upper()
    
    # Check for coupon code
    for pattern in PATTERNS['coupon_code']:
        match = re.search(pattern, text_upper)
        if match:
            code = match.group(1) if len(match.groups()) > 0 else match.group(0)
            return 'coupon_code', code, 0.8
    
    # Check for expiry date
    for pattern in PATTERNS['expiry_date']:
        match = re.search(pattern, text_upper)
        if match:
            date_str = match.group(1)
            try:
                # Try to parse the date
                parsed_date = dateutil.parser.parse(date_str, fuzzy=True)
                normalized_date = parsed_date.strftime('%Y-%m-%d')
                return 'expiry_date', date_str, 0.8, normalized_date
            except:
                pass
    
    # Check for discount amount
    for pattern in PATTERNS['discount_amount']:
        match = re.search(pattern, text_upper)
        if match:
            amount = match.group(1)
            
            # Determine discount type and value
            if '%' in amount:
                discount_type = 'percentage'
                value = float(amount.replace('%', ''))
            else:
                discount_type = 'fixed'
                value = float(re.sub(r'[^\d.]', '', amount))
            
            return 'discount_amount', amount, 0.7, value, discount_type
    
    # Check for minimum purchase
    for pattern in PATTERNS['min_purchase']:
        match = re.search(pattern, text_upper)
        if match:
            amount = match.group(1)
            value = float(re.sub(r'[^\d.]', '', amount))
            return 'min_purchase', amount, 0.7, value
    
    # Check for store name
    for pattern in PATTERNS['store_name']:
        match = re.search(pattern, text_upper)
        if match:
            store_name = match.group(1).strip()
            return 'store_name', store_name, 0.6
    
    # If no specific field detected, check for common keywords
    if any(keyword in text_upper for keyword in ['TERMS', 'CONDITIONS', 'VALID', 'APPLICABLE']):
        return 'description', text, 0.5
    
    # Default to unknown
    return None, text, 0.3

def auto_detect_fields(image_path):
    """Automatically detect fields in a coupon image."""
    try:
        # Load image
        image = cv2.imread(image_path)
        if image is None:
            logger.error(f"Failed to load image: {image_path}")
            return None
        
        # Preprocess image
        gray, thresh, dilated = preprocess_for_text_detection(image)
        
        # Detect text regions
        text_regions = detect_text_regions(image, dilated)
        
        # Extract text and detect field types
        detected_fields = {}
        
        for region in text_regions:
            text = extract_text_from_region(gray, region)
            
            if not text:
                continue
            
            # Calculate region size
            region_size = (region[2] - region[0]) * (region[3] - region[1])
            
            # Detect field type
            field_info = detect_field_type(text, region_size)
            
            if field_info and field_info[0]:
                field_type = field_info[0]
                field_text = field_info[1]
                confidence = field_info[2]
                
                # Create field entry
                field_entry = {
                    "text": field_text,
                    "bounding_box": list(region),
                    "confidence": confidence
                }
                
                # Add additional field-specific information
                if field_type == 'expiry_date' and len(field_info) > 3:
                    field_entry["normalized_date"] = field_info[3]
                
                elif field_type == 'discount_amount' and len(field_info) > 4:
                    field_entry["value"] = field_info[3]
                    field_entry["type"] = field_info[4]
                
                elif field_type == 'min_purchase' and len(field_info) > 3:
                    field_entry["value"] = field_info[3]
                
                # Add to detected fields
                detected_fields[field_type] = field_entry
        
        # Create annotation
        image_id = os.path.splitext(os.path.basename(image_path))[0]
        
        annotation = {
            "image_id": image_id,
            "source": "auto_detection",
            "annotation_date": datetime.now().isoformat(),
            "annotator_id": "auto_detector",
            "fields": detected_fields,
            "verification_status": "unverified"
        }
        
        return annotation
    
    except Exception as e:
        logger.error(f"Error detecting fields in {image_path}: {e}")
        return None

def visualize_detection(image_path, annotation, output_path=None):
    """Visualize detected fields on the image."""
    try:
        # Load image
        image = cv2.imread(image_path)
        if image is None:
            logger.error(f"Failed to load image: {image_path}")
            return None
        
        # Create a copy for visualization
        vis_image = image.copy()
        
        # Define colors for different field types
        colors = {
            'store_name': (255, 0, 0),      # Blue
            'coupon_code': (0, 255, 0),     # Green
            'expiry_date': (0, 0, 255),     # Red
            'discount_amount': (255, 0, 255),  # Magenta
            'min_purchase': (255, 255, 0),  # Cyan
            'description': (0, 165, 255)    # Orange
        }
        
        # Draw bounding boxes and labels
        for field_type, field in annotation.get('fields', {}).items():
            if 'bounding_box' in field:
                bbox = field['bounding_box']
                x1, y1, x2, y2 = bbox
                
                # Draw rectangle
                cv2.rectangle(vis_image, (x1, y1), (x2, y2), colors.get(field_type, (0, 0, 0)), 2)
                
                # Draw label
                label = f"{field_type}: {field['text']}"
                cv2.putText(vis_image, label, (x1, y1 - 5), cv2.FONT_HERSHEY_SIMPLEX, 0.5, colors.get(field_type, (0, 0, 0)), 2)
        
        # Save or return the visualization
        if output_path:
            cv2.imwrite(output_path, vis_image)
            return output_path
        else:
            return vis_image
    
    except Exception as e:
        logger.error(f"Error visualizing detection for {image_path}: {e}")
        return None

def process_images(input_dir, visualize=True):
    """Process all images in the input directory."""
    ensure_directories_exist()
    
    # Get all image files
    image_extensions = ['.jpg', '.jpeg', '.png', '.gif']
    image_paths = []
    
    for root, _, files in os.walk(input_dir):
        for file in files:
            if any(file.lower().endswith(ext) for ext in image_extensions):
                image_paths.append(os.path.join(root, file))
    
    logger.info(f"Found {len(image_paths)} images to process")
    
    results = []
    
    for i, image_path in enumerate(image_paths):
        logger.info(f"Processing image {i+1}/{len(image_paths)}: {image_path}")
        
        # Auto-detect fields
        annotation = auto_detect_fields(image_path)
        
        if annotation:
            # Save annotation
            image_id = annotation['image_id']
            annotation_path = os.path.join(ANNOTATIONS_DIR, f"{image_id}_auto.json")
            
            with open(annotation_path, 'w') as f:
                json.dump(annotation, f, indent=2)
            
            # Visualize detection if requested
            if visualize:
                vis_path = os.path.join(AUTO_DETECTION_DIR, f"{image_id}_detection.jpg")
                visualize_detection(image_path, annotation, vis_path)
            
            # Add to results
            results.append({
                "image_path": image_path,
                "annotation_path": annotation_path,
                "fields_detected": list(annotation.get('fields', {}).keys())
            })
    
    # Save results summary
    results_path = os.path.join(AUTO_DETECTION_DIR, 'detection_results.json')
    with open(results_path, 'w') as f:
        json.dump(results, f, indent=2)
    
    logger.info(f"Processing complete. Detected fields in {len(results)} images.")
    return results

def main():
    """Main function to automatically detect fields in coupon images."""
    parser = argparse.ArgumentParser(description='Automatically detect fields in coupon images.')
    parser.add_argument('--input-dir', default=PROCESSED_IMAGES_DIR, help='Input directory containing processed images')
    parser.add_argument('--no-visualize', action='store_true', help='Disable visualization of detections')
    
    args = parser.parse_args()
    
    process_images(args.input_dir, not args.no_visualize)

if __name__ == "__main__":
    main()
