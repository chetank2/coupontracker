#!/usr/bin/env python3
import os
import subprocess
import argparse
import shutil
from pathlib import Path

def check_tesseract_training_tools():
    """Check if Tesseract training tools are available"""
    try:
        result = subprocess.run(["tesseract", "--version"], capture_output=True, text=True)
        print(f"Tesseract version: {result.stdout.split()[1]}")

        # Check for training tools
        training_tools = ["text2image", "combine_tessdata", "lstmtraining"]
        missing_tools = []

        for tool in training_tools:
            try:
                subprocess.run([tool, "--version"], capture_output=True)
            except:
                missing_tools.append(tool)

        if missing_tools:
            print(f"Warning: The following Tesseract training tools are missing: {', '.join(missing_tools)}")
            print("You may need to install the Tesseract training tools package.")
            return False

        return True
    except:
        print("Error: Tesseract is not installed or not in PATH.")
        return False

def prepare_training_data(box_files_dir, output_dir, lang_code="coupon"):
    """Prepare the training data for Tesseract"""
    # Create output directory
    os.makedirs(output_dir, exist_ok=True)

    # Copy box files and TIFF images to output directory
    box_files = list(Path(box_files_dir).glob("*.box"))
    for box_file in box_files:
        tiff_file = box_file.with_suffix(".tiff")
        if tiff_file.exists():
            shutil.copy(box_file, output_dir)
            shutil.copy(tiff_file, output_dir)

    # Create training list file
    training_list_path = os.path.join(output_dir, f"{lang_code}.training_files.txt")
    with open(training_list_path, 'w') as f:
        for box_file in box_files:
            f.write(f"{box_file.stem}\n")

    return training_list_path

def run_training_command(command, description):
    """Run a training command and log the output"""
    print(f"\n=== {description} ===")
    print(f"Running: {' '.join(command)}")

    try:
        result = subprocess.run(command, capture_output=True, text=True)
        if result.returncode == 0:
            print(f"Success: {description}")
            return True
        else:
            print(f"Error: {description} failed")
            print(f"Error output: {result.stderr}")
            return False
    except Exception as e:
        print(f"Exception: {e}")
        return False

def train_tesseract_model(training_data_dir, output_dir, lang_code="coupon"):
    """Train a Tesseract model using the prepared training data"""
    # Create output directory
    os.makedirs(output_dir, exist_ok=True)

    # Set up paths
    tessdata_path = os.path.join(output_dir, "tessdata")
    os.makedirs(tessdata_path, exist_ok=True)

    # Copy the English traineddata as a starting point
    # Try multiple possible locations for eng.traineddata
    possible_paths = [
        "/usr/share/tesseract-ocr/4.00/tessdata/eng.traineddata",
        "/opt/homebrew/share/tessdata/eng.traineddata",
        "/usr/local/share/tessdata/eng.traineddata",
        "/usr/share/tessdata/eng.traineddata"
    ]

    eng_traineddata = None
    for path in possible_paths:
        if os.path.exists(path):
            eng_traineddata = path
            break

    if eng_traineddata:
        print(f"Found English traineddata at {eng_traineddata}")
        shutil.copy(eng_traineddata, tessdata_path)
    else:
        print("Warning: Could not find English traineddata")
        print("Searched in the following locations:")
        for path in possible_paths:
            print(f"  - {path}")
        print("You may need to specify the correct path to eng.traineddata")

    # Step 1: Generate training data
    print("\n=== Generating training data ===")
    training_files = []
    for tiff_file in Path(training_data_dir).glob("*.tiff"):
        base_name = tiff_file.stem
        box_file = tiff_file.with_suffix(".box")

        if box_file.exists():
            training_files.append(base_name)
            print(f"Found training pair: {tiff_file.name} and {box_file.name}")

    if not training_files:
        print("Error: No training data found")
        return False

    # Step 2: Run Tesseract training commands
    current_dir = os.getcwd()
    os.chdir(training_data_dir)

    try:
        # Generate .tr files
        for base_name in training_files:
            command = [
                "tesseract",
                f"{base_name}.tiff",
                base_name,
                "box.train"
            ]
            if not run_training_command(command, f"Generate .tr file for {base_name}"):
                return False

        # Extract unicharset
        command = [
            "unicharset_extractor",
            *[f"{base_name}.box" for base_name in training_files]
        ]
        if not run_training_command(command, "Extract unicharset"):
            return False

        # Create font properties file
        font_properties_path = "font_properties"
        with open(font_properties_path, 'w') as f:
            for base_name in training_files:
                f.write(f"{base_name} 0 0 0 0 0\n")

        # Create training data
        command = [
            "mftraining",
            "-F", "font_properties",
            "-U", "unicharset",
            *[f"{base_name}.tr" for base_name in training_files]
        ]
        if not run_training_command(command, "mftraining"):
            return False

        # Generate normproto
        command = [
            "cntraining",
            *[f"{base_name}.tr" for base_name in training_files]
        ]
        if not run_training_command(command, "cntraining"):
            return False

        # Rename files
        for file_prefix in ["inttemp", "normproto", "pffmtable", "shapetable"]:
            os.rename(file_prefix, f"{lang_code}.{file_prefix}")

        # Combine into traineddata
        command = [
            "combine_tessdata",
            f"{lang_code}."
        ]
        if not run_training_command(command, "combine_tessdata"):
            return False

        # Copy the final traineddata file to the output directory
        traineddata_file = f"{lang_code}.traineddata"
        if os.path.exists(traineddata_file):
            shutil.copy(traineddata_file, os.path.join(output_dir, traineddata_file))
            print(f"\nTraining complete! Traineddata file created: {os.path.join(output_dir, traineddata_file)}")
            return True
        else:
            print(f"Error: Failed to create {traineddata_file}")
            return False

    finally:
        os.chdir(current_dir)

def main():
    parser = argparse.ArgumentParser(description="Train a custom Tesseract model for coupon recognition")
    parser.add_argument("--box-files-dir", default="../data/box-files", help="Directory containing box files")
    parser.add_argument("--output-dir", default="../models", help="Directory to save the trained model")
    parser.add_argument("--lang-code", default="coupon", help="Language code for the model")

    args = parser.parse_args()

    # Check if Tesseract training tools are available
    if not check_tesseract_training_tools():
        print("Error: Tesseract training tools are not available")
        return

    # Prepare training data
    print("\n=== Preparing training data ===")
    training_data_dir = os.path.join(args.output_dir, "training_data")
    training_list_path = prepare_training_data(args.box_files_dir, training_data_dir, args.lang_code)
    print(f"Training data prepared in: {training_data_dir}")

    # Train the model
    print("\n=== Training Tesseract model ===")
    success = train_tesseract_model(training_data_dir, args.output_dir, args.lang_code)

    if success:
        print("\n=== Training Summary ===")
        print(f"Model language code: {args.lang_code}")
        print(f"Trained model saved to: {os.path.join(args.output_dir, f'{args.lang_code}.traineddata')}")
        print("\nTo use this model in your Android app:")
        print(f"1. Copy the {args.lang_code}.traineddata file to your app's assets/tessdata directory")
        print(f"2. Update your app to use the '{args.lang_code}' language code when initializing Tesseract")
    else:
        print("\nTraining failed. Please check the error messages above.")

if __name__ == "__main__":
    main()
