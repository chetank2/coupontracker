#!/usr/bin/env python3
"""
Coupon Trainer CLI - Command-line interface for the coupon training pipeline.

This module provides a command-line interface for the entire coupon training pipeline,
from scraping to training.
"""

import os
import sys
import json
import logging
import argparse
import datetime
import time
from tqdm import tqdm

# Import the pipeline components
from coupon_scraper import CouponScraper
from image_processor import CouponImageProcessor
from coupon_annotator import CouponAnnotator
from train_model import CouponModelTrainer
from update_app import update_app_model

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("coupon_trainer.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("coupon_trainer_cli")

class CouponTrainer:
    """Main class for the coupon training pipeline."""
    
    def __init__(self, base_dir="data", models_dir="models"):
        """Initialize the trainer.
        
        Args:
            base_dir (str): Base directory for data
            models_dir (str): Directory for models
        """
        self.base_dir = base_dir
        self.models_dir = models_dir
        
        # Create directories
        os.makedirs(base_dir, exist_ok=True)
        os.makedirs(models_dir, exist_ok=True)
        
        # Define subdirectories
        self.scraped_dir = os.path.join(base_dir, "scraped_coupons")
        self.processed_dir = os.path.join(base_dir, "processed_coupons")
        self.annotated_dir = os.path.join(base_dir, "annotated_coupons")
        
        # Create subdirectories
        os.makedirs(self.scraped_dir, exist_ok=True)
        os.makedirs(self.processed_dir, exist_ok=True)
        os.makedirs(self.annotated_dir, exist_ok=True)
        
        logger.info(f"Initialized trainer with base directory: {base_dir}, models directory: {models_dir}")
    
    def train_from_url(self, url, update_app=False, **kwargs):
        """Train the model from a URL.
        
        Args:
            url (str): URL to scrape
            update_app (bool): Whether to update the app with the trained model
            **kwargs: Additional arguments for the pipeline components
            
        Returns:
            dict: Training metrics
        """
        logger.info(f"Starting training pipeline from URL: {url}")
        
        # Step 1: Scrape coupons
        print("Step 1/5: Scraping coupons...")
        scraper = CouponScraper(output_dir=self.scraped_dir, delay=kwargs.get("delay", 1))
        coupons = scraper.scrape(url)
        
        if not coupons:
            logger.error("No coupons found")
            print("Error: No coupons found at the provided URL.")
            return None
        
        scraper.save_results(coupons)
        print(f"Scraped {len(coupons)} coupons.")
        
        # Step 2: Process images
        print("Step 2/5: Processing images...")
        processor = CouponImageProcessor(input_dir=self.scraped_dir, output_dir=self.processed_dir)
        processed_images = processor.process_images(filter_non_coupons=kwargs.get("filter", True))
        
        if not processed_images:
            logger.error("No images processed")
            print("Error: No images could be processed.")
            return None
        
        # Generate augmented images if requested
        if kwargs.get("augment", False):
            print("Generating augmented images...")
            all_images = processor.generate_augmented_images(
                processed_images, 
                num_augmentations=kwargs.get("num_augmentations", 3)
            )
            print(f"Processed {processor.images_processed} images, filtered out {processor.images_filtered} non-coupon images.")
            print(f"Generated {len(all_images) - len(processed_images)} augmented images.")
        else:
            print(f"Processed {processor.images_processed} images, filtered out {processor.images_filtered} non-coupon images.")
        
        # Step 3: Annotate images
        print("Step 3/5: Annotating images...")
        annotator = CouponAnnotator(input_dir=self.processed_dir, output_dir=self.annotated_dir)
        
        # Create metadata from scraped coupons
        metadata = {}
        for coupon in coupons:
            if "image_path" in coupon:
                filename = os.path.basename(coupon["image_path"])
                metadata[filename] = {
                    "store": coupon.get("store"),
                    "partner": coupon.get("partner"),
                    "discount": coupon.get("discount"),
                    "code": coupon.get("code"),
                    "min_order": coupon.get("min_order"),
                    "expiry_date": coupon.get("expiry_date")
                }
        
        # Save metadata
        metadata_file = os.path.join(self.annotated_dir, "metadata.json")
        with open(metadata_file, 'w') as f:
            json.dump(metadata, f, indent=2)
        
        annotations = annotator.annotate_images(metadata_file=metadata_file)
        
        if not annotations:
            logger.error("No annotations generated")
            print("Error: No annotations could be generated.")
            return None
        
        pattern_file = annotator.generate_pattern_file(annotations)
        print(f"Annotated {annotator.images_annotated} images.")
        print(f"Generated pattern file: {pattern_file}")
        
        # Step 4: Train model
        print("Step 4/5: Training model...")
        trainer = CouponModelTrainer(input_dir=self.annotated_dir, output_dir=self.models_dir)
        metrics = trainer.train(
            test_size=kwargs.get("test_size", 0.2),
            val_size=kwargs.get("val_size", 0.2),
            epochs=kwargs.get("epochs", 20)
        )
        
        if not metrics:
            logger.error("Training failed")
            print("Error: Training failed.")
            return None
        
        print(f"Training completed with test accuracy: {metrics['test_accuracy']:.4f}")
        print(f"Train samples: {metrics['train_samples']}")
        print(f"Validation samples: {metrics['val_samples']}")
        print(f"Test samples: {metrics['test_samples']}")
        
        # Step 5: Update app if requested
        if update_app:
            print("Step 5/5: Updating app...")
            success = update_app_model()
            
            if success:
                print("App updated successfully.")
            else:
                print("Error: App update failed.")
        else:
            print("Step 5/5: Skipping app update.")
        
        print("Training pipeline completed successfully.")
        return metrics
    
    def generate_report(self, metrics, output_file="training_report.json"):
        """Generate a report of the training pipeline.
        
        Args:
            metrics (dict): Training metrics
            output_file (str): Path to the output file
            
        Returns:
            str: Path to the generated report
        """
        # Create report
        report = {
            "timestamp": datetime.datetime.now().isoformat(),
            "metrics": metrics,
            "directories": {
                "scraped": self.scraped_dir,
                "processed": self.processed_dir,
                "annotated": self.annotated_dir,
                "models": self.models_dir
            }
        }
        
        # Add model information
        history_file = os.path.join(self.models_dir, "history.json")
        if os.path.exists(history_file):
            with open(history_file, 'r') as f:
                model_history = json.load(f)
            
            if model_history:
                report["model"] = model_history[0]
        
        # Save the report
        output_path = os.path.join(self.base_dir, output_file)
        with open(output_path, 'w') as f:
            json.dump(report, f, indent=2)
        
        logger.info(f"Generated report: {output_path}")
        return output_path

def main():
    """Main function."""
    parser = argparse.ArgumentParser(description="Train the coupon recognition model from a URL")
    parser.add_argument("url", help="URL to scrape")
    parser.add_argument("--base-dir", default="data", help="Base directory for data")
    parser.add_argument("--models-dir", default="models", help="Directory for models")
    parser.add_argument("--update-app", action="store_true", help="Update the app with the trained model")
    parser.add_argument("--filter", action="store_true", help="Filter out non-coupon images")
    parser.add_argument("--augment", action="store_true", help="Generate augmented versions of processed images")
    parser.add_argument("--num-augmentations", type=int, default=3, help="Number of augmentations to generate per image")
    parser.add_argument("--delay", type=float, default=1, help="Delay between requests in seconds")
    parser.add_argument("--test-size", type=float, default=0.2, help="Fraction of data to use for testing")
    parser.add_argument("--val-size", type=float, default=0.2, help="Fraction of training data to use for validation")
    parser.add_argument("--epochs", type=int, default=20, help="Number of training epochs")
    parser.add_argument("--report", action="store_true", help="Generate a report of the training pipeline")
    parser.add_argument("--report-file", default="training_report.json", help="Path to the report file")
    
    args = parser.parse_args()
    
    # Create trainer
    trainer = CouponTrainer(base_dir=args.base_dir, models_dir=args.models_dir)
    
    # Train from URL
    metrics = trainer.train_from_url(
        args.url,
        update_app=args.update_app,
        filter=args.filter,
        augment=args.augment,
        num_augmentations=args.num_augmentations,
        delay=args.delay,
        test_size=args.test_size,
        val_size=args.val_size,
        epochs=args.epochs
    )
    
    # Generate report if requested
    if args.report and metrics:
        report_path = trainer.generate_report(metrics, output_file=args.report_file)
        print(f"Generated report: {report_path}")

if __name__ == "__main__":
    main()
