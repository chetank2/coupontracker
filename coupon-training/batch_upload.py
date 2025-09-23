#!/usr/bin/env python3
"""
Batch upload coupon images to the web training interface.
"""

import os
import sys
import requests
from pathlib import Path
import argparse

def batch_upload_images(folder_path, server_url="http://localhost:5002"):
    """Upload all images from a folder to the training interface."""
    folder = Path(folder_path)
    if not folder.exists():
        print(f"❌ Folder not found: {folder_path}")
        return False
    
    # Find all image files
    image_extensions = {'.jpg', '.jpeg', '.png', '.gif', '.bmp', '.tiff', '.webp'}
    image_files = []
    
    for file_path in folder.rglob("*"):
        if file_path.is_file() and file_path.suffix.lower() in image_extensions:
            image_files.append(file_path)
    
    if not image_files:
        print(f"❌ No image files found in {folder_path}")
        return False
    
    print(f"📸 Found {len(image_files)} images to upload")
    
    # Upload in batches of 10 to avoid overwhelming the server
    batch_size = 10
    uploaded_count = 0
    failed_count = 0
    
    for i in range(0, len(image_files), batch_size):
        batch = image_files[i:i + batch_size]
        print(f"\n📤 Uploading batch {i//batch_size + 1} ({len(batch)} images)...")
        
        # Prepare files for upload
        files = []
        for img_path in batch:
            try:
                files.append(('files[]', (img_path.name, open(img_path, 'rb'), 'image/jpeg')))
            except Exception as e:
                print(f"⚠️  Failed to open {img_path.name}: {e}")
                failed_count += 1
                continue
        
        if not files:
            continue
        
        # Upload batch
        try:
            response = requests.post(f"{server_url}/api/upload/training", files=files, timeout=60)
            
            # Close file handles
            for _, file_tuple in files:
                if len(file_tuple) > 1:
                    file_tuple[1].close()
            
            if response.status_code == 200:
                result = response.json()
                batch_uploaded = len(result.get('files', []))
                uploaded_count += batch_uploaded
                print(f"✅ Uploaded {batch_uploaded} images successfully")
            else:
                print(f"❌ Upload failed: HTTP {response.status_code}")
                print(f"Response: {response.text}")
                failed_count += len(batch)
                
        except Exception as e:
            print(f"❌ Upload error: {e}")
            # Close any remaining file handles
            for _, file_tuple in files:
                if len(file_tuple) > 1:
                    try:
                        file_tuple[1].close()
                    except:
                        pass
            failed_count += len(batch)
    
    print(f"\n📊 Upload Summary:")
    print(f"✅ Successfully uploaded: {uploaded_count} images")
    if failed_count > 0:
        print(f"❌ Failed uploads: {failed_count} images")
    
    print(f"\n🌐 Open the web interface to start annotating: {server_url}/training")
    
    return uploaded_count > 0

def main():
    parser = argparse.ArgumentParser(description="Batch upload images to coupon training web interface")
    parser.add_argument("folder", type=str, help="Folder containing coupon images")
    parser.add_argument("--server", type=str, default="http://localhost:5002", 
                       help="Web interface URL (default: http://localhost:5002)")
    
    args = parser.parse_args()
    
    print("🚀 Coupon Batch Upload Tool")
    print(f"📂 Source folder: {args.folder}")
    print(f"🌐 Target server: {args.server}")
    print()
    
    # Check if server is running
    try:
        response = requests.get(f"{args.server}/", timeout=5)
        if response.status_code != 200:
            print(f"❌ Web interface not responding at {args.server}")
            print("Please make sure the web app is running with: python3 run_web_ui.py")
            return
    except Exception as e:
        print(f"❌ Cannot connect to web interface at {args.server}")
        print(f"Error: {e}")
        print("Please make sure the web app is running with: python3 run_web_ui.py")
        return
    
    # Upload images
    success = batch_upload_images(args.folder, args.server)
    
    if success:
        print("\n🎉 Upload completed!")
        print("Next steps:")
        print(f"1. Open {args.server}/training")
        print("2. Start annotating your uploaded images")
        print("3. Train your model!")
    else:
        print("\n❌ Upload failed. Please check your folder path and try again.")

if __name__ == "__main__":
    main()
