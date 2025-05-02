#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import cv2
import json
import numpy as np
import pytesseract
import argparse
import time
import datetime
from pathlib import Path
from difflib import SequenceMatcher
from tqdm import tqdm

# Add parent directory to path to import from utils
import sys
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from utils import text_utils

def similar(a, b):
    """Calculate string similarity ratio"""
    return SequenceMatcher(None, a, b).ratio()

def extract_text_from_region(image, region, region_type=None, lang_code="coupon"):
    """Extract text from a specific region of the image using the trained model

    Args:
        image: Input image
        region: Region coordinates (x1, y1, x2, y2)
        region_type: Type of region (store, code, amount, description, expiry)
        lang_code: Language code for Tesseract

    Returns:
        str: Extracted text
    """
    if region is None:
        return None

    # Use our enhanced text extraction function
    return text_utils.extract_text_from_region(
        image,
        region,
        lang=lang_code,
        element_type=region_type
    )

def evaluate_model(image_path, annotation_path, lang_code="eng"):
    """Evaluate the model on a single image

    Args:
        image_path: Path to the image
        annotation_path: Path to the annotation file
        lang_code: Language code for Tesseract

    Returns:
        dict: Evaluation results
    """
    # Load the image
    image = cv2.imread(str(image_path))
    if image is None:
        print(f"Error: Could not read image {image_path}")
        return None

    # Load the annotations
    with open(annotation_path, 'r') as f:
        annotations = json.load(f)

    # Map region names to types for better OCR
    region_type_map = {
        'store': 'store',
        'store_name': 'store',
        'code': 'code',
        'coupon_code': 'code',
        'amount': 'amount',
        'discount': 'amount',
        'description': 'description',
        'offer': 'description',
        'expiry': 'expiry',
        'expiry_date': 'expiry',
        'valid_till': 'expiry'
    }

    # Extract ground truth text from each region
    ground_truth = {}
    for region_name, region_coords in annotations.get('regions', {}).items():
        if region_coords:
            # Determine region type
            region_type = region_type_map.get(region_name.lower(), None)

            # Extract text using standard English model for ground truth
            text = extract_text_from_region(image, region_coords, region_type, "eng")
            if text:
                ground_truth[region_name] = text

    # Extract text using the trained model
    predictions = {}
    for region_name, region_coords in annotations.get('regions', {}).items():
        if region_coords:
            # Determine region type
            region_type = region_type_map.get(region_name.lower(), None)

            # Extract text using the trained model
            text = extract_text_from_region(image, region_coords, region_type, lang_code)
            if text:
                predictions[region_name] = text

    # Calculate similarity scores
    scores = {}
    for region_name in ground_truth.keys():
        if region_name in predictions:
            gt_text = ground_truth[region_name]
            pred_text = predictions[region_name]

            # Calculate similarity
            similarity = similar(gt_text, pred_text)
            scores[region_name] = similarity

    # Calculate per-type scores
    type_scores = {}
    for region_name, score in scores.items():
        region_type = region_type_map.get(region_name.lower(), 'other')
        if region_type not in type_scores:
            type_scores[region_type] = []
        type_scores[region_type].append(score)

    # Average scores by type
    avg_type_scores = {}
    for region_type, type_score_list in type_scores.items():
        avg_type_scores[region_type] = sum(type_score_list) / len(type_score_list)

    # Create result object
    result = {
        "image_path": str(image_path),
        "ground_truth": ground_truth,
        "predictions": predictions,
        "similarity_scores": scores,
        "type_scores": avg_type_scores,
        "average_score": sum(scores.values()) / len(scores) if scores else 0
    }

    return result

