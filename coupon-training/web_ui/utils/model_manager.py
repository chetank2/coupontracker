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
    from scripts.prepare_simplified_model import generate_pattern_file
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
            generate_pattern_file(
                annotations_dir=os.path.join(self.data_dir, 'annotated'),
                output_file=pattern_file
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
            shutil.copy2(pattern_file, app_pattern_file)
            
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
            # Import the CouponPatternRecognizer class
            sys.path.append(os.path.join(self.base_dir, '..', 'app', 'src', 'main', 'kotlin', 'com', 'example', 'coupontracker', 'util'))
            
            # This is a simplified version for testing - in a real implementation,
            # we would need to use a Python version of the pattern recognizer
            # or call the Android code through some bridge
            
            # For now, we'll simulate the results
            pattern_file = os.path.join(self.simplified_dir, 'coupon_patterns.txt')
            
            if not os.path.exists(pattern_file):
                return {
                    'error': 'Pattern file not found'
                }
            
            # Read patterns
            patterns = self._read_patterns(pattern_file)
            
            # Simulate recognition results
            results = {
                'elements': [],
                'text': {}
            }
            
            # Add simulated elements based on patterns
            for pattern_type, regions in patterns.items():
                for i, region in enumerate(regions):
                    results['elements'].append({
                        'type': pattern_type,
                        'region': region,
                        'confidence': 0.85 + (i * 0.02)  # Simulated confidence score
                    })
                    
                    # Simulated extracted text
                    if pattern_type == 'store':
                        results['text'][pattern_type] = 'Sample Store'
                    elif pattern_type == 'description':
                        results['text'][pattern_type] = 'Sample coupon description'
                    elif pattern_type == 'expiry':
                        results['text'][pattern_type] = '2023-12-31'
                    elif pattern_type == 'code':
                        results['text'][pattern_type] = 'SAMPLE123'
                    elif pattern_type == 'amount':
                        results['text'][pattern_type] = '₹100 OFF'
            
            return results
        except Exception as e:
            print(f"Error testing image: {e}")
            return {
                'error': str(e)
            }
    
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
