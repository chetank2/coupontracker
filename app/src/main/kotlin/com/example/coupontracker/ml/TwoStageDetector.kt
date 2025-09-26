package com.example.coupontracker.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Two-Stage Multi-Coupon Detection System
 * Stage 1: Detect coupon instances in full image
 * Stage 2: Detect fields within each coupon crop
 * 
 * This class handles the complete multi-coupon detection pipeline:
 * - Single coupon screenshots
 * - Multiple coupon screenshots (grid/list)
 * - Partial coupon screenshots (top/bottom cut-off)
 * - Scrollable coupon lists
 */
class TwoStageDetector(private val context: Context) {
    
    companion object {
        private const val TAG = "TwoStageDetector"
        private const val STAGE1_INPUT_SIZE = 640
        private const val STAGE2_INPUT_SIZE = 320
        private const val CONFIDENCE_THRESHOLD = 0.5f
        private const val IOU_THRESHOLD = 0.4f
        private const val MAX_DETECTIONS = 50
        
        // Model paths
        private const val STAGE1_MODEL_PATH = "models/multi_coupon/stage1_coupon_detector.tflite"
        private const val STAGE2_MODEL_PATH = "models/multi_coupon/stage2_field_detector.tflite"
        private const val MANIFEST_PATH = "models/multi_coupon/manifest.json"
    }
    
    // TensorFlow Lite interpreters
    private var stage1Interpreter: Interpreter? = null  // Coupon detection
    private var stage2Interpreter: Interpreter? = null  // Field detection
    
    // Image processors
    private var stage1Processor: ImageProcessor? = null
    private var stage2Processor: ImageProcessor? = null
    
    // Model configuration
    private var modelManifest: JSONObject? = null
    private val stage1Classes = arrayOf("coupon_complete", "coupon_partial_top", "coupon_partial_bottom")
    private val stage2Classes = arrayOf("code_region", "benefit_region", "expiry_region", "app_region", "terms_region")
    
    // Initialization state
    private var isInitialized = false
    
    init {
        initializeModels()
    }
    
    /**
     * Initialize both stage models and processors
     */
    private fun initializeModels() {
        try {
            Log.d(TAG, "Initializing two-stage detection models...")
            
            // Load model manifest
            loadModelManifest()
            
            // Load Stage 1 Model (Coupon Detection)
            loadStage1Model()
            
            // Load Stage 2 Model (Field Detection)  
            loadStage2Model()
            
            // Initialize image processors
            initializeImageProcessors()
            
            isInitialized = true
            Log.i(TAG, "Two-stage models initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing models", e)
            isInitialized = false
        }
    }
    
    private fun loadModelManifest() {
        try {
            val manifestJson = context.assets.open(MANIFEST_PATH).bufferedReader().use { it.readText() }
            modelManifest = JSONObject(manifestJson)
            
            val version = modelManifest?.optString("model_version", "unknown")
            val modelType = modelManifest?.optString("model_type", "unknown")
            
            Log.d(TAG, "Model manifest loaded - Version: $version, Type: $modelType")
            
        } catch (e: Exception) {
            Log.w(TAG, "Could not load model manifest, using defaults", e)
        }
    }
    
    private fun loadStage1Model() {
        try {
            val stage1ModelBuffer = FileUtil.loadMappedFile(context, STAGE1_MODEL_PATH)
            stage1Interpreter = Interpreter(stage1ModelBuffer)
            Log.d(TAG, "Stage 1 model loaded successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading Stage 1 model", e)
            throw e
        }
    }
    
    private fun loadStage2Model() {
        try {
            val stage2ModelBuffer = FileUtil.loadMappedFile(context, STAGE2_MODEL_PATH)
            stage2Interpreter = Interpreter(stage2ModelBuffer)
            Log.d(TAG, "Stage 2 model loaded successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading Stage 2 model", e)
            throw e
        }
    }
    
