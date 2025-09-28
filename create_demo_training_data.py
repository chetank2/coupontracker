#!/usr/bin/env python3
"""
Create proper demo training data for multi-coupon detection
Generates synthetic coupon images with proper annotations
"""

import os
import json
import numpy as np
from PIL import Image, ImageDraw, ImageFont
import random

def create_synthetic_coupon_image(width=640, height=400, coupon_type="single"):
    """Create a synthetic coupon image"""
    
    # Create base image
    img = Image.new('RGB', (width, height), color='white')
    draw = ImageDraw.Draw(img)
    
    if coupon_type == "single":
        # Single coupon - full image
        # Draw coupon border
        border_color = (random.randint(100, 200), random.randint(100, 200), random.randint(100, 200))
        draw.rectangle([20, 20, width-20, height-20], outline=border_color, width=3)
        
        # Add some text regions
        draw.rectangle([50, 50, 300, 100], fill=(240, 240, 240), outline=(100, 100, 100))  # Store name area
        draw.rectangle([50, 150, 400, 200], fill=(255, 255, 200), outline=(100, 100, 100))  # Code area
        draw.rectangle([50, 250, 350, 300], fill=(200, 255, 200), outline=(100, 100, 100))  # Benefit area
        draw.rectangle([50, 320, 250, 360], fill=(255, 200, 200), outline=(100, 100, 100))  # Expiry area
        
        # Add text
        try:
            font = ImageFont.load_default()
            draw.text((60, 65), "SAMPLE STORE", fill=(0, 0, 0), font=font)
            draw.text((60, 165), "CODE: DEMO123", fill=(0, 0, 0), font=font)
            draw.text((60, 265), "50% OFF", fill=(0, 0, 0), font=font)
            draw.text((60, 335), "Valid till 2025-12-31", fill=(0, 0, 0), font=font)
        except:
            pass
            
        return img, [(20, 20, width-20, height-20)]  # Return image and coupon bounds
        
    elif coupon_type == "multi":
        # Multiple coupons in grid
        coupon_bounds = []
        
        # Create 2x2 grid of coupons
        coupon_width = (width - 60) // 2
        coupon_height = (height - 60) // 2
        
        for row in range(2):
            for col in range(2):
                x1 = 20 + col * (coupon_width + 20)
                y1 = 20 + row * (coupon_height + 20)
                x2 = x1 + coupon_width
                y2 = y1 + coupon_height
                
                # Draw coupon
                color = (random.randint(150, 255), random.randint(150, 255), random.randint(150, 255))
                draw.rectangle([x1, y1, x2, y2], fill=color, outline=(0, 0, 0), width=2)
                
                # Add some content
                draw.rectangle([x1+10, y1+10, x1+100, y1+40], fill=(255, 255, 255), outline=(100, 100, 100))
                draw.rectangle([x1+10, y1+50, x1+120, y1+80], fill=(255, 255, 200), outline=(100, 100, 100))
                
                try:
                    font = ImageFont.load_default()
                    draw.text((x1+15, y1+20), f"Store {row*2+col+1}", fill=(0, 0, 0), font=font)
                    draw.text((x1+15, y1+60), f"CODE{row*2+col+1}", fill=(0, 0, 0), font=font)
                except:
                    pass
                
                coupon_bounds.append((x1, y1, x2, y2))
        
        return img, coupon_bounds

def create_yolo_annotation(image_width, image_height, boxes, class_id=0):
    """Create YOLO format annotation"""
    annotations = []
    
    for box in boxes:
        x1, y1, x2, y2 = box
        
        # Convert to YOLO format (normalized center x, center y, width, height)
        center_x = (x1 + x2) / 2 / image_width
        center_y = (y1 + y2) / 2 / image_height
        width = (x2 - x1) / image_width
        height = (y2 - y1) / image_height
        
        annotations.append(f"{class_id} {center_x:.6f} {center_y:.6f} {width:.6f} {height:.6f}")
    
    return "\n".join(annotations)

def create_field_annotations(coupon_crop_width, coupon_crop_height):
    """Create field annotations for Stage 2"""
    # Define relative field positions within a coupon
    fields = [
        (0.1, 0.1, 0.8, 0.25, 0),  # code_region
        (0.1, 0.3, 0.7, 0.5, 1),   # benefit_region  
        (0.1, 0.6, 0.6, 0.8, 2),   # expiry_region
    ]
    
    annotations = []
    for x1_rel, y1_rel, x2_rel, y2_rel, class_id in fields:
        center_x = (x1_rel + x2_rel) / 2
        center_y = (y1_rel + y2_rel) / 2
        width = x2_rel - x1_rel
        height = y2_rel - y1_rel
        
        annotations.append(f"{class_id} {center_x:.6f} {center_y:.6f} {width:.6f} {height:.6f}")
    
    return "\n".join(annotations)

