#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import random
import shutil
import json
from pathlib import Path
from tqdm import tqdm

def create_validation_set(data_dir, validation_ratio=0.2, random_seed=42):
    """Split the dataset into training and validation sets

    Args:
        data_dir (str): Base data directory
        validation_ratio (float): Ratio of data to use for validation
        random_seed (int): Random seed for reproducibility

    Returns:
        tuple: (train_count, val_count) - Number of items in each set
    """
    # Set random seed for reproducibility
    random.seed(random_seed)

    # Define directories
    raw_dir = os.path.join(data_dir, 'raw')
    processed_dir = os.path.join(data_dir, 'processed')
    annotated_dir = os.path.join(data_dir, 'annotated')
    train_dir = os.path.join(data_dir, 'train')
    val_dir = os.path.join(data_dir, 'validation')

    # Create directories if they don't exist
    os.makedirs(train_dir, exist_ok=True)
    os.makedirs(val_dir, exist_ok=True)

    # Get list of annotated files
    annotation_files = [f for f in os.listdir(annotated_dir) if f.endswith('.json')]

    if not annotation_files:
        print("No annotation files found in", annotated_dir)
        return 0, 0

    # Shuffle the files
    random.shuffle(annotation_files)

    # Calculate split
    val_count = int(len(annotation_files) * validation_ratio)
    train_count = len(annotation_files) - val_count

    # Split the files
    train_files = annotation_files[val_count:]
    val_files = annotation_files[:val_count]

    print(f"Splitting dataset: {train_count} training, {val_count} validation")

    # Process training files
    for annotation_file in tqdm(train_files, desc="Processing training set"):
        # Load annotation
        annotation_path = os.path.join(annotated_dir, annotation_file)
        with open(annotation_path, 'r') as f:
            annotation_data = json.load(f)

        # Get image filename - check both 'image' and 'image_path' fields
        image_filename = annotation_data.get('image')
        if not image_filename:
            # Try to extract from image_path
            image_path = annotation_data.get('image_path', '')
            if image_path:
                # Extract just the filename from the path
                image_filename = os.path.basename(image_path)
            else:
                print(f"Warning: No image filename in {annotation_file}")
                continue

        # Copy image if it exists - try both raw and processed directories
        image_path = os.path.join(raw_dir, image_filename)
        if not os.path.exists(image_path):
            # Try processed directory
            image_path = os.path.join(processed_dir, image_filename)

        if os.path.exists(image_path):
            shutil.copy2(image_path, os.path.join(train_dir, image_filename))
        else:
            print(f"Warning: Image {image_filename} not found")
            continue

        # Copy annotation
        shutil.copy2(annotation_path, os.path.join(train_dir, annotation_file))

    # Process validation files
    for annotation_file in tqdm(val_files, desc="Processing validation set"):
        # Load annotation
        annotation_path = os.path.join(annotated_dir, annotation_file)
        with open(annotation_path, 'r') as f:
            annotation_data = json.load(f)

        # Get image filename - check both 'image' and 'image_path' fields
        image_filename = annotation_data.get('image')
        if not image_filename:
            # Try to extract from image_path
            image_path = annotation_data.get('image_path', '')
            if image_path:
                # Extract just the filename from the path
                image_filename = os.path.basename(image_path)
            else:
                print(f"Warning: No image filename in {annotation_file}")
                continue

        # Copy image if it exists - try both raw and processed directories
        image_path = os.path.join(raw_dir, image_filename)
        if not os.path.exists(image_path):
            # Try processed directory
            image_path = os.path.join(processed_dir, image_filename)

        if os.path.exists(image_path):
            shutil.copy2(image_path, os.path.join(val_dir, image_filename))
        else:
            print(f"Warning: Image {image_filename} not found")
            continue

        # Copy annotation
        shutil.copy2(annotation_path, os.path.join(val_dir, annotation_file))

    return train_count, val_count

if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Create validation dataset")
    parser.add_argument("--data-dir", type=str, default="../data", help="Base data directory")
    parser.add_argument("--ratio", type=float, default=0.2, help="Validation set ratio")
    parser.add_argument("--seed", type=int, default=42, help="Random seed")

    args = parser.parse_args()

    train_count, val_count = create_validation_set(args.data_dir, args.ratio, args.seed)

    print(f"Dataset split complete: {train_count} training samples, {val_count} validation samples")
