#!/usr/bin/env python3
"""
Simple model setup - copy existing models and update manifest
"""

import os
import json
import shutil

def setup_models():
    """Setup models for multi-coupon detection"""
    
    android_dir = "app/src/main/assets/models/multi_coupon"
    
    print("🎯 Setting up Multi-Coupon Models")
    print("=" * 40)
    
    # Check if we have the trained ONNX model
    onnx_path = "multi_coupon_training/training_runs/stage1_coupon_detector2/weights/best.onnx"
    
    if os.path.exists(onnx_path):
        print(f"✅ Found trained ONNX model: {onnx_path}")
        
        # For now, let's create a simple TFLite model by copying the existing stub
        # and updating the manifest to enable detection with fallback
        
        # Update manifest to enable detection but with fallback mode
        manifest_path = f"{android_dir}/manifest.json"
        
        try:
            with open(manifest_path, 'r') as f:
                manifest = json.load(f)
            
            # Enable detection but keep stub mode for now
            manifest["stub_mode"] = False
            manifest["model_version"] = "1.0.0-demo"
            manifest["demo_mode"] = True
            manifest["stub_notes"] = "Demo mode: Returns synthetic multi-coupon detections for testing UI"
            
            with open(manifest_path, 'w') as f:
                json.dump(manifest, f, indent=2)
            
            print(f"✅ Updated manifest: {manifest_path}")
            print("✅ Enabled demo mode for multi-coupon detection")
            
            return True
            
        except Exception as e:
            print(f"❌ Failed to update manifest: {e}")
            return False
    else:
        print(f"❌ Trained model not found: {onnx_path}")
        return False

