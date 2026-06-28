package com.example.coupontracker.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tesseract-based OCR engine implementation
 * Fully offline, no network dependencies
 */
@Singleton
class TesseractOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : OcrEngine {
    
    companion object {
        private const val TAG = "TesseractOcrEngine"
        private const val TESSDATA_DIR = "tessdata"
        private const val LANG = "eng"
        private const val TRAINED_DATA_FILE = "$LANG.traineddata"
        private const val SENTINEL_FILE = "eng.traineddata.version"
        private const val TRAINED_DATA_VERSION = "tess-two-9.1.0-eng"
        private const val TRAINED_DATA_CHECKSUM = "8280aed0782fe27257a68ea10fe7ef324ca0f8d85bd2fd145d1c2b560bcb66ba"
        private const val MAX_INIT_RETRIES = 3
        private val RETRY_BACKOFF_MS = longArrayOf(200L, 600L, 1200L)

        // Shared lock for thread-safe initialization
        private val initLock = Any()
    }

    private var tessBaseAPI: TessBaseAPI? = null
    private val tessDataDir = File(context.filesDir, TESSDATA_DIR)
    private val tessDataInstaller = TessDataInstaller(
        assetOpener = { context.assets.open("$TESSDATA_DIR/$TRAINED_DATA_FILE") },
        tessDataDir = tessDataDir,
        trainedDataFilename = TRAINED_DATA_FILE,
        sentinelFilename = SENTINEL_FILE,
        expectedChecksum = TRAINED_DATA_CHECKSUM,
        expectedVersion = TRAINED_DATA_VERSION,
        logger = TessDataInstaller.Logger(
            debug = { message -> Log.d(TAG, message) },
            info = { message -> Log.i(TAG, message) },
            warn = { message -> Log.w(TAG, message) },
            error = { message, throwable -> Log.e(TAG, message, throwable) }
        )
    )
    private val nativeLogDir = File(context.filesDir, "tesseract")
    private val nativeLogFile = File(nativeLogDir, "native.log")
    @Volatile
    private var lastInitStats: InitStats = InitStats()
    private var initAttemptCounter: Int = 0
    @Volatile
    private var isInitialized = false

    init {
        // Synchronize initialization across all threads
        synchronized(initLock) {
            if (!isInitialized) {
                initializeTesseract()
            }
        }
    }
    
    @Synchronized
    private fun initializeTesseract(): Boolean {
        if (isInitialized) {
            Log.d(TAG, "Already initialized, skipping")
            return true
        }

        val attemptNumber = ++initAttemptCounter
        var installResult: TessDataInstaller.Result? = null

        try {
            Log.d(TAG, "🔧 Initializing Tesseract OCR [attempt=$attemptNumber, thread=${Thread.currentThread().name}]")

            installResult = tessDataInstaller.installIfNeeded()
            Log.d(
                TAG,
                "eng.traineddata ready (copied=${installResult.copied}, checksum=${installResult.installedChecksum})"
            )

            prepareNativeLogFile()

            val dataPath = context.filesDir.absolutePath
            tessBaseAPI = TessBaseAPI().apply {
                setDebug(true)
                val success = init(dataPath, LANG)
                if (!success) {
                    val errorMsg = "Tesseract init() returned false for attempt $attemptNumber"
                    Log.e(TAG, errorMsg)
                    throw IllegalStateException(errorMsg)
                }

                try {
                    setVariable("debug_file", nativeLogFile.absolutePath)
                } catch (ignored: Exception) {
                    Log.w(TAG, "Unable to set debug_file for native logging", ignored)
                }

                pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                Log.d(TAG, "✅ Tesseract initialized successfully [PSM: PSM_AUTO]")
            }

            isInitialized = true
            lastInitStats = InitStats(
                attempt = attemptNumber,
                success = true,
                assetChecksum = installResult.assetChecksum,
                installedChecksum = installResult.installedChecksum,
                copiedTrainedData = installResult.copied,
                nativeLogTail = readNativeLogTail()
            )
            return true
        } catch (e: Exception) {
            val nativeTail = readNativeLogTail()
            lastInitStats = InitStats(
                attempt = attemptNumber,
                success = false,
                assetChecksum = installResult?.assetChecksum,
                installedChecksum = installResult?.installedChecksum,
                copiedTrainedData = installResult?.copied ?: false,
                errorMessage = e.message,
                nativeLogTail = nativeTail
            )
            Log.e(TAG, "❌ Failed to initialize Tesseract - will use ML Kit as fallback", e)
            nativeTail?.let { Log.e(TAG, "Native log tail:\n$it") }
            tessBaseAPI?.end()
            tessBaseAPI = null
            isInitialized = false
            return false
        }
    }

