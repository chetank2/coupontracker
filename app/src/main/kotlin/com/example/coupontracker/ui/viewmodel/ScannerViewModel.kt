package com.example.coupontracker.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.data.util.CouponDedupUtils
import com.example.coupontracker.ml.TwoStageDetector
import com.example.coupontracker.ml.CouponInstance
import com.example.coupontracker.util.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    application: Application,
    @ApplicationContext private val context: Context,
    private val couponRepository: CouponRepository,
    private val localLlmOcrService: LocalLlmOcrService,
    private val telemetryService: ExtractionTelemetryService
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<ScannerUiState>(ScannerUiState.Initial)
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val multiEngineOCR: MultiEngineOCR = MultiEngineOCR(context)
    private val twoStageDetector: TwoStageDetector = TwoStageDetector(context)
    private val uriPersistenceManager = UriPersistenceManager(context)
    private val fieldHeuristics: GenericFieldHeuristics = GenericFieldHeuristics
    private val manualOverrides = mutableMapOf<String, CouponInstance>()
    private var pendingPreview: PendingPreview? = null

    companion object {
        private const val TAG = "ScannerViewModel"
    }

    init {
        // Assume network is available by default
        multiEngineOCR.setNetworkAvailability(true)
        
        // Log detector initialization
        val modelInfo = twoStageDetector.getModelInfo()
        Log.d(TAG, "TwoStageDetector initialized: $modelInfo")
    }

    /**
     * Enhanced scan method that uses two-stage detection for multi-coupon support
     */
    fun scanImage(imageUri: Uri, persistImmediately: Boolean = true) {
        viewModelScope.launch {
            try {
                _uiState.value = ScannerUiState.Scanning
                Log.d(TAG, "Starting enhanced multi-coupon scan for: $imageUri")

                // Load bitmap from URI
                val bitmap = loadBitmapFromUri(imageUri) ?: run {
                    _uiState.value = ScannerUiState.Error("Could not load image")
                    return@launch
                }

                // Run two-stage detection
                val couponInstances = withContext(Dispatchers.IO) {
                    twoStageDetector.detectMultiCoupons(bitmap)
                }

                Log.d(TAG, "Two-stage detection completed: ${couponInstances.size} coupons detected")

                when {
                    couponInstances.isEmpty() -> {
                        // Fallback to traditional OCR if no coupons detected
                        Log.d(TAG, "No coupons detected, falling back to traditional OCR")
                        fallbackToTraditionalOCR(imageUri)
                    }
                    couponInstances.size == 1 -> {
                        // Single coupon detected - process directly
                        val couponInstance = couponInstances.first()
                        processSingleCoupon(
                            couponInstance = couponInstance,
                            imageUri = imageUri.toString(),
                            persistImmediately = persistImmediately
                        )
                    }
                    else -> {
                        // Multiple coupons detected - show selection interface
                        _uiState.value = ScannerUiState.MultiCouponDetected(couponInstances, bitmap, imageUri.toString())
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in enhanced scanning", e)
                _uiState.value = ScannerUiState.Error("Error processing image: ${e.message}")
            }
        }
    }

    /**
     * Process a captured bitmap to extract coupon information
     */
    fun processCapturedImage(
        bitmap: Bitmap,
        imageUri: Uri? = null,
        persistImmediately: Boolean = true
    ) {
        viewModelScope.launch {
            try {
                _uiState.value = ScannerUiState.Scanning
                Log.d(TAG, "Processing captured bitmap with two-stage detection")

                // Run two-stage detection
                val couponInstances = withContext(Dispatchers.IO) {
                    twoStageDetector.detectMultiCoupons(bitmap)
                }

                Log.d(TAG, "Two-stage detection on bitmap completed: ${couponInstances.size} coupons detected")

                when {
                    couponInstances.isEmpty() -> {
                        // Fallback to traditional OCR
                        Log.d(TAG, "No coupons detected in bitmap, falling back to traditional OCR")
                        fallbackToTraditionalOCRBitmap(bitmap, imageUri)
                    }
                    couponInstances.size == 1 -> {
                        // Single coupon detected
                        val couponInstance = couponInstances.first()
                        processSingleCoupon(
                            couponInstance = couponInstance,
                            imageUri = imageUri?.toString(),
                            persistImmediately = persistImmediately
                        )
                    }
                    else -> {
                        // Multiple coupons detected
                        _uiState.value = ScannerUiState.MultiCouponDetected(couponInstances, bitmap, imageUri?.toString())
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing captured image", e)
                _uiState.value = ScannerUiState.Error("Error processing image: ${e.message}")
            }
        }
    }

    /**
     * Process a single detected coupon instance
     */
    private suspend fun processSingleCoupon(
        couponInstance: CouponInstance,
        imageUri: String?,
        persistImmediately: Boolean
    ) {
        try {
            Log.d(TAG, "Processing single coupon with ${couponInstance.fields.size} detected fields")

            // Extract text from detected fields using OCR
            val extractionResult = extractTextFromFields(couponInstance)

            // Create coupon from extracted information
            val coupon = createCouponFromInstance(couponInstance, extractionResult.fields, imageUri)

            val normalizedDescription = CouponDedupUtils.normalizeDescription(coupon.description)
            pendingPreview = null

            if (persistImmediately) {
                persistCoupon(
                    coupon = coupon,
                    normalizedDescription = normalizedDescription,
                    miniCpmStatus = extractionResult.miniCpmStatus
                )
            } else {
                pendingPreview = PendingPreview(
                    coupon = coupon,
                    normalizedDescription = normalizedDescription,
                    miniCpmStatus = extractionResult.miniCpmStatus
                )
                _uiState.value = ScannerUiState.Success(coupon, extractionResult.miniCpmStatus)
                Log.d(TAG, "Preview ready for review without immediate persistence")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing single coupon", e)
            _uiState.value = ScannerUiState.Error("Error processing coupon: ${e.message}")
        }
    }

    private suspend fun persistCoupon(
        coupon: Coupon,
        normalizedDescription: String,
        miniCpmStatus: MiniCpmProgress
    ) {
        // First check if a duplicate already exists using the saveOrMerge helper
        val savedCouponId = couponRepository.saveOrMergeCoupon(
            coupon = coupon,
            normalizedDescription = normalizedDescription,
            imagePhash = null, // TODO: Add image hashing if needed
            imageSignature = null // TODO: Add image signature if needed
        )

        val savedCoupon = couponRepository.getCouponById(savedCouponId)

        if (savedCoupon != null) {
            val isDuplicate = savedCoupon.createdAt.before(coupon.createdAt ?: savedCoupon.createdAt)

            if (isDuplicate) {
                _uiState.value = ScannerUiState.AlreadySaved(savedCoupon, miniCpmStatus)
                Log.d(
                    TAG,
                    "Duplicate coupon detected, existing ID: ${savedCoupon.id}, store: ${savedCoupon.storeName}"
                )
            } else {
                _uiState.value = ScannerUiState.Saved(savedCoupon)
                Log.d(TAG, "New coupon saved with ID: ${savedCoupon.id}, store: ${savedCoupon.storeName}")
            }
        } else {
            val fallbackCoupon = coupon.copy(id = savedCouponId)
            _uiState.value = ScannerUiState.Saved(fallbackCoupon)
            Log.d(TAG, "Coupon saved (fallback) with ID: $savedCouponId")
        }
    }

    fun confirmPreviewSave(updatedCoupon: Coupon? = null) {
        val preview = pendingPreview ?: return
        val couponToPersist = updatedCoupon ?: preview.coupon
        val normalizedDescription = CouponDedupUtils.normalizeDescription(couponToPersist.description)

        viewModelScope.launch {
            try {
                persistCoupon(
                    coupon = couponToPersist,
                    normalizedDescription = normalizedDescription,
                    miniCpmStatus = preview.miniCpmStatus
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error saving preview coupon", e)
                _uiState.value = ScannerUiState.Error("Error saving coupon: ${e.message}")
            } finally {
                pendingPreview = null
            }
        }
    }

    fun clearPendingPreview() {
        pendingPreview = null
    }

    @VisibleForTesting
    internal fun setPendingPreviewForTest(coupon: Coupon, miniCpmStatus: MiniCpmProgress) {
        pendingPreview = PendingPreview(
            coupon = coupon,
            normalizedDescription = CouponDedupUtils.normalizeDescription(coupon.description),
            miniCpmStatus = miniCpmStatus
        )
    }

    /**
     * Handle selection of a specific coupon from multiple detected coupons
     */
    fun selectCoupon(selectedInstance: CouponInstance, originalImageUri: String?) {
        viewModelScope.launch {
            try {
                _uiState.value = ScannerUiState.Scanning
                Log.d(TAG, "Processing selected coupon: ${selectedInstance.id}")

                // Process the selected coupon
                processSingleCoupon(
                    couponInstance = selectedInstance,
                    imageUri = originalImageUri,
                    persistImmediately = true
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error processing selected coupon", e)
                _uiState.value = ScannerUiState.Error("Error processing selected coupon: ${e.message}")
            }
        }
    }

    /**
     * Process all detected coupons and save them
     */
    fun processAllCoupons(couponInstances: List<CouponInstance>, originalImageUri: String?) {
        viewModelScope.launch {
            try {
                _uiState.value = ScannerUiState.Scanning
                val adjustedInstances = getManualAdjustedInstances(couponInstances, includeManualExtras = true)
                Log.d(
                    TAG,
                    "Processing all ${adjustedInstances.size} detected coupons (requested ${couponInstances.size})"
                )

                processCouponBatch(adjustedInstances, originalImageUri)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing all coupons", e)
                _uiState.value = ScannerUiState.Error("Error processing coupons: ${e.message}")
            }
        }
    }

    fun processSelectedCoupons(couponInstances: List<CouponInstance>, originalImageUri: String?) {
        viewModelScope.launch {
            try {
                _uiState.value = ScannerUiState.Scanning
                val adjustedInstances = getManualAdjustedInstances(couponInstances, includeManualExtras = false)
                Log.d(
                    TAG,
                    "Processing ${adjustedInstances.size} selected coupons (requested ${couponInstances.size})"
                )

                processCouponBatch(adjustedInstances, originalImageUri)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing selected coupons", e)
                _uiState.value = ScannerUiState.Error("Error processing coupons: ${e.message}")
            }
        }
    }

    private fun getManualAdjustedInstances(
        instances: List<CouponInstance>,
        includeManualExtras: Boolean
    ): List<CouponInstance> {
        if (manualOverrides.isEmpty()) {
            return instances
        }

        if (instances.isEmpty()) {
            return if (includeManualExtras) {
                manualOverrides.values.toList()
            } else {
                emptyList()
            }
        }

        val selectedIds = instances.mapTo(mutableSetOf()) { it.id }
        val adjusted = instances.map { instance ->
            manualOverrides[instance.id] ?: instance
        }.toMutableList()

        if (includeManualExtras) {
            manualOverrides.forEach { (id, override) ->
                if (selectedIds.add(id)) {
                    adjusted.add(override)
                }
            }
        }

        return adjusted
    }

    private suspend fun processCouponBatch(
        couponInstances: List<CouponInstance>,
        originalImageUri: String?
    ) {
        val processedResults = mutableListOf<CouponProcessingSummary>()

        for ((index, instance) in couponInstances.withIndex()) {
            try {
                val extractionResult = extractTextFromFields(instance)
                val coupon = createCouponFromInstance(instance, extractionResult.fields, originalImageUri)

                couponRepository.insertCoupon(coupon)
                processedResults.add(CouponProcessingSummary(coupon, extractionResult.miniCpmStatus))

                Log.d(
                    TAG,
                    "Saved coupon ${processedResults.size}/${couponInstances.size}: ${coupon.redeemCode}"
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error processing coupon $index", e)
                // Continue with other coupons
            }
        }

        if (processedResults.isNotEmpty()) {
            _uiState.value = ScannerUiState.AllCouponsSaved(processedResults)
        } else {
            _uiState.value = ScannerUiState.Error("Failed to save any coupons")
        }
    }

    /**
     * Extract text from detected fields using OCR with run-path telemetry
     */
    private suspend fun extractTextFromFields(couponInstance: CouponInstance): FieldExtractionResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val extractedInfo = mutableMapOf<String, String>()
            var progress = MiniCpmProgress.SUCCESS
            val triedStages = mutableListOf<String>()
            var finalStage = "UNKNOWN"

            extractedInfo["minicpmConfidence"] = couponInstance.confidence.toString()
            extractedInfo["minicpmDetectionStatus"] = couponInstance.status.name

            try {
                // Try LLM extraction first
                triedStages.add("LLM")
                finalStage = "LLM"
                
                val result = localLlmOcrService.processCouponImageTyped(couponInstance.cropBitmap)
                
                when (result) {
                    is ExtractResult.Good -> {
                        extractedInfo.putAll(mapCouponInfoToFields(result.info))
                        progress = MiniCpmProgress.SUCCESS
                        Log.d(TAG, "✅ LLM extraction successful (quality: ${result.signals.qualityScore})")
                    }
                    
                    is ExtractResult.LowQuality -> {
                        extractedInfo.putAll(mapCouponInfoToFields(result.info))
                        progress = MiniCpmProgress.NEEDS_REVIEW
                        
                        // Try fallback for low quality
                        triedStages.add("FALLBACK_OCR")
                        finalStage = "FALLBACK_OCR"
                        val fallbackFields = runFallbackOcr(couponInstance.cropBitmap)
                        mergeValidatedFields(extractedInfo, fallbackFields)
                        
                        Log.w(TAG, "⚠️ LLM low quality (${result.reason}), used fallback")
                    }
                    
                    is ExtractResult.Failed -> {
                        // LLM failed, use fallback
                        triedStages.add("FALLBACK_OCR")
                        finalStage = "FALLBACK_OCR"
                        progress = MiniCpmProgress.FALLBACK
                        val fallbackFields = runFallbackOcr(couponInstance.cropBitmap)
                        extractedInfo.putAll(fallbackFields)
                        
                        Log.e(TAG, "❌ LLM extraction failed: ${result.error.message}")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "MiniCPM processing failed, using fallback", e)
                triedStages.add("FALLBACK_OCR")
                finalStage = "FALLBACK_OCR"
                progress = MiniCpmProgress.FALLBACK
                val fallbackFields = runFallbackOcr(couponInstance.cropBitmap)
                extractedInfo.putAll(fallbackFields)
            }

            // Track run path telemetry
            val totalTime = System.currentTimeMillis() - startTime
            val runPath = RunPath(
                primary = "LLM",
                tried = triedStages,
                final = finalStage,
                nativeAvailable = localLlmOcrService.isServiceAvailable(),
                totalTimeMs = totalTime
            )
            
            telemetryService.trackRunPath(runPath)
            
            extractedInfo["minicpmProcessing"] = progress.name
            extractedInfo["runPath"] = "${runPath.primary} → ${runPath.final}"
            extractedInfo["processingTimeMs"] = totalTime.toString()

            FieldExtractionResult(extractedInfo, progress)
        }
    }

    private fun mapCouponInfoToFields(couponInfo: CouponInfo): MutableMap<String, String> {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val fields = mutableMapOf<String, String>()

        val storeName = couponInfo.storeName
        if (storeName.isNotBlank() && !fieldHeuristics.isGenericOrMissing(storeName)) {
            fields["storeName"] = storeName
        }

        val description = couponInfo.description
        if (description.isNotBlank() && !fieldHeuristics.isGenericOrMissing(description)) {
            fields["description"] = description
        }

        couponInfo.redeemCode?.takeIf { it.isNotBlank() }?.let { fields["code"] = it }

        couponInfo.expiryDate?.let { date ->
            fields["expiryDate"] = formatter.format(date)
        }

        couponInfo.cashbackAmount?.takeIf { !GenericFieldHeuristics.isZeroOrMeaningless(it) }?.let {
            fields["amount"] = formatNumeric(it)
        }

        couponInfo.minimumPurchase?.takeIf { !GenericFieldHeuristics.isZeroOrMeaningless(it) }?.let {
            fields["minOrderAmount"] = formatNumeric(it)
        }

        couponInfo.paymentMethod?.takeIf { it.isNotBlank() }?.let { fields["paymentMethod"] = it }
        couponInfo.platformType?.takeIf { it.isNotBlank() }?.let { fields["platformType"] = it }
        couponInfo.status?.takeIf { it.isNotBlank() }?.let { status ->
            fields["status"] = status
        }

        return fields
    }

    private fun shouldFlagForReview(couponInfo: CouponInfo, fields: Map<String, String>): Boolean {
        val storeWeak = fieldHeuristics.isGenericOrMissing(fields["storeName"])
        val descriptionWeak = fieldHeuristics.isGenericOrMissing(fields["description"])
        val duplicateStoreAndCode = fieldHeuristics.areDuplicateFields(fields["storeName"], fields["code"])
        val codeMissing = couponInfo.redeemCode.isNullOrBlank()
        val amountWeak = GenericFieldHeuristics.isZeroOrMeaningless(couponInfo.cashbackAmount)

        return storeWeak || descriptionWeak || duplicateStoreAndCode || (codeMissing && amountWeak)
    }

    private suspend fun runFallbackOcr(bitmap: Bitmap): Map<String, String> {
        return when (val result = multiEngineOCR.processImage(bitmap)) {
            is MultiEngineOCR.OCRResult.Success -> result.extractedInfo
            is MultiEngineOCR.OCRResult.Error -> {
                Log.w(TAG, "Fallback OCR failed: ${result.message}")
                emptyMap()
            }
        }
    }

    private fun mergeValidatedFields(primary: MutableMap<String, String>, fallback: Map<String, String>) {
        fallback.forEach { (key, value) ->
            val sanitized = value.trim()
            if (sanitized.isEmpty()) {
                return@forEach
            }

            val existing = primary[key]
            if (shouldReplaceExisting(existing, sanitized, key)) {
                primary[key] = sanitized
            }
        }
    }

    private fun shouldReplaceExisting(existing: String?, candidate: String, key: String): Boolean {
        if (candidate.isBlank()) {
            return false
        }

        if (existing.isNullOrBlank()) {
            return true
        }

        return when (key) {
            "amount", "minOrderAmount" -> {
                val currentValue = extractNumericValue(existing)
                GenericFieldHeuristics.isZeroOrMeaningless(currentValue)
            }
            else -> fieldHeuristics.isGenericOrMissing(existing)
        }
    }

    private fun extractNumericValue(value: String?): Double? {
        return IndianCurrencyParser.parseAmount(value)
    }

    private fun formatNumeric(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.2f", value)
        }
    }

    /**
     * Create a Coupon object from a detected coupon instance
     */
    private fun createCouponFromInstance(
        couponInstance: CouponInstance, 
        extractedInfo: Map<String, String>, 
        imageUri: String?
    ): Coupon {
        // Parse expiry date string to Date if available
        val expiryDate = parseExpiryDate(extractedInfo["expiryDate"])

        // Parse amount to double with Indian currency support
        val amount = extractedInfo["amount"]?.let {
            IndianCurrencyParser.parseAmount(it) ?: 0.0
        } ?: 0.0

        // Note: Quality determination could be used for analytics or sorting

        return Coupon(
            id = 0, // Auto-generated by Room
            storeName = extractedInfo["storeName"] ?: extractedInfo["app"] ?: "Unknown Store",
            description = extractedInfo["description"] ?: extractedInfo["benefit"] ?: "Multi-coupon detected",
            expiryDate = expiryDate,
            cashbackAmount = amount,
            redeemCode = extractedInfo["code"] ?: generateFallbackCode(),
            imageUri = imageUri,
            category = determineCategory(extractedInfo),
            rating = null,
            status = when (couponInstance.status) {
                com.example.coupontracker.ml.CouponStatus.COMPLETE -> "ACTIVE"
                com.example.coupontracker.ml.CouponStatus.PARTIAL_TOP -> "PARTIAL"
                com.example.coupontracker.ml.CouponStatus.PARTIAL_BOTTOM -> "PARTIAL"
            },
            createdAt = Date(),
            updatedAt = Date()
        )
    }

    /**
     * Fallback to traditional OCR when no coupons are detected
     */
    private suspend fun fallbackToTraditionalOCR(imageUri: Uri) {
        try {
            Log.d(TAG, "Using traditional OCR fallback")
            
            // First persist the URI
            val persistedUri = uriPersistenceManager.persistUri(imageUri)
            val finalUri = persistedUri ?: imageUri
            
            when (val result = multiEngineOCR.processImage(imageUri)) {
                is MultiEngineOCR.OCRResult.Success -> {
                    val extractedInfo = result.extractedInfo
                    Log.d(TAG, "Traditional OCR extracted: $extractedInfo")

                    if (extractedInfo.isEmpty()) {
                        _uiState.value = ScannerUiState.Error("Could not extract any coupon information from the image")
                    } else {
                        val coupon = createCouponFromExtractedInfo(extractedInfo, finalUri.toString())
                        _uiState.value = ScannerUiState.Success(coupon, MiniCpmProgress.FALLBACK)
                    }
                }
                is MultiEngineOCR.OCRResult.Error -> {
                    _uiState.value = ScannerUiState.Error(result.message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in traditional OCR fallback", e)
            _uiState.value = ScannerUiState.Error("Error processing image: ${e.message}")
        }
    }

    /**
     * Fallback to traditional OCR for bitmap when no coupons are detected
     */
    private suspend fun fallbackToTraditionalOCRBitmap(bitmap: Bitmap, imageUri: Uri?) {
        try {
            Log.d(TAG, "Using traditional OCR fallback for bitmap")
            
            when (val result = multiEngineOCR.processImage(bitmap)) {
                is MultiEngineOCR.OCRResult.Success -> {
                    val extractedInfo = result.extractedInfo
                    Log.d(TAG, "Traditional OCR extracted from bitmap: $extractedInfo")

                    if (extractedInfo.isEmpty()) {
                        _uiState.value = ScannerUiState.Error("Could not extract any coupon information from the image")
                    } else {
                        val coupon = createCouponFromExtractedInfo(extractedInfo, imageUri?.toString())
                        _uiState.value = ScannerUiState.Success(coupon, MiniCpmProgress.FALLBACK)
                    }
                }
                is MultiEngineOCR.OCRResult.Error -> {
                    _uiState.value = ScannerUiState.Error(result.message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in traditional OCR fallback for bitmap", e)
            _uiState.value = ScannerUiState.Error("Error processing image: ${e.message}")
        }
    }

    /**
     * Load bitmap from URI
     */
    private suspend fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                BitmapFactory.decodeStream(inputStream)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading bitmap from URI", e)
                null
            }
        }
    }

    /**
     * Legacy method - Create a Coupon object from the extracted information
     */
    private fun createCouponFromExtractedInfo(extractedInfo: Map<String, String>, imageUri: String? = null): Coupon {
        // Parse expiry date string to Date if available
        val expiryDate = parseExpiryDate(extractedInfo["expiryDate"])

        // Parse amount to double with Indian currency support
        val amount = extractedInfo["amount"]?.let {
            IndianCurrencyParser.parseAmount(it) ?: 0.0
        } ?: 0.0

        return Coupon(
            id = 0, // Auto-generated by Room
            storeName = extractedInfo["storeName"] ?: "Unknown Store",
            description = extractedInfo["description"] ?: "No description",
            expiryDate = expiryDate,
            cashbackAmount = amount,
            redeemCode = extractedInfo["code"] ?: generateFallbackCode(),
            imageUri = imageUri,
            category = determineCategory(extractedInfo),
            rating = null,
            status = "ACTIVE",
            createdAt = Date(),
            updatedAt = Date()
        )
    }

    /**
     * Parse expiry date string to Date
     */
    private fun parseExpiryDate(dateString: String?): Date? {
        if (dateString.isNullOrBlank()) return null

        val cleanedDate = dateString
            .trim()
            .replace("\u202F", " ")
            .let {
                val timeRegex = Regex("\\s*(?:at\\s*)?\\d{1,2}:\\d{2}(?:\\s*[AaPp][Mm])?(?:\\s*[A-Za-z]+)?$")
                timeRegex.replace(it) { _ -> "" }.trim()
            }

        val dateFormats = listOf(
            "dd/MM/yyyy",
            "MM/dd/yyyy",
            "yyyy/MM/dd",
            "dd-MM-yyyy",
            "MM-dd-yyyy",
            "yyyy-MM-dd",
            "dd MMM yyyy",
            "dd MMMM yyyy",
            "dd MMM, yyyy",
            "dd MMMM, yyyy"
        )

        for (format in dateFormats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.getDefault())
                sdf.isLenient = false
                val parsed = sdf.parse(cleanedDate)
                if (parsed != null) {
                    return parsed
                }
            } catch (e: Exception) {
                // Try next format
            }
        }

        return Date()
    }

    /**
     * Generate fallback coupon code
     */
    private fun generateFallbackCode(): String {
        return "COUPON_${System.currentTimeMillis().toString().takeLast(6)}"
    }

    /**
     * Determine category from extracted information
     */
    private fun determineCategory(extractedInfo: Map<String, String>): String {
        val relevantKeys = listOf("storeName", "description", "terms", "benefit", "app")
        val text = relevantKeys.mapNotNull { key -> extractedInfo[key] }
            .joinToString(" ")
            .lowercase()
        
        return when {
            text.contains("food") || text.contains("restaurant") || text.contains("zomato") || text.contains("swiggy") -> "Food"
            text.contains("fashion") || text.contains("clothing") || text.contains("myntra") -> "Fashion"
            text.contains("grocery") || text.contains("bigbasket") || text.contains("grofers") -> "Grocery"
            text.contains("travel") || text.contains("booking") || text.contains("hotel") -> "Travel"
            text.contains("electronics") || text.contains("amazon") || text.contains("flipkart") -> "Electronics"
            else -> "Other"
        }
    }

    /**
     * Save the scanned coupon to the repository
     */
    fun saveCoupon(coupon: Coupon) {
        viewModelScope.launch {
            try {
                couponRepository.insertCoupon(coupon)
                _uiState.value = ScannerUiState.Saved(coupon)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving coupon", e)
                _uiState.value = ScannerUiState.Error("Error saving coupon: ${e.message}")
            }
        }
    }

    /**
     * Reset the UI state
     */
    fun resetState() {
        clearPendingPreview()
        _uiState.value = ScannerUiState.Initial
    }

    /**
     * Clean up resources
     */
    override fun onCleared() {
        super.onCleared()
        try {
            twoStageDetector.cleanupBitmaps()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up TwoStageDetector", e)
        }
    }
}

/**
 * Enhanced UI state for the scanner with multi-coupon support
 */
sealed class ScannerUiState {
    object Initial : ScannerUiState()
    object Scanning : ScannerUiState()
    data class Success(val coupon: Coupon, val miniCpmStatus: MiniCpmProgress) : ScannerUiState()
    data class Saved(val coupon: Coupon) : ScannerUiState()
    data class AlreadySaved(val existingCoupon: Coupon, val miniCpmStatus: MiniCpmProgress) : ScannerUiState()
    data class Error(val message: String) : ScannerUiState()
    
    // New states for multi-coupon support
    data class MultiCouponDetected(
        val couponInstances: List<CouponInstance>, 
        val originalBitmap: Bitmap, 
        val imageUri: String?
    ) : ScannerUiState()
    
    data class AllCouponsSaved(val processedCoupons: List<CouponProcessingSummary>) : ScannerUiState()
}

data class CouponProcessingSummary(
    val coupon: Coupon,
    val miniCpmStatus: MiniCpmProgress
)

private data class PendingPreview(
    val coupon: Coupon,
    val normalizedDescription: String,
    val miniCpmStatus: MiniCpmProgress
)

enum class MiniCpmProgress {
    SUCCESS,
    NEEDS_REVIEW,
    FALLBACK;

    fun displayName(): String {
        val lower = name.lowercase(Locale.getDefault()).replace('_', ' ')
        return lower.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
        }
    }
}

data class FieldExtractionResult(
    val fields: Map<String, String>,
    val miniCpmStatus: MiniCpmProgress
)
