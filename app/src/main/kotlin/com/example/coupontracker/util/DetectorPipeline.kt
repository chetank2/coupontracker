package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight detector pipeline that reads the active model bundle via [ModelManager], performs
 * preprocessing, runs the TFLite detector, and post-processes the raw outputs into bounding boxes.
 * If inference fails for any reason, the pipeline falls back to returning a single ROI covering the
 * whole image so downstream OCR can still proceed.
 */
class DetectorPipeline(private val context: Context) : Closeable {

    data class Detection(
        val boundingBox: RectF,
        val label: String,
        val confidence: Float
    )

    private data class PreprocessResult(
        val buffer: ByteBuffer,
        val scale: Float,
        val padX: Float,
        val padY: Float,
        val inputWidth: Int,
        val inputHeight: Int
    )

    private val modelManager = ModelManager.getInstance(context)
    private val loggerTag = "DetectorPipeline"

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var inputWidth: Int = 640
    private var inputHeight: Int = 640
    private var mean: FloatArray = floatArrayOf(0f, 0f, 0f)
    private var std: FloatArray = floatArrayOf(1f, 1f, 1f)
    private var paddingColor: IntArray = intArrayOf(114, 114, 114)
    private var confidenceThreshold: Float = 0.25f
    private var iouThreshold: Float = 0.45f
    private var maxDetections: Int = 100

    private val bundleListener = ModelManager.ModelBundleListener { bundle ->
        Log.d(loggerTag, "Bundle activated: ${bundle.name} ${bundle.version}")
        loadBundle(bundle)
    }

    init {
        modelManager.addListener(bundleListener)
        loadBundle(modelManager.active())
    }

    private fun loadBundle(bundle: ModelManager.ModelBundle) {
        synchronized(this) {
            runCatching { interpreter?.close() }
            interpreter = null

            try {
                labels = readLabels(bundle)
                readPreprocessConfig(bundle)
                readPostprocessConfig(bundle)
                interpreter = createInterpreter(bundle)
                Log.d(
                    loggerTag,
                    "Detector initialized (${bundle.name} ${bundle.version.raw}) with ${labels.size} labels"
                )
            } catch (t: Throwable) {
                Log.e(loggerTag, "Failed to load detector bundle", t)
            }
        }
    }

    private fun readLabels(bundle: ModelManager.ModelBundle): List<String> {
        return try {
            modelManager.openFile(bundle, ModelFile.LABELS).bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                val labelsArray = json.optJSONArray("labels") ?: JSONArray()
                buildList {
                    for (i in 0 until labelsArray.length()) {
                        val entry = labelsArray.getJSONObject(i)
                        add(entry.optString("name", "label_$i"))
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(loggerTag, "Unable to read labels", t)
            listOf("coupon")
        }
    }

    private fun readPreprocessConfig(bundle: ModelManager.ModelBundle) {
        try {
            modelManager.openFile(bundle, ModelFile.PREPROCESS).bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                val shapeArray = json.optJSONArray("input_shape")
                if (shapeArray != null && shapeArray.length() >= 4) {
                    inputWidth = shapeArray.getInt(shapeArray.length() - 2)
                    inputHeight = shapeArray.getInt(shapeArray.length() - 3)
                }
                val normObj = json.optJSONObject("normalization")
                if (normObj != null) {
                    mean = normObj.optJSONArray("mean")?.toFloatArray(3) ?: mean
                    std = normObj.optJSONArray("std")?.toFloatArray(3) ?: std
                }
                val padding = json.optJSONObject("scaling")?.optJSONArray("padding_color")
                if (padding != null && padding.length() >= 3) {
                    paddingColor = intArrayOf(padding.getInt(0), padding.getInt(1), padding.getInt(2))
                }
            }
        } catch (t: Throwable) {
            Log.w(loggerTag, "Preprocess configuration read failed, using defaults", t)
        }
    }

    private fun readPostprocessConfig(bundle: ModelManager.ModelBundle) {
        try {
            modelManager.openFile(bundle, ModelFile.POSTPROCESS).bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                confidenceThreshold = json.optDouble("confidence_threshold", confidenceThreshold.toDouble()).toFloat()
                iouThreshold = json.optDouble("iou_threshold", iouThreshold.toDouble()).toFloat()
                maxDetections = json.optInt("max_detections", maxDetections)
            }
        } catch (t: Throwable) {
            Log.w(loggerTag, "Postprocess configuration read failed, using defaults", t)
        }
    }