def main():
    parser = argparse.ArgumentParser(description="Evaluate a trained Tesseract model")
    parser.add_argument("--data-dir", default="../data", help="Base data directory")
    parser.add_argument("--validation-dir", default="../data/validation", help="Validation data directory")
    parser.add_argument("--lang-code", default="coupon", help="Language code of the trained model")
    parser.add_argument("--output-file", default="../evaluation_results.json", help="File to save evaluation results")
    parser.add_argument("--log-dir", default="../logs", help="Directory to save evaluation logs")

    args = parser.parse_args()

    # Create log directory if it doesn't exist
    os.makedirs(args.log_dir, exist_ok=True)

    # Use validation directory if it exists, otherwise use annotated directory
    eval_dir = args.validation_dir if os.path.exists(args.validation_dir) else os.path.join(args.data_dir, "annotated")

    # Get all annotation files
    annotation_paths = list(Path(eval_dir).glob("*.json"))

    if not annotation_paths:
        print(f"No annotation files found in {eval_dir}")
        return

    print(f"Found {len(annotation_paths)} annotation files for evaluation in {eval_dir}")

    # Evaluate each image
    results = []

    # Create a timestamp for this evaluation run
    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")

    # Create a progress bar
    for annotation_path in tqdm(annotation_paths, desc="Evaluating images"):
        try:
            # Load the annotation
            with open(annotation_path, 'r') as f:
                annotation = json.load(f)

            # Get the image filename - check both 'image' and 'image_path' fields
            image_filename = annotation.get('image')
            if not image_filename:
                # Try to extract from image_path
                image_path_str = annotation.get('image_path', '')
                if image_path_str:
                    # Extract just the filename from the path
                    image_filename = os.path.basename(image_path_str)

                    # If it's a processed image, look in the processed directory
                    if "_processed" in image_filename:
                        image_path = os.path.join(os.path.dirname(eval_dir), "processed", image_filename)
                        if os.path.exists(image_path):
                            # Evaluate the model with this path
                            result = evaluate_model(image_path, annotation_path, args.lang_code)
                            if result:
                                results.append(result)
                                tqdm.write(f"Evaluated {image_filename}: Score = {result['average_score']:.2f}")

                                # Log detailed type scores if available
                                if 'type_scores' in result and result['type_scores']:
                                    for region_type, score in result['type_scores'].items():
                                        tqdm.write(f"  - {region_type}: {score:.2f}")
                            continue
                else:
                    # Try to extract from the annotation filename
                    annotation_filename = os.path.basename(str(annotation_path))
                    if "_annotations.json" in annotation_filename:
                        base_name = annotation_filename.replace("_annotations.json", "")
                        # Try to find the corresponding image in the processed directory
                        processed_dir = os.path.join(os.path.dirname(eval_dir), "processed")
                        possible_extensions = [".png", ".jpg", ".jpeg"]

                        for ext in possible_extensions:
                            test_path = os.path.join(processed_dir, base_name + ext)
                            if os.path.exists(test_path):
                                # Evaluate the model with this path
                                result = evaluate_model(test_path, annotation_path, args.lang_code)
                                if result:
                                    results.append(result)
                                    tqdm.write(f"Evaluated {base_name + ext}: Score = {result['average_score']:.2f}")

                                    # Log detailed type scores if available
                                    if 'type_scores' in result and result['type_scores']:
                                        for region_type, score in result['type_scores'].items():
                                            tqdm.write(f"  - {region_type}: {score:.2f}")
                                continue

                    tqdm.write(f"Error: No image filename in {annotation_path}")
                    continue

            # Get the image path
            image_path = os.path.join(eval_dir, image_filename)
            if not os.path.exists(image_path):
                tqdm.write(f"Error: Image not found: {image_path}")
                continue

            # Evaluate the model
            result = evaluate_model(image_path, annotation_path, args.lang_code)
            if result:
                results.append(result)
                tqdm.write(f"Evaluated {image_filename}: Score = {result['average_score']:.2f}")

                # Log detailed type scores if available
                if 'type_scores' in result and result['type_scores']:
                    for region_type, score in result['type_scores'].items():
                        tqdm.write(f"  - {region_type}: {score:.2f}")
        except Exception as e:
            tqdm.write(f"Error evaluating {annotation_path}: {e}")

    # Calculate overall metrics
    if results:
        # Overall average score
        overall_score = sum(r['average_score'] for r in results) / len(results)

        # Calculate scores by type across all images
        all_type_scores = {}
        for result in results:
            if 'type_scores' in result:
                for region_type, score in result['type_scores'].items():
                    if region_type not in all_type_scores:
                        all_type_scores[region_type] = []
                    all_type_scores[region_type].append(score)

        # Average scores by type
        avg_all_type_scores = {}
        for region_type, scores in all_type_scores.items():
            avg_all_type_scores[region_type] = sum(scores) / len(scores)

        print(f"\nOverall evaluation complete.")
        print(f"Average similarity score across all images: {overall_score:.2f}")

        # Print scores by type
        print("\nScores by element type:")
        for region_type, score in avg_all_type_scores.items():
            print(f"  - {region_type}: {score:.2f}")

        # Create evaluation summary
        evaluation_summary = {
            "timestamp": timestamp,
            "overall_score": overall_score,
            "type_scores": avg_all_type_scores,
            "num_images": len(results),
            "lang_code": args.lang_code,
            "results": results
        }

        # Save results to file
        with open(args.output_file, 'w') as f:
            json.dump(evaluation_summary, f, indent=4)

        # Also save a timestamped copy for history
        history_file = os.path.join(args.log_dir, f"evaluation_{timestamp}.json")
        with open(history_file, 'w') as f:
            json.dump(evaluation_summary, f, indent=4)

        print(f"Evaluation results saved to: {args.output_file}")
        print(f"Evaluation history saved to: {history_file}")
    else:
        print("No results to report.")

if __name__ == "__main__":
    main()
