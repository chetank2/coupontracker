#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Optimize Model

This script optimizes the ONNX model by converting it to TFLite format
and applying quantization to reduce the model size.
"""

import os
import sys
import logging
import argparse
import numpy as np
import onnx
import tensorflow as tf
import tf2onnx

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("optimize_model.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("optimize_model")

def convert_onnx_to_tflite(onnx_model_path, output_dir, quantize=True):
    """
    Convert ONNX model to TFLite format with optional quantization
    
    Args:
        onnx_model_path (str): Path to the ONNX model
        output_dir (str): Directory to save the optimized model
        quantize (bool): Whether to apply quantization
        
    Returns:
        str: Path to the optimized TFLite model
    """
    try:
        # Ensure output directory exists
        os.makedirs(output_dir, exist_ok=True)
        
        # Load ONNX model
        logger.info(f"Loading ONNX model from {onnx_model_path}")
        onnx_model = onnx.load(onnx_model_path)
        
        # Convert ONNX model to TensorFlow
        logger.info("Converting ONNX model to TensorFlow")
        tf_rep = tf2onnx.convert.from_onnx(onnx_model)
        
        # Save TensorFlow model
        tf_model_path = os.path.join(output_dir, "tf_model")
        logger.info(f"Saving TensorFlow model to {tf_model_path}")
        tf.saved_model.save(tf_rep, tf_model_path)
        
        # Convert TensorFlow model to TFLite
        logger.info("Converting TensorFlow model to TFLite")
        converter = tf.lite.TFLiteConverter.from_saved_model(tf_model_path)
        
        if quantize:
            # Apply quantization to reduce model size
            logger.info("Applying quantization")
            converter.optimizations = [tf.lite.Optimize.DEFAULT]
            converter.target_spec.supported_types = [tf.float16]
            
            # Define representative dataset for quantization
            def representative_dataset():
                for _ in range(100):
                    # Generate random input data based on your model's input shape
                    # Adjust the shape according to your model's input requirements
                    data = np.random.rand(1, 224, 224, 3).astype(np.float32)
                    yield [data]
            
            converter.representative_dataset = representative_dataset
            converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
            converter.inference_input_type = tf.uint8
            converter.inference_output_type = tf.uint8
        
        tflite_model = converter.convert()
        
        # Save TFLite model
        tflite_model_path = os.path.join(output_dir, "optimized_model.tflite")
        with open(tflite_model_path, 'wb') as f:
            f.write(tflite_model)
        
        logger.info(f"Optimized TFLite model saved to {tflite_model_path}")
        
        # Get file sizes
        onnx_size = os.path.getsize(onnx_model_path) / (1024 * 1024)
        tflite_size = os.path.getsize(tflite_model_path) / (1024 * 1024)
        
        logger.info(f"Original ONNX model size: {onnx_size:.2f} MB")
        logger.info(f"Optimized TFLite model size: {tflite_size:.2f} MB")
        logger.info(f"Size reduction: {(1 - tflite_size / onnx_size) * 100:.2f}%")
        
        return tflite_model_path
    
    except Exception as e:
        logger.error(f"Error converting model: {e}")
        return None

def main():
    """Main function"""
    parser = argparse.ArgumentParser(description="Optimize ONNX model by converting to TFLite format")
    parser.add_argument("--input-model", default="../app/src/main/assets/models/india_coupon_model.onnx", 
                       help="Path to the input ONNX model")
    parser.add_argument("--output-dir", default="../app/src/main/assets/models", 
                       help="Directory to save the optimized model")
    parser.add_argument("--no-quantize", action="store_true", 
                       help="Disable quantization")
    
    args = parser.parse_args()
    
    # Convert model
    tflite_model_path = convert_onnx_to_tflite(
        args.input_model, 
        args.output_dir, 
        not args.no_quantize
    )
    
    if tflite_model_path:
        print(f"\nModel optimization complete. Optimized model saved to {tflite_model_path}")
    else:
        print("\nError optimizing model. Check the logs for details.")
        return 1
    
    return 0

if __name__ == "__main__":
    sys.exit(main())