    private fun createInterpreter(bundle: ModelManager.ModelBundle): Interpreter? {
        return try {
            modelManager.openFile(bundle, ModelFile.MODEL).use { inputStream ->
                val bytes = inputStream.readBytes()
                val modelBuffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                modelBuffer.put(bytes)
                modelBuffer.rewind()
                Interpreter(modelBuffer, Interpreter.Options().apply {
                    setNumThreads(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
                })
            }
        } catch (t: Throwable) {
            Log.e(loggerTag, "Failed to create interpreter", t)
            null
        }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val interpreter = this.interpreter ?: return fallbackDetection(bitmap)
        return synchronized(this) {
            runCatching {
                val preprocessStart = SystemClock.elapsedRealtimeNanos()
                val preprocessResult = preprocess(bitmap)
                val preprocessMs = (SystemClock.elapsedRealtimeNanos() - preprocessStart) / 1_000_000

                val inferenceStart = SystemClock.elapsedRealtimeNanos()
                val detections = runInterpreter(interpreter, preprocessResult, bitmap.width, bitmap.height)
                val inferenceMs = (SystemClock.elapsedRealtimeNanos() - inferenceStart) / 1_000_000

                Log.d(
                    loggerTag,
                    "Detector inference completed with ${detections.size} ROI(s) in preprocess=${preprocessMs}ms, infer=${inferenceMs}ms"
                )

                detections.ifEmpty { fallbackDetection(bitmap) }
            }.getOrElse { throwable ->
                Log.e(loggerTag, "Detector inference failed", throwable)
                fallbackDetection(bitmap)
            }
        }
    }

    private fun preprocess(bitmap: Bitmap): PreprocessResult {
        val resized = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resized)
        canvas.drawColor(Color.rgb(paddingColor[0], paddingColor[1], paddingColor[2]))

        val scale = min(inputWidth.toFloat() / bitmap.width, inputHeight.toFloat() / bitmap.height)
        val scaledWidth = (bitmap.width * scale).toInt()
        val scaledHeight = (bitmap.height * scale).toInt()
        val padX = (inputWidth - scaledWidth) / 2f
        val padY = (inputHeight - scaledHeight) / 2f

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val matrix = android.graphics.Matrix().apply {
            postScale(scale, scale)
        }
        val scaledBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        canvas.drawBitmap(scaledBitmap, padX, padY, paint)

        val buffer = ByteBuffer.allocateDirect(4 * inputWidth * inputHeight * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputWidth * inputHeight)
        resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        for (y in 0 until inputHeight) {
            for (x in 0 until inputWidth) {
                val pixel = pixels[y * inputWidth + x]
                val r = ((pixel shr 16) and 0xFF).toFloat()
                val g = ((pixel shr 8) and 0xFF).toFloat()
                val b = (pixel and 0xFF).toFloat()
                buffer.putFloat((r - mean[0]) / std[0])
                buffer.putFloat((g - mean[1]) / std[1])
                buffer.putFloat((b - mean[2]) / std[2])
            }
        }
        buffer.rewind()

        return PreprocessResult(buffer, scale, padX, padY, inputWidth, inputHeight)
    }

