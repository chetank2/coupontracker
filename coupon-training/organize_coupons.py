#!/usr/bin/env python3
"""
Script to organize coupon images for training.
Supports various input methods and automatically structures data for training.
"""

import os
import sys
import shutil
import argparse
from pathlib import Path
from datetime import datetime
import json

def organize_images_from_folder(source_folder, target_base_dir, copy_mode=True):
    """Organize images from a source folder into training structure."""
    source_path = Path(source_folder)
    if not source_path.exists():
        print(f"❌ Source folder not found: {source_folder}")
        return False
    
    # Image extensions
    image_extensions = {'.jpg', '.jpeg', '.png', '.gif', '.bmp', '.tiff', '.webp'}
    
    # Create target directories
    raw_dir = target_base_dir / "raw" / "manual_upload"
    processed_dir = target_base_dir / "processed" / "manual_upload"
    
    raw_dir.mkdir(parents=True, exist_ok=True)
    processed_dir.mkdir(parents=True, exist_ok=True)
    
    # Find all images
    images = []
    for file_path in source_path.rglob("*"):
        if file_path.is_file() and file_path.suffix.lower() in image_extensions:
            images.append(file_path)
    
    if not images:
        print(f"❌ No images found in {source_folder}")
        return False
    
    print(f"📸 Found {len(images)} images")
    print(f"📁 Organizing into: {raw_dir}")
    
    # Copy/move images
    copied_count = 0
    for img_path in images:
        # Create a clean filename
        clean_name = f"coupon_{copied_count + 1:04d}{img_path.suffix.lower()}"
        target_path = raw_dir / clean_name
        
        try:
            if copy_mode:
                shutil.copy2(img_path, target_path)
            else:
                shutil.move(str(img_path), target_path)
            copied_count += 1
            
            if copied_count % 50 == 0:
                print(f"  Processed {copied_count}/{len(images)} images...")
                
        except Exception as e:
            print(f"⚠️  Failed to process {img_path.name}: {e}")
    
    print(f"✅ Successfully organized {copied_count} images!")
    
    # Create a manifest file
    manifest = {
        "source": str(source_folder),
        "target": str(raw_dir),
        "total_images": copied_count,
        "created_at": datetime.now().isoformat(),
        "method": "copy" if copy_mode else "move"
    }
    
    manifest_path = raw_dir / "manifest.json"
    with open(manifest_path, 'w') as f:
        json.dump(manifest, f, indent=2)
    
    print(f"📋 Created manifest: {manifest_path}")
    return True

def create_training_batch(data_dir, batch_name=None):
    """Create a training batch from organized images."""
    if batch_name is None:
        batch_name = f"batch_{datetime.now().strftime('%Y%m%d_%H%M%S')}"
    
    raw_dir = data_dir / "raw"
    batch_dir = data_dir / "train" / batch_name
    batch_dir.mkdir(parents=True, exist_ok=True)
    
    # Find all images in raw directories
    images = []
    for raw_subdir in raw_dir.iterdir():
        if raw_subdir.is_dir():
            for img_file in raw_subdir.rglob("*"):
                if img_file.suffix.lower() in {'.jpg', '.jpeg', '.png', '.gif'}:
                    images.append(img_file)
    
    if not images:
        print("❌ No images found in raw directories")
        return False
    
    print(f"🎯 Creating training batch '{batch_name}' with {len(images)} images")
    
    # Copy images to training batch
    for i, img_path in enumerate(images):
        target_name = f"img_{i+1:04d}{img_path.suffix}"
        target_path = batch_dir / target_name
        shutil.copy2(img_path, target_path)
    
    # Create batch info
    batch_info = {
        "batch_name": batch_name,
        "total_images": len(images),
        "created_at": datetime.now().isoformat(),
        "status": "ready_for_annotation"
    }
    
    info_path = batch_dir / "batch_info.json"
    with open(info_path, 'w') as f:
        json.dump(batch_info, f, indent=2)
    
    print(f"✅ Training batch created: {batch_dir}")
    print(f"📋 Batch info: {info_path}")
    return True

def main():
    parser = argparse.ArgumentParser(description="Organize coupon images for training")
    parser.add_argument("--source", type=str, required=True, help="Source folder with coupon images")
    parser.add_argument("--copy", action="store_true", help="Copy images (default: move)")
    parser.add_argument("--batch-name", type=str, help="Name for training batch")
    parser.add_argument("--create-batch", action="store_true", help="Also create training batch")
    
    args = parser.parse_args()
    
    # Set up paths
    script_dir = Path(__file__).parent
    data_dir = script_dir / "data"
    
    print("🎯 Coupon Image Organizer")
    print(f"📂 Source: {args.source}")
    print(f"📁 Target: {data_dir}")
    print(f"🔄 Mode: {'Copy' if args.copy else 'Move'}")
    print()
    
    # Organize images
    success = organize_images_from_folder(args.source, data_dir, copy_mode=args.copy)
    
    if success and args.create_batch:
        print("\n🚀 Creating training batch...")
        create_training_batch(data_dir, args.batch_name)
    
    if success:
        print("\n🎉 Ready for training!")
        print("Next steps:")
        print("1. Open the web interface: http://localhost:5002")
        print("2. Go to Training tab")
        print("3. Start annotating your images")
        print("4. Train your model!")

if __name__ == "__main__":
    main()