    private fun initializeImageProcessors() {
        stage1Processor = ImageProcessor.Builder()
            .add(ResizeOp(STAGE1_INPUT_SIZE, STAGE1_INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
            .build()
            
        stage2Processor = ImageProcessor.Builder()
            .add(ResizeOp(STAGE2_INPUT_SIZE, STAGE2_INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
            .build()
            
        Log.d(TAG, "Image processors initialized")
    }
    
    /**
     * Main detection method - processes image through both stages
     * @param bitmap Input image bitmap
     * @return List of detected coupon instances with their fields
     */
    fun detectMultiCoupons(bitmap: Bitmap): List<CouponInstance> {
        if (!isInitialized) {
            Log.e(TAG, "Models not initialized")
            return emptyList()
        }
        
        val stage1Interpreter = this.stage1Interpreter
        val stage2Interpreter = this.stage2Interpreter
        
        if (stage1Interpreter == null || stage2Interpreter == null) {
            Log.e(TAG, "Interpreters not available")
            return emptyList()
        }
        
        try {
            val startTime = System.currentTimeMillis()
            
            // Stage 1: Detect coupon instances
            val couponDetections = detectCouponInstances(bitmap)
            val stage1Time = System.currentTimeMillis()
            
            Log.d(TAG, "Stage 1 detected ${couponDetections.size} coupons in ${stage1Time - startTime}ms")
            
            if (couponDetections.isEmpty()) {
                Log.i(TAG, "No coupons detected in Stage 1")
                return emptyList()
            }
            
            // Stage 2: Detect fields in each coupon
            val couponInstances = mutableListOf<CouponInstance>()
            
            couponDetections.forEachIndexed { index, couponDetection ->
                try {
                    // Crop coupon from original image
                    val couponCrop = cropBitmap(bitmap, couponDetection.boundingBox)
                    
                    if (couponCrop.width < 10 || couponCrop.height < 10) {
                        Log.w(TAG, "Coupon crop too small, skipping instance $index")
                        return@forEachIndexed
                    }
                    
                    // Detect fields in this coupon crop
                    val fieldDetections = detectFieldsInCoupon(couponCrop)
                    
                    // Adjust field coordinates back to original image space
                    val adjustedFields = adjustFieldCoordinates(fieldDetections, couponDetection.boundingBox)
                    
                    couponInstances.add(
                        CouponInstance(
                            id = "coupon_${System.currentTimeMillis()}_$index",
                            boundingBox = couponDetection.boundingBox,
                            status = couponDetection.status,
                            confidence = couponDetection.confidence,
                            fields = adjustedFields,
                            cropBitmap = couponCrop
                        )
                    )
                    
                    Log.d(TAG, "Coupon $index: ${adjustedFields.size} fields detected")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing coupon $index", e)
                }
            }
            
            val totalTime = System.currentTimeMillis() - startTime
            Log.i(TAG, "Two-stage detection completed in ${totalTime}ms: ${couponInstances.size} coupons with ${couponInstances.sumOf { it.fields.size }} total fields")
            
            return couponInstances
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in multi-coupon detection", e)
            return emptyList()
        }
    }
    
    /**
     * Stage 1: Detect coupon instances in the full image
     */
    private fun detectCouponInstances(bitmap: Bitmap): List<CouponDetection> {
        val interpreter = stage1Interpreter ?: return emptyList()
        val processor = stage1Processor ?: return emptyList()
        
        try {
            // Preprocess image
            val tensorImage = TensorImage.fromBitmap(bitmap)
            val processedImage = processor.process(tensorImage)
            
            // Prepare input buffer
            val inputBuffer = processedImage.buffer
            
            // Get output tensor info
            val outputTensor = interpreter.getOutputTensor(0)
            val outputShape = outputTensor.shape() // Expected: [1, 84, 8400] for YOLOv8
            
            // Prepare output buffer
            val outputSize = outputShape[1] * outputShape[2] * 4 // float32 = 4 bytes
            val outputBuffer = ByteBuffer.allocateDirect(outputSize)
            outputBuffer.order(ByteOrder.nativeOrder())
            
            // Run inference
            interpreter.run(inputBuffer, outputBuffer)
            
            // Parse detections
            return parseCouponDetections(outputBuffer, outputShape, bitmap.width, bitmap.height)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in Stage 1 detection", e)
            return emptyList()
        }
    }
    
    /**
     * Stage 2: Detect fields within a coupon crop
     */
    private fun detectFieldsInCoupon(couponCrop: Bitmap): List<FieldDetection> {
        val interpreter = stage2Interpreter ?: return emptyList()
        val processor = stage2Processor ?: return emptyList()
        
        try {
            // Preprocess cropped image
            val tensorImage = TensorImage.fromBitmap(couponCrop)
            val processedImage = processor.process(tensorImage)
            
            // Prepare input buffer
            val inputBuffer = processedImage.buffer
            
            // Get output tensor info
            val outputTensor = interpreter.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            
            // Prepare output buffer
            val outputSize = outputShape[1] * outputShape[2] * 4 // float32 = 4 bytes
            val outputBuffer = ByteBuffer.allocateDirect(outputSize)
            outputBuffer.order(ByteOrder.nativeOrder())
            
            // Run inference
            interpreter.run(inputBuffer, outputBuffer)
            
            // Parse field detections
            return parseFieldDetections(outputBuffer, outputShape, couponCrop.width, couponCrop.height)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in Stage 2 detection", e)
            return emptyList()
        }
    }
    
    /**
     * Parse Stage 1 coupon detection results from YOLOv8 output
     */
    private fun parseCouponDetections(
        outputBuffer: ByteBuffer, 
        outputShape: IntArray, 
        imageWidth: Int, 
        imageHeight: Int
    ): List<CouponDetection> {
        outputBuffer.rewind()
        val detections = mutableListOf<CouponDetection>()
        
        val numDetections = outputShape[2] // 8400 for YOLOv8
        val numClasses = stage1Classes.size
        val outputDim = outputShape[1] // 84 for YOLOv8 (4 bbox + 80 classes, but we have 3 classes)
        
        for (i in 0 until numDetections) {
            try {
                // YOLOv8 output format: [x_center, y_center, width, height, class_0_conf, class_1_conf, ...]
                val baseIndex = i * outputDim
                
                val xCenter = outputBuffer.getFloat(baseIndex * 4) / STAGE1_INPUT_SIZE * imageWidth
                val yCenter = outputBuffer.getFloat((baseIndex + 1) * 4) / STAGE1_INPUT_SIZE * imageHeight
                val width = outputBuffer.getFloat((baseIndex + 2) * 4) / STAGE1_INPUT_SIZE * imageWidth
                val height = outputBuffer.getFloat((baseIndex + 3) * 4) / STAGE1_INPUT_SIZE * imageHeight
                
                // Find best class and confidence
                var maxConf = 0f
                var bestClass = 0
                
                for (j in 0 until numClasses) {
                    val conf = outputBuffer.getFloat((baseIndex + 4 + j) * 4)
                    if (conf > maxConf) {
                        maxConf = conf
                        bestClass = j
                    }
                }
                
                if (maxConf > CONFIDENCE_THRESHOLD) {
                    val boundingBox = RectF(
                        max(0f, xCenter - width / 2),
                        max(0f, yCenter - height / 2),
                        min(imageWidth.toFloat(), xCenter + width / 2),
                        min(imageHeight.toFloat(), yCenter + height / 2)
                    )
                    
                    val status = when (bestClass) {
                        0 -> CouponStatus.COMPLETE
                        1 -> CouponStatus.PARTIAL_TOP
                        2 -> CouponStatus.PARTIAL_BOTTOM
                        else -> CouponStatus.COMPLETE
                    }
                    
                    detections.add(CouponDetection(boundingBox, maxConf, status))
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing detection $i", e)
                continue
            }
        }
        
        // Apply NMS
        return applyNMS(detections)
    }
    
    /**
     * Parse Stage 2 field detection results from YOLOv8 output
     */
    private fun parseFieldDetections(
        outputBuffer: ByteBuffer,
        outputShape: IntArray,
        imageWidth: Int,
        imageHeight: Int
    ): List<FieldDetection> {
        outputBuffer.rewind()
        val detections = mutableListOf<FieldDetection>()
        
        val numDetections = outputShape[2]
        val numClasses = stage2Classes.size
        val outputDim = outputShape[1]
        
        for (i in 0 until numDetections) {
            try {
                val baseIndex = i * outputDim
                
                val xCenter = outputBuffer.getFloat(baseIndex * 4) / STAGE2_INPUT_SIZE * imageWidth
                val yCenter = outputBuffer.getFloat((baseIndex + 1) * 4) / STAGE2_INPUT_SIZE * imageHeight
                val width = outputBuffer.getFloat((baseIndex + 2) * 4) / STAGE2_INPUT_SIZE * imageWidth
                val height = outputBuffer.getFloat((baseIndex + 3) * 4) / STAGE2_INPUT_SIZE * imageHeight
                
                var maxConf = 0f
                var bestClass = 0
                
                for (j in 0 until numClasses) {
                    val conf = outputBuffer.getFloat((baseIndex + 4 + j) * 4)
                    if (conf > maxConf) {
                        maxConf = conf
                        bestClass = j
                    }
                }
                
                if (maxConf > CONFIDENCE_THRESHOLD) {
                    val boundingBox = RectF(
                        max(0f, xCenter - width / 2),
                        max(0f, yCenter - height / 2),
                        min(imageWidth.toFloat(), xCenter + width / 2),
                        min(imageHeight.toFloat(), yCenter + height / 2)
                    )
                    
                    val fieldType = when (bestClass) {
                        0 -> FieldType.CODE_REGION
                        1 -> FieldType.BENEFIT_REGION
                        2 -> FieldType.EXPIRY_REGION
                        3 -> FieldType.APP_REGION
                        4 -> FieldType.TERMS_REGION
                        else -> FieldType.CODE_REGION
                    }
                    
                    detections.add(FieldDetection(fieldType, boundingBox, maxConf))
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing field detection $i", e)
                continue
            }
        }
        
        return detections
    }
    
    /**
     * Crop bitmap to specified rectangle with bounds checking
     */
    private fun cropBitmap(bitmap: Bitmap, rect: RectF): Bitmap {
        val left = max(0, rect.left.toInt())
        val top = max(0, rect.top.toInt())
        val right = min(bitmap.width, rect.right.toInt())
        val bottom = min(bitmap.height, rect.bottom.toInt())
        
        val width = right - left
        val height = bottom - top
        
        return if (width > 0 && height > 0) {
            Bitmap.createBitmap(bitmap, left, top, width, height)
        } else {
            // Return a small placeholder if crop is invalid
            Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        }
    }
    
    /**
     * Adjust field coordinates from crop space back to original image space
     */
    private fun adjustFieldCoordinates(fields: List<FieldDetection>, couponBox: RectF): List<FieldDetection> {
        return fields.map { field ->
            val adjustedBox = RectF(
                couponBox.left + field.boundingBox.left,
                couponBox.top + field.boundingBox.top,
                couponBox.left + field.boundingBox.right,
                couponBox.top + field.boundingBox.bottom
            )
            
            field.copy(boundingBox = adjustedBox)
        }
    }
    
    /**
     * Apply Non-Maximum Suppression to remove overlapping detections
     */
    private fun applyNMS(detections: List<CouponDetection>): List<CouponDetection> {
        if (detections.isEmpty()) return detections
        
        val sortedDetections = detections.sortedByDescending { it.confidence }
        val selectedDetections = mutableListOf<CouponDetection>()
        
        for (detection in sortedDetections) {
            var shouldAdd = true
            
            for (selectedDetection in selectedDetections) {
                val iou = calculateIoU(detection.boundingBox, selectedDetection.boundingBox)
                if (iou > IOU_THRESHOLD) {
                    shouldAdd = false
                    break
                }
            }
            
            if (shouldAdd) {
                selectedDetections.add(detection)
            }
            
            if (selectedDetections.size >= MAX_DETECTIONS) {
                break
            }
        }
        
        return selectedDetections
    }
    
    /**
     * Calculate Intersection over Union (IoU) between two rectangles
     */
    private fun calculateIoU(rect1: RectF, rect2: RectF): Float {
        val intersectionLeft = max(rect1.left, rect2.left)
        val intersectionTop = max(rect1.top, rect2.top)
        val intersectionRight = min(rect1.right, rect2.right)
        val intersectionBottom = min(rect1.bottom, rect2.bottom)
        
        if (intersectionLeft >= intersectionRight || intersectionTop >= intersectionBottom) {
            return 0f
        }
        
        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val rect1Area = (rect1.right - rect1.left) * (rect1.bottom - rect1.top)
        val rect2Area = (rect2.right - rect2.left) * (rect2.bottom - rect2.top)
        val unionArea = rect1Area + rect2Area - intersectionArea
        
        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }
    
    /**
     * Get model information
     */
    fun getModelInfo(): Map<String, Any> {
        return mapOf(
            "isInitialized" to isInitialized,
            "stage1Classes" to stage1Classes.toList(),
            "stage2Classes" to stage2Classes.toList(),
            "modelVersion" to (modelManifest?.optString("model_version") ?: "unknown"),
            "modelType" to (modelManifest?.optString("model_type") ?: "two_stage_yolo"),
            "capabilities" to mapOf(
                "singleCoupon" to true,
                "multiCoupon" to true,
                "partialCoupon" to true,
                "scrollableList" to true
            )
        )
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        try {
            stage1Interpreter?.close()
            stage2Interpreter?.close()
            stage1Interpreter = null
            stage2Interpreter = null
            isInitialized = false
            Log.d(TAG, "TwoStageDetector cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}

/**
 * Data classes for detection results
 */

/**
 * Represents a complete coupon instance with all its detected fields
 */
data class CouponInstance(
    val id: String,
    val boundingBox: RectF,
    val status: CouponStatus,
    val confidence: Float,
    val fields: List<FieldDetection>,
    val cropBitmap: Bitmap
) {
    fun getFieldByType(fieldType: FieldType): FieldDetection? {
        return fields.find { it.fieldType == fieldType }
    }
    
    fun hasRequiredFields(): Boolean {
        val requiredTypes = setOf(FieldType.CODE_REGION, FieldType.BENEFIT_REGION)
        return requiredTypes.all { requiredType ->
            fields.any { it.fieldType == requiredType }
        }
    }
}

/**
 * Represents a detected field within a coupon
 */
data class FieldDetection(
    val fieldType: FieldType,
    val boundingBox: RectF,
    val confidence: Float,
    val text: String? = null
)

/**
 * Represents a detected coupon boundary (Stage 1 result)
 */
data class CouponDetection(
    val boundingBox: RectF,
    val confidence: Float,
    val status: CouponStatus
)

/**
 * Coupon status enum
 */
enum class CouponStatus {
    COMPLETE,
    PARTIAL_TOP,
    PARTIAL_BOTTOM
}

/**
 * Field type enum with display names
 */
enum class FieldType(val id: Int, val displayName: String) {
    CODE_REGION(0, "Coupon Code"),
    BENEFIT_REGION(1, "Benefit/Offer"),
    EXPIRY_REGION(2, "Expiry Date"),
    APP_REGION(3, "App/Brand"),
    TERMS_REGION(4, "Terms & Conditions")
}
