#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import sys
import json
import shutil
import datetime
from pathlib import Path

# Add parent directory to path to import from scripts
sys.path.append(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

# Import training scripts
try:
    from scripts.prepare_simplified_model import create_coupon_patterns_file
    from scripts.prepare_android_model import copy_model_to_app
except ImportError as e:
    print(f"Error importing training scripts: {e}")

class ModelManager:
    """Manages the coupon pattern recognition model"""

    def __init__(self):
        # Base paths
        self.base_dir = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        self.data_dir = os.path.join(self.base_dir, 'data')
        self.models_dir = os.path.join(self.base_dir, 'models')
        self.simplified_dir = os.path.join(self.models_dir, 'simplified')
        self.app_assets_dir = os.path.join(self.base_dir, '..', 'app', 'src', 'main', 'assets', 'coupon_model')

        # Ensure directories exist
        os.makedirs(os.path.join(self.data_dir, 'annotated'), exist_ok=True)
        os.makedirs(self.simplified_dir, exist_ok=True)

        # Model history file
        self.history_file = os.path.join(self.simplified_dir, 'model_history.json')
        if not os.path.exists(self.history_file):
            with open(self.history_file, 'w') as f:
                json.dump([], f)

    def save_annotations(self, image_path, annotations):
        """Save annotation data for an image

        Args:
            image_path (str): Path to the image
            annotations (list): List of annotation objects with type, x, y, width, height

        Returns:
            bool: True if successful, False otherwise
        """
        try:
            # Extract image filename
            image_filename = os.path.basename(image_path)

            # Create annotation filename
            annotation_filename = os.path.splitext(image_filename)[0] + '.json'
            annotation_path = os.path.join(self.data_dir, 'annotated', annotation_filename)

            # Save annotations
            with open(annotation_path, 'w') as f:
                json.dump({
                    'image': image_filename,
                    'annotations': annotations,
                    'timestamp': datetime.datetime.now().isoformat()
                }, f, indent=2)

            return True
        except Exception as e:
            print(f"Error saving annotations: {e}")
            return False

    def train_model(self):
        """Train the model using annotated images

        Returns:
            dict: Result of training process
        """
        try:
            # Generate pattern file from annotations
            pattern_file = os.path.join(self.simplified_dir, 'coupon_patterns.txt')

            # Call the script function to generate the pattern file
            create_coupon_patterns_file(
                annotated_dir=os.path.join(self.data_dir, 'annotated'),
                output_dir=os.path.dirname(pattern_file)
            )

            # Create a version identifier
            version = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")

            # Save model info to history
            self._add_to_history({
                'version': version,
                'timestamp': datetime.datetime.now().isoformat(),
                'pattern_file': pattern_file,
                'num_patterns': self._count_patterns(pattern_file)
            })

            return {
                'success': True,
                'model_info': {
                    'version': version,
                    'pattern_file': pattern_file,
                    'num_patterns': self._count_patterns(pattern_file)
                }
            }
        except Exception as e:
            print(f"Error training model: {e}")
            return {
                'success': False,
                'error': str(e)
            }

    def update_app_model(self):
        """Update the model in the Android app

        Returns:
            dict: Result of update process
        """
        try:
            # Get the latest model
            models = self.get_models()
            if not models:
                return {
                    'success': False,
                    'error': 'No models available'
                }

            latest_model = models[0]  # Assuming models are sorted by date

            # Copy the model to the app
            pattern_file = os.path.join(self.simplified_dir, 'coupon_patterns.txt')

            # Ensure app assets directory exists
            os.makedirs(self.app_assets_dir, exist_ok=True)

            # Copy the pattern file to the app
            app_pattern_file = os.path.join(self.app_assets_dir, 'coupon_patterns.txt')
            copy_model_to_app(pattern_file, self.app_assets_dir)

            return {
                'success': True,
                'details': {
                    'model_version': latest_model['version'],
                    'app_pattern_file': app_pattern_file
                }
            }
        except Exception as e:
            print(f"Error updating app model: {e}")
            return {
                'success': False,
                'error': str(e)
            }

    def test_image(self, image_path):
        """Test an image with the current model

        Args:
            image_path (str): Path to the image to test

        Returns:
            dict: Recognition results
        """
        try:
            import cv2
            import pytesseract
            from PIL import Image
            import numpy as np

            # Check if pattern file exists
            pattern_file = os.path.join(self.simplified_dir, 'coupon_patterns.txt')
            if not os.path.exists(pattern_file):
                print("Pattern file not found:", pattern_file)
                return {
                    'error': 'Pattern file not found. Please train the model first.'
                }

            # Read patterns
            patterns = self._read_patterns(pattern_file)
            if not patterns:
                print("No patterns found in pattern file")
                return {
                    'error': 'No patterns found in pattern file. Please annotate and train the model first.'
                }

            print(f"Found {sum(len(regions) for regions in patterns.values())} patterns")

            # Load the image
            try:
                img = cv2.imread(image_path)
                if img is None:
                    print(f"Failed to load image: {image_path}")
                    return {'error': 'Failed to load image'}

                img_height, img_width = img.shape[:2]
                print(f"Image dimensions: {img_width}x{img_height}")
            except Exception as e:
                print(f"Error loading image: {e}")
                return {'error': f'Error loading image: {str(e)}'}

            # Initialize results
            results = {
                'elements': [],
                'text': {}
            }

            # Process each pattern type
            for pattern_type, regions in patterns.items():
                print(f"Processing pattern type: {pattern_type} with {len(regions)} regions")

                # For each region of this type
                for i, region in enumerate(regions):
                    # Scale the region to match the image dimensions
                    # This is a simple scaling approach - in a real implementation,
                    # we would need a more sophisticated approach

                    # Get region coordinates
                    left = region['left']
                    top = region['top']
                    right = region['right']
                    bottom = region['bottom']

                    # Add the region to the results
                    results['elements'].append({
                        'type': pattern_type,
                        'region': region,
                        'confidence': 0.85 + (i * 0.02)  # Simulated confidence score for now
                    })

                    # Extract the region from the image
                    try:
                        # Ensure coordinates are within image bounds
                        left = max(0, min(left, img_width - 1))
                        top = max(0, min(top, img_height - 1))
                        right = max(left + 1, min(right, img_width))
                        bottom = max(top + 1, min(bottom, img_height))

                        # Extract the region
                        region_img = img[top:bottom, left:right]

                        # Convert to PIL Image for Tesseract
                        region_pil = Image.fromarray(cv2.cvtColor(region_img, cv2.COLOR_BGR2RGB))

                        # Use Tesseract to extract text
                        extracted_text = pytesseract.image_to_string(region_pil).strip()

                        if extracted_text:
                            print(f"Extracted text for {pattern_type}: {extracted_text}")

                            # If we already have text for this type, append with a separator
                            if pattern_type in results['text']:
                                results['text'][pattern_type] += f" | {extracted_text}"
                            else:
                                results['text'][pattern_type] = extracted_text
                        else:
                            print(f"No text extracted for {pattern_type} region {i+1}")

                            # If we don't have any text yet for this type, add a placeholder
                            if pattern_type not in results['text']:
                                results['text'][pattern_type] = f"[No text detected]"
                    except Exception as e:
                        print(f"Error extracting text from region: {e}")
                        # Add a placeholder if we don't have any text yet for this type
                        if pattern_type not in results['text']:
                            results['text'][pattern_type] = f"[Error: {str(e)}]"

            # If we didn't extract any text, add some default values
            if not results['text']:
                print("No text extracted, adding default values")
                results['text'] = {
                    'store': 'Unknown Store',
                    'description': 'No description detected',
                    'expiry': 'Unknown expiry date',
                    'code': 'No code detected',
                    'amount': 'Unknown amount'
                }

            # Add a summary of the coupon
            coupon_summary = self._generate_coupon_summary(results['text'])
            results['summary'] = coupon_summary

            print(f"Returning results with {len(results['elements'])} elements and {len(results['text'])} text entries")
            return results
        except Exception as e:
            print(f"Error testing image: {e}")
            import traceback
            traceback.print_exc()
            return {
                'error': str(e)
            }

    def _generate_coupon_summary(self, text_data):
        """Generate a summary of the coupon based on extracted text

        Args:
            text_data (dict): Dictionary of extracted text by type

        Returns:
            str: Summary of the coupon
        """
        summary_parts = []

        # Add store name if available
        if 'store' in text_data and text_data['store'] != '[No text detected]':
            summary_parts.append(f"Store: {text_data['store']}")

        # Add amount if available
        if 'amount' in text_data and text_data['amount'] != '[No text detected]':
            summary_parts.append(f"Discount: {text_data['amount']}")

        # Add description if available
        if 'description' in text_data and text_data['description'] != '[No text detected]':
            summary_parts.append(f"Offer: {text_data['description']}")

        # Add code if available
        if 'code' in text_data and text_data['code'] != '[No text detected]':
            summary_parts.append(f"Code: {text_data['code']}")

        # Add expiry if available
        if 'expiry' in text_data and text_data['expiry'] != '[No text detected]':
            summary_parts.append(f"Valid until: {text_data['expiry']}")

        # Join all parts with newlines
        if summary_parts:
            return "\n".join(summary_parts)
        else:
            return "Could not generate a summary for this coupon."

    def get_models(self):
        """Get list of available models

        Returns:
            list: List of model information
        """
        try:
            with open(self.history_file, 'r') as f:
                history = json.load(f)

            # Sort by timestamp (newest first)
            history.sort(key=lambda x: x['timestamp'], reverse=True)

            return history
        except Exception as e:
            print(f"Error getting models: {e}")
            return []

    def _add_to_history(self, model_info):
        """Add a model to the history

        Args:
            model_info (dict): Model information
        """
        try:
            with open(self.history_file, 'r') as f:
                history = json.load(f)

            history.append(model_info)

            with open(self.history_file, 'w') as f:
                json.dump(history, f, indent=2)
        except Exception as e:
            print(f"Error adding to history: {e}")

    def _count_patterns(self, pattern_file):
        """Count the number of patterns in a pattern file

        Args:
            pattern_file (str): Path to the pattern file

        Returns:
            int: Number of patterns
        """
        try:
            count = 0
            with open(pattern_file, 'r') as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith('#'):
                        count += 1
            return count
        except Exception as e:
            print(f"Error counting patterns: {e}")
            return 0

    def _read_patterns(self, pattern_file):
        """Read patterns from a pattern file

        Args:
            pattern_file (str): Path to the pattern file

        Returns:
            dict: Dictionary of pattern types and regions
        """
        patterns = {}

        try:
            with open(pattern_file, 'r') as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith('#'):
                        if ':' in line:
                            parts = line.split(':')
                            if len(parts) == 2:
                                pattern_type = parts[0]
                                coords = parts[1].split(',')

                                if len(coords) == 4:
                                    region = {
                                        'left': int(coords[0]),
                                        'top': int(coords[1]),
                                        'right': int(coords[2]),
                                        'bottom': int(coords[3])
                                    }

                                    if pattern_type not in patterns:
                                        patterns[pattern_type] = []

                                    patterns[pattern_type].append(region)
        except Exception as e:
            print(f"Error reading patterns: {e}")

        return patterns
