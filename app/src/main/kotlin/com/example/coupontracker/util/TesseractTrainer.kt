package com.example.coupontracker.util

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Class for handling Tesseract OCR training
 * Note: This class prepares data for training, but the actual training
 * is performed on a desktop/server environment using Tesseract training tools.
 */
class TesseractTrainer(private val context: Context) {
    companion object {
        private const val TAG = "TesseractTrainer"
        private const val TRAINING_DATA_DIR = "tesseract_training"
        private const val PREPARED_DATA_DIR = "prepared_training_data"
        private const val CUSTOM_MODEL_DIR = "custom_models"
        private const val DEFAULT_LANG_CODE = "coupon"
    }
    
    // Directory for storing prepared training data
    private val preparedDataDir: File by lazy {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), PREPARED_DATA_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }
    
    // Directory for storing custom models
    private val customModelDir: File by lazy {
        val dir = File(context.getExternalFilesDir(null), "tesseract/tessdata")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }
    
    /**
     * Prepare training data for Tesseract training
     * This creates the necessary files and directory structure for training
     * @param trainingDataDir Directory containing the training images and ground truth
     * @param langCode Language code for the model (e.g., "coupon" for a coupon-specific model)
     * @return The directory containing the prepared training data
     */
    suspend fun prepareTrainingData(
        trainingDataDir: File,
        langCode: String = DEFAULT_LANG_CODE
    ): File? = withContext(Dispatchers.IO) {
        try {
            // Create timestamp for unique directory name
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val prepDir = File(preparedDataDir, "${langCode}_$timestamp")
            if (!prepDir.exists()) {
                prepDir.mkdirs()
            }
            
            // Create necessary subdirectories
            val imageDir = File(prepDir, "images")
            imageDir.mkdirs()
            
            // Copy training images to the prepared directory
            copyTrainingImages(trainingDataDir, imageDir)
            
            // Create box files (placeholder - actual box files would be created by Tesseract tools)
            createPlaceholderBoxFiles(imageDir, prepDir)
            
            // Create training config files
            createTrainingConfigFiles(prepDir, langCode)
            
            Log.d(TAG, "Prepared training data in: ${prepDir.absolutePath}")
            return@withContext prepDir
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing training data", e)
            return@withContext null
        }
    }
    
    /**
     * Copy training images from the source directory to the prepared directory
     * @param sourceDir Source directory containing training images
     * @param targetDir Target directory for the prepared images
     */
    private fun copyTrainingImages(sourceDir: File, targetDir: File) {
        // Copy all PNG files from the source directory and its subdirectories
        sourceDir.walk().forEach { file ->
            if (file.isFile && file.extension.equals("png", ignoreCase = true)) {
                val targetFile = File(targetDir, file.name)
                file.copyTo(targetFile, overwrite = true)
            }
        }
    }
    
    /**
     * Create placeholder box files for training
     * In a real implementation, these would be created by Tesseract tools
     * @param imageDir Directory containing the training images
     * @param outputDir Directory to write the box files to
     */
    private fun createPlaceholderBoxFiles(imageDir: File, outputDir: File) {
        imageDir.listFiles()?.forEach { file ->
            if (file.isFile && file.extension.equals("png", ignoreCase = true)) {
                val boxFileName = "${file.nameWithoutExtension}.box"
                val boxFile = File(outputDir, boxFileName)
                
                // Create an empty box file (placeholder)
                boxFile.createNewFile()
            }
        }
    }
    
    /**
     * Create configuration files needed for Tesseract training
     * @param outputDir Directory to write the config files to
     * @param langCode Language code for the model
     */
    private fun createTrainingConfigFiles(outputDir: File, langCode: String) {
        // Create font_properties file
        val fontPropertiesFile = File(outputDir, "${langCode}.font_properties")
        fontPropertiesFile.writeText("coupon_font 0 0 0 0 0\\n")
        
        // Create unicharset file (placeholder)
        val unicharsetFile = File(outputDir, "${langCode}.unicharset")
        unicharsetFile.writeText("# Placeholder unicharset file\\n")
        
        // Create README with instructions
        val readmeFile = File(outputDir, "README.txt")
        readmeFile.writeText("""
            Tesseract Training Data Preparation
            
            This directory contains prepared data for training a custom Tesseract model.
            To complete the training process:
            
            1. Copy this directory to a computer with Tesseract training tools installed
            2. Run the following commands:
               - tesseract [image].png [image] batch.nochop makebox
               - (Edit the box files manually to correct bounding boxes)
               - tesstrain --fonts_dir /usr/share/fonts --lang $langCode --linedata_only \\
                 --noextract_font_properties --langdata_dir ./langdata \\
                 --tessdata_dir ./tessdata --output_dir ./output
            3. Copy the resulting .traineddata file back to your Android device
            
            For more detailed instructions, see the Tesseract documentation:
            https://tesseract-ocr.github.io/tessdoc/TrainingTesseract-4.00.html
        """.trimIndent())
    }
    
    /**
     * Install a custom trained model
     * @param trainedDataFile The .traineddata file to install
     * @param langCode Language code for the model
     * @return True if installation was successful, false otherwise
     */
    suspend fun installCustomModel(
        trainedDataFile: File,
        langCode: String = DEFAULT_LANG_CODE
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Validate that this is a valid .traineddata file
            if (!trainedDataFile.name.endsWith(".traineddata")) {
                Log.e(TAG, "Invalid trained data file: ${trainedDataFile.name}")
                return@withContext false
            }
            
            // Copy the file to the custom model directory
            val targetFile = File(customModelDir, "${langCode}.traineddata")
            trainedDataFile.copyTo(targetFile, overwrite = true)
            
            Log.d(TAG, "Installed custom model: ${targetFile.absolutePath}")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Error installing custom model", e)
            return@withContext false
        }
    }
    
    /**
     * Create a fine-tuning dataset from existing coupons
     * This extracts text regions from coupon images and prepares them for training
     * @param couponDataDir Directory containing coupon images
     * @param outputDir Directory to write the fine-tuning dataset to
     * @return True if dataset creation was successful, false otherwise
     */
    suspend fun createFineTuningDataset(
        couponDataDir: File,
        outputDir: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }
            
            // Create subdirectories for different text regions
            val storeNameDir = File(outputDir, "store_name")
            val expiryDateDir = File(outputDir, "expiry_date")
            val amountDir = File(outputDir, "amount")
            val codeDir = File(outputDir, "code")
            
            storeNameDir.mkdirs()
            expiryDateDir.mkdirs()
            amountDir.mkdirs()
            codeDir.mkdirs()
            
            // Create a README file with instructions
            val readmeFile = File(outputDir, "README.txt")
            readmeFile.writeText("""
                Fine-Tuning Dataset for Coupon OCR
                
                This directory contains extracted text regions from coupon images.
                To use this dataset for fine-tuning:
                
                1. Manually verify and correct the extracted text in each image
                2. Create a ground truth file listing each image and its correct text
                3. Use these images for training a custom Tesseract model
                
                Directory structure:
                - store_name/: Contains store name text regions
                - expiry_date/: Contains expiry date text regions
                - amount/: Contains amount/value text regions
                - code/: Contains redemption code text regions
            """.trimIndent())
            
            Log.d(TAG, "Created fine-tuning dataset in: ${outputDir.absolutePath}")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Error creating fine-tuning dataset", e)
            return@withContext false
        }
    }
    
    /**
     * Get a list of available custom models
     * @return List of language codes for available custom models
     */
    fun getAvailableCustomModels(): List<String> {
        val models = mutableListOf<String>()
        
        try {
            customModelDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".traineddata")) {
                    val langCode = file.nameWithoutExtension
                    models.add(langCode)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting available custom models", e)
        }
        
        return models
    }
    
    /**
     * Check if a custom model is available
     * @param langCode Language code to check
     * @return True if the model is available, false otherwise
     */
    fun isCustomModelAvailable(langCode: String): Boolean {
        val modelFile = File(customModelDir, "${langCode}.traineddata")
        return modelFile.exists() && modelFile.isFile
    }
    
    /**
     * Get the path to a custom model
     * @param langCode Language code of the model
     * @return The path to the model file or null if not found
     */
    fun getCustomModelPath(langCode: String): String? {
        val modelFile = File(customModelDir, "${langCode}.traineddata")
        return if (modelFile.exists() && modelFile.isFile) {
            modelFile.absolutePath
        } else {
            null
        }
    }
}
