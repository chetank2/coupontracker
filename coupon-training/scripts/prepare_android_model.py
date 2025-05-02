#!/usr/bin/env python3
import os
import shutil
import argparse
from pathlib import Path

def copy_model_to_app(pattern_file, app_assets_dir):
    """Copy the pattern file to the app's assets directory

    Args:
        pattern_file (str): Path to the pattern file
        app_assets_dir (str): Path to the app's assets directory

    Returns:
        bool: True if successful, False otherwise
    """
    try:
        # Ensure the pattern file exists
        if not os.path.exists(pattern_file):
            print(f"Error: Pattern file not found: {pattern_file}")
            return False

        # Ensure the app assets directory exists
        os.makedirs(app_assets_dir, exist_ok=True)

        # Copy the pattern file
        app_pattern_file = os.path.join(app_assets_dir, 'coupon_patterns.txt')
        shutil.copy2(pattern_file, app_pattern_file)

        print(f"Pattern file copied to app: {app_pattern_file}")
        return True
    except Exception as e:
        print(f"Error copying model to app: {e}")
        return False

def prepare_model_for_android(model_path, output_dir, lang_code="coupon"):
    """Prepare the trained model for use in an Android app"""
    # Create output directory
    os.makedirs(output_dir, exist_ok=True)

    # Check if model file exists
    if not os.path.exists(model_path):
        print(f"Error: Model file not found: {model_path}")
        return False

    # Copy the model file to the output directory
    output_path = os.path.join(output_dir, f"{lang_code}.traineddata")
    shutil.copy(model_path, output_path)

    print(f"Model prepared for Android: {output_path}")

    # Create a README file with instructions
    readme_path = os.path.join(output_dir, "README.txt")
    with open(readme_path, 'w') as f:
        f.write(f"""
Tesseract Custom Model for Coupon Recognition

Model: {lang_code}.traineddata

Instructions for integrating with your Android app:

1. Copy the {lang_code}.traineddata file to your app's assets/tessdata directory:
   - Create the directory if it doesn't exist: app/src/main/assets/tessdata/
   - Copy {lang_code}.traineddata to this directory

2. Update your app code to use this custom model:
   - When initializing Tesseract, use "{lang_code}" as the language code
   - Example:
     ```kotlin
     tesseractOCRHelper.initialize(
         language = "{lang_code}",
         useCustomModel = true
     )
     ```

3. Test the integration:
   - Make sure the app can load the custom model
   - Test with various coupon images to verify improved recognition
""")

    print(f"Instructions saved to: {readme_path}")
    return True

def main():
    parser = argparse.ArgumentParser(description="Prepare models for Android")
    subparsers = parser.add_subparsers(dest="command", help="Command to run")

    # Tesseract model preparation
    tesseract_parser = subparsers.add_parser("tesseract", help="Prepare a trained Tesseract model for Android")
    tesseract_parser.add_argument("--model-path", required=True, help="Path to the trained model (.traineddata file)")
    tesseract_parser.add_argument("--output-dir", default="../android_model", help="Directory to save the prepared model")
    tesseract_parser.add_argument("--lang-code", default="coupon", help="Language code for the model")

    # Pattern model preparation
    pattern_parser = subparsers.add_parser("pattern", help="Copy the pattern file to the app's assets directory")
    pattern_parser.add_argument("--pattern-file", default="../models/simplified/coupon_patterns.txt", help="Path to the pattern file")
    pattern_parser.add_argument("--app-assets-dir", default="../../app/src/main/assets/coupon_model", help="Path to the app's assets directory")

    args = parser.parse_args()

    if args.command == "tesseract":
        # Prepare the Tesseract model
        success = prepare_model_for_android(args.model_path, args.output_dir, args.lang_code)

        if success:
            print("\n=== Tesseract Model Preparation Complete ===")
            print("Next steps:")
            print("1. Copy the model file to your Android app's assets/tessdata directory")
            print("2. Update your app code to use the custom model")
            print("3. Test the integration with various coupon images")
        else:
            print("\nTesseract model preparation failed. Please check the error messages above.")

    elif args.command == "pattern":
        # Copy the pattern file to the app
        success = copy_model_to_app(args.pattern_file, args.app_assets_dir)

        if success:
            print("\n=== Pattern Model Update Complete ===")
            print("The pattern file has been copied to the app's assets directory.")
            print("You can now rebuild the app to use the updated model.")
        else:
            print("\nPattern model update failed. Please check the error messages above.")

    else:
        parser.print_help()

if __name__ == "__main__":
    main()
