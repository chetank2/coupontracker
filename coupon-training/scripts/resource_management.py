#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Resource Management for Coupon Tracker
This script implements proper resource management for bitmap processing and Tesseract resources.
"""

import os
import sys
import json
import argparse
import cv2
import numpy as np
from PIL import Image
import pytesseract
import logging
import gc
import contextlib
import tempfile
import shutil
import time
import psutil
import threading
from concurrent.futures import ThreadPoolExecutor

# Add parent directory to path
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("coupon_tracker_resources.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("ResourceManager")

class MemoryMonitor(threading.Thread):
    """
    Monitor memory usage in a separate thread
    """
    
    def __init__(self, interval=1.0):
        """
        Initialize the memory monitor
        
        Args:
            interval: Monitoring interval in seconds
        """
        super().__init__()
        self.interval = interval
        self.daemon = True
        self.stopped = threading.Event()
        self.max_memory = 0
        self.current_memory = 0
    
    def run(self):
        """Run the monitoring thread"""
        while not self.stopped.wait(self.interval):
            try:
                # Get current process
                process = psutil.Process(os.getpid())
                # Get memory info
                memory_info = process.memory_info()
                # Convert to MB
                memory_mb = memory_info.rss / (1024 * 1024)
                # Update current and max memory
                self.current_memory = memory_mb
                self.max_memory = max(self.max_memory, memory_mb)
            except Exception as e:
                logger.error(f"Error monitoring memory: {e}")
    
    def stop(self):
        """Stop the monitoring thread"""
        self.stopped.set()
    
    def get_memory_usage(self):
        """Get current memory usage in MB"""
        return self.current_memory
    
    def get_max_memory_usage(self):
        """Get maximum memory usage in MB"""
        return self.max_memory

@contextlib.contextmanager
def managed_image(image_path):
    """
    Context manager for loading and properly disposing of images
    
    Args:
        image_path: Path to the image
        
    Yields:
        tuple: (OpenCV image, PIL image)
    """
    cv_image = None
    pil_image = None
    
    try:
        # Load image with OpenCV
        cv_image = cv2.imread(image_path)
        if cv_image is None:
            raise ValueError(f"Could not read image {image_path}")
        
        # Convert to PIL
        pil_image = Image.fromarray(cv2.cvtColor(cv_image, cv2.COLOR_BGR2RGB))
        
        # Yield both images
        yield cv_image, pil_image
    
    finally:
        # Explicitly delete images
        if pil_image is not None:
            pil_image.close()
        
        if cv_image is not None:
            del cv_image
        
        # Force garbage collection
        gc.collect()

@contextlib.contextmanager
def managed_tesseract():
    """
    Context manager for Tesseract OCR resources
    
    Yields:
        None
    """
    temp_dir = None
    
    try:
        # Create temporary directory for Tesseract cache
        temp_dir = tempfile.mkdtemp(prefix="tesseract_")
        
        # Set Tesseract environment variables
        os.environ["TESSDATA_PREFIX"] = os.environ.get("TESSDATA_PREFIX", "/usr/share/tesseract-ocr/4.00/tessdata")
        os.environ["OMP_THREAD_LIMIT"] = "1"  # Limit OpenMP threads
        
        # Yield control
        yield
    
    finally:
        # Clean up temporary directory
        if temp_dir and os.path.exists(temp_dir):
            shutil.rmtree(temp_dir, ignore_errors=True)
        
        # Force garbage collection
        gc.collect()

class ResourceManager:
    """
    Resource manager for coupon recognition
    """
    
    def __init__(self, config_path=None):
        """
        Initialize the resource manager
        
        Args:
            config_path: Path to configuration file (optional)
        """
        # Default configuration
        self.config = {
            'max_workers': 4,
            'memory_limit_mb': 1024,
            'image_size_limit': (1920, 1080),
            'temp_dir': None,
            'cleanup_interval': 10
        }
        
        # Load configuration if provided
        if config_path and os.path.exists(config_path):
            try:
                with open(config_path, 'r') as f:
                    loaded_config = json.load(f)
                    # Update default config with loaded values
                    for key, value in loaded_config.items():
                        if key in self.config:
                            self.config[key] = value
            except Exception as e:
                logger.error(f"Error loading configuration: {e}")
        
        # Create temporary directory if not specified
        if not self.config['temp_dir']:
            self.config['temp_dir'] = tempfile.mkdtemp(prefix="coupon_tracker_")
        else:
            os.makedirs(self.config['temp_dir'], exist_ok=True)
        
        # Initialize memory monitor
        self.memory_monitor = MemoryMonitor()
        self.memory_monitor.start()
        
        # Initialize cleanup timer
        self.last_cleanup = time.time()
    
    def __del__(self):
        """Destructor to clean up resources"""
        self.cleanup()
        
        # Stop memory monitor
        if hasattr(self, 'memory_monitor'):
            self.memory_monitor.stop()
        
        # Clean up temporary directory
        temp_dir = self.config.get('temp_dir')
        if temp_dir and os.path.exists(temp_dir):
            try:
                shutil.rmtree(temp_dir, ignore_errors=True)
            except Exception as e:
                logger.error(f"Error cleaning up temporary directory: {e}")
    
    def resize_image_if_needed(self, image):
        """
        Resize image if it exceeds the size limit
        
        Args:
            image: OpenCV image
            
        Returns:
            OpenCV image: Resized image
        """
        # Get image dimensions
        height, width = image.shape[:2]
        
        # Get size limit
        max_width, max_height = self.config['image_size_limit']
        
        # Check if resizing is needed
        if width > max_width or height > max_height:
            # Calculate scaling factor
            scale_width = max_width / width
            scale_height = max_height / height
            scale = min(scale_width, scale_height)
            
            # Calculate new dimensions
            new_width = int(width * scale)
            new_height = int(height * scale)
            
            # Resize image
            resized = cv2.resize(image, (new_width, new_height), interpolation=cv2.INTER_AREA)
            
            logger.info(f"Resized image from {width}x{height} to {new_width}x{new_height}")
            
            return resized
        
        # No resizing needed
        return image
    
    def check_memory_usage(self):
        """
        Check memory usage and perform cleanup if needed
        
        Returns:
            bool: True if memory usage is within limits, False otherwise
        """
        # Get current memory usage
        current_memory = self.memory_monitor.get_memory_usage()
        
        # Check if memory usage exceeds limit
        if current_memory > self.config['memory_limit_mb']:
            logger.warning(f"Memory usage ({current_memory:.2f} MB) exceeds limit ({self.config['memory_limit_mb']} MB)")
            
            # Perform cleanup
            self.cleanup()
            
            # Check memory usage again
            current_memory = self.memory_monitor.get_memory_usage()
            
            # Return status
            return current_memory <= self.config['memory_limit_mb']
        
        # Check if cleanup interval has elapsed
        current_time = time.time()
        if current_time - self.last_cleanup > self.config['cleanup_interval']:
            self.cleanup()
            self.last_cleanup = current_time
        
        return True
    
    def cleanup(self):
        """Perform cleanup to free resources"""
        # Force garbage collection
        gc.collect()
        
        # Clean up temporary files
        temp_dir = self.config.get('temp_dir')
        if temp_dir and os.path.exists(temp_dir):
            try:
                # Remove files older than cleanup interval
                current_time = time.time()
                for filename in os.listdir(temp_dir):
                    file_path = os.path.join(temp_dir, filename)
                    if os.path.isfile(file_path):
                        file_age = current_time - os.path.getmtime(file_path)
                        if file_age > self.config['cleanup_interval']:
                            os.remove(file_path)
            except Exception as e:
                logger.error(f"Error cleaning up temporary files: {e}")
        
        logger.info("Cleanup performed")
    
    def extract_text(self, image, config=None):
        """
        Extract text from an image with proper resource management
        
        Args:
            image: PIL Image
            config: Tesseract configuration string
            
        Returns:
            str: Extracted text
        """
        # Check memory usage
        if not self.check_memory_usage():
            logger.warning("Memory usage too high, text extraction may fail")
        
        # Use managed Tesseract context
        with managed_tesseract():
            try:
                # Extract text
                text = pytesseract.image_to_string(image, config=config).strip()
                return text
            except Exception as e:
                logger.error(f"Error extracting text: {e}")
                return ""
    
    def process_region(self, image, region, region_type=None):
        """
        Process a region with proper resource management
        
        Args:
            image: OpenCV image
            region: Region coordinates (left, top, right, bottom)
            region_type: Type of region (store, code, amount, expiry, description)
            
        Returns:
            str: Extracted text
        """
        try:
            # Extract region from image
            left, top, right, bottom = region['left'], region['top'], region['right'], region['bottom']
            
            # Skip if region is invalid
            if left >= right or top >= bottom:
                logger.warning(f"Invalid region: {region}")
                return ""
            
            roi = image[top:bottom, left:right]
            
            # Skip if region is empty
            if roi.size == 0:
                logger.warning(f"Empty region: {region}")
                return ""
            
            # Convert to PIL for OCR
            pil_roi = Image.fromarray(cv2.cvtColor(roi, cv2.COLOR_BGR2RGB))
            
            # Get OCR config based on region type
            config = None
            if region_type == 'code':
                config = '--oem 3 --psm 7 -c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
            elif region_type == 'amount':
                config = '--oem 3 --psm 7 -c tessedit_char_whitelist=0123456789.,%₹$ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz '
            elif region_type == 'expiry':
                config = '--oem 3 --psm 7 -c tessedit_char_whitelist=0123456789/-.ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz '
            
            # Extract text
            text = self.extract_text(pil_roi, config)
            
            # Clean up
            pil_roi.close()
            del roi
            
            return text
            
        except Exception as e:
            logger.error(f"Error processing region: {e}")
            return ""
    
    def process_image(self, image_path, regions):
        """
        Process an image with proper resource management
        
        Args:
            image_path: Path to the image
            regions: Dictionary of regions by element type
            
        Returns:
            dict: Dictionary of results by element type
        """
        # Use managed image context
        with managed_image(image_path) as (cv_image, pil_image):
            try:
                # Resize image if needed
                cv_image = self.resize_image_if_needed(cv_image)
                
                # Process each region
                results = {}
                
                # Use thread pool for parallel processing
                with ThreadPoolExecutor(max_workers=self.config['max_workers']) as executor:
                    futures = {}
                    
                    for region_type, region_list in regions.items():
                        for i, region in enumerate(region_list):
                            # Submit task to thread pool
                            future = executor.submit(
                                self.process_region, 
                                cv_image, 
                                region, 
                                region_type
                            )
                            futures[(region_type, i)] = future
                    
                    # Collect results
                    for (region_type, i), future in futures.items():
                        try:
                            text = future.result()
                            if text:
                                if region_type not in results:
                                    results[region_type] = text
                                elif len(text) > len(results[region_type]):
                                    # Keep the longer text
                                    results[region_type] = text
                        except Exception as e:
                            logger.error(f"Error getting result for {region_type}: {e}")
                
                return results
                
            except Exception as e:
                logger.error(f"Error processing image: {e}")
                return {}

def main():
    parser = argparse.ArgumentParser(description="Resource Management for Coupon Tracker")
    parser.add_argument("--image", required=True, help="Path to the image to process")
    parser.add_argument("--regions", required=True, help="Path to regions JSON file")
    parser.add_argument("--config", help="Path to configuration file")
    parser.add_argument("--output", help="Path to save results")
    
    args = parser.parse_args()
    
    # Load regions
    try:
        with open(args.regions, 'r') as f:
            regions = json.load(f)
    except Exception as e:
        logger.error(f"Error loading regions: {e}")
        return
    
    # Create resource manager
    manager = ResourceManager(args.config)
    
    # Start memory monitoring
    logger.info("Starting processing with memory monitoring")
    start_time = time.time()
    
    # Process image
    results = manager.process_image(args.image, regions)
    
    # Calculate processing time
    processing_time = time.time() - start_time
    max_memory = manager.memory_monitor.get_max_memory_usage()
    
    # Print results
    print("\nRecognition Results:")
    for element_type, text in results.items():
        print(f"  {element_type}: {text}")
    
    print(f"\nProcessing time: {processing_time:.2f} seconds")
    print(f"Maximum memory usage: {max_memory:.2f} MB")
    
    # Save results if output path is provided
    if args.output:
        try:
            with open(args.output, 'w') as f:
                json.dump({
                    'results': results,
                    'performance': {
                        'processing_time': processing_time,
                        'max_memory_usage': max_memory
                    }
                }, f, indent=2)
            print(f"Results saved to {args.output}")
        except Exception as e:
            logger.error(f"Error saving results: {e}")
    
    # Clean up
    manager.cleanup()

if __name__ == "__main__":
    main()
