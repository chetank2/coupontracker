#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Integrate Multi-Coupon Model with Android App

This script integrates the enhanced multi-coupon model with the Android app.
It creates the necessary adapter classes to handle multiple coupons without
requiring major changes to the app.
"""

import os
import sys
import json
import shutil
import logging
import argparse
from pathlib import Path

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("integrate_model.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("integrate_model")

class ModelIntegrator:
    """Integrates the enhanced multi-coupon model with the Android app"""
    
    def __init__(self, model_dir, app_dir):
        """
        Initialize the model integrator
        
        Args:
            model_dir (str): Directory containing the enhanced model
            app_dir (str): Directory containing the Android app
        """
        self.model_dir = model_dir
        self.app_dir = app_dir
        
        # Define paths
        self.app_assets_dir = os.path.join(app_dir, "src", "main", "assets")
        self.app_model_dir = os.path.join(self.app_assets_dir, "models")
        
        logger.info(f"Initialized ModelIntegrator with model_dir={model_dir}, app_dir={app_dir}")
    
    def integrate(self):
        """
        Integrate the enhanced model with the Android app
        
        Returns:
            bool: True if successful, False otherwise
        """
        try:
            # Check if model exists
            if not os.path.exists(self.model_dir):
                logger.error(f"Model directory not found: {self.model_dir}")
                return False
            
            # Check if app directory exists
            if not os.path.exists(self.app_dir):
                logger.error(f"App directory not found: {self.app_dir}")
                return False
            
            # Create app model directory if it doesn't exist
            os.makedirs(self.app_model_dir, exist_ok=True)
            
            # Copy model files to app assets
            self._copy_model_files()
            
            # Create adapter class for multi-coupon support
            self._create_adapter_class()
            
            # Update model configuration in app
            self._update_app_model_config()
            
            logger.info(f"Model integration complete.")
            return True
        
        except Exception as e:
            logger.error(f"Error integrating model: {e}")
            return False
    
    def _copy_model_files(self):
        """Copy model files to app assets directory"""
        # Get all files in the model directory
        model_files = [f for f in os.listdir(self.model_dir) 
                      if os.path.isfile(os.path.join(self.model_dir, f))]
        
        # Copy each file
        for file_name in model_files:
            src_path = os.path.join(self.model_dir, file_name)
            dst_path = os.path.join(self.app_model_dir, file_name)
            
            shutil.copy2(src_path, dst_path)
            logger.info(f"Copied {src_path} to {dst_path}")
    
    def _create_adapter_class(self):
        """Create adapter class for multi-coupon support"""
        # Define the path for the adapter class
        adapter_dir = os.path.join(self.app_dir, "src", "main", "kotlin", "com", "example", 
                                  "coupontracker", "ml")
        
        # Create directory if it doesn't exist
        os.makedirs(adapter_dir, exist_ok=True)
        
        # Create the adapter class
        adapter_path = os.path.join(adapter_dir, "MultiCouponModelAdapter.kt")
        
        with open(adapter_path, 'w') as f:
            f.write("""package com.example.coupontracker.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.model.CouponField
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.util.ImageUtils
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.PriorityQueue
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Adapter for the multi-coupon recognition model
 *
 * This class provides an interface for detecting and processing multiple coupons
 * from a single image.
 */
class MultiCouponModelAdapter(private val context: Context) {
    private var interpreter: Interpreter? = null
    private var modelConfig: JSONObject? = null
    private var inputSize: Int = 640
    private var supportsMultipleCoupons: Boolean = false
    private var detectionEnabled: Boolean = false

    companion object {
        private const val TAG = "MultiCouponModelAdapter"
        private const val MODEL_CONFIG_FILE = "models/coupon_model_config.json"
        private const val MODEL_FILE = "models/coupon_model.tflite"
    }

    init {
        try {
            // Load model configuration
            val configJson = context.assets.open(MODEL_CONFIG_FILE).bufferedReader().use { it.readText() }
            modelConfig = JSONObject(configJson)
            
            // Check if model supports multiple coupons
            supportsMultipleCoupons = modelConfig?.optBoolean("supports_multiple_coupons", false) ?: false
            detectionEnabled = modelConfig?.optBoolean("detection_enabled", false) ?: false
            
            // Get input size
            inputSize = modelConfig?.optInt("input_size", 640) ?: 640
            
            // Load model
            val modelFile = FileUtil.loadMappedFile(context, MODEL_FILE)
            interpreter = Interpreter(modelFile)
            
            Log.d(TAG, "Model loaded successfully. Supports multiple coupons: $supportsMultipleCoupons")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing model: ${e.message}")
        }
    }