    override suspend fun recognize(bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        val api = try {
            requireApiWithRetry()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to obtain Tesseract instance after retries", e)
            throw e
        }

        return@withContext try {
            api.setImage(bitmap)
            val text = api.utF8Text ?: ""
            Log.d(TAG, "Extracted ${text.length} chars")
            text
        } catch (e: Exception) {
            Log.e(TAG, "OCR extraction failed", e)
            ""
        }
    }
    
    override suspend fun recognizeWithBoxes(bitmap: Bitmap): List<OcrTextSpan> = withContext(Dispatchers.Default) {
        val api = try {
            requireApiWithRetry()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to obtain Tesseract instance after retries", e)
            throw e
        }

        return@withContext try {
            api.setImage(bitmap)
            val iterator = api.resultIterator
            if (iterator == null) {
                Log.w(TAG, "Tesseract result iterator unavailable")
                return@withContext emptyList()
            }

            val spans = mutableListOf<OcrTextSpan>()
            val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
            try {
                iterator.begin()
                do {
                    val text = iterator.getUTF8Text(level)?.trim().orEmpty()
                    val box = iterator.getBoundingRect(level)
                    if (text.isNotBlank() && box.width() > 0 && box.height() > 0) {
                        spans.add(
                            OcrTextSpan(
                                text = text,
                                boundingBox = Rect(box.left, box.top, box.right, box.bottom),
                                confidence = (iterator.confidence(level) / 100f).coerceIn(0f, 1f)
                            )
                        )
                    }
                } while (iterator.next(level))
            } finally {
                iterator.delete()
            }

            Log.d(TAG, "Extracted ${spans.size} boxed text spans")
            spans
        } catch (e: Exception) {
            Log.e(TAG, "OCR bounding box extraction failed", e)
            emptyList()
        } finally {
            runCatching { api.clear() }
        }
    }
    
    override fun isReady(): Boolean = isInitialized && tessBaseAPI != null
    
    override fun release() {
        tessBaseAPI?.end()
        tessBaseAPI = null
        isInitialized = false
        Log.d(TAG, "Tesseract resources released")
    }

    fun lastInitializationStats(): InitStats = lastInitStats

    private suspend fun requireApiWithRetry(): TessBaseAPI {
        var attempt = 0
        var lastError: String? = null
        while (attempt < MAX_INIT_RETRIES) {
            tessBaseAPI?.let { return it }
            attempt++
            withContext(Dispatchers.IO) {
                synchronized(initLock) {
                    if (!isInitialized) {
                        val success = initializeTesseract()
                        if (!success) {
                            lastError = lastInitStats.errorMessage
                        }
                    }
                }
            }
            tessBaseAPI?.let { return it }
            if (attempt < MAX_INIT_RETRIES) {
                val backoff = RETRY_BACKOFF_MS.getOrElse(attempt - 1) { RETRY_BACKOFF_MS.last() }
                Log.w(TAG, "Tesseract unavailable (attempt=$attempt, error=$lastError). Retrying in ${backoff}ms")
                delay(backoff)
            }
        }
        throw IllegalStateException("Tesseract OCR not available after $attempt attempts. Last error: ${lastError ?: "unknown"}")
    }

    private fun prepareNativeLogFile() {
        nativeLogDir.mkdirs()
        if (nativeLogFile.exists()) {
            try {
                nativeLogFile.writeText("")
            } catch (io: IOException) {
                Log.w(TAG, "Unable to clear native log file", io)
            }
        }
    }

    private fun readNativeLogTail(maxChars: Int = 1200): String? {
        if (!nativeLogFile.exists() || nativeLogFile.length() == 0L) {
            return null
        }
        return try {
            val content = nativeLogFile.readText()
            if (content.length <= maxChars) content else content.takeLast(maxChars)
        } catch (io: IOException) {
            Log.w(TAG, "Unable to read native log file", io)
            null
        }
    }

    data class InitStats(
        val attempt: Int = 0,
        val success: Boolean = false,
        val assetChecksum: String? = null,
        val installedChecksum: String? = null,
        val copiedTrainedData: Boolean = false,
        val errorMessage: String? = null,
        val nativeLogTail: String? = null,
        val timestampMs: Long = System.currentTimeMillis()
    )
}
