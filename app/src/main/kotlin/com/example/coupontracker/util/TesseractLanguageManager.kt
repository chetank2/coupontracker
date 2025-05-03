package com.example.coupontracker.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Manager class for Tesseract OCR language settings
 */
class TesseractLanguageManager(private val context: Context) {
    private val TAG = "TesseractLangManager"
    
    // SharedPreferences for storing language settings
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "tesseract_language_prefs", Context.MODE_PRIVATE
    )
    
    // Constants for preference keys
    companion object {
        const val KEY_SELECTED_LANGUAGE = "selected_language"
        const val DEFAULT_LANGUAGE = "eng"
        
        // Language display names
        val LANGUAGE_DISPLAY_NAMES = mapOf(
            "eng" to "English",
            "spa" to "Spanish",
            "fra" to "French",
            "deu" to "German"
        )
    }
    
    /**
     * Get the currently selected language code
     * @return The language code (e.g., "eng", "spa")
     */
    fun getSelectedLanguage(): String {
        return prefs.getString(KEY_SELECTED_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }
    
    /**
     * Set the selected language
     * @param languageCode The language code to set
     */
    fun setSelectedLanguage(languageCode: String) {
        prefs.edit().putString(KEY_SELECTED_LANGUAGE, languageCode).apply()
        Log.d(TAG, "Selected language set to: $languageCode")
    }
    
    /**
     * Get the display name for a language code
     * @param languageCode The language code
     * @return The display name or the code itself if not found
     */
    fun getLanguageDisplayName(languageCode: String): String {
        return LANGUAGE_DISPLAY_NAMES[languageCode] ?: languageCode
    }
    
    /**
     * Get all available languages from the TesseractOCRHelper
     * @return A list of language codes
     */
    fun getAvailableLanguages(): List<String> {
        val tesseractHelper = TesseractOCRHelper(context)
        return tesseractHelper.getAvailableLanguages()
    }
    
    /**
     * Get all available languages with their display names
     * @return A map of language codes to display names
     */
    fun getAvailableLanguagesWithNames(): Map<String, String> {
        val languages = getAvailableLanguages()
        return languages.associateWith { getLanguageDisplayName(it) }
    }
}
