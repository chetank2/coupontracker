#!/usr/bin/env python3
import os
import cv2
import json
import numpy as np
import pytesseract
import argparse
from pathlib import Path
import re

def detect_text_regions(image):
    """Detect potential text regions in the image"""
    # Convert to grayscale if needed
    if len(image.shape) == 3:
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    else:
        gray = image
    
    # Apply threshold to get binary image
    _, binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    
    # Apply morphological operations to find text regions
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
    dilated = cv2.dilate(binary, kernel, iterations=3)
    
    # Find contours
    contours, _ = cv2.findContours(dilated, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    
    # Filter contours to find potential text regions
    text_regions = []
    for contour in contours:
        x, y, w, h = cv2.boundingRect(contour)
        
        # Filter out very small regions
        if w < 20 or h < 20:
            continue
        
        # Filter out very large regions
        if w > image.shape[1] * 0.9 or h > image.shape[0] * 0.9:
            continue
        
        # Filter based on aspect ratio
        aspect_ratio = w / float(h)
        if aspect_ratio > 10 or aspect_ratio < 0.1:
            continue
        
        text_regions.append((x, y, x + w, y + h))
    
    return text_regions

def extract_text_from_region(image, region):
    """Extract text from a specific region of the image"""
    if region is None:
        return None
    
    x1, y1, x2, y2 = region
    roi = image[y1:y2, x1:x2]
    
    # Use Tesseract to extract text
    text = pytesseract.image_to_string(roi, config='--psm 6')
    return text.strip()

def classify_region(text):
    """Classify a region based on its text content"""
    if not text:
        return None
    
    # Check for store name patterns
    if re.search(r'(myntra|abhibus|newmee|ixigo|boat|xyxx|mivi|cred)', text.lower()):
        return "store_name"
    
    # Check for coupon code patterns
    if re.search(r'(code|coupon|use|apply)[:;]?\s*[A-Z0-9]{5,}', text.lower()) or re.search(r'\b[A-Z0-9]{5,}\b', text):
        return "coupon_code"
    
    # Check for expiry date patterns
    if re.search(r'(expires?|valid|expiry)', text.lower()) or re.search(r'\d{1,2}[/-]\d{1,2}[/-]\d{2,4}', text):
        return "expiry_date"
    
    # Check for amount patterns
    if re.search(r'(\d+%|₹\d+|\$\d+|rs\.?\s*\d+)', text.lower()) or re.search(r'(off|discount|cashback|save)', text.lower()):
        return "amount"
    
    # Default to description for other text
    return "description"

def auto_annotate_image(image_path, output_dir):
    """Automatically annotate an image"""
    # Read the image
    image = cv2.imread(str(image_path))
    if image is None:
        print(f"Error: Could not read image {image_path}")
        return False
    
    # Get image dimensions
    height, width = image.shape[:2]
    
    # Detect text regions
    regions = detect_text_regions(image)
    
    # Extract text from each region and classify
    classified_regions = {}
    for region in regions:
        text = extract_text_from_region(image, region)
        if text:
            region_type = classify_region(text)
            if region_type and region_type not in classified_regions:
                classified_regions[region_type] = region
    
    # If we couldn't find all region types, use default positions
    if len(classified_regions) < 5:
        # Top 20% for store name
        if "store_name" not in classified_regions:
            classified_regions["store_name"] = (0, 0, width, int(height * 0.2))
        
        # Middle for coupon code
        if "coupon_code" not in classified_regions:
            classified_regions["coupon_code"] = (int(width * 0.1), int(height * 0.4), int(width * 0.9), int(height * 0.6))
        
        # Bottom 20% for expiry date
        if "expiry_date" not in classified_regions:
            classified_regions["expiry_date"] = (0, int(height * 0.8), width, height)
        
        # Middle-top for description
        if "description" not in classified_regions:
            classified_regions["description"] = (int(width * 0.1), int(height * 0.2), int(width * 0.9), int(height * 0.4))
        
        # Middle-bottom for amount
        if "amount" not in classified_regions:
            classified_regions["amount"] = (int(width * 0.1), int(height * 0.6), int(width * 0.9), int(height * 0.8))
    
    # Create annotations
    annotations = {
        "image_path": str(image_path),
        "image_width": width,
        "image_height": height,
        "regions": {
            "store_name": classified_regions.get("store_name"),
            "coupon_code": classified_regions.get("coupon_code"),
            "expiry_date": classified_regions.get("expiry_date"),
            "description": classified_regions.get("description"),
            "amount": classified_regions.get("amount")
        }
    }
    
    # Create filename for annotations
    image_filename = os.path.basename(image_path)
    name, ext = os.path.splitext(image_filename)
    json_filename = f"{name}_annotations.json"
    json_path = os.path.join(output_dir, json_filename)
    
    # Save annotations to JSON file
    with open(json_path, 'w') as f:
        json.dump(annotations, f, indent=4)
    
    print(f"Annotations saved to: {json_path}")
    
    # Save annotated image
    annotated_image = image.copy()
    for region_name, region_coords in annotations["regions"].items():
        if region_coords:
            x1, y1, x2, y2 = region_coords
            color = (0, 255, 0)  # Green
            cv2.rectangle(annotated_image, (x1, y1), (x2, y2), color, 2)
            cv2.putText(annotated_image, region_name, (x1, y1 - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 2)
    
    # Save the annotated image
    annotated_filename = f"{name}_annotated{ext}"
    annotated_path = os.path.join(output_dir, annotated_filename)
    cv2.imwrite(annotated_path, annotated_image)
    print(f"Annotated image saved to: {annotated_path}")
    
    return True

def main():
    parser = argparse.ArgumentParser(description="Automatically annotate coupon images")
    parser.add_argument("--input-dir", default="../data/processed", help="Directory containing processed images")
    parser.add_argument("--output-dir", default="../data/annotated", help="Directory to save annotations")
    
    args = parser.parse_args()
    
    # Ensure output directory exists
    os.makedirs(args.output_dir, exist_ok=True)
    
    # Get all image files
    image_extensions = ['.jpg', '.jpeg', '.png', '.bmp', '.tiff']
    image_paths = []
    for ext in image_extensions:
        image_paths.extend(list(Path(args.input_dir).glob(f"*{ext}")))
        image_paths.extend(list(Path(args.input_dir).glob(f"*{ext.upper()}")))
    
    if not image_paths:
        print(f"No images found in {args.input_dir}")
        return
    
    print(f"Found {len(image_paths)} images to annotate")
    
    # Process each image
    success_count = 0
    for i, image_path in enumerate(image_paths):
        print(f"\nAnnotating image {i+1}/{len(image_paths)}: {image_path}")
        try:
            if auto_annotate_image(image_path, args.output_dir):
                success_count += 1
        except Exception as e:
            print(f"Error annotating {image_path}: {e}")
    
    print(f"Annotation complete. Successfully annotated {success_count}/{len(image_paths)} images.")

if __name__ == "__main__":
    main()
