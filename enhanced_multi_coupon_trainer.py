#!/usr/bin/env python3
"""
Enhanced Multi-Coupon Training Pipeline
Converts PWA training data to YOLO format and trains two-stage models

This script provides a complete end-to-end training pipeline for multi-coupon detection:
1. Stage 1: Coupon boundary detection (complete, partial_top, partial_bottom)
2. Stage 2: Field detection within coupon crops (code, benefit, expiry, app, terms)

Usage:
    python enhanced_multi_coupon_trainer.py --pwa-export pwa_export.json --output-dir training_data
"""

import os
import json
import cv2
import numpy as np
import yaml
import shutil
import argparse
import base64
from pathlib import Path
from datetime import datetime
from typing import Dict, List, Tuple, Optional
import logging

# Set up logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

class MultiCouponTrainer:
    """Enhanced trainer for multi-coupon detection using two-stage YOLO approach"""
    
    def __init__(self, output_dir: str = "multi_coupon_training"):
        self.output_dir = output_dir
        self.stage1_classes = ['coupon_complete', 'coupon_partial_top', 'coupon_partial_bottom']
        self.stage2_classes = ['code_region', 'benefit_region', 'expiry_region', 'app_region', 'terms_region']
        
        # Create output directory
        os.makedirs(output_dir, exist_ok=True)
        logger.info(f"Initialized MultiCouponTrainer with output directory: {output_dir}")
        
    def convert_pwa_data(self, pwa_export_file: str) -> Tuple[str, str]:
        """Convert PWA export to YOLO training format for both stages"""
        logger.info(f"🔄 Converting PWA data from {pwa_export_file}")
        
        with open(pwa_export_file, 'r') as f:
            pwa_data = json.load(f)
        
        # Validate PWA data format
        if not self._validate_pwa_data(pwa_data):
            raise ValueError("Invalid PWA data format")
        
        # Create directory structure for both stages
        stage1_dir = f"{self.output_dir}/stage1_coupon_detection"
        stage2_dir = f"{self.output_dir}/stage2_field_detection"
        
        for stage_dir in [stage1_dir, stage2_dir]:
            for split in ['train', 'val']:
                os.makedirs(f"{stage_dir}/images/{split}", exist_ok=True)
                os.makedirs(f"{stage_dir}/labels/{split}", exist_ok=True)
        
        # Process Stage 1: Coupon Detection Dataset
        stage1_stats = self._create_stage1_dataset(pwa_data, stage1_dir)
        
        # Process Stage 2: Field Detection Dataset  
        stage2_stats = self._create_stage2_dataset(pwa_data, stage2_dir)
        
        logger.info(f"✅ Conversion complete. Datasets saved to {self.output_dir}")
        logger.info(f"Stage 1 stats: {stage1_stats}")
        logger.info(f"Stage 2 stats: {stage2_stats}")
        
        return stage1_dir, stage2_dir
    
    def _validate_pwa_data(self, pwa_data: Dict) -> bool:
        """Validate PWA export data format"""
        required_keys = ['version', 'type', 'stage1_coupon_detection', 'stage2_field_detection']
        
        for key in required_keys:
            if key not in pwa_data:
                logger.error(f"Missing required key: {key}")
                return False
        
        if pwa_data.get('type') != 'multi_coupon_training':
            logger.error(f"Invalid data type: {pwa_data.get('type')}")
            return False
            
        logger.info(f"Validated PWA data: {len(pwa_data['stage1_coupon_detection'])} stage1 items, "
                   f"{len(pwa_data['stage2_field_detection'])} stage2 items")
        
        return True
    
    def _create_stage1_dataset(self, pwa_data: Dict, output_dir: str) -> Dict:
        """Create Stage 1 dataset for coupon boundary detection"""
        logger.info("📦 Creating Stage 1 dataset (Coupon Boundaries)")
        
        # Create dataset.yaml
        dataset_config = {
            'path': os.path.abspath(output_dir),
            'train': 'images/train',
            'val': 'images/val',
            'nc': len(self.stage1_classes),
            'names': self.stage1_classes
        }
        
        with open(f"{output_dir}/dataset.yaml", 'w') as f:
            yaml.dump(dataset_config, f, default_flow_style=False)
        
        # Process stage 1 data
        stage1_data = pwa_data.get('stage1_coupon_detection', [])
        if not stage1_data:
            logger.warning("No Stage 1 data found")
            return {'processed': 0, 'train': 0, 'val': 0}
        
        # Split data (80% train, 20% validation)
        train_split = int(len(stage1_data) * 0.8)
        stats = {'processed': 0, 'train': 0, 'val': 0, 'class_counts': {}}
        
        for idx, item in enumerate(stage1_data):
            try:
                split = 'train' if idx < train_split else 'val'
                
                # Save image
                image_path = self._save_image_from_pwa_data(
                    item, f"{output_dir}/images/{split}", item['image_id']
                )
                
                if not image_path:
                    logger.warning(f"Failed to save image: {item['image_id']}")
                    continue
                
                # Create YOLO label
                label_path = f"{output_dir}/labels/{split}/{Path(item['image_id']).stem}.txt"
                yolo_labels = []
                
                # Get class ID
                class_name = item['class_name']
                if class_name not in self.stage1_classes:
                    logger.warning(f"Unknown class: {class_name}, skipping")
                    continue
                    
                class_id = self.stage1_classes.index(class_name)
                bbox = item['bounding_box']
                
                # Convert to YOLO format (normalized coordinates)
                center_x = bbox['x'] + bbox['width'] / 2
                center_y = bbox['y'] + bbox['height'] / 2
                width = bbox['width']
                height = bbox['height']
                
                yolo_labels.append(f"{class_id} {center_x:.6f} {center_y:.6f} {width:.6f} {height:.6f}")
                
                # Save labels
                with open(label_path, 'w') as f:
                    f.write('\n'.join(yolo_labels))
                
                # Update statistics
                stats['processed'] += 1
                stats[split] += 1
                stats['class_counts'][class_name] = stats['class_counts'].get(class_name, 0) + 1
                
            except Exception as e:
                logger.error(f"Error processing stage1 item {idx}: {e}")
                continue
        
        logger.info(f"✅ Stage 1 dataset created: {stats}")
        return stats
    
    def _create_stage2_dataset(self, pwa_data: Dict, output_dir: str) -> Dict:
        """Create Stage 2 dataset for field detection within coupon crops"""
        logger.info("📦 Creating Stage 2 dataset (Field Detection)")
        
        # Create dataset.yaml
        dataset_config = {
            'path': os.path.abspath(output_dir),
            'train': 'images/train',
            'val': 'images/val',
            'nc': len(self.stage2_classes),
            'names': self.stage2_classes
        }
        
        with open(f"{output_dir}/dataset.yaml", 'w') as f:
            yaml.dump(dataset_config, f, default_flow_style=False)
        
        # Process stage 2 data
        stage2_data = pwa_data.get('stage2_field_detection', [])
        stage1_data = pwa_data.get('stage1_coupon_detection', [])
        
        if not stage2_data:
            logger.warning("No Stage 2 data found")
            return {'processed': 0, 'train': 0, 'val': 0}
        
        # Create mapping of instances to boundaries
        instance_boundaries = {}
        original_images = {}
        
        for item in stage1_data:
            instance_id = item['instance_id']
            instance_boundaries[instance_id] = item['bounding_box']
            
            # Store original image data for cropping
            if item['image_id'] not in original_images and 'image_data' in item:
                original_images[item['image_id']] = item['image_data']
        
        # Group field annotations by image and instance
        grouped_fields = {}
        for field in stage2_data:
            key = f"{field['image_id']}_{field['instance_id']}"
            if key not in grouped_fields:
                grouped_fields[key] = {
                    'image_id': field['image_id'],
                    'instance_id': field['instance_id'],
                    'fields': []
                }
            grouped_fields[key]['fields'].append(field)
        
        # Create cropped images and labels for each instance
        train_split = int(len(grouped_fields) * 0.8)
        stats = {'processed': 0, 'train': 0, 'val': 0, 'class_counts': {}}
        
        for idx, (key, group) in enumerate(grouped_fields.items()):
            try:
                split = 'train' if idx < train_split else 'val'
                image_id = group['image_id']
                instance_id = group['instance_id']
                fields = group['fields']
                
                # Check if we have the boundary for this instance
                if instance_id not in instance_boundaries:
                    logger.warning(f"No boundary found for instance {instance_id}")
                    continue
                
                # Load and crop original image
                original_image = self._load_image_from_pwa_data(original_images.get(image_id))
                if original_image is None:
                    logger.warning(f"Could not load original image: {image_id}")
                    continue
                
                boundary = instance_boundaries[instance_id]
                cropped_image = self._crop_image(original_image, boundary)
                
                if cropped_image is None or cropped_image.size == 0:
                    logger.warning(f"Invalid crop for instance {instance_id}")
                    continue
                
                # Save cropped image
                crop_filename = f"{Path(image_id).stem}_{instance_id}.jpg"
                crop_path = f"{output_dir}/images/{split}/{crop_filename}"
                cv2.imwrite(crop_path, cropped_image)
                
                # Create YOLO labels for fields within crop
                label_path = f"{output_dir}/labels/{split}/{Path(crop_filename).stem}.txt"
                yolo_labels = []
                
                crop_height, crop_width = cropped_image.shape[:2]
                
                for field in fields:
                    field_type = field['field_type']
                    if field_type not in self.stage2_classes:
                        logger.warning(f"Unknown field type: {field_type}")
                        continue
                        
                    class_id = self.stage2_classes.index(field_type)
                    field_bbox = field['bounding_box']
                    
                    # Adjust coordinates relative to crop (convert from original image space to crop space)
                    rel_x = (field_bbox['x'] - boundary['x']) / boundary['width']
                    rel_y = (field_bbox['y'] - boundary['y']) / boundary['height']
                    rel_w = field_bbox['width'] / boundary['width']
                    rel_h = field_bbox['height'] / boundary['height']
                    
                    # Clamp to valid range [0, 1]
                    rel_x = max(0, min(1, rel_x))
                    rel_y = max(0, min(1, rel_y))
                    rel_w = max(0, min(1, rel_w))
                    rel_h = max(0, min(1, rel_h))
                    
                    # Convert to YOLO format (center coordinates)
                    center_x = rel_x + rel_w / 2
                    center_y = rel_y + rel_h / 2
                    
                    # Ensure center coordinates are within bounds
                    center_x = max(rel_w/2, min(1 - rel_w/2, center_x))
                    center_y = max(rel_h/2, min(1 - rel_h/2, center_y))
                    
                    yolo_labels.append(f"{class_id} {center_x:.6f} {center_y:.6f} {rel_w:.6f} {rel_h:.6f}")
                    
                    # Update statistics
                    stats['class_counts'][field_type] = stats['class_counts'].get(field_type, 0) + 1
                
                # Save labels
                if yolo_labels:  # Only save if we have valid labels
                    with open(label_path, 'w') as f:
                        f.write('\n'.join(yolo_labels))
                    
                    stats['processed'] += 1
                    stats[split] += 1
                
            except Exception as e:
                logger.error(f"Error processing stage2 group {idx}: {e}")
                continue
        
        logger.info(f"✅ Stage 2 dataset created: {stats}")
        return stats
    
    def _save_image_from_pwa_data(self, item: Dict, output_dir: str, filename: str) -> Optional[str]:
        """Save image from PWA data (base64) to file"""
        try:
            if 'image_data' not in item:
                logger.warning(f"No image_data in item for {filename}")
                return None
            
            image_data = item['image_data']
            
            # Handle base64 data URL format
            if image_data.startswith('data:image'):
                header, encoded = image_data.split(',', 1)
                image_bytes = base64.b64decode(encoded)
            else:
                # Assume it's already base64 encoded
                image_bytes = base64.b64decode(image_data)
            
            # Convert to numpy array and then to OpenCV format
            nparr = np.frombuffer(image_bytes, np.uint8)
            image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
            
            if image is None:
                logger.error(f"Could not decode image: {filename}")
                return None
            
            # Save image
            output_path = f"{output_dir}/{filename}"
            cv2.imwrite(output_path, image)
            return output_path
            
        except Exception as e:
            logger.error(f"Error saving image {filename}: {e}")
            return None
    
    def _load_image_from_pwa_data(self, image_data: str) -> Optional[np.ndarray]:
        """Load image from PWA data (base64) as numpy array"""
        try:
            if not image_data:
                return None
            
            # Handle base64 data URL format
            if image_data.startswith('data:image'):
                header, encoded = image_data.split(',', 1)
                image_bytes = base64.b64decode(encoded)
            else:
                image_bytes = base64.b64decode(image_data)
            
            # Convert to numpy array and then to OpenCV format
            nparr = np.frombuffer(image_bytes, np.uint8)
            image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
            return image
            
        except Exception as e:
            logger.error(f"Error loading image from data: {e}")
            return None
    
    def _crop_image(self, image: np.ndarray, boundary: Dict) -> Optional[np.ndarray]:
        """Crop image using normalized boundary coordinates"""
        try:
            h, w = image.shape[:2]
            
            # Convert normalized coordinates to pixel coordinates
            x = int(boundary['x'] * w)
            y = int(boundary['y'] * h)
            width = int(boundary['width'] * w)
            height = int(boundary['height'] * h)
            
            # Ensure coordinates are within image bounds
            x = max(0, min(w - 1, x))
            y = max(0, min(h - 1, y))
            width = max(1, min(w - x, width))
            height = max(1, min(h - y, height))
            
            # Crop image
            cropped = image[y:y+height, x:x+width]
            return cropped
            
        except Exception as e:
            logger.error(f"Error cropping image: {e}")
            return None
    
    def train_stage1_model(self, dataset_dir: str, config: Dict = None) -> Tuple[str, str]:
        """Train Stage 1 coupon detection model"""
        logger.info("🚀 Training Stage 1 Model (Coupon Boundaries)")
        
        try:
            from ultralytics import YOLO
        except ImportError:
            logger.error("ultralytics package not found. Install with: pip install ultralytics")
            raise
        
        # Default training configuration
        default_config = {
            'epochs': 100,
            'batch': 16,
            'imgsz': 640,
            'patience': 20,
            'device': 'cpu',  # Change to 'cuda' if GPU available
            'workers': 4,
            'augment': True,
            'mixup': 0.1,
            'degrees': 10,
            'translate': 0.1,
            'scale': 0.5,
            'shear': 2.0,
            'flipud': 0.0,
            'fliplr': 0.5,
            'mosaic': 1.0
        }
        
        if config:
            default_config.update(config)
        
        # Initialize model
        model = YOLO('yolov8n.pt')  # Start with nano model
        
        # Training arguments
        training_args = {
            'data': f"{dataset_dir}/dataset.yaml",
            'name': 'stage1_coupon_detector',
            'project': f"{self.output_dir}/training_runs",
            'save_period': 10,
            **default_config
        }
        
        # Start training
        logger.info(f"Starting Stage 1 training with config: {training_args}")
        results = model.train(**training_args)
        
        # Export to TensorFlow Lite
        best_model_path = results.save_dir / 'weights' / 'best.pt'
        model = YOLO(str(best_model_path))
        tflite_path = model.export(format='tflite', int8=True)
        
        logger.info(f"✅ Stage 1 model trained and exported: {tflite_path}")
        return str(best_model_path), str(tflite_path)
    
    def train_stage2_model(self, dataset_dir: str, config: Dict = None) -> Tuple[str, str]:
        """Train Stage 2 field detection model"""
        logger.info("🚀 Training Stage 2 Model (Field Detection)")
        
        try:
            from ultralytics import YOLO
        except ImportError:
            logger.error("ultralytics package not found. Install with: pip install ultralytics")
            raise
        
        # Default training configuration (optimized for smaller crops)
        default_config = {
            'epochs': 150,
            'batch': 16,
            'imgsz': 320,  # Smaller input size for cropped images
            'patience': 25,
            'device': 'cpu',
            'workers': 4,
            'augment': True,
            'mixup': 0.1,
            'degrees': 5,   # Less rotation for text fields
            'translate': 0.05,
            'scale': 0.3,
            'shear': 1.0,
            'flipud': 0.0,
            'fliplr': 0.2,  # Less horizontal flip for text
            'mosaic': 0.8
        }
        
        if config:
            default_config.update(config)
        
        # Initialize model
        model = YOLO('yolov8n.pt')
        
        # Training arguments
        training_args = {
            'data': f"{dataset_dir}/dataset.yaml",
            'name': 'stage2_field_detector',
            'project': f"{self.output_dir}/training_runs",
            'save_period': 10,
            **default_config
        }
        
        # Start training
        logger.info(f"Starting Stage 2 training with config: {training_args}")
        results = model.train(**training_args)
        
        # Export to TensorFlow Lite
        best_model_path = results.save_dir / 'weights' / 'best.pt'
        model = YOLO(str(best_model_path))
        tflite_path = model.export(format='tflite', int8=True)
        
        logger.info(f"✅ Stage 2 model trained and exported: {tflite_path}")
        return str(best_model_path), str(tflite_path)
    
    def package_for_android(self, stage1_tflite: str, stage2_tflite: str, android_assets_dir: str) -> str:
        """Package both models for Android app"""
        logger.info("📦 Packaging models for Android app")
        
        # Create models directory in Android assets
        models_dir = f"{android_assets_dir}/models/multi_coupon"
        os.makedirs(models_dir, exist_ok=True)
        
        # Copy models
        shutil.copy(stage1_tflite, f"{models_dir}/stage1_coupon_detector.tflite")
        shutil.copy(stage2_tflite, f"{models_dir}/stage2_field_detector.tflite")
        
        # Create model manifest
        manifest = {
            "model_version": f"multi_coupon_v{datetime.now().strftime('%Y%m%d_%H%M%S')}",
            "training_date": datetime.now().isoformat(),
            "model_type": "two_stage_yolo",
            "stage1_model": "stage1_coupon_detector.tflite",
            "stage2_model": "stage2_field_detector.tflite",
            "stage1_classes": self.stage1_classes,
            "stage2_classes": self.stage2_classes,
            "input_sizes": {
                "stage1": [640, 640],
                "stage2": [320, 320]
            },
            "thresholds": {
                "confidence": 0.5,
                "iou": 0.4
            },
            "capabilities": {
                "single_coupon": True,
                "multi_coupon": True,
                "partial_coupon": True,
                "scrollable_list": True
            },
            "training_stats": {
                "stage1_classes": len(self.stage1_classes),
                "stage2_classes": len(self.stage2_classes),
                "created_at": datetime.now().isoformat()
            }
        }
        
        with open(f"{models_dir}/manifest.json", 'w') as f:
            json.dump(manifest, f, indent=2)
        
        logger.info(f"✅ Models packaged for Android: {models_dir}")
        return models_dir
    
    def run_complete_pipeline(self, pwa_export_file: str, android_assets_dir: str, 
                            stage1_config: Dict = None, stage2_config: Dict = None) -> Dict:
        """Run the complete training pipeline"""
        logger.info("🎯 Starting Complete Multi-Coupon Training Pipeline")
        
        pipeline_start = datetime.now()
        
        try:
            # Step 1: Convert PWA data
            logger.info("Step 1/4: Converting PWA data...")
            stage1_dir, stage2_dir = self.convert_pwa_data(pwa_export_file)
            
            # Step 2: Train Stage 1 model
            logger.info("Step 2/4: Training Stage 1 model...")
            stage1_model, stage1_tflite = self.train_stage1_model(stage1_dir, stage1_config)
            
            # Step 3: Train Stage 2 model
            logger.info("Step 3/4: Training Stage 2 model...")
            stage2_model, stage2_tflite = self.train_stage2_model(stage2_dir, stage2_config)
            
            # Step 4: Package for Android
            logger.info("Step 4/4: Packaging for Android...")
            android_models_dir = self.package_for_android(
                stage1_tflite, stage2_tflite, android_assets_dir
            )
            
            pipeline_end = datetime.now()
            duration = pipeline_end - pipeline_start
            
            results = {
                'success': True,
                'stage1_model': stage1_model,
                'stage2_model': stage2_model,
                'stage1_tflite': stage1_tflite,
                'stage2_tflite': stage2_tflite,
                'android_models_dir': android_models_dir,
                'training_duration': str(duration),
                'completed_at': pipeline_end.isoformat()
            }
            
            logger.info("🎉 Complete pipeline finished successfully!")
            logger.info(f"📱 Android models ready: {android_models_dir}")
            logger.info(f"⏱️  Total training time: {duration}")
            
            return results
            
        except Exception as e:
            logger.error(f"Pipeline failed: {e}")
            return {
                'success': False,
                'error': str(e),
                'completed_at': datetime.now().isoformat()
            }


