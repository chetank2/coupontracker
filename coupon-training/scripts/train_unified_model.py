#!/usr/bin/env python3
"""
Train Unified Model Script

This script trains a unified model on the combined dataset of standard and Indian coupons.
"""

import os
import argparse
import logging
import subprocess
import yaml
import json
from pathlib import Path
import shutil

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("train_unified_model.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("train_unified_model")

def train_model(data_dir, output_dir, epochs=100, batch_size=16, img_size=640):
    """
    Train a YOLOv5 model on the combined dataset.
    
    Args:
        data_dir: Directory containing the combined dataset
        output_dir: Directory to save the trained model
        epochs: Number of training epochs
        batch_size: Batch size for training
        img_size: Input image size for the model
    """
    # Create output directory
    os.makedirs(output_dir, exist_ok=True)
    
    # Path to dataset.yaml
    dataset_yaml = os.path.join(data_dir, 'dataset.yaml')
    
    if not os.path.exists(dataset_yaml):
        logger.error(f"Dataset configuration file not found: {dataset_yaml}")
        return False
    
    # Clone YOLOv5 repository if not already present
    yolov5_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'yolov5')
    if not os.path.exists(yolov5_dir):
        logger.info("Cloning YOLOv5 repository...")
        subprocess.run(
            ['git', 'clone', 'https://github.com/ultralytics/yolov5.git', yolov5_dir],
            check=True
        )
    
    # Install YOLOv5 dependencies
    logger.info("Installing YOLOv5 dependencies...")
    subprocess.run(
        ['pip', 'install', '-r', os.path.join(yolov5_dir, 'requirements.txt')],
        check=True
    )
    
    # Train the model
    logger.info("Training the unified model...")
    train_command = [
        'python', os.path.join(yolov5_dir, 'train.py'),
        '--data', dataset_yaml,
        '--epochs', str(epochs),
        '--batch-size', str(batch_size),
        '--img', str(img_size),
        '--project', output_dir,
        '--name', 'unified_coupon_model',
        '--cache'
    ]
    
    subprocess.run(train_command, check=True)
    
    # Export the model to ONNX format
    logger.info("Exporting the model to ONNX format...")
    best_pt = os.path.join(output_dir, 'unified_coupon_model', 'weights', 'best.pt')
    
    if not os.path.exists(best_pt):
        logger.error(f"Trained model not found: {best_pt}")
        return False
    
    export_command = [
        'python', os.path.join(yolov5_dir, 'export.py'),
        '--weights', best_pt,
        '--include', 'onnx',
        '--img', str(img_size),
        '--simplify'
    ]
    
    subprocess.run(export_command, check=True)
    
    # Copy the exported model to the output directory
    onnx_model = os.path.join(os.path.dirname(best_pt), 'best.onnx')
    if os.path.exists(onnx_model):
        shutil.copy(onnx_model, os.path.join(output_dir, 'unified_coupon_model.onnx'))
    
    # Export the model to TFLite format
    logger.info("Exporting the model to TFLite format...")
    export_tflite_command = [
        'python', os.path.join(yolov5_dir, 'export.py'),
        '--weights', best_pt,
        '--include', 'tflite',
        '--img', str(img_size)
    ]
    
    subprocess.run(export_tflite_command, check=True)
    
    # Copy the exported TFLite model to the output directory
    tflite_model = os.path.join(os.path.dirname(best_pt), 'best.tflite')
    if os.path.exists(tflite_model):
        shutil.copy(tflite_model, os.path.join(output_dir, 'unified_coupon_model.tflite'))
    
    # Create model configuration file
    create_model_config(data_dir, output_dir, img_size)
    
    logger.info(f"Model training and export completed. Model saved to {output_dir}")
    return True

def create_model_config(data_dir, output_dir, img_size):
    """
    Create a configuration file for the model.
    
    Args:
        data_dir: Directory containing the dataset
        output_dir: Directory to save the model configuration
        img_size: Input image size for the model
    """
    # Read dataset.yaml to get class names
    dataset_yaml = os.path.join(data_dir, 'dataset.yaml')
    with open(dataset_yaml, 'r') as f:
        dataset_config = yaml.safe_load(f)
    
    class_names = dataset_config.get('names', [])
    
    # Create model configuration
    model_config = {
        "model_name": "unified_coupon_recognizer",
        "model_file": "unified_coupon_model.tflite",
        "input_size": img_size,
        "classes": class_names,
        "confidence_threshold": 0.25,
        "iou_threshold": 0.45,
        "version": "2.0.0",
        "description": "YOLOv5s model trained on combined standard and Indian coupon data"
    }
    
    # Save model configuration
    with open(os.path.join(output_dir, 'unified_coupon_model_config.json'), 'w') as f:
        json.dump(model_config, f, indent=2)

def main():
    """Main function."""
    parser = argparse.ArgumentParser(description='Train a unified model on the combined dataset.')
    parser.add_argument('--data-dir', required=True, help='Directory containing the combined dataset')
    parser.add_argument('--output-dir', required=True, help='Directory to save the trained model')
    parser.add_argument('--epochs', type=int, default=100, help='Number of training epochs')
    parser.add_argument('--batch-size', type=int, default=16, help='Batch size for training')
    parser.add_argument('--img-size', type=int, default=640, help='Input image size for the model')
    args = parser.parse_args()
    
    train_model(
        args.data_dir,
        args.output_dir,
        args.epochs,
        args.batch_size,
        args.img_size
    )

if __name__ == '__main__':
    main()
