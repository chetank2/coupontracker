package com.example.coupontracker.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
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
 * Manager for the Indian coupon recognition model
 */
class IndiaCouponModelManager(private val context: Context) {
    private var interpreter: Interpreter? = null
    private var modelConfig: JSONObject? = null
    private var inputSize: Int = 640
    private var classes: List<String> = emptyList()
    private var confidenceThreshold: Float = 0.25f
    private var iouThreshold: Float = 0.45f

    companion object {
        private const val TAG = "IndiaCouponModelManager"
        private const val MODEL_CONFIG_FILE = "models/india_coupon_model_config.json"
        private const val MODEL_FILE = "models/india_coupon_model.tflite"
    }

    /**
     * Initialize the model
     */
    fun initialize() {
        try {
            // Load model configuration
            val configJson = context.assets.open(MODEL_CONFIG_FILE).bufferedReader().use { it.readText() }
            modelConfig = JSONObject(configJson)

            // Extract configuration values
            inputSize = modelConfig?.optInt("input_size", 640) ?: 640
            classes = modelConfig?.optJSONArray("classes")?.let { array ->
                (0 until array.length()).map { array.getString(it) }
            } ?: emptyList()
            confidenceThreshold = modelConfig?.optDouble("confidence_threshold", 0.25)?.toFloat() ?: 0.25f
            iouThreshold = modelConfig?.optDouble("iou_threshold", 0.45)?.toFloat() ?: 0.45f

            // Load model file
            val modelFile = modelConfig?.optString("model_file")
            if (modelFile.isNullOrEmpty()) {
                Log.e(TAG, "Model file not specified in configuration")
                return
            }

            // Copy model file to cache directory
            val modelPath = copyAssetToCache(MODEL_FILE)
            if (modelPath == null) {
                Log.e(TAG, "Failed to copy model file to cache")
                return
            }

            // Create interpreter options
            val options = Interpreter.Options()
            
            // Create interpreter
            interpreter = Interpreter(modelPath, options)

            Log.i(TAG, "Model initialized successfully with ${classes.size} classes")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing model", e)
        }
    }

