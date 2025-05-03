#!/usr/bin/env python3
import os
import sys
import subprocess
import argparse

def create_directory_structure():
    """Create the directory structure for training"""
    directories = [
        "data/raw",
        "data/annotated",
        "data/processed",
        "data/box-files",
        "models",
        "scripts",
        "utils"
    ]
    
    for directory in directories:
        os.makedirs(directory, exist_ok=True)
        print(f"Created directory: {directory}")

def check_dependencies():
    """Check if required dependencies are installed"""
    try:
        import cv2
        print(f"OpenCV version: {cv2.__version__}")
    except ImportError:
        print("OpenCV not found. Installing...")
        subprocess.check_call([sys.executable, "-m", "pip", "install", "opencv-python"])
    
    try:
        import pytesseract
        print(f"PyTesseract found")
    except ImportError:
        print("PyTesseract not found. Installing...")
        subprocess.check_call([sys.executable, "-m", "pip", "install", "pytesseract"])
    
    try:
        import numpy
        print(f"NumPy version: {numpy.__version__}")
    except ImportError:
        print("NumPy not found. Installing...")
        subprocess.check_call([sys.executable, "-m", "pip", "install", "numpy"])
    
    # Check if Tesseract is installed
    try:
        result = subprocess.run(["tesseract", "--version"], capture_output=True, text=True)
        print(f"Tesseract version: {result.stdout.split()[1]}")
    except:
        print("Tesseract not found. Please install Tesseract OCR on your system.")
        print("Visit: https://github.com/tesseract-ocr/tesseract")
        print("\nInstallation instructions:")
        print("- macOS: brew install tesseract")
        print("- Ubuntu/Debian: sudo apt-get install tesseract-ocr")
        print("- Windows: Download installer from https://github.com/UB-Mannheim/tesseract/wiki")

def main():
    parser = argparse.ArgumentParser(description="Set up the coupon training environment")
    parser.add_argument("--check-only", action="store_true", help="Only check dependencies without creating directories")
    
    args = parser.parse_args()
    
    if not args.check_only:
        create_directory_structure()
    
    check_dependencies()
    
    print("\nEnvironment setup complete!")
    print("Next steps:")
    print("1. Place coupon images in the 'data/raw' directory")
    print("2. Run the preprocessing script to prepare images for training")
    print("3. Run the annotation tool to mark regions of interest")

if __name__ == "__main__":
    main()
