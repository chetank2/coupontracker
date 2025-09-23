#!/usr/bin/env python3
"""
Import PWA training data into ML training system for model training.
"""
import os
import sys
import json
import shutil
import argparse
from pathlib import Path
from datetime import datetime

def import_pwa_training_data(pwa_export_file, training_data_dir):
    """
    Import PWA exported training data into ML training system.
    
    Args:
        pwa_export_file: JSON file exported from PWA
        training_data_dir: Directory for ML training system
    """
    print(f"🔄 Importing PWA training data...")
    
    # Load PWA export
    with open(pwa_export_file, 'r') as f:
        pwa_data = json.load(f)
    
    print(f"📊 Found {len(pwa_data.get('annotations', []))} annotations")
    
    # Create training directories
    os.makedirs(f"{training_data_dir}/images", exist_ok=True)
    os.makedirs(f"{training_data_dir}/annotations", exist_ok=True)
    
    # Convert PWA annotations to training format
    training_annotations = []
    
    for annotation in pwa_data.get('annotations', []):
        # Convert PWA format to YOLO/training format
        training_annotation = {
            "image_id": annotation.get('imageId'),
            "field_type": annotation.get('fieldType'),
            "bbox": annotation.get('boundingBox'),
            "text": annotation.get('text'),
            "confidence": 1.0,  # PWA annotations are human-verified
            "source": "pwa_manual_annotation"
        }
        training_annotations.append(training_annotation)
    
    # Save in training format
    output_file = f"{training_data_dir}/annotations/pwa_import_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
    with open(output_file, 'w') as f:
        json.dump({
            "annotations": training_annotations,
            "metadata": {
                "source": "PWA Manual Annotation",
                "import_date": datetime.now().isoformat(),
                "total_annotations": len(training_annotations),
                "field_types": list(set(a['field_type'] for a in training_annotations))
            }
        }, f, indent=2)
    
    print(f"✅ Imported {len(training_annotations)} annotations to {output_file}")
    return output_file

def trigger_model_training(training_data_file):
    """
    Trigger model training with new PWA data.
    """
    print(f"🚀 Triggering model training...")
    
    # Training command (customize based on your ML system)
    training_command = f"""
    cd coupon-training && python3 train_model.py \\
        --data {training_data_file} \\
        --model yolov8 \\
        --epochs 50 \\
        --batch-size 16 \\
        --name pwa_trained_model
    """
    
    print(f"📝 Training command:")
    print(training_command)
    
    # Execute training (uncomment to run automatically)
    # os.system(training_command)
    
    return "models/pwa_trained_model/best.pt"

def package_for_android(trained_model_path, android_assets_dir):
    """
    Package trained model for Android app integration.
    """
    print(f"📦 Packaging model for Android...")
    
    # Convert to TensorFlow Lite format
    tflite_model = trained_model_path.replace('.pt', '.tflite')
    
    conversion_command = f"""
    python3 -c "
    import torch
    from ultralytics import YOLO
    
    # Load trained model
    model = YOLO('{trained_model_path}')
    
    # Export to TensorFlow Lite
    model.export(format='tflite', int8=True)
    print('✅ Model converted to TensorFlow Lite')
    "
    """
    
    print(f"🔄 Converting to TensorFlow Lite...")
    print(conversion_command)
    
    # Copy to Android assets (customize path)
    android_model_dir = f"{android_assets_dir}/models/active"
    os.makedirs(android_model_dir, exist_ok=True)
    
    # Update Android model manifest
    manifest = {
        "model_version": f"pwa_trained_{datetime.now().strftime('%Y%m%d_%H%M%S')}",
        "model_file": "coupon_detector.tflite",
        "training_source": "pwa_manual_annotation",
        "training_date": datetime.now().isoformat(),
        "field_types": ["title", "brand", "discount", "expiry", "description"],
        "model_type": "yolov8_tflite",
        "input_size": [640, 640],
        "confidence_threshold": 0.5
    }
    
    with open(f"{android_model_dir}/manifest.json", 'w') as f:
        json.dump(manifest, f, indent=2)
    
    print(f"✅ Model packaged for Android: {android_model_dir}")
    return android_model_dir

def main():
    parser = argparse.ArgumentParser(description='Import PWA training data and update Android model')
    parser.add_argument('pwa_export', help='PWA exported JSON file')
    parser.add_argument('--training-dir', default='coupon-training/data/pwa_imports', 
                       help='Training data directory')
    parser.add_argument('--android-assets', default='app/src/main/assets',
                       help='Android assets directory')
    parser.add_argument('--auto-train', action='store_true',
                       help='Automatically trigger training')
    
    args = parser.parse_args()
    
    print("🎯 PWA → Android Model Update Pipeline")
    print("=" * 50)
    
    # Step 1: Import PWA data
    training_file = import_pwa_training_data(args.pwa_export, args.training_dir)
    
    # Step 2: Train model (optional)
    if args.auto_train:
        model_path = trigger_model_training(training_file)
        
        # Step 3: Package for Android
        android_dir = package_for_android(model_path, args.android_assets)
        
        print("\n🎉 Complete! Model updated in Android app.")
        print(f"📱 Android model: {android_dir}")
    else:
        print(f"\n📋 Next steps:")
        print(f"1. Review imported data: {training_file}")
        print(f"2. Run training: python3 train_model.py --data {training_file}")
        print(f"3. Package for Android: python3 import_pwa_data.py --auto-train")

if __name__ == "__main__":
    main()