def main():
    """Main function for command line usage"""
    parser = argparse.ArgumentParser(description='Enhanced Multi-Coupon Training Pipeline')
    parser.add_argument('--pwa-export', required=True, help='Path to PWA export JSON file')
    parser.add_argument('--output-dir', default='multi_coupon_training', help='Output directory for training data')
    parser.add_argument('--android-assets', default='../app/src/main/assets', help='Android assets directory')
    parser.add_argument('--stage1-epochs', type=int, default=100, help='Stage 1 training epochs')
    parser.add_argument('--stage2-epochs', type=int, default=150, help='Stage 2 training epochs')
    parser.add_argument('--batch-size', type=int, default=16, help='Batch size for training')
    parser.add_argument('--device', default='cpu', help='Training device (cpu/cuda)')
    parser.add_argument('--verbose', action='store_true', help='Enable verbose logging')
    
    args = parser.parse_args()
    
    if args.verbose:
        logging.getLogger().setLevel(logging.DEBUG)
    
    # Check if PWA export file exists
    if not os.path.exists(args.pwa_export):
        logger.error(f"PWA export file not found: {args.pwa_export}")
        return 1
    
    # Initialize trainer
    trainer = MultiCouponTrainer(args.output_dir)
    
    # Training configurations
    stage1_config = {
        'epochs': args.stage1_epochs,
        'batch': args.batch_size,
        'device': args.device
    }
    
    stage2_config = {
        'epochs': args.stage2_epochs,
        'batch': args.batch_size,
        'device': args.device
    }
    
    # Run complete pipeline
    results = trainer.run_complete_pipeline(
        pwa_export_file=args.pwa_export,
        android_assets_dir=args.android_assets,
        stage1_config=stage1_config,
        stage2_config=stage2_config
    )
    
    if results['success']:
        print("\n🎯 Training Complete!")
        print(f"Stage 1 Model: {results['stage1_tflite']}")
        print(f"Stage 2 Model: {results['stage2_tflite']}")
        print(f"Android Ready: {results['android_models_dir']}")
        print(f"Duration: {results['training_duration']}")
        return 0
    else:
        print(f"\n❌ Training Failed: {results['error']}")
        return 1


if __name__ == "__main__":
    exit(main())
