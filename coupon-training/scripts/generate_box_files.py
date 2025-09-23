#!/usr/bin/env python3
import os
import cv2
import json
import numpy as np
import pytesseract
import argparse
from pathlib import Path

def extract_text_from_region(image, region):
    """Extract text from a specific region of the image"""
    if region is None:
        return None

    x1, y1, x2, y2 = region
    roi = image[y1:y2, x1:x2]

    # Use Tesseract to extract text
    text = pytesseract.image_to_string(roi, config='--psm 6')
    return text.strip()

def create_box_file(image_path, annotation_path, output_dir):
    """Create a box file for Tesseract training based on annotations"""
    # Load the image
    image = cv2.imread(str(image_path))
    if image is None:
        print(f"Error: Could not read image {image_path}")
        return False

    # Load the annotations
    with open(annotation_path, 'r') as f:
        annotations = json.load(f)

    # Get the filename without extension
    filename = os.path.basename(image_path)
    name, ext = os.path.splitext(filename)

    # Create output paths
    box_path = os.path.join(output_dir, f"{name}.box")
    tiff_path = os.path.join(output_dir, f"{name}.tiff")

    # Extract text from each region
    regions_text = {}
    for region_name, region_coords in annotations['regions'].items():
        if region_coords:
            text = extract_text_from_region(image, region_coords)
            if text:
                regions_text[region_name] = text

    # Save the image as TIFF (required for Tesseract training)
    cv2.imwrite(tiff_path, image)

    # Generate box file content
    box_content = []

    # For each character in each region, generate box file entries
    for region_name, text in regions_text.items():
        region_coords = annotations['regions'][region_name]
        if not region_coords:
            continue

        x1, y1, x2, y2 = region_coords
        region_width = x2 - x1
        region_height = y2 - y1

        # Get character-level bounding boxes using Tesseract
        roi = image[y1:y2, x1:x2]
        boxes = pytesseract.image_to_boxes(roi)

        # Process each character box
        for box_line in boxes.splitlines():
            parts = box_line.split()
            if len(parts) >= 5:
                char = parts[0]
                char_x1 = int(parts[1]) + x1
                char_y1 = annotations['image_height'] - (int(parts[2]) + y1)
                char_x2 = int(parts[3]) + x1
                char_y2 = annotations['image_height'] - (int(parts[4]) + y1)

                # Add to box content
                box_content.append(f"{char} {char_x1} {char_y2} {char_x2} {char_y1} 0")

    # Write box file
    with open(box_path, 'w') as f:
        f.write('\n'.join(box_content))

    print(f"Created box file: {box_path}")
    print(f"Created TIFF image: {tiff_path}")

    return True

def main():
    parser = argparse.ArgumentParser(description="Generate box files for Tesseract training")
    parser.add_argument("--annotated-dir", default="../data/annotated", help="Directory containing annotated images")
    parser.add_argument("--output-dir", default="../data/box-files", help="Directory to save box files")

    args = parser.parse_args()

    # Ensure output directory exists
    os.makedirs(args.output_dir, exist_ok=True)

    # Get all annotation files
    annotation_paths = list(Path(args.annotated_dir).glob("*_annotations.json"))

    if not annotation_paths:
        print(f"No annotation files found in {args.annotated_dir}")
        return

    print(f"Found {len(annotation_paths)} annotation files")

    # Process each annotation file
    success_count = 0
    for annotation_path in annotation_paths:
        try:
            # Load the annotation
            with open(annotation_path, 'r') as f:
                annotation = json.load(f)

            # Extract the filename from the annotation path
            annotation_filename = os.path.basename(str(annotation_path))
            base_name = annotation_filename.replace("_annotations.json", "")

            # Try to find the corresponding image in the processed directory
            processed_dir = os.path.join(os.getcwd(), "data", "processed")
            possible_extensions = [".png", ".jpg", ".jpeg"]

            image_path = None
            for ext in possible_extensions:
                test_path = os.path.join(processed_dir, base_name + ext)
                if os.path.exists(test_path):
                    image_path = test_path
                    break

            if image_path is None:
                print(f"Error: Could not find image for {annotation_filename}")
                continue

            print(f"Processing {image_path}...")
            if create_box_file(image_path, annotation_path, args.output_dir):
                success_count += 1
        except Exception as e:
            print(f"Error processing {annotation_path}: {e}")

    print(f"Box file generation complete. Successfully processed {success_count}/{len(annotation_paths)} annotations.")

if __name__ == "__main__":
    main()