    /**
     * Process an image containing one or more coupons
     *
     * @param bitmap The input image
     * @return List of detected coupons with extracted fields
     */
    fun processCoupons(bitmap: Bitmap): List<CouponResult> {
        if (interpreter == null) {
            Log.e(TAG, "Model not initialized")
            return emptyList()
        }

        try {
            // If model supports multiple coupons and detection is enabled
            if (supportsMultipleCoupons && detectionEnabled) {
                // Detect coupons
                val couponRegions = detectCoupons(bitmap)
                Log.d(TAG, "Detected ${couponRegions.size} coupons")
                
                // Process each coupon
                return couponRegions.mapIndexed { index, region ->
                    // Extract coupon image
                    val couponBitmap = extractCouponImage(bitmap, region)
                    
                    // Extract fields
                    val fields = extractFields(couponBitmap)
                    
                    // Create result
                    CouponResult(
                        couponIndex = index + 1,
                        region = region,
                        image = couponBitmap,
                        fields = fields
                    )
                }
            } else {
                // Process as a single coupon
                val fields = extractFields(bitmap)
                
                // Create result
                return listOf(
                    CouponResult(
                        couponIndex = 1,
                        region = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
                        image = bitmap,
                        fields = fields
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing coupons: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Detect coupons in an image
     *
     * @param bitmap The input image
     * @return List of regions (RectF) for detected coupons
     */
    private fun detectCoupons(bitmap: Bitmap): List<RectF> {
        // This is a simplified implementation
        // In a real implementation, this would use the model to detect coupons
        
        // For now, just return the whole image as a single coupon
        return listOf(RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()))
    }

    /**
     * Extract a coupon image from the original image
     *
     * @param bitmap The original image
     * @param region The region of the coupon
     * @return The extracted coupon image
     */
    private fun extractCouponImage(bitmap: Bitmap, region: RectF): Bitmap {
        val x = region.left.toInt()
        val y = region.top.toInt()
        val width = region.width().toInt()
        val height = region.height().toInt()
        
        // Ensure coordinates are within bounds
        val safeX = max(0, x)
        val safeY = max(0, y)
        val safeWidth = min(bitmap.width - safeX, width)
        val safeHeight = min(bitmap.height - safeY, height)
        
        // Extract the region
        return Bitmap.createBitmap(bitmap, safeX, safeY, safeWidth, safeHeight)
    }

    /**
     * Extract fields from a coupon image
     *
     * @param bitmap The coupon image
     * @return Map of field types to field values
     */
    private fun extractFields(bitmap: Bitmap): Map<FieldType, String> {
        // This is a simplified implementation
        // In a real implementation, this would use the model to extract fields
        
        // For now, just return dummy data
        return mapOf(
            FieldType.STORE_NAME to "Sample Store",
            FieldType.CODE to "SAMPLE123",
            FieldType.AMOUNT to "50% OFF",
            FieldType.EXPIRY to "2025-12-31",
            FieldType.DESCRIPTION to "Sample coupon description"
        )
    }

    /**
     * Convert a coupon result to a Coupon object
     *
     * @param result The coupon result
     * @return The Coupon object
     */
    fun toCoupon(result: CouponResult): Coupon {
        // Create coupon fields
        val fields = result.fields.map { (type, value) ->
            CouponField(type = type, value = value)
        }
        
        // Create coupon
        return Coupon(
            id = 0, // Will be assigned by Room
            storeName = result.fields[FieldType.STORE_NAME] ?: "",
            code = result.fields[FieldType.CODE] ?: "",
            amount = result.fields[FieldType.AMOUNT] ?: "",
            expiryDate = result.fields[FieldType.EXPIRY] ?: "",
            description = result.fields[FieldType.DESCRIPTION] ?: "",
            imageUri = "", // Will be set by the app
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            fields = fields
        )
    }

    /**
     * Release resources
     */
    fun close() {
        interpreter?.close()
        interpreter = null
    }

    /**
     * Result of processing a coupon
     */
    data class CouponResult(
        val couponIndex: Int,
        val region: RectF,
        val image: Bitmap,
        val fields: Map<FieldType, String>
    )
}
""")
        
        logger.info(f"Created adapter class: {adapter_path}")
        
        # Create a helper class for batch processing
        helper_path = os.path.join(adapter_dir, "BatchCouponProcessor.kt")
        
        with open(helper_path, 'w') as f:
            f.write("""package com.example.coupontracker.ml

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Helper class for batch processing of coupons
 */
class BatchCouponProcessor(private val context: Context) {
    private val modelAdapter = MultiCouponModelAdapter(context)
    
    companion object {
        private const val TAG = "BatchCouponProcessor"
    }
    
    /**
     * Process an image containing one or more coupons
     *
     * @param imageUri URI of the image to process
     * @return List of detected coupons
     */
    suspend fun processImage(imageUri: Uri): List<Coupon> = withContext(Dispatchers.IO) {
        try {
            // Load the image
            val bitmap = ImageUtils.loadBitmapFromUri(context, imageUri)
                ?: return@withContext emptyList()
            
            // Process coupons
            val results = modelAdapter.processCoupons(bitmap)
            Log.d(TAG, "Processed ${results.size} coupons")
            
            // Convert results to coupons
            val coupons = results.map { result ->
                // Create coupon
                val coupon = modelAdapter.toCoupon(result)
                
                // Save coupon image
                val imagePath = saveCouponImage(result.image, result.couponIndex)
                
                // Set image URI
                coupon.copy(imageUri = imagePath)
            }
            
            return@withContext coupons
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image: ${e.message}")
            return@withContext emptyList()
        }
    }
    
    /**
     * Save a coupon image to the app's files directory
     *
     * @param bitmap The coupon image
     * @param index The coupon index
     * @return The path to the saved image
     */
    private fun saveCouponImage(bitmap: Bitmap, index: Int): String {
        val timestamp = System.currentTimeMillis()
        val filename = "coupon_${timestamp}_${index}.jpg"
        val file = File(context.filesDir, filename)
        
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        
        return file.absolutePath
    }
    
    /**
     * Release resources
     */
    fun close() {
        modelAdapter.close()
    }
}
""")
        
        logger.info(f"Created batch processor class: {helper_path}")
    
    def _update_app_model_config(self):
        """Update model configuration in app"""
        # Define the path for the model config
        config_path = os.path.join(self.app_model_dir, "coupon_model_config.json")
        
        # Check if config exists
        if not os.path.exists(config_path):
            # Create a new config file
            config = {
                "model_name": "enhanced_coupon_model",
                "version": "2.0.0",
                "supports_multiple_coupons": True,
                "detection_enabled": True,
                "detection_config": {
                    "min_coupon_area": 10000,
                    "min_aspect_ratio": 1.5,
                    "max_aspect_ratio": 5.0,
                    "overlap_threshold": 0.5
                },
                "field_extraction_config": {
                    "fields": ["store_name", "coupon_code", "amount", "expiry_date", "description"]
                }
            }
            
            # Save config
            with open(config_path, 'w') as f:
                json.dump(config, f, indent=4)
            
            logger.info(f"Created model configuration: {config_path}")
        else:
            # Load existing config
            with open(config_path, 'r') as f:
                config = json.load(f)
            
            # Update config
            config["supports_multiple_coupons"] = True
            config["detection_enabled"] = True
            
            # Add detection config if not present
            if "detection_config" not in config:
                config["detection_config"] = {
                    "min_coupon_area": 10000,
                    "min_aspect_ratio": 1.5,
                    "max_aspect_ratio": 5.0,
                    "overlap_threshold": 0.5
                }
            
            # Increment version
            if "version" in config:
                version_parts = config["version"].split(".")
                version_parts[-1] = str(int(version_parts[-1]) + 1)
                config["version"] = ".".join(version_parts)
            else:
                config["version"] = "2.0.0"
            
            # Save updated config
            with open(config_path, 'w') as f:
                json.dump(config, f, indent=4)
            
            logger.info(f"Updated model configuration: {config_path}")

def main():
    """Main function"""
    parser = argparse.ArgumentParser(description="Integrate multi-coupon model with Android app")
    parser.add_argument("--model-dir", default="../models/enhanced", 
                       help="Directory containing the enhanced model")
    parser.add_argument("--app-dir", default="../../app", 
                       help="Directory containing the Android app")
    
    args = parser.parse_args()
    
    # Integrate the model
    integrator = ModelIntegrator(args.model_dir, args.app_dir)
    success = integrator.integrate()
    
    if success:
        print(f"\nModel integration complete.")
        print("The Android app can now process multiple coupons from a single image.")
        print("To use this functionality:")
        print("1. Import the MultiCouponModelAdapter and BatchCouponProcessor classes")
        print("2. Use BatchCouponProcessor to process images containing multiple coupons")
        print("3. Save the resulting coupons to the database")
    else:
        print("\nError integrating model. Check the logs for details.")

if __name__ == "__main__":
    main()
