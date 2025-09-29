package com.example.coupontracker.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.coupontracker.data.repository.CouponRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility class for migrating existing coupons with invalid content:// URIs
 * to use persistent app storage URIs
 */
@Singleton
class CouponUriMigrationManager @Inject constructor(
    private val context: Context,
    private val couponRepository: CouponRepository
) {

    companion object {
        private const val TAG = "CouponUriMigrationManager"
        private const val MIGRATION_PREF_KEY = "coupon_uri_migration_completed"
        private const val MIGRATION_VERSION = 1
    }

    private val uriPersistenceManager = UriPersistenceManager(context)

    /**
     * Check if migration is needed and perform it
     * This should be called during app startup
     */
    suspend fun performMigrationIfNeeded() = withContext(Dispatchers.IO) {
        try {
            val sharedPrefs = context.getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)
            val migrationCompleted = sharedPrefs.getInt(MIGRATION_PREF_KEY, 0)

            if (migrationCompleted >= MIGRATION_VERSION) {
                Log.d(TAG, "URI migration already completed (version $migrationCompleted)")
                return@withContext
            }

            Log.d(TAG, "Starting URI migration for existing coupons")
            val migrationResult = migrateExistingCoupons()

            if (migrationResult.success) {
                // Mark migration as completed
                sharedPrefs.edit()
                    .putInt(MIGRATION_PREF_KEY, MIGRATION_VERSION)
                    .apply()

                Log.d(TAG, "URI migration completed successfully: ${migrationResult.migratedCount} coupons migrated, ${migrationResult.failedCount} failed")
            } else {
                Log.e(TAG, "URI migration failed, will retry on next app start")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during URI migration", e)
        }
    }

    /**
     * Migrate existing coupons with invalid URIs
     * @return Migration result with counts
     */
    private suspend fun migrateExistingCoupons(): MigrationResult = withContext(Dispatchers.IO) {
        try {
            val allCoupons = couponRepository.getAllCoupons().first()
            var migratedCount = 0
            var failedCount = 0
            var skippedCount = 0

            Log.d(TAG, "Found ${allCoupons.size} coupons to check for migration")

            for (coupon in allCoupons) {
                val imageUri = coupon.imageUri
                if (imageUri.isNullOrBlank()) {
                    skippedCount++
                    continue
                }

                try {
                    val uri = Uri.parse(imageUri)
                    
                    // Check if this is a content:// URI that might be invalid
                    if (shouldMigrateUri(uri)) {
                        // Try to access the URI to see if it's still valid
                        val isAccessible = isUriAccessible(uri)
                        
                        if (!isAccessible) {
                            Log.d(TAG, "URI is not accessible, marking for cleanup: $imageUri")
                            // URI is not accessible, set to null to remove broken reference
                            val updatedCoupon = coupon.copy(imageUri = null)
                            couponRepository.updateCoupon(updatedCoupon)
                            migratedCount++
                        } else {
                            // URI is still accessible, try to persist it
                            val persistedUri = uriPersistenceManager.persistUri(uri)
                            if (persistedUri != null) {
                                val updatedCoupon = coupon.copy(imageUri = persistedUri.toString())
                                couponRepository.updateCoupon(updatedCoupon)
                                migratedCount++
                                Log.d(TAG, "Successfully migrated URI: $imageUri -> $persistedUri")
                            } else {
                                Log.w(TAG, "Failed to persist accessible URI: $imageUri")
                                failedCount++
                            }
                        }
                    } else {
                        // URI doesn't need migration (already in app storage or file URI)
                        skippedCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error migrating coupon ${coupon.id} with URI: $imageUri", e)
                    failedCount++
                }
            }

            Log.d(TAG, "Migration completed: $migratedCount migrated, $failedCount failed, $skippedCount skipped")
            return@withContext MigrationResult(
                success = failedCount == 0,
                migratedCount = migratedCount,
                failedCount = failedCount,
                skippedCount = skippedCount
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error during coupon migration", e)
            return@withContext MigrationResult(success = false, migratedCount = 0, failedCount = 1, skippedCount = 0)
        }
    }

    /**
     * Check if a URI should be migrated
     * @param uri The URI to check
     * @return True if the URI should be migrated
     */
    private fun shouldMigrateUri(uri: Uri): Boolean {
        return when (uri.scheme) {
            "content" -> {
                // Migrate content:// URIs that are not from our FileProvider
                val authority = uri.authority
                authority != "${context.packageName}.fileprovider"
            }
            "file" -> {
                // Migrate file:// URIs that are not in our app storage
                val path = uri.path ?: return false
                !path.startsWith(context.filesDir.absolutePath)
            }
            else -> false
        }
    }

    /**
     * Check if a URI is still accessible
     * @param uri The URI to check
     * @return True if the URI can be accessed
     */
    private fun isUriAccessible(uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { 
                // Just try to open and immediately close
                true
            } ?: false
        } catch (e: Exception) {
            Log.d(TAG, "URI not accessible: $uri - ${e.message}")
            false
        }
    }

    /**
     * Get migration statistics
     * @return Migration statistics or null if migration hasn't run
     */
    suspend fun getMigrationStats(): MigrationStats? = withContext(Dispatchers.IO) {
        try {
            val sharedPrefs = context.getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)
            val migrationCompleted = sharedPrefs.getInt(MIGRATION_PREF_KEY, 0)
            
            if (migrationCompleted == 0) {
                return@withContext null
            }

            val allCoupons = couponRepository.getAllCoupons().first()
            val couponsWithImages = allCoupons.count { !it.imageUri.isNullOrBlank() }
            val couponsWithoutImages = allCoupons.size - couponsWithImages

            return@withContext MigrationStats(
                migrationVersion = migrationCompleted,
                totalCoupons = allCoupons.size,
                couponsWithImages = couponsWithImages,
                couponsWithoutImages = couponsWithoutImages
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting migration stats", e)
            return@withContext null
        }
    }

    /**
     * Force re-run migration (for debugging/testing)
     */
    suspend fun forceMigration() = withContext(Dispatchers.IO) {
        val sharedPrefs = context.getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().remove(MIGRATION_PREF_KEY).apply()
        performMigrationIfNeeded()
    }
}

/**
 * Result of URI migration operation
 */
data class MigrationResult(
    val success: Boolean,
    val migratedCount: Int,
    val failedCount: Int,
    val skippedCount: Int
)

/**
 * Statistics about URI migration
 */
data class MigrationStats(
    val migrationVersion: Int,
    val totalCoupons: Int,
    val couponsWithImages: Int,
    val couponsWithoutImages: Int
)