def main():
    """Generate demo training data"""
    
    # Create directories
    stage1_dir = "multi_coupon_training/stage1_coupon_detection"
    stage2_dir = "multi_coupon_training/stage2_field_detection"
    
    os.makedirs(f"{stage1_dir}/images/train", exist_ok=True)
    os.makedirs(f"{stage1_dir}/images/val", exist_ok=True)
    os.makedirs(f"{stage1_dir}/labels/train", exist_ok=True)
    os.makedirs(f"{stage1_dir}/labels/val", exist_ok=True)
    
    os.makedirs(f"{stage2_dir}/images/train", exist_ok=True)
    os.makedirs(f"{stage2_dir}/images/val", exist_ok=True)
    os.makedirs(f"{stage2_dir}/labels/train", exist_ok=True)
    os.makedirs(f"{stage2_dir}/labels/val", exist_ok=True)
    
    print("🎯 Creating Stage 1 training data (Coupon Detection)...")
    
    # Generate Stage 1 training images
    for i in range(10):  # 10 training images
        if i < 5:
            img, boxes = create_synthetic_coupon_image(640, 400, "single")
            filename = f"single_coupon_{i:03d}"
        else:
            img, boxes = create_synthetic_coupon_image(640, 400, "multi") 
            filename = f"multi_coupon_{i:03d}"
        
        # Save image
        img_path = f"{stage1_dir}/images/train/{filename}.jpg"
        img.save(img_path, "JPEG", quality=95)
        
        # Save annotation
        annotation = create_yolo_annotation(640, 400, boxes, class_id=0)  # All as "coupon_complete"
        with open(f"{stage1_dir}/labels/train/{filename}.txt", "w") as f:
            f.write(annotation)
    
    # Generate Stage 1 validation images
    for i in range(3):  # 3 validation images
        img, boxes = create_synthetic_coupon_image(640, 400, "single" if i < 2 else "multi")
        filename = f"val_coupon_{i:03d}"
        
        # Save image
        img_path = f"{stage1_dir}/images/val/{filename}.jpg"
        img.save(img_path, "JPEG", quality=95)
        
        # Save annotation
        annotation = create_yolo_annotation(640, 400, boxes, class_id=0)
        with open(f"{stage1_dir}/labels/val/{filename}.txt", "w") as f:
            f.write(annotation)
    
    print("✅ Stage 1 data created: 10 train + 3 val images")
    
    print("🎯 Creating Stage 2 training data (Field Detection)...")
    
    # Generate Stage 2 training images (cropped coupons with field annotations)
    for i in range(8):  # 8 training images
        img, _ = create_synthetic_coupon_image(320, 200, "single")  # Smaller cropped coupon
        filename = f"coupon_crop_{i:03d}"
        
        # Save image
        img_path = f"{stage2_dir}/images/train/{filename}.jpg"
        img.save(img_path, "JPEG", quality=95)
        
        # Save field annotations
        annotation = create_field_annotations(320, 200)
        with open(f"{stage2_dir}/labels/train/{filename}.txt", "w") as f:
            f.write(annotation)
    
    # Generate Stage 2 validation images
    for i in range(2):  # 2 validation images
        img, _ = create_synthetic_coupon_image(320, 200, "single")
        filename = f"val_crop_{i:03d}"
        
        # Save image
        img_path = f"{stage2_dir}/images/val/{filename}.jpg"
        img.save(img_path, "JPEG", quality=95)
        
        # Save field annotations
        annotation = create_field_annotations(320, 200)
        with open(f"{stage2_dir}/labels/val/{filename}.txt", "w") as f:
            f.write(annotation)
    
    print("✅ Stage 2 data created: 8 train + 2 val images")
    
    # Create dataset YAML files
    stage1_yaml = """
# Stage 1: Coupon Detection Dataset
path: .
train: images/train
val: images/val

# Classes
names:
  0: coupon_complete
  1: coupon_partial_top
  2: coupon_partial_bottom

nc: 3
"""
    
    stage2_yaml = """
# Stage 2: Field Detection Dataset  
path: .
train: images/train
val: images/val

# Classes
names:
  0: code_region
  1: benefit_region
  2: expiry_region
  3: app_region
  4: terms_region

nc: 5
"""
    
    with open(f"{stage1_dir}/dataset.yaml", "w") as f:
        f.write(stage1_yaml.strip())
    
    with open(f"{stage2_dir}/dataset.yaml", "w") as f:
        f.write(stage2_yaml.strip())
    
    print("✅ Dataset YAML files created")
    print("🎉 Demo training data generation complete!")
    print(f"📁 Stage 1: {stage1_dir}")
    print(f"📁 Stage 2: {stage2_dir}")

if __name__ == "__main__":
    main()
