#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import json
import uuid
import time
import threading
from datetime import datetime, timedelta

class ModelManager:
    """Utility class for managing the model"""

    def __init__(self):
        """Initialize the model manager"""
        self.base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        self.models_dir = os.path.join(self.base_dir, 'models')
        self.data_dir = os.path.join(self.base_dir, 'data')
        self.metrics_file = os.path.join(self.models_dir, 'metrics.json')
        self.sessions_file = os.path.join(self.models_dir, 'sessions.json')
        self.url_tasks = {}

        # Create directories if they don't exist
        os.makedirs(self.models_dir, exist_ok=True)
        os.makedirs(self.data_dir, exist_ok=True)

        # Initialize metrics file if it doesn't exist
        if not os.path.exists(self.metrics_file):
            self._initialize_metrics_file()

        # Initialize sessions file if it doesn't exist
        if not os.path.exists(self.sessions_file):
            self._initialize_sessions_file()

    def _initialize_metrics_file(self):
        """Initialize the metrics file with default values"""
        metrics = {
            'test_accuracy': 0.8741,
            'train_loss': 0.2777,
            'val_loss': 0.4622,
            'train_samples': 9,
            'val_samples': 2,
            'test_samples': 3,
            'model_type': 'India Coupon Recognizer',
            'model_version': '1.0.1',
            'last_updated': datetime.now().strftime('%b %d, %Y'),
            'history': {
                'train_loss': [0.9978, 0.9963, 0.9625, 0.8524, 0.8337, 0.7592, 0.7504, 0.7072, 0.6436, 0.6719, 0.6424, 0.5221, 0.4807, 0.4933, 0.4466, 0.3617, 0.3319, 0.3508, 0.2825, 0.2777],
                'val_loss': [1.2616, 1.1672, 1.1382, 0.9995, 0.9950, 1.1240, 0.9238, 1.0279, 1.0193, 0.9826, 0.8443, 0.7378, 0.8111, 0.7108, 0.7849, 0.7319, 0.5647, 0.5242, 0.6235, 0.4622]
            }
        }

        with open(self.metrics_file, 'w') as f:
            json.dump(metrics, f, indent=4)

    def _initialize_sessions_file(self):
        """Initialize the sessions file with default values"""
        sessions = [
            {
                'id': str(uuid.uuid4()),
                'date': (datetime.now() - timedelta(days=7)).strftime('%Y-%m-%d %H:%M:%S'),
                'model_version': '0.9.0',
                'test_accuracy': 0.8123,
                'train_loss': 0.3456,
                'val_loss': 0.5678,
                'train_samples': 7,
                'val_samples': 2,
                'test_samples': 2
            },
            {
                'id': str(uuid.uuid4()),
                'date': (datetime.now() - timedelta(days=3)).strftime('%Y-%m-%d %H:%M:%S'),
                'model_version': '0.9.5',
                'test_accuracy': 0.8432,
                'train_loss': 0.3123,
                'val_loss': 0.5234,
                'train_samples': 8,
                'val_samples': 2,
                'test_samples': 3
            },
            {
                'id': str(uuid.uuid4()),
                'date': datetime.now().strftime('%Y-%m-%d %H:%M:%S'),
                'model_version': '1.0.1',
                'test_accuracy': 0.8741,
                'train_loss': 0.2777,
                'val_loss': 0.4622,
                'train_samples': 9,
                'val_samples': 2,
                'test_samples': 3
            }
        ]

        with open(self.sessions_file, 'w') as f:
            json.dump(sessions, f, indent=4)

    def get_model_metrics(self, version='latest'):
        """Get the model metrics

        Args:
            version (str): The model version to get metrics for

        Returns:
            dict: The model metrics
        """
        try:
            with open(self.metrics_file, 'r') as f:
                metrics = json.load(f)

            return metrics
        except Exception as e:
            print(f"Error getting model metrics: {e}")
            return None

    def get_training_sessions(self):
        """Get all training sessions

        Returns:
            list: List of training sessions
        """
        try:
            with open(self.sessions_file, 'r') as f:
                sessions = json.load(f)

            return sessions
        except Exception as e:
            print(f"Error getting training sessions: {e}")
            return []

    def save_annotations(self, image_path, annotations):
        """Save annotation data for an image

        Args:
            image_path (str): Path to the image
            annotations (dict): Annotation data

        Returns:
            bool: True if successful, False otherwise
        """
        try:
            # For demo purposes, just return True
            return True
        except Exception as e:
            print(f"Error saving annotations: {e}")
            return False

    def train_model(self):
        """Train the model using annotated images

        Returns:
            dict: Result of the training process
        """
        try:
            # For demo purposes, simulate training
            time.sleep(2)

            # Update metrics
            metrics = self.get_model_metrics()
            metrics['test_accuracy'] = 0.8912
            metrics['train_loss'] = 0.2345
            metrics['val_loss'] = 0.4123
            metrics['train_samples'] += 1
            metrics['last_updated'] = datetime.now().strftime('%b %d, %Y')

            # Add new values to history
            metrics['history']['train_loss'].append(0.2345)
            metrics['history']['val_loss'].append(0.4123)

            # Save updated metrics
            with open(self.metrics_file, 'w') as f:
                json.dump(metrics, f, indent=4)

            # Add new session
            sessions = self.get_training_sessions()
            sessions.append({
                'id': str(uuid.uuid4()),
                'date': datetime.now().strftime('%Y-%m-%d %H:%M:%S'),
                'model_version': '1.0.1',
                'test_accuracy': 0.8912,
                'train_loss': 0.2345,
                'val_loss': 0.4123,
                'train_samples': metrics['train_samples'],
                'val_samples': metrics['val_samples'],
                'test_samples': metrics['test_samples']
            })

            # Save updated sessions
            with open(self.sessions_file, 'w') as f:
                json.dump(sessions, f, indent=4)

            return {
                'success': True,
                'model_info': {
                    'version': '1.0.1',
                    'accuracy': 0.8912,
                    'train_loss': 0.2345,
                    'val_loss': 0.4123
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
            dict: Result of the update process
        """
        try:
            # For demo purposes, simulate updating the app
            time.sleep(2)

            return {
                'success': True,
                'details': 'Model updated in app successfully'
            }
        except Exception as e:
            print(f"Error updating app model: {e}")
            return {
                'success': False,
                'error': str(e)
            }

    def get_models(self):
        """Get list of available models

        Returns:
            list: List of available models
        """
        try:
            # For demo purposes, return a list of models
            return [
                {
                    'version': '1.0.1',
                    'accuracy': 0.8741,
                    'date': (datetime.now() - timedelta(days=3)).strftime('%Y-%m-%d')
                },
                {
                    'version': '0.9.5',
                    'accuracy': 0.8432,
                    'date': (datetime.now() - timedelta(days=7)).strftime('%Y-%m-%d')
                },
                {
                    'version': '0.9.0',
                    'accuracy': 0.8123,
                    'date': (datetime.now() - timedelta(days=14)).strftime('%Y-%m-%d')
                }
            ]
        except Exception as e:
            print(f"Error getting models: {e}")
            return []

    def test_image(self, image_path):
        """Test an image with the model

        Args:
            image_path (str): Path to the image

        Returns:
            dict: Recognition results
        """
        try:
            # For demo purposes, return mock results
            return {
                'store_name': {
                    'text': 'Zomato',
                    'confidence': 0.95
                },
                'coupon_code': {
                    'text': 'WELCOME50',
                    'confidence': 0.98
                },
                'discount': {
                    'text': '50% OFF up to ₹150',
                    'confidence': 0.85
                },
                'expiry_date': {
                    'text': '31 May 2025',
                    'confidence': 0.65
                }
            }
        except Exception as e:
            print(f"Error testing image: {e}")
            return {}

    def train_from_url(self, url, filter_images=True, augment_images=True, update_app=False):
        """Train the model from a URL

        Args:
            url (str): URL containing coupon images
            filter_images (bool): Whether to filter images
            augment_images (bool): Whether to augment images
            update_app (bool): Whether to update the app with the new model

        Returns:
            str: Task ID for tracking progress
        """
        try:
            # Generate a task ID
            task_id = str(uuid.uuid4())

            # Initialize task status
            self.url_tasks[task_id] = {
                'status': 'initializing',
                'progress': 0,
                'message': 'Initializing training process...',
                'url': url,
                'filter_images': filter_images,
                'augment_images': augment_images,
                'update_app': update_app,
                'start_time': datetime.now().strftime('%Y-%m-%d %H:%M:%S'),
                'end_time': None,
                'results': None
            }

            # Start training in a background thread
            thread = threading.Thread(target=self._train_from_url_thread, args=(task_id,))
            thread.daemon = True
            thread.start()

            return task_id
        except Exception as e:
            print(f"Error starting URL training: {e}")
            return None

    def _train_from_url_thread(self, task_id):
        """Background thread for training from URL

        Args:
            task_id (str): Task ID
        """
        try:
            task = self.url_tasks[task_id]

            # Simulate downloading images
            task['status'] = 'downloading'
            task['progress'] = 10
            task['message'] = f"Downloading images from: {task['url']}"
            time.sleep(2)

            # Simulate processing images
            task['status'] = 'processing'
            task['progress'] = 30
            task['message'] = f"Processing downloaded images{' with filtering' if task['filter_images'] else ''}"
            time.sleep(2)

            # Simulate preparing training data
            task['status'] = 'preparing'
            task['progress'] = 50
            task['message'] = f"Preparing training data{' with augmentation' if task['augment_images'] else ''}"
            time.sleep(2)

            # Simulate training model
            task['status'] = 'training'
            task['progress'] = 70
            task['message'] = "Training the recognition model with the new data"
            time.sleep(3)

            # Simulate evaluating model
            task['status'] = 'evaluating'
            task['progress'] = 90
            task['message'] = "Evaluating model performance on test data"
            time.sleep(2)

            # Simulate updating app if requested
            if task['update_app']:
                task['status'] = 'updating_app'
                task['progress'] = 95
                task['message'] = "Updating app with the new model"
                time.sleep(2)

            # Complete the task
            task['status'] = 'completed'
            task['progress'] = 100
            task['message'] = f"Training completed successfully!{' App has been updated with the new model.' if task['update_app'] else ''}"
            task['end_time'] = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            task['results'] = {
                'test_accuracy': 0.8912,
                'train_loss': 0.2345,
                'val_loss': 0.4123,
                'train_samples': 12,
                'val_samples': 3,
                'test_samples': 4
            }

            # Update metrics
            metrics = self.get_model_metrics()
            metrics['test_accuracy'] = 0.8912
            metrics['train_loss'] = 0.2345
            metrics['val_loss'] = 0.4123
            metrics['train_samples'] = 12
            metrics['val_samples'] = 3
            metrics['test_samples'] = 4
            metrics['last_updated'] = datetime.now().strftime('%b %d, %Y')

            # Add new values to history
            metrics['history']['train_loss'].append(0.2345)
            metrics['history']['val_loss'].append(0.4123)

            # Save updated metrics
            with open(self.metrics_file, 'w') as f:
                json.dump(metrics, f, indent=4)

            # Add new session
            sessions = self.get_training_sessions()
            sessions.append({
                'id': str(uuid.uuid4()),
                'date': datetime.now().strftime('%Y-%m-%d %H:%M:%S'),
                'model_version': '1.0.1',
                'test_accuracy': 0.8912,
                'train_loss': 0.2345,
                'val_loss': 0.4123,
                'train_samples': 12,
                'val_samples': 3,
                'test_samples': 4,
                'source': f"URL: {task['url']}"
            })

            # Save updated sessions
            with open(self.sessions_file, 'w') as f:
                json.dump(sessions, f, indent=4)
        except Exception as e:
            print(f"Error in URL training thread: {e}")
            if task_id in self.url_tasks:
                self.url_tasks[task_id]['status'] = 'failed'
                self.url_tasks[task_id]['message'] = f"Training failed: {str(e)}"
                self.url_tasks[task_id]['end_time'] = datetime.now().strftime('%Y-%m-%d %H:%M:%S')

    def get_url_training_status(self, task_id):
        """Get the status of a URL training task

        Args:
            task_id (str): Task ID

        Returns:
            dict: Task status
        """
        try:
            if task_id in self.url_tasks:
                return self.url_tasks[task_id]
            else:
                return {
                    'status': 'not_found',
                    'message': 'Task not found'
                }
        except Exception as e:
            print(f"Error getting URL training status: {e}")
            return {
                'status': 'error',
                'message': f"Error getting status: {str(e)}"
            }
