package com.example.coupontracker.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Parcelable
import android.util.DisplayMetrics
import android.util.Log
import androidx.annotation.VisibleForTesting
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
import kotlin.math.roundToInt
import kotlinx.parcelize.Parcelize

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
class TwoStageDetector(private val context: Context, initializeOnCreate: Boolean = true) {
    
    companion object {
        private const val TAG = "TwoStageDetector"
        private const val STAGE1_INPUT_SIZE = 640
        private const val STAGE2_INPUT_SIZE = 320
        private const val CONFIDENCE_THRESHOLD = 0.5f
        private const val IOU_THRESHOLD = 0.4f
        private const val MAX_DETECTIONS = 50
        private const val DEFAULT_CROP_PADDING_DP = 12f
        
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
    private var stubMode: Boolean = false
    private var demoMode: Boolean = false
    private val stage1Classes = arrayOf("coupon_complete", "coupon_partial_top", "coupon_partial_bottom")
    private val stage2Classes = arrayOf("code_region", "benefit_region", "expiry_region", "app_region", "terms_region")
    
    // Initialization state
    private var isInitialized = false
    
    init {
        if (initializeOnCreate) {
            initializeModels()
        }
    }
    
    /**
     * Initialize both stage models and processors
     */
    private fun initializeModels() {
        try {
            Log.d(TAG, "Initializing two-stage detection models...")
            
            // Load model manifest
            loadModelManifest()
            
            if (stubMode) {
                Log.w(
                    TAG,
                    "Multi-coupon manifest is marked as stub_mode. Skipping TensorFlow Lite interpreter initialization. " +
                        "Replace the placeholder assets with trained binaries for production use."
                )
                stage1Interpreter = null
                stage2Interpreter = null
                initializeImageProcessors()
                isInitialized = true
                return
            }

            // Try to load models, but fallback gracefully if they're placeholders
            var modelsLoaded = false
            try {
                // Load Stage 1 Model (Coupon Detection)
                loadStage1Model()

                // Load Stage 2 Model (Field Detection)
                loadStage2Model()
                
                modelsLoaded = true
                Log.i(TAG, "Production models loaded successfully")
                
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load production models (likely placeholders): ${e.message}")
                
                if (demoMode) {
                    Log.i(TAG, "Demo mode enabled - continuing with placeholder models for synthetic detections")
                    stage1Interpreter = null
                    stage2Interpreter = null
                    modelsLoaded = true // Allow demo mode to work
                } else {
                    Log.e(TAG, "No demo mode fallback available, initialization failed")
                    throw e
                }
            }

            if (modelsLoaded) {
                // Initialize image processors
                initializeImageProcessors()
                isInitialized = true
                
                if (demoMode) {
                    Log.i(TAG, "Two-stage detector initialized in demo mode")
                } else {
                    Log.i(TAG, "Two-stage detector initialized with production models")
                }
            }
            
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
            
            stubMode = modelManifest?.optBoolean("stub_mode", false) ?: false
            demoMode = modelManifest?.optBoolean("demo_mode", false) ?: false

            Log.d(TAG, "Model manifest loaded - Version: $version, Type: $modelType, Stub: $stubMode, Demo: $demoMode")
            
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
        
        if (demoMode) {
            if (stubMode) {
                Log.i(
                    TAG,
                    "TwoStageDetector is running in demo mode with stub assets; returning synthetic detections."
                )
            } else {
                Log.i(TAG, "TwoStageDetector is running in demo mode; returning synthetic detections.")
            }
            return createDemoDetections(bitmap)
        }

        if (stubMode) {
            Log.w(TAG, "TwoStageDetector is running in stub mode; multi-coupon detections are disabled.")
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
                    val cropResult = cropBitmap(bitmap, couponDetection.boundingBox)
                    val couponCrop = cropResult.bitmap
                    
                    if (couponCrop.width < 10 || couponCrop.height < 10) {
                        Log.w(TAG, "Coupon crop too small, skipping instance $index")
                        return@forEachIndexed
                    }
                    
                    // Detect fields in this coupon crop
                    val fieldDetections = detectFieldsInCoupon(couponCrop)
                    
                    // Adjust field coordinates back to original image space
                    val adjustedFields = adjustFieldCoordinates(
                        fieldDetections,
                        couponDetection.boundingBox,
                        cropResult.padding
                    )

                    couponInstances.add(
                        CouponInstance(
                            id = "coupon_${System.currentTimeMillis()}_$index",
                            boundingBox = couponDetection.boundingBox,
                            status = couponDetection.status,
                            confidence = couponDetection.confidence,
                            fields = adjustedFields,
                            cropBitmap = couponCrop,
                            cropPadding = cropResult.padding
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
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun cropBitmap(
        bitmap: Bitmap,
        rect: RectF,
        paddingDp: Float = DEFAULT_CROP_PADDING_DP
    ): CropResult {
        val density = if (bitmap.density > 0) bitmap.density else DisplayMetrics.DENSITY_DEFAULT
        val densityScale = density / DisplayMetrics.DENSITY_DEFAULT.toFloat()
        val paddingPx = (paddingDp * densityScale).roundToInt()

        val expandedLeft = rect.left - paddingPx
        val expandedTop = rect.top - paddingPx
        val expandedRight = rect.right + paddingPx
        val expandedBottom = rect.bottom + paddingPx

        val clampedLeft = max(0f, expandedLeft)
        val clampedTop = max(0f, expandedTop)
        val clampedRight = min(bitmap.width.toFloat(), expandedRight)
        val clampedBottom = min(bitmap.height.toFloat(), expandedBottom)

        val left = clampedLeft.toInt()
        val top = clampedTop.toInt()
        val right = clampedRight.toInt()
        val bottom = clampedBottom.toInt()

        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) {
            return CropResult(
                bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888),
                padding = CropPadding.ZERO
            )
        }

        val crop = Bitmap.createBitmap(bitmap, left, top, width, height)

        val paddingLeft = (rect.left - left).coerceAtLeast(0f)
        val paddingTop = (rect.top - top).coerceAtLeast(0f)
        val paddingRight = (right - rect.right).coerceAtLeast(0f)
        val paddingBottom = (bottom - rect.bottom).coerceAtLeast(0f)

        return CropResult(
            bitmap = crop,
            padding = CropPadding(
                left = paddingLeft,
                top = paddingTop,
                right = paddingRight,
                bottom = paddingBottom
            )
        )
    }

    /**
     * Adjust field coordinates from crop space back to original image space
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun adjustFieldCoordinates(
        fields: List<FieldDetection>,
        couponBox: RectF,
        padding: CropPadding
    ): List<FieldDetection> {
        val offsetLeft = couponBox.left - padding.left
        val offsetTop = couponBox.top - padding.top

        return fields.map { field ->
            val adjustedBox = RectF(
                offsetLeft + field.boundingBox.left,
                offsetTop + field.boundingBox.top,
                offsetLeft + field.boundingBox.right,
                offsetTop + field.boundingBox.bottom
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
    }
}

/**
 * Data classes for detection results
 */

/**
 * Represents the bitmap crop result and the padding that was applied.
 */
data class CropResult(
    val bitmap: Bitmap,
    val padding: CropPadding
)

/**
 * Metadata describing how much padding was applied around a crop on each edge.
 */
@Parcelize
data class CropPadding(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) : Parcelable {
    companion object {
        val ZERO = CropPadding(0f, 0f, 0f, 0f)
    }
}

/**
 * Represents a complete coupon instance with all its detected fields
 */
@Parcelize
data class CouponInstance(
    val id: String,
    val boundingBox: RectF,
    val status: CouponStatus,
    val confidence: Float,
    val fields: List<FieldDetection>,
    val cropBitmap: Bitmap,
    val cropPadding: CropPadding = CropPadding.ZERO
) : Parcelable {
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
@Parcelize
data class FieldDetection(
    val fieldType: FieldType,
    val boundingBox: RectF,
    val confidence: Float,
    val text: String? = null
) : Parcelable

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