    /**
     * Copy an asset file to the cache directory
     */
    private fun copyAssetToCache(assetPath: String): File? {
        return try {
            val file = File(context.cacheDir, assetPath.substringAfterLast("/"))
            if (!file.exists()) {
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Error copying asset to cache: $assetPath", e)
            null
        }
    }

    /**
     * Recognize coupon fields in an image
     */
    fun recognizeCouponFields(bitmap: Bitmap): List<CouponField> {
        if (interpreter == null) {
            Log.e(TAG, "Model not initialized")
            return emptyList()
        }

        try {
            // Preprocess image
            val processedBitmap = ImageUtils.resizeBitmap(bitmap, inputSize, inputSize)
            val inputBuffer = preprocessImage(processedBitmap)

            // Run inference
            val outputBuffer = runInference(inputBuffer)

            // Process results
            val detections = processResults(outputBuffer, bitmap.width, bitmap.height)

            // Convert to CouponField objects
            return detections.map { detection ->
                val fieldType = when (classes[detection.classId]) {
                    "store_name" -> FieldType.STORE_NAME
                    "coupon_code" -> FieldType.COUPON_CODE
                    "expiry_date" -> FieldType.EXPIRY_DATE
                    "description" -> FieldType.DESCRIPTION
                    "amount" -> FieldType.AMOUNT
                    "min_purchase" -> FieldType.MIN_PURCHASE
                    else -> FieldType.OTHER
                }

                CouponField(
                    type = fieldType,
                    boundingBox = detection.boundingBox,
                    confidence = detection.confidence,
                    text = extractTextFromRegion(bitmap, detection.boundingBox)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recognizing coupon fields", e)
            return emptyList()
        }
    }

    /**
     * Extract text from a region of the image
     */
    private fun extractTextFromRegion(bitmap: Bitmap, boundingBox: RectF): String {
        // In a real implementation, you would use OCR to extract text from this region
        // For now, we'll return an empty string
        return ""
    }

    /**
     * Preprocess image for the model
     */
    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val inputBuffer = ByteBuffer.allocateDirect(1 * 3 * inputSize * inputSize * 4)
        inputBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (i in 0 until inputSize * inputSize) {
            val pixel = pixels[i]
            // Extract RGB values and normalize to [0, 1]
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)
        }

        inputBuffer.rewind()
        return inputBuffer
    }

    /**
     * Run inference with the model
     */
    private fun runInference(inputBuffer: ByteBuffer): FloatBuffer {
        // Create output buffer (25200 detections with 11 values each: 4 for bbox, 1 for confidence, 6 for class probabilities)
        val outputSize = 1 * 25200 * (5 + classes.size)
        val outputBuffer = FloatBuffer.allocate(outputSize)
        
        // Run inference
        interpreter?.run(inputBuffer, outputBuffer)
        
        outputBuffer.rewind()
        return outputBuffer
    }

    /**
     * Process model results
     */
    private fun processResults(outputBuffer: FloatBuffer, originalWidth: Int, originalHeight: Int): List<Detection> {
        val numDetections = 25200 // Default for YOLOv5s
        val numValues = 5 + classes.size
        val detections = mutableListOf<Detection>()

        // Extract detections
        for (i in 0 until numDetections) {
            val offset = i * numValues
            val confidence = outputBuffer.get(offset + 4)

            if (confidence > confidenceThreshold) {
                // Find class with highest probability
                var maxClassProb = 0f
                var classId = 0

                for (c in 0 until classes.size) {
                    val classProb = outputBuffer.get(offset + 5 + c)
                    if (classProb > maxClassProb) {
                        maxClassProb = classProb
                        classId = c
                    }
                }

                val score = confidence * maxClassProb
                if (score > confidenceThreshold) {
                    // Get bounding box coordinates (xywh format)
                    val x = outputBuffer.get(offset)
                    val y = outputBuffer.get(offset + 1)
                    val w = outputBuffer.get(offset + 2)
                    val h = outputBuffer.get(offset + 3)

                    // Convert to pixel coordinates and adjust to original image size
                    val xScale = originalWidth.toFloat() / inputSize
                    val yScale = originalHeight.toFloat() / inputSize

                    val left = (x - w / 2) * xScale
                    val top = (y - h / 2) * yScale
                    val right = (x + w / 2) * xScale
                    val bottom = (y + h / 2) * yScale

                    detections.add(
                        Detection(
                            classId = classId,
                            confidence = score,
                            boundingBox = RectF(left, top, right, bottom)
                        )
                    )
                }
            }
        }

        // Apply non-maximum suppression
        return nonMaxSuppression(detections, iouThreshold)
    }

    /**
     * Apply non-maximum suppression to remove overlapping detections
     */
    private fun nonMaxSuppression(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        // Sort detections by confidence (descending)
        val sortedDetections = detections.sortedByDescending { it.confidence }
        val selectedDetections = mutableListOf<Detection>()

        // Apply NMS
        for (detection in sortedDetections) {
            var shouldSelect = true

            // Check if this detection overlaps with any selected detection
            for (selectedDetection in selectedDetections) {
                if (selectedDetection.classId == detection.classId) {
                    val iou = calculateIoU(detection.boundingBox, selectedDetection.boundingBox)
                    if (iou > iouThreshold) {
                        shouldSelect = false
                        break
                    }
                }
            }

            if (shouldSelect) {
                selectedDetections.add(detection)
            }
        }

        return selectedDetections
    }

    /**
     * Calculate Intersection over Union (IoU) between two bounding boxes
     */
    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = max(box1.left, box2.left)
        val intersectionTop = max(box1.top, box2.top)
        val intersectionRight = min(box1.right, box2.right)
        val intersectionBottom = min(box1.bottom, box2.bottom)

        if (intersectionLeft >= intersectionRight || intersectionTop >= intersectionBottom) {
            return 0f
        }

        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val box1Area = box1.width() * box1.height()
        val box2Area = box2.width() * box2.height()
        val unionArea = box1Area + box2Area - intersectionArea

        return intersectionArea / unionArea
    }

    /**
     * Release resources
     */
    fun close() {
        interpreter?.close()
        interpreter = null
    }

    /**
     * Detection result
     */
    data class Detection(
        val classId: Int,
        val confidence: Float,
        val boundingBox: RectF
    )
}
