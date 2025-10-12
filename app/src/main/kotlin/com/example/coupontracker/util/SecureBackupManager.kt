package com.example.coupontracker.util

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.example.coupontracker.data.model.Coupon
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.KeyStore
import java.util.Date
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure backup manager using Android Keystore for encryption
 * Provides encrypted export/import of coupon data
 */
@Singleton
class SecureBackupManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()
    
    companion object {
        private const val TAG = "SecureBackupManager"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "CouponTrackerBackupKey"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_SIZE = 128
        
        // Backup file format version for future compatibility
        private const val BACKUP_VERSION = 1
    }
    
    /**
     * Backup metadata
     */
    data class BackupData(
        val version: Int,
        val timestamp: Long,
        val deviceId: String?,
        val coupons: List<Coupon>
    )
    
    /**
     * Result of backup/restore operation
     */
    sealed class BackupResult {
        data class Success(val message: String, val count: Int = 0) : BackupResult()
        data class Error(val message: String, val exception: Exception? = null) : BackupResult()
    }
    
    init {
        // Ensure encryption key exists
        try {
            getOrCreateSecretKey()
            Log.d(TAG, "SecureBackupManager initialized with encryption key")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encryption key", e)
        }
    }
    
    /**
     * Export coupons to encrypted backup file
     */
    suspend fun exportSecureBackup(uri: Uri, coupons: List<Coupon>): BackupResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting secure backup export: ${coupons.size} coupons")
                
                // Create backup data structure
                val backupData = BackupData(
                    version = BACKUP_VERSION,
                    timestamp = System.currentTimeMillis(),
                    deviceId = getDeviceId(),
                    coupons = coupons
                )
                
                // Serialize to JSON
                val json = gson.toJson(backupData)
                val plaintext = json.toByteArray(Charsets.UTF_8)
                
                // Encrypt the data
                val (encryptedData, iv) = encrypt(plaintext)
                
                // Write to file: IV + encrypted data
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(iv)
                    outputStream.write(encryptedData)
                    outputStream.flush()
                }
                
                Log.i(TAG, "✅ Secure backup exported successfully: ${coupons.size} coupons")
                BackupResult.Success("Backup exported successfully", coupons.size)
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Secure backup export failed", e)
                BackupResult.Error("Failed to export backup: ${e.message}", e)
            }
        }
    }
    
    /**
     * Import coupons from encrypted backup file
     */
    suspend fun importSecureBackup(uri: Uri): BackupResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting secure backup import")
                
                // Read encrypted data from file
                val encryptedDataWithIv = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.readBytes()
                } ?: throw Exception("Failed to read backup file")
                
                if (encryptedDataWithIv.size < IV_SIZE) {
                    throw Exception("Invalid backup file format (too small)")
                }
                
                // Extract IV and encrypted data
                val iv = encryptedDataWithIv.copyOfRange(0, IV_SIZE)
                val encryptedData = encryptedDataWithIv.copyOfRange(IV_SIZE, encryptedDataWithIv.size)
                
                // Decrypt the data
                val plaintext = decrypt(encryptedData, iv)
                val json = String(plaintext, Charsets.UTF_8)
                
                // Deserialize backup data
                val backupData: BackupData = try {
                    gson.fromJson(json, BackupData::class.java)
                } catch (e: Exception) {
                    // Try legacy format (plain list of coupons)
                    Log.w(TAG, "Backup is in legacy format, attempting direct import")
                    val type = object : TypeToken<List<Coupon>>() {}.type
                    val coupons: List<Coupon> = gson.fromJson(json, type)
                    BackupData(
                        version = 0,
                        timestamp = System.currentTimeMillis(),
                        deviceId = null,
                        coupons = coupons
                    )
                }
                
                // Validate backup
                if (backupData.coupons.isEmpty()) {
                    return@withContext BackupResult.Error("Backup file contains no coupons")
                }
                
                Log.i(TAG, "✅ Secure backup imported successfully: ${backupData.coupons.size} coupons")
                Log.d(TAG, "   Backup version: ${backupData.version}")
                Log.d(TAG, "   Backup timestamp: ${Date(backupData.timestamp)}")
                
                BackupResult.Success("Backup imported successfully", backupData.coupons.size)
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Secure backup import failed", e)
                BackupResult.Error("Failed to import backup: ${e.message}", e)
            }
        }
    }
    
    /**
     * Read coupons from backup file without validation
     * Used by repository to get the actual coupon list
     */
    suspend fun readCouponsFromBackup(uri: Uri): List<Coupon> {
        return withContext(Dispatchers.IO) {
            try {
                // Read encrypted data from file
                val encryptedDataWithIv = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.readBytes()
                } ?: throw Exception("Failed to read backup file")

                if (encryptedDataWithIv.size < IV_SIZE) {
                    throw Exception("Invalid backup file format (too small)")
                }

                // Extract IV and encrypted data
                val iv = encryptedDataWithIv.copyOfRange(0, IV_SIZE)
                val encryptedData = encryptedDataWithIv.copyOfRange(IV_SIZE, encryptedDataWithIv.size)

                // Decrypt the data
                val plaintext = decrypt(encryptedData, iv)
                val json = String(plaintext, Charsets.UTF_8)

                // Deserialize backup data
                val backupData: BackupData = try {
                    gson.fromJson(json, BackupData::class.java)
                } catch (e: Exception) {
                    // Try legacy format (plain list of coupons)
                    val type = object : TypeToken<List<Coupon>>() {}.type
                    val coupons: List<Coupon> = gson.fromJson(json, type)
                    BackupData(
                        version = 0,
                        timestamp = System.currentTimeMillis(),
                        deviceId = null,
                        coupons = coupons
                    )
                }

                backupData.coupons
            } catch (e: AEADBadTagException) {
                Log.e(TAG, "Failed to decrypt backup - incorrect key", e)
                throw IllegalStateException(
                    "Unable to decrypt backup. Please ensure you created it on this device.",
                    e
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read coupons from backup", e)
                throw e
            }
        }
    }
    
    /**
     * Get or create encryption key from Android Keystore
     */
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)
        
        // Check if key already exists
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return keyStore.getKey(KEY_ALIAS, null) as SecretKey
        }
        
        // Create new key
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        
        val keySpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false) // No biometric for backup (optional enhancement)
            .build()
        
        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }
    
    /**
     * Encrypt data using AES-GCM
     * Returns pair of (encrypted data, IV)
     */
    private fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        
        return Pair(ciphertext, iv)
    }
    
    /**
     * Decrypt data using AES-GCM
     */
    private fun decrypt(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(TAG_SIZE, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), spec)
        
        return cipher.doFinal(ciphertext)
    }
    
    /**
     * Get device ID for backup metadata
     */
    private fun getDeviceId(): String? {
        return try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get device ID", e)
            null
        }
    }
}

