package com.example.coupontracker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.util.CouponDedupUtils

@Database(
    entities = [
        Coupon::class,
        LearnedPattern::class,          // V2: Pattern storage
        ExtractionFeedback::class,      // V2: Feedback & telemetry
        ValidatorFeedbackRecord::class  // Validator override & correction dataset
    ],
    version = 14,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class CouponDatabase : RoomDatabase() {
    abstract fun couponDao(): CouponDao
    abstract fun learnedPatternDao(): LearnedPatternDao              // V2: Pattern management
    abstract fun extractionFeedbackDao(): ExtractionFeedbackDao      // V2: Feedback management
    abstract fun validatorFeedbackDao(): ValidatorFeedbackDao        // Validator dataset management

    companion object {
        const val DATABASE_NAME = "coupon_database"

        // Migration from version 2 to 3 (adding new coupon fields)
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add new columns to the coupons table
                database.execSQL("ALTER TABLE coupons ADD COLUMN minimumPurchase REAL")
                database.execSQL("ALTER TABLE coupons ADD COLUMN maximumDiscount REAL")
                database.execSQL("ALTER TABLE coupons ADD COLUMN isPriority INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE coupons ADD COLUMN paymentMethod TEXT")
                database.execSQL("ALTER TABLE coupons ADD COLUMN usageLimit INTEGER")
                database.execSQL("ALTER TABLE coupons ADD COLUMN usageCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE coupons ADD COLUMN reminderDate INTEGER")
                database.execSQL("ALTER TABLE coupons ADD COLUMN platformType TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE coupons ADD COLUMN normalizedDescription TEXT")
                database.execSQL("ALTER TABLE coupons ADD COLUMN imagePhash TEXT")
                database.execSQL("ALTER TABLE coupons ADD COLUMN imageSignature TEXT")

                database.query("SELECT id, description FROM coupons").use { cursor ->
                    val updateStatement = database.compileStatement(
                        "UPDATE coupons SET normalizedDescription = ? WHERE id = ?"
                    )
                    try {
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(0)
                            val description = cursor.getString(1)
                            val normalized = CouponDedupUtils.normalizeDescription(description)
                            updateStatement.bindString(1, normalized)
                            updateStatement.bindLong(2, id)
                            updateStatement.executeUpdateDelete()
                            updateStatement.clearBindings()
                        }
                    } finally {
                        updateStatement.close()
                    }
                }

                // The legacy database only stored URIs to coupon images, so we cannot
                // backfill perceptual hashes or signatures for existing rows. The new
                // columns remain null until fresh image data is processed.
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `coupons_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `storeName` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `normalizedDescription` TEXT,
                        `expiryDate` INTEGER,
                        `cashbackAmount` REAL NOT NULL,
                        `redeemCode` TEXT,
                        `imageUri` TEXT,
                        `imagePhash` TEXT,
                        `imageSignature` TEXT,
                        `category` TEXT,
                        `status` TEXT,
                        `minimumPurchase` REAL,
                        `maximumDiscount` REAL,
                        `isPriority` INTEGER NOT NULL DEFAULT 0,
                        `paymentMethod` TEXT,
                        `usageLimit` INTEGER,
                        `usageCount` INTEGER NOT NULL DEFAULT 0,
                        `reminderDate` INTEGER,
                        `platformType` TEXT,
                        `rating` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    INSERT INTO `coupons_new` (
                        `id`, `storeName`, `description`, `normalizedDescription`, `expiryDate`,
                        `cashbackAmount`, `redeemCode`, `imageUri`, `imagePhash`, `imageSignature`,
                        `category`, `status`, `minimumPurchase`, `maximumDiscount`, `isPriority`,
                        `paymentMethod`, `usageLimit`, `usageCount`, `reminderDate`, `platformType`,
                        `rating`, `createdAt`, `updatedAt`
                    )
                    SELECT
                        `id`, `storeName`, `description`, `normalizedDescription`, `expiryDate`,
                        `cashbackAmount`, `redeemCode`, `imageUri`, `imagePhash`, `imageSignature`,
                        `category`, `status`, `minimumPurchase`, `maximumDiscount`, `isPriority`,
                        `paymentMethod`, `usageLimit`, `usageCount`, `reminderDate`, `platformType`,
                        `rating`, `createdAt`, `updatedAt`
                    FROM `coupons`
                    """.trimIndent()
                )

                database.execSQL("DROP TABLE `coupons`")
                database.execSQL("ALTER TABLE `coupons_new` RENAME TO `coupons`")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add new typed cashback fields
                database.execSQL("ALTER TABLE coupons ADD COLUMN cashbackType TEXT")
                database.execSQL("ALTER TABLE coupons ADD COLUMN cashbackValueNum REAL")
                database.execSQL("ALTER TABLE coupons ADD COLUMN cashbackCurrency TEXT DEFAULT 'INR'")

                // Migrate existing cashbackAmount data to typed fields
                // This query attempts to detect percentages vs amounts based on value and description
                database.execSQL("""
                    UPDATE coupons SET
                        cashbackType = CASE
                            WHEN cashbackAmount <= 100 AND (
                                description LIKE '%off%'
                                OR instr(description, '%') > 0
                                OR description LIKE '%percent%'
                            ) THEN 'percent'
                            ELSE 'amount'
                        END,
                        cashbackValueNum = cashbackAmount
                    WHERE cashbackType IS NULL
                """)
            }
        }
        
        /**
         * V2 Architecture Migration: Add pattern learning and feedback tables
         * Version 6 → 7
         * 
         * Changes:
         * - Creates learned_patterns_v1 table for storing extraction patterns
         * - Creates extraction_feedback_v1 table for user feedback and telemetry
         * - Adds indices for efficient queries
         * - Zero impact on existing coupons table
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create learned_patterns_v1 table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `learned_patterns_v1` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `brand` TEXT,
                        `fieldType` TEXT NOT NULL,
                        `regex` TEXT NOT NULL,
                        `weight` REAL NOT NULL DEFAULT 0.0,
                        `source` TEXT NOT NULL,
                        `sampleValue` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `successCount` INTEGER NOT NULL DEFAULT 1,
                        `attemptCount` INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
                
                // Create indices for learned_patterns_v1
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_learned_patterns_v1_fieldType` ON `learned_patterns_v1` (`fieldType`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_learned_patterns_v1_brand` ON `learned_patterns_v1` (`brand`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_learned_patterns_v1_weight` ON `learned_patterns_v1` (`weight` DESC)")
                
                // Create extraction_feedback_v1 table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `extraction_feedback_v1` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `couponId` INTEGER,
                        `extractionStrategy` TEXT NOT NULL,
                        `feedbackType` TEXT NOT NULL,
                        `originalValues` TEXT NOT NULL,
                        `correctedValues` TEXT,
                        `signalsJson` TEXT NOT NULL,
                        `runPathJson` TEXT NOT NULL,
                        `deviceInfo` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `consentGiven` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                
                // Create indices for extraction_feedback_v1
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_extraction_feedback_v1_timestamp` ON `extraction_feedback_v1` (`timestamp` DESC)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_extraction_feedback_v1_extractionStrategy` ON `extraction_feedback_v1` (`extractionStrategy`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_extraction_feedback_v1_feedbackType` ON `extraction_feedback_v1` (`feedbackType`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_extraction_feedback_v1_couponId` ON `extraction_feedback_v1` (`couponId`)")
                
                android.util.Log.d("CouponDatabase", "✅ V2 Migration 6→7 complete: Pattern learning and feedback tables created")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `coupons_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `storeName` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `normalizedDescription` TEXT,
                        `expiryDate` INTEGER,
                        `cashbackAmount` REAL NOT NULL,
                        `redeemCode` TEXT,
                        `cashbackType` TEXT,
                        `cashbackValueNum` REAL,
                        `cashbackCurrency` TEXT,
                        `imageUri` TEXT,
                        `imagePhash` TEXT,
                        `imageSignature` TEXT,
                        `category` TEXT,
                        `status` TEXT,
                        `minimumPurchase` REAL,
                        `maximumDiscount` REAL,
                        `isPriority` INTEGER NOT NULL DEFAULT 0,
                        `paymentMethod` TEXT,
                        `usageLimit` INTEGER,
                        `usageCount` INTEGER NOT NULL DEFAULT 0,
                        `reminderDate` INTEGER,
                        `platformType` TEXT,
                        `rating` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())

                database.execSQL("""
                    INSERT INTO `coupons_new` (
                        `id`, `storeName`, `description`, `normalizedDescription`, `expiryDate`,
                        `cashbackAmount`, `redeemCode`, `cashbackType`, `cashbackValueNum`, `cashbackCurrency`,
                        `imageUri`, `imagePhash`, `imageSignature`, `category`, `status`,
                        `minimumPurchase`, `maximumDiscount`, `isPriority`, `paymentMethod`, `usageLimit`,
                        `usageCount`, `reminderDate`, `platformType`, `rating`, `createdAt`, `updatedAt`
                    )
                    SELECT
                        `id`, `storeName`, `description`, `normalizedDescription`, `expiryDate`,
                        `cashbackAmount`, `redeemCode`, `cashbackType`, `cashbackValueNum`, `cashbackCurrency`,
                        `imageUri`, `imagePhash`, `imageSignature`, `category`, `status`,
                        `minimumPurchase`, `maximumDiscount`, `isPriority`, `paymentMethod`, `usageLimit`,
                        `usageCount`, `reminderDate`, `platformType`, `rating`, `createdAt`, `updatedAt`
                    FROM `coupons`
                """.trimIndent())

                database.execSQL("DROP TABLE `coupons`")
                database.execSQL("ALTER TABLE `coupons_new` RENAME TO `coupons`")

                android.util.Log.d("CouponDatabase", "✅ Migration 7→8 complete: offerText column removed")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `coupons_v9` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `storeName` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `normalizedDescription` TEXT,
                        `expiryDate` INTEGER,
                        `cashbackAmount` REAL NOT NULL,
                        `redeemCode` TEXT,
                        `cashbackType` TEXT,
                        `cashbackValueNum` REAL,
                        `cashbackCurrency` TEXT,
                        `imageUri` TEXT,
                        `imagePhash` TEXT,
                        `imageSignature` TEXT,
                        `category` TEXT,
                        `status` TEXT,
                        `minimumPurchase` REAL,
                        `maximumDiscount` REAL,
                        `isPriority` INTEGER NOT NULL DEFAULT 0,
                        `paymentMethod` TEXT,
                        `usageLimit` INTEGER,
                        `usageCount` INTEGER NOT NULL DEFAULT 0,
                        `reminderDate` INTEGER,
                        `platformType` TEXT,
                        `extractionQualityScore` INTEGER,
                        `extractionConfidenceBreakdown` TEXT NOT NULL DEFAULT '{}',
                        `extractionStage` TEXT,
                        `extractionRunPath` TEXT,
                        `extractionTimestamp` INTEGER,
                        `rating` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    INSERT INTO `coupons_v9` (
                        `id`, `storeName`, `description`, `normalizedDescription`, `expiryDate`,
                        `cashbackAmount`, `redeemCode`, `cashbackType`, `cashbackValueNum`, `cashbackCurrency`,
                        `imageUri`, `imagePhash`, `imageSignature`, `category`, `status`,
                        `minimumPurchase`, `maximumDiscount`, `isPriority`, `paymentMethod`, `usageLimit`,
                        `usageCount`, `reminderDate`, `platformType`, `extractionQualityScore`, `extractionConfidenceBreakdown`,
                        `extractionStage`, `extractionRunPath`, `extractionTimestamp`, `rating`, `createdAt`, `updatedAt`
                    )
                    SELECT
                        `id`, `storeName`, `description`, `normalizedDescription`, `expiryDate`,
                        `cashbackAmount`, `redeemCode`, `cashbackType`, `cashbackValueNum`, `cashbackCurrency`,
                        `imageUri`, `imagePhash`, `imageSignature`, `category`, `status`,
                        `minimumPurchase`, `maximumDiscount`, `isPriority`, `paymentMethod`, `usageLimit`,
                        `usageCount`, `reminderDate`, `platformType`,
                        NULL AS `extractionQualityScore`,
                        '{}' AS `extractionConfidenceBreakdown`,
                        NULL AS `extractionStage`,
                        NULL AS `extractionRunPath`,
                        COALESCE(`updatedAt`, `createdAt`) AS `extractionTimestamp`,
                        `rating`, `createdAt`, `updatedAt`
                    FROM `coupons`
                    """.trimIndent()
                )

                database.execSQL("DROP TABLE `coupons`")
                database.execSQL("ALTER TABLE `coupons_v9` RENAME TO `coupons`")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `coupons_v10` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `storeName` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `normalizedDescription` TEXT,
                        `expiryDate` INTEGER,
                        `cashbackAmount` REAL NOT NULL,
                        `redeemCode` TEXT,
                        `cashbackType` TEXT,
                        `cashbackValueNum` REAL,
                        `cashbackCurrency` TEXT,
                        `imageUri` TEXT,
                        `imagePhash` TEXT,
                        `imageSignature` TEXT,
                        `category` TEXT,
                        `status` TEXT,
                        `minimumPurchase` REAL,
                        `maximumDiscount` REAL,
                        `isPriority` INTEGER NOT NULL DEFAULT 0,
                        `paymentMethod` TEXT,
                        `usageLimit` INTEGER,
                        `usageCount` INTEGER NOT NULL DEFAULT 0,
                        `reminderDate` INTEGER,
                        `platformType` TEXT,
                        `extractionQualityScore` INTEGER,
                        `extractionConfidenceBreakdown` TEXT NOT NULL DEFAULT '{}',
                        `extractionStage` TEXT,
                        `extractionRunPath` TEXT,
                        `extractionTimestamp` INTEGER,
                        `rating` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `needsAttention` INTEGER NOT NULL DEFAULT 0,
                        `storeNameSource` TEXT,
                        `storeNameEvidence` TEXT NOT NULL DEFAULT '[]'
                    )
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    INSERT INTO `coupons_v10` (
                        `id`, `storeName`, `description`, `normalizedDescription`, `expiryDate`,
                        `cashbackAmount`, `redeemCode`, `cashbackType`, `cashbackValueNum`, `cashbackCurrency`,
                        `imageUri`, `imagePhash`, `imageSignature`, `category`, `status`,
                        `minimumPurchase`, `maximumDiscount`, `isPriority`, `paymentMethod`, `usageLimit`,
                        `usageCount`, `reminderDate`, `platformType`, `extractionQualityScore`, `extractionConfidenceBreakdown`,
                        `extractionStage`, `extractionRunPath`, `extractionTimestamp`, `rating`, `createdAt`, `updatedAt`,
                        `needsAttention`, `storeNameSource`, `storeNameEvidence`
                    )
                    SELECT
                        `id`, `storeName`, `description`, `normalizedDescription`, `expiryDate`,
                        `cashbackAmount`, `redeemCode`, `cashbackType`, `cashbackValueNum`, `cashbackCurrency`,
                        `imageUri`, `imagePhash`, `imageSignature`, `category`, `status`,
                        `minimumPurchase`, `maximumDiscount`, `isPriority`, `paymentMethod`, `usageLimit`,
                        `usageCount`, `reminderDate`, `platformType`,
                        `extractionQualityScore`, `extractionConfidenceBreakdown`,
                        `extractionStage`, `extractionRunPath`, `extractionTimestamp`, `rating`, `createdAt`, `updatedAt`,
                        0 AS `needsAttention`,
                        NULL AS `storeNameSource`,
                        '[]' AS `storeNameEvidence`
                    FROM `coupons`
                    """.trimIndent()
                )

                database.execSQL("DROP TABLE `coupons`")
                database.execSQL("ALTER TABLE `coupons_v10` RENAME TO `coupons`")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `coupons_v11` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `storeName` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `normalizedDescription` TEXT,
                        `expiryDate` INTEGER,
                        `cashbackAmount` REAL NOT NULL,
                        `redeemCode` TEXT,
                        `cashbackType` TEXT,
                        `cashbackValueNum` REAL,
                        `cashbackCurrency` TEXT,
                        `imageUri` TEXT,
                        `imagePhash` TEXT,
                        `imageSignature` TEXT,
                        `category` TEXT,
                        `status` TEXT,
                        `minimumPurchase` REAL,
                        `maximumDiscount` REAL,
                        `isPriority` INTEGER NOT NULL DEFAULT 0,
                        `paymentMethod` TEXT,
                        `usageLimit` INTEGER,
                        `usageCount` INTEGER NOT NULL DEFAULT 0,
                        `reminderDate` INTEGER,
                        `platformType` TEXT,
                        `extractionQualityScore` INTEGER,
                        `extractionConfidenceBreakdown` TEXT NOT NULL DEFAULT '{}',
                        `extractionStage` TEXT,
                        `extractionRunPath` TEXT,
                        `extractionTimestamp` INTEGER,
                        `rating` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `needsAttention` INTEGER NOT NULL DEFAULT 0,
                        `storeNameSource` TEXT,
                        `storeNameEvidence` TEXT NOT NULL DEFAULT '[]'
                    )
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    INSERT INTO `coupons_v11` (
                        `id`, `storeName`, `description`, `normalizedDescription`, `expiryDate`,
                        `cashbackAmount`, `redeemCode`, `cashbackType`, `cashbackValueNum`, `cashbackCurrency`,
                        `imageUri`, `imagePhash`, `imageSignature`, `category`, `status`,
                        `minimumPurchase`, `maximumDiscount`, `isPriority`, `paymentMethod`, `usageLimit`,
                        `usageCount`, `reminderDate`, `platformType`, `extractionQualityScore`, `extractionConfidenceBreakdown`,
                        `extractionStage`, `extractionRunPath`, `extractionTimestamp`, `rating`, `createdAt`, `updatedAt`,
                        `needsAttention`, `storeNameSource`, `storeNameEvidence`
                    )
                    SELECT
                        `id`, `storeName`, `description`, `normalizedDescription`, `expiryDate`,
                        `cashbackAmount`, `redeemCode`, `cashbackType`, `cashbackValueNum`, `cashbackCurrency`,
                        `imageUri`, `imagePhash`, `imageSignature`, `category`, `status`,
                        `minimumPurchase`, `maximumDiscount`, `isPriority`, `paymentMethod`, `usageLimit`,
                        `usageCount`, `reminderDate`, `platformType`,
                        `extractionQualityScore`, `extractionConfidenceBreakdown`,
                        `extractionStage`, `extractionRunPath`, `extractionTimestamp`, `rating`, `createdAt`, `updatedAt`,
                        `needsAttention`, `storeNameSource`, COALESCE(`storeNameEvidence`, '[]')
                    FROM `coupons`
                    """.trimIndent()
                )

                database.execSQL("DROP TABLE `coupons`")
                database.execSQL("ALTER TABLE `coupons_v11` RENAME TO `coupons`")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `validator_feedback_v1` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `eventType` TEXT NOT NULL,
                        `fieldOutcomesJson` TEXT NOT NULL,
                        `rationaleJson` TEXT NOT NULL,
                        `metadataJson` TEXT NOT NULL,
                        `ocrHash` TEXT,
                        `ocrPreview` TEXT,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_validator_feedback_v1_timestamp` ON `validator_feedback_v1` (`timestamp` DESC)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_validator_feedback_v1_eventType` ON `validator_feedback_v1` (`eventType`)"
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE coupons ADD COLUMN reminderLeadTimeMinutes INTEGER")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `coupons_v13` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `storeName` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `normalizedDescription` TEXT,
                        `expiryDate` INTEGER,
                        `redeemCode` TEXT,
                        `imageUri` TEXT,
                        `imagePhash` TEXT,
                        `imageSignature` TEXT,
                        `category` TEXT,
                        `status` TEXT,
                        `minimumPurchase` REAL,
                        `maximumDiscount` REAL,
                        `isPriority` INTEGER NOT NULL DEFAULT 0,
                        `paymentMethod` TEXT,
                        `usageLimit` INTEGER,
                        `usageCount` INTEGER NOT NULL DEFAULT 0,
                        `reminderDate` INTEGER,
                        `reminderLeadTimeMinutes` INTEGER,
                        `platformType` TEXT,
                        `extractionQualityScore` INTEGER,
                        `extractionConfidenceBreakdown` TEXT NOT NULL DEFAULT '{}',
                        `extractionStage` TEXT,
                        `extractionRunPath` TEXT,
                        `extractionTimestamp` INTEGER,
                        `rating` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `needsAttention` INTEGER NOT NULL DEFAULT 0,
                        `storeNameSource` TEXT,
                        `storeNameEvidence` TEXT NOT NULL DEFAULT '[]'
                    )
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    WITH source AS (
                        SELECT
                            `id`,
                            `storeName`,
                            `description`,
                            `expiryDate`,
                            `redeemCode`,
                            `imageUri`,
                            `imagePhash`,
                            `imageSignature`,
                            `category`,
                            `status`,
                            `minimumPurchase`,
                            `maximumDiscount`,
                            `isPriority`,
                            `paymentMethod`,
                            `usageLimit`,
                            `usageCount`,
                            `reminderDate`,
                            `reminderLeadTimeMinutes`,
                            `platformType`,
                            `extractionQualityScore`,
                            `extractionConfidenceBreakdown`,
                            `extractionStage`,
                            `extractionRunPath`,
                            `extractionTimestamp`,
                            `rating`,
                            `createdAt`,
                            `updatedAt`,
                            `needsAttention`,
                            `storeNameSource`,
                            `storeNameEvidence`,
                            CASE
                                WHEN `cashbackType` = 'percent' AND `cashbackValueNum` IS NOT NULL AND `cashbackValueNum` > 0
                                    THEN printf('Cashback: %.0f%% off', `cashbackValueNum`)
                                WHEN `cashbackValueNum` IS NOT NULL AND `cashbackValueNum` > 0
                                    THEN printf(
                                        'Cashback: %s%.0f off',
                                        CASE
                                            WHEN `cashbackCurrency` IS NULL OR TRIM(`cashbackCurrency`) = '' THEN '₹'
                                            WHEN UPPER(`cashbackCurrency`) IN ('INR', '₹', 'RS', 'RS.') THEN '₹'
                                            WHEN UPPER(`cashbackCurrency`) IN ('USD', '$') THEN '$'
                                            WHEN UPPER(`cashbackCurrency`) IN ('EUR', '€') THEN '€'
                                            WHEN UPPER(`cashbackCurrency`) IN ('GBP', '£') THEN '£'
                                            ELSE `cashbackCurrency` || ' '
                                        END,
                                        `cashbackValueNum`
                                    )
                                WHEN `cashbackAmount` IS NOT NULL AND `cashbackAmount` > 0
                                    THEN printf('Cashback: ₹%.0f off', `cashbackAmount`)
                                ELSE NULL
                            END AS `cashbackDetail`
                        FROM `coupons`
                    ),
                    enriched AS (
                        SELECT
                            `id`,
                            `storeName`,
                            `description`,
                            `expiryDate`,
                            `redeemCode`,
                            `imageUri`,
                            `imagePhash`,
                            `imageSignature`,
                            `category`,
                            `status`,
                            `minimumPurchase`,
                            `maximumDiscount`,
                            `isPriority`,
                            `paymentMethod`,
                            `usageLimit`,
                            `usageCount`,
                            `reminderDate`,
                            `reminderLeadTimeMinutes`,
                            `platformType`,
                            `extractionQualityScore`,
                            `extractionConfidenceBreakdown`,
                            `extractionStage`,
                            `extractionRunPath`,
                            `extractionTimestamp`,
                            `rating`,
                            `createdAt`,
                            `updatedAt`,
                            `needsAttention`,
                            `storeNameSource`,
                            `storeNameEvidence`,
                            `cashbackDetail`,
                            CASE
                                WHEN TRIM(COALESCE(`description`, '')) = '' THEN COALESCE(`cashbackDetail`, 'Coupon offer')
                                ELSE TRIM(`description`) ||
                                    CASE
                                        WHEN `cashbackDetail` IS NOT NULL THEN char(10) || `cashbackDetail`
                                        ELSE ''
                                    END
                            END AS `newDescription`
                        FROM source
                    )
                    INSERT INTO `coupons_v13` (
                        `id`, `storeName`, `description`, `normalizedDescription`, `expiryDate`,
                        `redeemCode`, `imageUri`, `imagePhash`, `imageSignature`, `category`,
                        `status`, `minimumPurchase`, `maximumDiscount`, `isPriority`, `paymentMethod`,
                        `usageLimit`, `usageCount`, `reminderDate`, `reminderLeadTimeMinutes`, `platformType`,
                        `extractionQualityScore`, `extractionConfidenceBreakdown`, `extractionStage`, `extractionRunPath`,
                        `extractionTimestamp`, `rating`, `createdAt`, `updatedAt`, `needsAttention`,
                        `storeNameSource`, `storeNameEvidence`
                    )
                    SELECT
                        `id`,
                        `storeName`,
                        `newDescription`,
                        LOWER(`newDescription`),
                        `expiryDate`,
                        `redeemCode`,
                        `imageUri`,
                        `imagePhash`,
                        `imageSignature`,
                        `category`,
                        `status`,
                        `minimumPurchase`,
                        `maximumDiscount`,
                        `isPriority`,
                        `paymentMethod`,
                        `usageLimit`,
                        `usageCount`,
                        `reminderDate`,
                        `reminderLeadTimeMinutes`,
                        `platformType`,
                        `extractionQualityScore`,
                        `extractionConfidenceBreakdown`,
                        `extractionStage`,
                        `extractionRunPath`,
                        `extractionTimestamp`,
                        `rating`,
                        `createdAt`,
                        `updatedAt`,
                        `needsAttention`,
                        `storeNameSource`,
                        COALESCE(`storeNameEvidence`, '[]')
                    FROM enriched
                    """.trimIndent()
                )

                database.execSQL("DROP TABLE `coupons`")
                database.execSQL("ALTER TABLE `coupons_v13` RENAME TO `coupons`")

                database.query("SELECT id, description FROM `coupons`").use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow("id")
                    val descriptionIndex = cursor.getColumnIndexOrThrow("description")
                    val updateStatement = database.compileStatement(
                        "UPDATE `coupons` SET `normalizedDescription` = ? WHERE `id` = ?"
                    )
                    try {
                        while (cursor.moveToNext()) {
                            val description = cursor.getString(descriptionIndex) ?: ""
                            val normalized = CouponDedupUtils.normalizeDescription(description)
                            updateStatement.bindString(1, normalized)
                            updateStatement.bindLong(2, cursor.getLong(idIndex))
                            updateStatement.executeUpdateDelete()
                            updateStatement.clearBindings()
                        }
                    } finally {
                        updateStatement.close()
                    }
                }
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Schema v2: additive optional columns. All nullable TEXT so
                // existing rows survive untouched and writes only happen when
                // SchemaVersionFlag.isV2Enabled() is true.
                database.execSQL("ALTER TABLE coupons ADD COLUMN redeemCodes TEXT")
                database.execSQL("ALTER TABLE coupons ADD COLUMN primaryRedeemCode TEXT")
                database.execSQL("ALTER TABLE coupons ADD COLUMN storeUrl TEXT")
                database.execSQL("ALTER TABLE coupons ADD COLUMN offerType TEXT")
            }
        }
    }
}