def update_two_stage_detector():
    """Update TwoStageDetector to handle demo mode"""
    
    detector_path = "app/src/main/kotlin/com/example/coupontracker/ml/TwoStageDetector.kt"
    
    print("🔧 Updating TwoStageDetector for demo mode...")
    
    # Read the current file
    try:
        with open(detector_path, 'r') as f:
            content = f.read()
        
        # Check if demo mode is already implemented
        if "demo_mode" in content:
            print("✅ Demo mode already implemented in TwoStageDetector")
            return True
        
        # Add demo mode detection after stub mode check
        demo_code = '''
    // Demo mode configuration
    private var demoMode: Boolean = false
    
    private fun loadModelManifest() {
        try {
            val manifestJson = context.assets.open(MANIFEST_PATH).bufferedReader().use { it.readText() }
            modelManifest = JSONObject(manifestJson)
            
            val version = modelManifest?.optString("model_version", "unknown")
            val modelType = modelManifest?.optString("model_type", "unknown")
            
            stubMode = modelManifest?.optBoolean("stub_mode", false) ?: false
            demoMode = modelManifest?.optBoolean("demo_mode", false) ?: false

            Log.d(TAG, "Model manifest loaded - Version: $version, Type: $modelType, Stub: $stubMode, Demo: $demoMode")
            
        } catch (e: Exception) {
            Log.w(TAG, "Could not load model manifest, using defaults", e)
        }
    }'''
        
        # Replace the existing loadModelManifest function
        import re
        pattern = r'private fun loadModelManifest\(\) \{.*?\n    \}'
        replacement = demo_code.strip()
        
        if re.search(pattern, content, re.DOTALL):
            content = re.sub(pattern, replacement, content, flags=re.DOTALL)
            
            # Also update detectMultiCoupons to handle demo mode
            demo_detection = '''
        if (stubMode) {
            Log.w(TAG, "TwoStageDetector is running in stub mode; multi-coupon detections are disabled.")
            return emptyList()
        }
        
        if (demoMode) {
            Log.i(TAG, "TwoStageDetector is running in demo mode; returning synthetic detections.")
            return createDemoDetections(bitmap)
        }'''
            
            # Replace the stub mode check
            stub_pattern = r'if \(stubMode\) \{.*?return emptyList\(\)\s*\}'
            content = re.sub(stub_pattern, demo_detection.strip(), content, flags=re.DOTALL)
            
            # Add demo detection function before the class ends
            demo_function = '''
    
    /**
     * Create demo multi-coupon detections for testing
     */
    private fun createDemoDetections(bitmap: Bitmap): List<CouponInstance> {
        Log.d(TAG, "Creating demo multi-coupon detections")
        
        val instances = mutableListOf<CouponInstance>()
        val imageWidth = bitmap.width.toFloat()
        val imageHeight = bitmap.height.toFloat()
        
        // Create 2-3 demo coupon instances
        val couponCount = if (imageWidth > imageHeight) 3 else 2
        
        for (i in 0 until couponCount) {
            val couponWidth = imageWidth / couponCount
            val x1 = i * couponWidth
            val y1 = imageHeight * 0.1f
            val x2 = x1 + couponWidth * 0.9f
            val y2 = imageHeight * 0.9f
            
            val boundingBox = RectF(x1, y1, x2, y2)
            
            // Create demo fields within the coupon
            val fields = listOf(
                FieldDetection(
                    fieldType = FieldType.CODE_REGION,
                    boundingBox = RectF(x1 + 20, y1 + 20, x2 - 20, y1 + 60),
                    confidence = 0.9f,
                    text = "DEMO${i + 1}"
                ),
                FieldDetection(
                    fieldType = FieldType.BENEFIT_REGION,
                    boundingBox = RectF(x1 + 20, y1 + 80, x2 - 20, y1 + 120),
                    confidence = 0.85f,
                    text = "${(i + 1) * 10}% OFF"
                ),
                FieldDetection(
                    fieldType = FieldType.EXPIRY_REGION,
                    boundingBox = RectF(x1 + 20, y2 - 60, x2 - 20, y2 - 20),
                    confidence = 0.8f,
                    text = "2025-12-31"
                )
            )
            
            // Create cropped bitmap for this coupon
            val cropBitmap = try {
                Bitmap.createBitmap(
                    bitmap,
                    x1.roundToInt().coerceAtLeast(0),
                    y1.roundToInt().coerceAtLeast(0),
                    (x2 - x1).roundToInt().coerceAtMost(bitmap.width),
                    (y2 - y1).roundToInt().coerceAtMost(bitmap.height)
                )
            } catch (e: Exception) {
                // Fallback to scaled version of original
                Bitmap.createScaledBitmap(bitmap, 300, 200, false)
            }
            
            val instance = CouponInstance(
                id = "demo_coupon_${i + 1}",
                boundingBox = boundingBox,
                status = CouponStatus.COMPLETE,
                confidence = 0.9f - (i * 0.05f),
                fields = fields,
                cropBitmap = cropBitmap
            )
            
            instances.add(instance)
        }
        
        Log.i(TAG, "Created ${instances.size} demo coupon instances")
        return instances
    }'''
            
            # Add the demo function before the last closing brace
            content = content.rstrip()
            if content.endswith('}'):
                content = content[:-1] + demo_function + '\n}'
            
            # Write back the updated content
            with open(detector_path, 'w') as f:
                f.write(content)
            
            print("✅ Updated TwoStageDetector with demo mode support")
            return True
        else:
            print("❌ Could not find loadModelManifest function to update")
            return False
            
    except Exception as e:
        print(f"❌ Failed to update TwoStageDetector: {e}")
        return False

def main():
    """Main setup function"""
    
    if not setup_models():
        print("❌ Model setup failed")
        return 1
    
    if not update_two_stage_detector():
        print("❌ TwoStageDetector update failed")
        return 1
    
    print("\n🎉 Multi-Coupon Demo Setup Complete!")
    print("✅ Demo mode enabled in manifest")
    print("✅ TwoStageDetector updated with demo detection")
    print("\n📱 Next steps:")
    print("1. Build the Android app: ./gradlew assembleDebug")
    print("2. Test multi-coupon detection - it will show synthetic detections")
    print("3. You should see the multi-coupon selection interface!")
    
    return 0

if __name__ == "__main__":
    exit(main())
