package com.example.coupontracker.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.data.util.CouponDedupUtils
import com.example.coupontracker.debug.ExtractionDebugRepository
import com.example.coupontracker.debug.ExtractionDebugSnapshot
import com.example.coupontracker.llm.LlmRuntimeManager
import com.example.coupontracker.util.CouponNotificationManager
import com.example.coupontracker.util.SecurePreferencesManager
import com.example.coupontracker.worker.VerifyCouponWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CouponRepository,
    private val notificationManager: CouponNotificationManager,
    private val debugRepository: ExtractionDebugRepository,
    private val securePreferencesManager: SecurePreferencesManager
) : ViewModel() {

    companion object {
        private const val TAG = "DetailViewModel"
    }

    private val _coupon = MutableStateFlow<Coupon?>(null)
    val coupon: StateFlow<Coupon?> = _coupon.asStateFlow()

    private val currentCouponId = MutableStateFlow<Long?>(null)

    val debugSnapshot: StateFlow<ExtractionDebugSnapshot?> = currentCouponId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(null)
            } else {
                debugRepository.snapshots.map { it[id] }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    private var couponId: Long = 0
    private var couponObserverJob: Job? = null

    fun loadCoupon(id: Long) {
        couponId = id
        currentCouponId.value = id
        couponObserverJob?.cancel()
        couponObserverJob = viewModelScope.launch {
            repository.observeCouponById(id).collect { couponData ->
                _coupon.value = couponData
            }
        }
    }

    fun verifyCoupon() {
        viewModelScope.launch {
            _coupon.value?.let { coupon ->
                repository.updateCoupon(
                    coupon.copy(
                        cleanupStatus = Coupon.CleanupStatus.PENDING,
                        cleanupError = null,
                        cleanupStartedAt = null,
                        cleanupFinishedAt = null,
                        updatedAt = Date()
                    )
                )
                VerifyCouponWorker.enqueueUserRequested(context, coupon.id)
            }
        }
    }

    fun cleanOfferText() {
        viewModelScope.launch {
            val coupon = _coupon.value ?: return@launch
            val offerText = coupon.description.trim()
            if (offerText.isBlank()) {
                markOfferCleanFailed(coupon, "Offer text is empty.")
                return@launch
            }
            if (!securePreferencesManager.isQwenTextCleanerEnabled()) {
                markOfferCleanFailed(coupon, "Qwen text cleaner is turned off in Settings.")
                return@launch
            }

            val runtime = LlmRuntimeManager.getInstance(context)
            if (!runtime.isModelAvailable()) {
                markOfferCleanFailed(coupon, "Qwen model is not installed. Set it up in Settings.")
                return@launch
            }

            repository.updateCoupon(
                coupon.copy(
                    cleanupStatus = Coupon.CleanupStatus.RUNNING,
                    cleanupError = null,
                    cleanupStartedAt = Date(),
                    cleanupFinishedAt = null,
                    updatedAt = Date()
                )
            )

            try {
                val rawResponse = runtime.runTextInference(
                    ocrText = offerText,
                    prompt = buildOfferCleanerPrompt(coupon),
                    keepLoaded = false,
                    maxTokensOverride = 80
                )
                val cleanedOffer = extractOfferJson(rawResponse)
                    ?.takeIf { it.isNotBlank() }
                    ?.takeUnless { it.equals("unknown", ignoreCase = true) }
                    ?.takeIf { it.length <= 220 }

                if (cleanedOffer == null) {
                    markOfferCleanFailed(coupon, "Qwen did not return a valid cleaned offer.")
                    return@launch
                }

                val latest = repository.getCouponById(coupon.id) ?: coupon
                val updated = latest.copy(
                    description = cleanedOffer,
                    normalizedDescription = CouponDedupUtils.normalizeDescription(cleanedOffer),
                    cleanupStatus = Coupon.CleanupStatus.CLEANED,
                    cleanupStartedAt = latest.cleanupStartedAt,
                    cleanupFinishedAt = Date(),
                    cleanupError = null,
                    lastCleanedBy = "Qwen offer cleaner",
                    extractionRunPath = JSONObject()
                        .put("stage", "offer_clean")
                        .put("offer", "QWEN_TEXT")
                        .put("protected_fields", "UNCHANGED")
                        .toString(),
                    extractionSource = Coupon.ExtractionSource.QWEN_CLEANED,
                    updatedAt = Date()
                )
                repository.updateCoupon(updated)
            } catch (error: Throwable) {
                Log.e(TAG, "Offer clean failed", error)
                markOfferCleanFailed(coupon, error.message ?: "Qwen offer clean failed.")
            }
        }
    }

    private suspend fun markOfferCleanFailed(coupon: Coupon, message: String) {
        val latest = repository.getCouponById(coupon.id) ?: coupon
        repository.updateCoupon(
            latest.copy(
                cleanupStatus = Coupon.CleanupStatus.FAILED,
                cleanupError = message,
                cleanupFinishedAt = Date(),
                updatedAt = Date()
            )
        )
    }

    private fun buildOfferCleanerPrompt(coupon: Coupon): String {
        val offerText = coupon.description.trim()
        return """
            You clean coupon offer text only.
            Return one JSON object matching the app coupon schema:
            {"storeName":"unknown","description":"...","redeemCode":"unknown","expiryDate":"unknown","storeNameSource":"unknown","storeNameEvidence":[],"needsAttention":false}

            Rules:
            - Put the cleaned offer sentence only in description.
            - Set storeName, redeemCode, expiryDate, and storeNameSource to "unknown".
            - Set storeNameEvidence to [].
            - Set needsAttention to false.
            - Rewrite only the offer sentence below for readability.
            - Do not invent data.
            - Do not change or output the app name: ${coupon.storeName}
            - Do not change or output the coupon code: ${coupon.redeemCode ?: "null"}
            - Do not change or output the expiry date.
            - Do not add cashback, discount, terms, dates, or codes unless already present in the offer text.
            - Keep the cleaned offer concise.

            Offer text:
            $offerText
        """.trimIndent()
    }

    private fun extractOfferJson(rawResponse: String?): String? {
        if (rawResponse.isNullOrBlank()) return null
        val trimmed = rawResponse.trim()
        val jsonText = if (trimmed.startsWith("{")) {
            trimmed
        } else {
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start >= 0 && end > start) trimmed.substring(start, end + 1) else return null
        }
        return runCatching {
            val json = JSONObject(jsonText)
            json.optString("offer").ifBlank {
                json.optString("description")
            }.trim()
        }.getOrNull()
    }

    fun deleteCoupon() {
        viewModelScope.launch {
            _coupon.value?.let { coupon ->
                // Cancel any notifications for this coupon
                notificationManager.cancelNotification(coupon.id)

                // Delete the coupon
                repository.deleteCoupon(coupon)
            }
        }
    }

    /**
     * Toggle the priority status of the coupon
     */
    fun togglePriority() {
        viewModelScope.launch {
            _coupon.value?.let { coupon ->
                repository.updateCouponPriority(coupon.id, !coupon.isPriority)
                // Refresh coupon data
                loadCoupon(coupon.id)
            }
        }
    }

    /**
     * Track usage of the coupon
     */
    fun trackUsage(amountSaved: Double = 0.0) {
        viewModelScope.launch {
            _coupon.value?.let { coupon ->
                try {
                    // Update usage count
                    repository.updateCouponUsageCount(coupon.id)
                    repository.updateCouponStatus(coupon.id, Coupon.Status.USED)

                    // If this was the last use and the coupon has a usage limit, mark as used
                    if (coupon.usageLimit != null && coupon.usageCount + 1 >= coupon.usageLimit) {
                        repository.updateCouponStatus(coupon.id, Coupon.Status.USED)
                    }

                    // Track savings if applicable
                    if (amountSaved > 0) {
                        // In a real app, you would update a savings tracker here
                        Log.d(TAG, "Saved $amountSaved with coupon ${coupon.id}")
                    }

                    // Refresh coupon data
                    loadCoupon(coupon.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Error tracking usage", e)
                }
            }
        }
    }

    /**
     * Set a reminder for the coupon
     */
    fun setReminderLeadTime(leadTimeMinutes: Int) {
        viewModelScope.launch {
            _coupon.value?.let { coupon ->
                val expiry = coupon.expiryDate ?: return@launch
                val reminderAt = Date(expiry.time - TimeUnit.MINUTES.toMillis(leadTimeMinutes.toLong()))
                try {
                    repository.updateCouponReminder(coupon.id, reminderAt, leadTimeMinutes)
                    loadCoupon(coupon.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting reminder lead time", e)
                }
            }
        }
    }

    /**
     * Set a reminder for the coupon
     */
    fun setReminder(date: Date) {
        viewModelScope.launch {
            _coupon.value?.let { coupon ->
                try {
                    // Update the reminder date
                    val leadMinutes = coupon.expiryDate?.let { expiry ->
                        val diffMillis = expiry.time - date.time
                        if (diffMillis > 0) {
                            TimeUnit.MILLISECONDS.toMinutes(diffMillis).toInt()
                        } else {
                            0
                        }
                    } ?: 0

                    repository.updateCouponReminder(coupon.id, date, leadMinutes)

                    // Schedule notification
                    notificationManager.showReminderNotification(
                        coupon.id,
                        coupon.storeName,
                        coupon.description,
                        "Reminder to use your coupon"
                    )

                    // Refresh coupon data
                    loadCoupon(coupon.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting reminder", e)
                }
            }
        }
    }

    /**
     * Cancel the reminder for the coupon
     */
    fun cancelReminder() {
        viewModelScope.launch {
            _coupon.value?.let { coupon ->
                try {
                    // Update the reminder date to null
                    repository.updateCouponReminder(coupon.id, null, null)

                    // Cancel notification
                    notificationManager.cancelNotification(coupon.id)

                    // Refresh coupon data
                    loadCoupon(coupon.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Error canceling reminder", e)
                }
            }
        }
    }
}
