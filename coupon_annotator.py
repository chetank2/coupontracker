#!/usr/bin/env python3
"""
Coupon Annotator - Automatically annotates coupon images.

This module provides functions to automatically detect and annotate
coupon fields such as store name, discount, code, expiry date, etc.
"""

import os
import re
import json
import logging
import argparse
import cv2
import numpy as np
import pytesseract
from PIL import Image
import datetime

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("coupon_annotator.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("coupon_annotator")

class CouponAnnotator:
    """Automatically annotates coupon images."""
    
    def __init__(self, input_dir="data/processed_coupons", output_dir="data/annotated_coupons"):
        """Initialize the annotator.
        
        Args:
            input_dir (str): Directory containing processed images
            output_dir (str): Directory to save annotation files
        """
        self.input_dir = input_dir
        self.output_dir = output_dir
        
        # Create output directory
        os.makedirs(output_dir, exist_ok=True)
        
        # Initialize counters
        self.images_annotated = 0
        
        # Define field types
        self.field_types = [
            "store",
            "partner",
            "discount",
            "code",
            "min_order",
            "expiry_date"
        ]
        
        # Define regex patterns for field types
        self.patterns = {
            "store": [
                r"(CRED|Swiggy|Zomato|Amazon|Flipkart|Myntra|PhonePe|GPay|Google Pay|Paytm|Zepto)",
                r"from\s+([A-Z][a-zA-Z]+)"
            ],
            "partner": [
                r"on\s+([A-Z][a-zA-Z]+)",
                r"at\s+([A-Z][a-zA-Z]+)",
                r"for\s+([A-Z][a-zA-Z]+)"
            ],
            "discount": [
                r"(\d+%\s+off)",
                r"(\d+%\s+discount)",
                r"(₹\s*\d+\s+off)",
                r"(Rs\.\s*\d+\s+off)",
                r"(Rs\s*\d+\s+off)",
                r"(Flat\s+\d+%)",
                r"(Flat\s+₹\s*\d+)"
            ],
            "code": [
                r"code\s*:?\s*([A-Z0-9]+)",
                r"coupon\s*:?\s*([A-Z0-9]+)",
                r"([A-Z0-9]{5,})"  # Assume codes are at least 5 characters
            ],
            "min_order": [
                r"min\s+order\s*:?\s*(₹\s*\d+)",
                r"min\s+order\s*:?\s*(Rs\.\s*\d+)",
                r"min\s+order\s*:?\s*(Rs\s*\d+)",
                r"minimum\s+order\s*:?\s*(₹\s*\d+)",
                r"minimum\s+order\s*:?\s*(Rs\.\s*\d+)",
                r"minimum\s+order\s*:?\s*(Rs\s*\d+)"
            ],
            "expiry_date": [
                r"valid\s+till\s+(\d{1,2}\s+[a-zA-Z]+\s+\d{4})",
                r"valid\s+till\s+(\d{1,2}\s+[a-zA-Z]+)",
                r"expires\s+on\s+(\d{1,2}\s+[a-zA-Z]+\s+\d{4})",
                r"expires\s+on\s+(\d{1,2}\s+[a-zA-Z]+)",
                r"expiry\s*:?\s*(\d{1,2}\s+[a-zA-Z]+\s+\d{4})",
                r"expiry\s*:?\s*(\d{1,2}\s+[a-zA-Z]+)"
            ]
        }
        
        logger.info(f"Initialized annotator with input directory: {input_dir}, output directory: {output_dir}")
    
    def annotate_images(self, metadata_file=None):
        """Annotate all images in the input directory.
        
        Args:
            metadata_file (str): Path to a JSON file containing metadata for the images
            
        Returns:
            list: List of dictionaries containing annotation data
        """
        # Get all image files
        image_files = []
        for ext in [".jpg", ".jpeg", ".png"]:
            image_files.extend([os.path.join(self.input_dir, f) for f in os.listdir(self.input_dir) if f.lower().endswith(ext)])
        
        logger.info(f"Found {len(image_files)} images to annotate")
        
        # Load metadata if provided
        metadata = {}
        if metadata_file and os.path.exists(metadata_file):
            try:
                with open(metadata_file, 'r') as f:
                    metadata = json.load(f)
                logger.info(f"Loaded metadata for {len(metadata)} images")
            except Exception as e:
                logger.error(f"Error loading metadata: {e}")
        
        annotations = []
        
        for image_path in image_files:
            try:
                # Get the base filename
                filename = os.path.basename(image_path)
                
                # Get metadata for this image if available
                image_metadata = metadata.get(filename, {})
                
                # Annotate the image
                annotation = self._annotate_image(image_path, image_metadata)
                
                if annotation:
                    annotations.append(annotation)
                    self.images_annotated += 1
                    
                    # Save the annotation
                    self._save_annotation(annotation)
            
            except Exception as e:
                logger.error(f"Error annotating image {image_path}: {e}")
        
        logger.info(f"Annotated {self.images_annotated} images")
        return annotations
    
    def _annotate_image(self, image_path, metadata=None):
        """Annotate a single image.
        
        Args:
            image_path (str): Path to the image
            metadata (dict): Metadata for the image
            
        Returns:
            dict: Dictionary containing annotation data
        """
        try:
            # Generate output filename
            filename = os.path.basename(image_path)
            base_name, _ = os.path.splitext(filename)
            
            # Load the image
            img = cv2.imread(image_path)
            if img is None:
                logger.error(f"Failed to load image: {image_path}")
                return None
            
            # Get image dimensions
            height, width, _ = img.shape
            
            # Perform OCR
            text_regions = self._extract_text_regions(img)
            
            # Initialize annotation
            annotation = {
                "name": base_name,
                "image_path": image_path,
                "width": width,
                "height": height,
                "regions": [],
                "fields": {}
            }
            
            # Use metadata if available
            if metadata:
                for field in self.field_types:
                    if field in metadata:
                        annotation["fields"][field] = metadata[field]
            
            # Process text regions
            for region in text_regions:
                region_type = self._classify_region(region["text"])
                
                if region_type:
                    # Add the region
                    region_data = {
                        "type": region_type,
                        "box": region["box"],
                        "text": region["text"],
                        "confidence": region["confidence"]
                    }
                    
                    annotation["regions"].append(region_data)
                    
                    # Extract field value if not already set from metadata
                    if region_type not in annotation["fields"]:
                        field_value = self._extract_field_value(region["text"], region_type)
                        if field_value:
                            annotation["fields"][region_type] = field_value
            
            # If we couldn't classify some regions, try to extract fields from the entire text
            if len(annotation["regions"]) < 3:
                # Get all text from the image
                all_text = " ".join([region["text"] for region in text_regions])
                
                # Try to extract missing fields
                for field in self.field_types:
                    if field not in annotation["fields"]:
                        field_value = self._extract_field_value(all_text, field)
                        if field_value:
                            annotation["fields"][field] = field_value
            
            # Set default values for missing fields
            for field in self.field_types:
                if field not in annotation["fields"]:
                    annotation["fields"][field] = None
            
            # If we have very few fields, this might not be a coupon
            if sum(1 for field in self.field_types if annotation["fields"][field]) < 2:
                logger.warning(f"Few fields detected in {filename}, might not be a coupon")
            
            logger.info(f"Annotated image: {filename} with {len(annotation['regions'])} regions")
            return annotation
        
        except Exception as e:
            logger.error(f"Error annotating image {image_path}: {e}")
            return None
    
    def _extract_text_regions(self, img):
        """Extract text regions from an image using OCR.
        
        Args:
            img (numpy.ndarray): Input image
            
        Returns:
            list: List of dictionaries containing text regions
        """
        # Convert to grayscale
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        
        # Apply threshold to get a binary image
        _, binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
        
        # Find contours
        contours, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        
        # Sort contours by y-coordinate (top to bottom)
        contours = sorted(contours, key=lambda c: cv2.boundingRect(c)[1])
        
        text_regions = []
        
        # Process each contour
        for contour in contours:
            # Get the bounding rectangle
            x, y, w, h = cv2.boundingRect(contour)
            
            # Skip very small regions
            if w < 20 or h < 10:
                continue
            
            # Skip very large regions
            if w > img.shape[1] * 0.9 or h > img.shape[0] * 0.9:
                continue
            
            # Extract the region
            roi = img[y:y+h, x:x+w]
            
            # Convert to PIL Image for Tesseract
            pil_img = Image.fromarray(cv2.cvtColor(roi, cv2.COLOR_BGR2RGB))
            
            # Perform OCR
            ocr_result = pytesseract.image_to_data(pil_img, output_type=pytesseract.Output.DICT)
            
            # Extract text and confidence
            text = " ".join([word for word in ocr_result["text"] if word.strip()])
            
            # Skip empty regions
            if not text:
                continue
            
            # Calculate average confidence
            confidences = [conf for conf, word in zip(ocr_result["conf"], ocr_result["text"]) if word.strip() and conf != -1]
            avg_confidence = sum(confidences) / len(confidences) if confidences else 0
            
            # Add the region
            text_regions.append({
                "box": [x, y, x + w, y + h],
                "text": text,
                "confidence": avg_confidence
            })
        
        # If no regions found using contours, try full-image OCR
        if not text_regions:
            # Convert to PIL Image for Tesseract
            pil_img = Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
            
            # Perform OCR with bounding boxes
            ocr_result = pytesseract.image_to_data(pil_img, output_type=pytesseract.Output.DICT)
            
            # Group words into lines
            lines = {}
            for i, (text, conf, x, y, w, h) in enumerate(zip(
                ocr_result["text"], ocr_result["conf"], 
                ocr_result["left"], ocr_result["top"], 
                ocr_result["width"], ocr_result["height"]
            )):
                if not text.strip() or conf == -1:
                    continue
                
                # Use the y-coordinate as the line key (with some tolerance)
                line_key = y // 10
                
                if line_key not in lines:
                    lines[line_key] = {
                        "text": [],
                        "conf": [],
                        "x": [],
                        "y": [],
                        "w": [],
                        "h": []
                    }
                
                lines[line_key]["text"].append(text)
                lines[line_key]["conf"].append(conf)
                lines[line_key]["x"].append(x)
                lines[line_key]["y"].append(y)
                lines[line_key]["w"].append(w)
                lines[line_key]["h"].append(h)
            
            # Create text regions from lines
            for line_key, line in lines.items():
                if not line["text"]:
                    continue
                
                # Calculate bounding box
                x1 = min(line["x"])
                y1 = min(line["y"])
                x2 = max(x + w for x, w in zip(line["x"], line["w"]))
                y2 = max(y + h for y, h in zip(line["y"], line["h"]))
                
                # Calculate average confidence
                avg_confidence = sum(line["conf"]) / len(line["conf"])
                
                # Add the region
                text_regions.append({
                    "box": [x1, y1, x2, y2],
                    "text": " ".join(line["text"]),
                    "confidence": avg_confidence
                })
        
        return text_regions
    
    def _classify_region(self, text):
        """Classify a text region.
        
        Args:
            text (str): Text to classify
            
        Returns:
            str: Region type, or None if not classified
        """
        if not text:
            return None
        
        # Check each field type
        for field_type, patterns in self.patterns.items():
            for pattern in patterns:
                if re.search(pattern, text, re.IGNORECASE):
                    return field_type
        
        # If no match found, try some heuristics
        text_lower = text.lower()
        
        if "code" in text_lower and re.search(r"[A-Z0-9]{5,}", text):
            return "code"
        
        if "off" in text_lower and re.search(r"\d+", text):
            return "discount"
        
        if "valid" in text_lower or "expiry" in text_lower or "expires" in text_lower:
            return "expiry_date"
        
        if "min" in text_lower and re.search(r"\d+", text):
            return "min_order"
        
        # Check if it's a store name (all caps or title case)
        if text.isupper() or (text.istitle() and len(text.split()) <= 2):
            return "store"
        
        return None
    
    def _extract_field_value(self, text, field_type):
        """Extract the value for a field from text.
        
        Args:
            text (str): Text to extract from
            field_type (str): Type of field to extract
            
        Returns:
            str: Extracted value, or None if not found
        """
        if not text or not field_type:
            return None
        
        # Use the patterns for this field type
        for pattern in self.patterns.get(field_type, []):
            match = re.search(pattern, text, re.IGNORECASE)
            if match:
                return match.group(1)
        
        # If no match found, use some heuristics
        if field_type == "code":
            # Look for all-caps alphanumeric strings
            match = re.search(r"[A-Z0-9]{5,}", text)
            if match:
                return match.group(0)
        
        elif field_type == "discount":
            # Look for numbers followed by % or "off"
            match = re.search(r"(\d+%|\d+\s*%|\d+\s+off|₹\s*\d+)", text, re.IGNORECASE)
            if match:
                return match.group(0)
        
        elif field_type == "expiry_date":
            # Look for dates
            match = re.search(r"(\d{1,2}\s+[a-zA-Z]+\s+\d{4}|\d{1,2}\s+[a-zA-Z]+)", text)
            if match:
                return match.group(0)
        
        return None
    
    def _save_annotation(self, annotation):
        """Save an annotation to a JSON file.
        
        Args:
            annotation (dict): Annotation data
        """
        # Generate output filename
        base_name = annotation["name"]
        output_path = os.path.join(self.output_dir, f"{base_name}.json")
        
        # Save the annotation
        with open(output_path, 'w') as f:
            json.dump(annotation, f, indent=2)
        
        logger.info(f"Saved annotation to {output_path}")
    
    def generate_pattern_file(self, annotations, output_file="patterns.json"):
        """Generate a pattern file from annotations.
        
        Args:
            annotations (list): List of annotation dictionaries
            output_file (str): Path to the output file
            
        Returns:
            str: Path to the generated pattern file
        """
        patterns = []
        
        for annotation in annotations:
            # Skip annotations with few fields
            if sum(1 for field in self.field_types if annotation["fields"][field]) < 2:
                continue
            
            # Create a pattern
            pattern = {
                "name": f"{annotation['fields']['store'] or 'Unknown'} Coupon",
                "description": f"Pattern for {annotation['fields']['store'] or 'Unknown'} coupons",
                "regions": annotation["regions"],
                "fields": annotation["fields"]
            }
            
            patterns.append(pattern)
        
        # Save the pattern file
        output_path = os.path.join(self.output_dir, output_file)
        with open(output_path, 'w') as f:
            json.dump(patterns, f, indent=2)
        
        logger.info(f"Generated pattern file with {len(patterns)} patterns: {output_path}")
        return output_path

def main():
    """Main function."""
    parser = argparse.ArgumentParser(description="Annotate coupon images")
    parser.add_argument("--input-dir", default="data/processed_coupons", help="Directory containing processed images")
    parser.add_argument("--output-dir", default="data/annotated_coupons", help="Directory to save annotation files")
    parser.add_argument("--metadata", help="Path to a JSON file containing metadata for the images")
    parser.add_argument("--pattern-file", default="patterns.json", help="Name of the pattern file to generate")
    
    args = parser.parse_args()
    
    annotator = CouponAnnotator(input_dir=args.input_dir, output_dir=args.output_dir)
    annotations = annotator.annotate_images(metadata_file=args.metadata)
    
    if annotations:
        pattern_file = annotator.generate_pattern_file(annotations, output_file=args.pattern_file)
        print(f"Annotated {annotator.images_annotated} images")
        print(f"Generated pattern file: {pattern_file}")
    else:
        print("No annotations generated")

if __name__ == "__main__":
    main()
