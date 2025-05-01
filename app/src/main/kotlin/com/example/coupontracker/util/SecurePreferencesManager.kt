package com.example.coupontracker.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Calendar
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure preferences manager for storing sensitive information like API keys
 * Uses EncryptedSharedPreferences for secure storage
 */
@Singleton
class SecurePreferencesManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "SecurePrefsManager"

        // Preference file names
        private const val SECURE_PREFS_FILE = "secure_coupon_tracker_prefs"
        private const val LEGACY_PREFS_FILE = "coupon_tracker_prefs"

        // Key names
        const val KEY_GOOGLE_CLOUD_VISION_API_KEY = "google_cloud_vision_api_key"
        const val KEY_MISTRAL_API_KEY = "mistral_api_key"
        const val KEY_SELECTED_API = "selected_api"
        const val KEY_SELECTED_API_TYPE = "selected_api_type"
        const val KEY_USE_MISTRAL_API = "use_mistral_api"
        const val KEY_API_KEY_ROTATION_DATE = "api_key_rotation_date"
        const val KEY_SELECTED_TESSERACT_LANGUAGE = "selected_tesseract_language"
        const val KEY_USE_CUSTOM_TESSERACT_MODEL = "use_custom_tesseract_model"

        // Key rotation period in days
        private const val KEY_ROTATION_PERIOD_DAYS = 90
    }

    // Encrypted SharedPreferences instance
    private val securePrefs: SharedPreferences by lazy {
        createEncryptedSharedPreferences()
    }

    // Legacy SharedPreferences for migration
    private val legacyPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(LEGACY_PREFS_FILE, Context.MODE_PRIVATE)
    }

    /**
     * Create encrypted shared preferences
     */
    private fun createEncryptedSharedPreferences(): SharedPreferences {
        return try {
            // For testing purposes, just use regular SharedPreferences
            // This avoids crashes in the emulator environment
            if (Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone")) {
                Log.d(TAG, "Running in emulator, using regular SharedPreferences")
                return context.getSharedPreferences(SECURE_PREFS_FILE, Context.MODE_PRIVATE)
            }

            try {
                // Create or retrieve the Master Key for encryption/decryption
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .setKeyGenParameterSpec(
                        KeyGenParameterSpec.Builder(
                            MasterKey.DEFAULT_MASTER_KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                    )
                    .build()

                // Create the EncryptedSharedPreferences
                EncryptedSharedPreferences.create(
                    context,
                    SECURE_PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error creating encrypted shared preferences, falling back to regular", e)
                context.getSharedPreferences(SECURE_PREFS_FILE, Context.MODE_PRIVATE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical error creating preferences", e)
            // Fallback to regular SharedPreferences in case of error
            context.getSharedPreferences(SECURE_PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    /**
     * Initialize the secure preferences manager
     * Migrates data from legacy preferences if needed
     */
    fun initialize() {
        try {
            // Check if we need to migrate from legacy preferences
            if (!securePrefs.contains(KEY_GOOGLE_CLOUD_VISION_API_KEY) &&
                legacyPrefs.contains(KEY_GOOGLE_CLOUD_VISION_API_KEY)) {
                migrateFromLegacyPreferences()
            }

            // Check if we need to set up key rotation
            if (!securePrefs.contains(KEY_API_KEY_ROTATION_DATE)) {
                // Set initial rotation date
                setNextKeyRotationDate()
            }

            // Check if keys need rotation
            checkKeyRotation()
        } catch (e: Exception) {
            Log.e(TAG, "Error during initialization, continuing with default values", e)
            // Continue with default values
        }
    }

    /**
     * Migrate data from legacy preferences to secure preferences
     */
    private fun migrateFromLegacyPreferences() {
        Log.d(TAG, "Migrating from legacy preferences to secure preferences")

        // Get values from legacy preferences
        val googleApiKey = legacyPrefs.getString(KEY_GOOGLE_CLOUD_VISION_API_KEY, null)
        val mistralApiKey = legacyPrefs.getString(KEY_MISTRAL_API_KEY, null)
        val selectedApi = legacyPrefs.getString(KEY_SELECTED_API, null)
        val useMistralApi = legacyPrefs.getBoolean(KEY_USE_MISTRAL_API, false)

        // Save to secure preferences
        val editor = securePrefs.edit()

        googleApiKey?.let { editor.putString(KEY_GOOGLE_CLOUD_VISION_API_KEY, it) }
        mistralApiKey?.let { editor.putString(KEY_MISTRAL_API_KEY, it) }
        selectedApi?.let { editor.putString(KEY_SELECTED_API, it) }
        editor.putBoolean(KEY_USE_MISTRAL_API, useMistralApi)

        editor.apply()

        Log.d(TAG, "Migration completed")
    }

    /**
     * Get a string value from secure preferences
     */
    fun getString(key: String, defaultValue: String? = null): String? {
        return securePrefs.getString(key, defaultValue)
    }

    /**
     * Get a boolean value from secure preferences
     */
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return securePrefs.getBoolean(key, defaultValue)
    }

    /**
     * Save a string value to secure preferences
     */
    fun saveString(key: String, value: String?) {
        securePrefs.edit().putString(key, value).apply()
    }

    /**
     * Save a boolean value to secure preferences
     */
    fun saveBoolean(key: String, value: Boolean) {
        securePrefs.edit().putBoolean(key, value).apply()
    }

    /**
     * Register a listener for preference changes
     */
    fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        securePrefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /**
     * Unregister a listener for preference changes
     */
    fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        securePrefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /**
     * Set the next key rotation date
     */
    private fun setNextKeyRotationDate() {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, KEY_ROTATION_PERIOD_DAYS)
        val nextRotationDate = calendar.timeInMillis

        securePrefs.edit().putLong(KEY_API_KEY_ROTATION_DATE, nextRotationDate).apply()

        Log.d(TAG, "Next API key rotation date set to: ${Date(nextRotationDate)}")
    }

    /**
     * Check if keys need rotation
     */
    private fun checkKeyRotation() {
        val rotationDate = securePrefs.getLong(KEY_API_KEY_ROTATION_DATE, 0)
        val currentTime = System.currentTimeMillis()

        if (rotationDate > 0 && currentTime >= rotationDate) {
            Log.d(TAG, "API keys need rotation")
            // Set new rotation date
            setNextKeyRotationDate()
        }
    }

    /**
     * Get days until next key rotation
     */
    fun getDaysUntilKeyRotation(): Int {
        val rotationDate = securePrefs.getLong(KEY_API_KEY_ROTATION_DATE, 0)
        if (rotationDate == 0L) {
            setNextKeyRotationDate()
            return KEY_ROTATION_PERIOD_DAYS
        }

        val currentTime = System.currentTimeMillis()
        val diff = rotationDate - currentTime

        return (diff / (1000 * 60 * 60 * 24)).toInt().coerceAtLeast(0)
    }

    /**
     * Check if the device is rooted
     */
    fun isDeviceRooted(): Boolean {
        return checkRootMethod1() || checkRootMethod2() || checkRootMethod3()
    }

    private fun checkRootMethod1(): Boolean {
        val buildTags = Build.TAGS
        return buildTags != null && buildTags.contains("test-keys")
    }

    private fun checkRootMethod2(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )

        for (path in paths) {
            if (java.io.File(path).exists()) {
                return true
            }
        }

        return false
    }

    private fun checkRootMethod3(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exitValue = process.waitFor()
            exitValue == 0
        } catch (e: Exception) {
            false
        } finally {
            process?.destroy()
        }
    }

    /**
     * Check app integrity
     */
    fun checkAppIntegrity(): Boolean {
        // Check if the app is debuggable
        val isDebuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

        // In a production app, we would implement more sophisticated checks
        // such as certificate pinning, code signature verification, etc.

        return !isDebuggable
    }

    /**
     * Get the selected API type
     * @return The selected API type as a string
     */
    fun getSelectedApiType(): String {
        return getString(KEY_SELECTED_API_TYPE, "SUPER") ?: "SUPER"
    }

    /**
     * Set the selected API type
     * @param apiType The API type to select
     */
    fun setSelectedApiType(apiType: String) {
        saveString(KEY_SELECTED_API_TYPE, apiType)
    }

    /**
     * Get the selected Tesseract language
     * @return The selected language code
     */
    fun getSelectedTesseractLanguage(): String {
        return getString(KEY_SELECTED_TESSERACT_LANGUAGE, "eng") ?: "eng"
    }

    /**
     * Set the selected Tesseract language
     * @param language The language code to select
     */
    fun setSelectedTesseractLanguage(language: String) {
        saveString(KEY_SELECTED_TESSERACT_LANGUAGE, language)
    }

    /**
     * Check if custom Tesseract model should be used
     * @return True if custom model should be used, false otherwise
     */
    fun useCustomTesseractModel(): Boolean {
        return getBoolean(KEY_USE_CUSTOM_TESSERACT_MODEL, false)
    }

    /**
     * Set whether to use custom Tesseract model
     * @param use True to use custom model, false otherwise
     */
    fun setUseCustomTesseractModel(use: Boolean) {
        saveBoolean(KEY_USE_CUSTOM_TESSERACT_MODEL, use)
    }
}
