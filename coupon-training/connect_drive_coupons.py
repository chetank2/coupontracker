#!/usr/bin/env python3
"""
Script to connect Google Drive coupon folder to the training system.
Supports multiple methods: symlink, copy, or sync.
"""

import os
import sys
import shutil
import argparse
from pathlib import Path

def find_google_drive_path():
    """Find the Google Drive path on the system."""
    possible_paths = [
        Path.home() / "Google Drive",
        Path.home() / "GoogleDrive", 
        Path("/Volumes/GoogleDrive"),
        Path.home() / "Library/CloudStorage/GoogleDrive-*/My Drive",
    ]
    
    for path in possible_paths:
        if path.exists():
            return path
        # Handle wildcard for new Google Drive paths
        if "*" in str(path):
            import glob
            matches = glob.glob(str(path))
            if matches:
                return Path(matches[0])
    
    return None

def create_symlink(source_dir, target_dir):
    """Create a symbolic link from source to target."""
    try:
        if target_dir.exists():
            print(f"Target directory {target_dir} already exists. Removing...")
            if target_dir.is_symlink():
                target_dir.unlink()
            else:
                shutil.rmtree(target_dir)
        
        target_dir.symlink_to(source_dir)
        print(f"✅ Created symlink: {target_dir} -> {source_dir}")
        return True
    except Exception as e:
        print(f"❌ Failed to create symlink: {e}")
        return False

def copy_images(source_dir, target_dir, file_extensions=None):
    """Copy images from source to target directory."""
    if file_extensions is None:
        file_extensions = {'.jpg', '.jpeg', '.png', '.gif', '.bmp', '.tiff', '.webp'}
    
    target_dir.mkdir(parents=True, exist_ok=True)
    
    copied_count = 0
    for file_path in source_dir.rglob("*"):
        if file_path.is_file() and file_path.suffix.lower() in file_extensions:
            # Create relative path structure
            relative_path = file_path.relative_to(source_dir)
            target_file = target_dir / relative_path
            
            # Create parent directories
            target_file.parent.mkdir(parents=True, exist_ok=True)
            
            # Copy file
            shutil.copy2(file_path, target_file)
            copied_count += 1
            
            if copied_count % 10 == 0:
                print(f"Copied {copied_count} images...")
    
    print(f"✅ Copied {copied_count} images to {target_dir}")
    return copied_count

def main():
    parser = argparse.ArgumentParser(description="Connect Google Drive coupon folder to training system")
    parser.add_argument("--drive-folder", type=str, help="Path to your Google Drive coupon folder")
    parser.add_argument("--method", choices=["symlink", "copy", "list"], default="list", 
                       help="Method to connect the folder (default: list)")
    parser.add_argument("--target", choices=["raw", "processed", "custom"], default="raw",
                       help="Target directory in training system")
    parser.add_argument("--custom-target", type=str, help="Custom target directory path")
    
    args = parser.parse_args()
    
    # Set up paths
    script_dir = Path(__file__).parent
    data_dir = script_dir / "data"
    
    if args.target == "custom" and args.custom_target:
        target_dir = Path(args.custom_target)
    else:
        target_dir = data_dir / args.target / "drive_coupons"
    
    print(f"🔍 Coupon Training System - Drive Connection Tool")
    print(f"📁 Data directory: {data_dir}")
    print(f"🎯 Target directory: {target_dir}")
    print()
    
    # Find Google Drive path
    if args.drive_folder:
        drive_coupon_folder = Path(args.drive_folder)
    else:
        print("🔍 Searching for Google Drive...")
        drive_path = find_google_drive_path()
        if not drive_path:
            print("❌ Google Drive not found automatically.")
            print("Please specify the path using --drive-folder argument.")
            print("\nExample:")
            print(f'  python3 {sys.argv[0]} --drive-folder "/Users/yourname/Google Drive/Coupons"')
            return
        
        print(f"✅ Found Google Drive at: {drive_path}")
        
        # Look for coupon folders
        coupon_folders = []
        for folder in drive_path.iterdir():
            if folder.is_dir() and any(word in folder.name.lower() for word in ['coupon', 'deal', 'discount', 'offer']):
                coupon_folders.append(folder)
        
        if coupon_folders:
            print("\n📂 Found potential coupon folders:")
            for i, folder in enumerate(coupon_folders, 1):
                image_count = len([f for f in folder.rglob("*") if f.suffix.lower() in {'.jpg', '.jpeg', '.png', '.gif'}])
                print(f"  {i}. {folder.name} ({image_count} images)")
            
            if len(coupon_folders) == 1:
                drive_coupon_folder = coupon_folders[0]
            else:
                print("\nPlease specify which folder to use with --drive-folder")
                return
        else:
            print("❌ No coupon folders found automatically.")
            print("Please specify the exact path using --drive-folder argument.")
            return
    
    if not drive_coupon_folder.exists():
        print(f"❌ Drive coupon folder not found: {drive_coupon_folder}")
        return
    
    # Count images in source folder
    image_extensions = {'.jpg', '.jpeg', '.png', '.gif', '.bmp', '.tiff', '.webp'}
    image_count = len([f for f in drive_coupon_folder.rglob("*") if f.suffix.lower() in image_extensions])
    
    print(f"📂 Source folder: {drive_coupon_folder}")
    print(f"🖼️  Found {image_count} images")
    print()
    
    if args.method == "list":
        print("📋 Preview of images found:")
        count = 0
        for file_path in drive_coupon_folder.rglob("*"):
            if file_path.is_file() and file_path.suffix.lower() in image_extensions:
                print(f"  - {file_path.name}")
                count += 1
                if count >= 10:
                    print(f"  ... and {image_count - count} more images")
                    break
        
        print("\n🚀 Ready to connect! Use one of these commands:")
        print(f'  # Create symlink (recommended - no duplication):')
        print(f'  python3 {sys.argv[0]} --drive-folder "{drive_coupon_folder}" --method symlink')
        print(f'  ')
        print(f'  # Copy images (safe - creates independent copy):')
        print(f'  python3 {sys.argv[0]} --drive-folder "{drive_coupon_folder}" --method copy')
        
    elif args.method == "symlink":
        print("🔗 Creating symbolic link...")
        success = create_symlink(drive_coupon_folder, target_dir)
        if success:
            print(f"\n✅ Successfully connected!")
            print(f"📁 Your Drive coupons are now available at: {target_dir}")
            print(f"🎯 You can now use the web interface to train models on your {image_count} images")
        
    elif args.method == "copy":
        print("📋 Copying images...")
        copied = copy_images(drive_coupon_folder, target_dir)
        if copied > 0:
            print(f"\n✅ Successfully copied {copied} images!")
            print(f"📁 Images are now available at: {target_dir}")
            print(f"🎯 You can now use the web interface to train models")

if __name__ == "__main__":
    main()