    private fun runInterpreter(
        interpreter: Interpreter,
        preprocess: PreprocessResult,
        originalWidth: Int,
        originalHeight: Int
    ): List<Detection> {
        val outputTensor = interpreter.getOutputTensor(0)
        val outputShape = outputTensor.shape()
        val resultElements = outputTensor.numElements()
        val outputBuffer = ByteBuffer.allocateDirect(4 * resultElements).order(ByteOrder.nativeOrder())

        interpreter.run(preprocess.buffer, outputBuffer)
        outputBuffer.rewind()
        val floatBuffer = outputBuffer.asFloatBuffer()
        val floats = FloatArray(floatBuffer.capacity())
        floatBuffer.get(floats)

        val numDetections: Int
        val valuesPerDetection: Int
        when (outputShape.size) {
            2 -> {
                numDetections = outputShape[0]
                valuesPerDetection = outputShape[1]
            }
            3 -> {
                numDetections = outputShape[outputShape.size - 2]
                valuesPerDetection = outputShape.last()
            }
            else -> {
                Log.w(loggerTag, "Unexpected detector output shape: ${outputShape.contentToString()}")
                return emptyList()
            }
        }

        val numClasses = (valuesPerDetection - 5).coerceAtLeast(1)
        val detected = mutableListOf<Detection>()

        for (i in 0 until min(numDetections, maxDetections)) {
            val offset = i * valuesPerDetection
            if (offset + valuesPerDetection > floats.size) break

            val rawCx = floats[offset]
            val rawCy = floats[offset + 1]
            val rawW = floats[offset + 2]
            val rawH = floats[offset + 3]
            val objectness = floats[offset + 4]

            if (objectness < confidenceThreshold) continue

            var bestClassIdx = 0
            var bestClassScore = 0f
            for (c in 0 until numClasses) {
                val classScore = floats[offset + 5 + c]
                if (classScore > bestClassScore) {
                    bestClassScore = classScore
                    bestClassIdx = c
                }
            }
            val confidence = objectness * bestClassScore
            if (confidence < confidenceThreshold) continue

            val box = decodeBoundingBox(rawCx, rawCy, rawW, rawH, preprocess)
            val rect = projectToOriginal(box, preprocess, originalWidth, originalHeight)
            if (rect.width() <= 1f || rect.height() <= 1f) continue

            val label = labels.getOrNull(bestClassIdx) ?: "coupon"
            detected.add(Detection(rect, label, confidence.coerceIn(0f, 1f)))
        }

        return applyNms(detected, iouThreshold)
    }

    private fun decodeBoundingBox(
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        preprocess: PreprocessResult
    ): RectF {
        val inputW = preprocess.inputWidth.toFloat()
        val inputH = preprocess.inputHeight.toFloat()

        val absCx = if (cx > 1f) cx else cx * inputW
        val absCy = if (cy > 1f) cy else cy * inputH
        val absW = if (w > 1f) w else w * inputW
        val absH = if (h > 1f) h else h * inputH

        val left = absCx - absW / 2f
        val top = absCy - absH / 2f
        val right = absCx + absW / 2f
        val bottom = absCy + absH / 2f
        return RectF(left, top, right, bottom)
    }

    private fun projectToOriginal(
        rect: RectF,
        preprocess: PreprocessResult,
        originalWidth: Int,
        originalHeight: Int
    ): RectF {
        val scale = preprocess.scale
        val padX = preprocess.padX
        val padY = preprocess.padY

        val left = ((rect.left - padX) / scale).coerceIn(0f, originalWidth.toFloat())
        val top = ((rect.top - padY) / scale).coerceIn(0f, originalHeight.toFloat())
        val right = ((rect.right - padX) / scale).coerceIn(0f, originalWidth.toFloat())
        val bottom = ((rect.bottom - padY) / scale).coerceIn(0f, originalHeight.toFloat())
        return RectF(left, top, max(right, left + 1f), max(bottom, top + 1f))
    }

    private fun applyNms(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        if (detections.isEmpty()) return detections
        val sorted = detections.sortedByDescending { it.confidence }
        val kept = mutableListOf<Detection>()

        val suppressed = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            val detection = sorted[i]
            kept.add(detection)
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                if (iou(detection.boundingBox, sorted[j].boundingBox) > iouThreshold) {
                    suppressed[j] = true
                }
            }
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val intersection = RectF().apply { setIntersect(a, b) }
        val intersectionArea = max(0f, intersection.width()) * max(0f, intersection.height())
        if (intersectionArea <= 0f) return 0f
        val unionArea = a.width() * a.height() + b.width() * b.height() - intersectionArea
        return if (unionArea <= 0f) 0f else intersectionArea / unionArea
    }

    private fun fallbackDetection(bitmap: Bitmap): List<Detection> {
        return listOf(
            Detection(
                boundingBox = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
                label = "coupon",
                confidence = 0.5f
            )
        )
    }

    override fun close() {
        synchronized(this) {
            runCatching { interpreter?.close() }
            interpreter = null
            runCatching { modelManager.removeListener(bundleListener) }
        }
    }

    private fun JSONArray.toFloatArray(expectedSize: Int): FloatArray {
        val array = FloatArray(expectedSize)
        for (i in 0 until min(length(), expectedSize)) {
            array[i] = getDouble(i).toFloat()
        }
        return array
    }
}
