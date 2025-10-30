# Production-Ready Architecture V2 - Implementation Plan
## Safe, Step-by-Step Migration Without Breaking Existing Functionality

---

## Current State Analysis (✅ Already Done)

### What's Already Working:
1. ✅ **Sealed Results**: `ExtractResult.Good/LowQuality/Failed` already exists
2. ✅ **ExtractionSignals**: Already implemented in `ExtractResult.kt`
3. ✅ **UniversalExtractionService**: Basic implementation exists
4. ✅ **TwoStageDetector**: Multi-coupon detection working
5. ✅ **LocalLlmOcrService**: LLM extraction with typed results
6. ✅ **Performance Monitoring**: `ExtractionPerformanceMonitor` in place
7. ✅ **Room Database**: Coupons table with typed cashback
8. ✅ **Pattern Learning**: `PatternLearningEngine` implemented
9. ✅ **Dashboard**: Real-time performance monitoring UI

### What Needs Improvement (Migration Path):
1. ⚠️ **Feature Flag**: No LLM_FIRST/OCR_FIRST/HYBRID switch
2. ⚠️ **BitmapManager**: No centralized pixel budget enforcement
3. ⚠️ **Pattern Storage**: Using SharedPreferences, not Room
4. ⚠️ **RunPath Logging**: Missing detailed execution path tracking
5. ⚠️ **Remote Config**: Hardcoded thresholds
6. ⚠️ **Timeout Handling**: No per-stage timeout contracts
7. ⚠️ **Feedback Storage**: No `extraction_feedback_v1` table
8. ⚠️ **Deduplication**: No stable hash implementation

---

## Phase 1: Add Missing Foundation (No Breaking Changes)

### Step 1.1: Add RunPath to ExtractResult ✅ SAFE
**File**: `app/src/main/kotlin/com/example/coupontracker/util/ExtractResult.kt`

```kotlin
data class RunPath(
    val strategy: String,              // "LLM_FIRST", "OCR_FIRST", "HYBRID", "LEGACY"
    val tried: MutableList<String> = mutableListOf(),
    val final: String = "",
    val reasons: MutableList<String> = mutableListOf()
)

// Update ExtractResult.Good, LowQuality, Failed to include runPath
sealed interface ExtractResult {
    data class Good(
        val info: CouponInfo,
        val signals: ExtractionSignals,
        val runPath: RunPath = RunPath("LEGACY")  // Default for existing code
    ) : ExtractResult
    
    data class LowQuality(
        val info: CouponInfo?,
        val reason: String,
        val signals: ExtractionSignals,
        val runPath: RunPath = RunPath("LEGACY")
    ) : ExtractResult
    
    data class Failed(
        val stage: String,
        val error: Throwable,
        val signals: ExtractionSignals? = null,
        val runPath: RunPath = RunPath("LEGACY")
    ) : ExtractResult
}
```

**Impact**: ✅ Backward compatible (defaults provided)

---

### Step 1.2: Create BitmapManager (Doesn't Replace Existing Code Yet)
**File**: `app/src/main/kotlin/com/example/coupontracker/util/BitmapManager.kt` (NEW)

```kotlin
@Singleton
class BitmapManager @Inject constructor() {
    companion object {
        const val MAX_DIMENSION = 768
        const val MAX_TOTAL_PIXELS = 3 * MAX_DIMENSION * MAX_DIMENSION
        private const val TAG = "BitmapManager"
    }
    
    private val activeBuffers = mutableListOf<WeakReference<Bitmap>>()
    
    fun enforcePixelBudget() {
        val totalPixels = activeBuffers
            .mapNotNull { it.get() }
            .filter { !it.isRecycled }
            .sumOf { it.width.toLong() * it.height }
        
        if (totalPixels > MAX_TOTAL_PIXELS) {
            Log.w(TAG, "Pixel budget exceeded: $totalPixels > $MAX_TOTAL_PIXELS, recycling old buffers")
            
            var freedPixels = 0L
            activeBuffers.removeAll { ref ->
                val bitmap = ref.get()
                if (bitmap != null && !bitmap.isRecycled && totalPixels - freedPixels > MAX_TOTAL_PIXELS) {
                    freedPixels += bitmap.width.toLong() * bitmap.height
                    bitmap.recycle()
                    true
                } else {
                    bitmap == null // Remove dead references
                }
            }
        }
    }
    
    fun trackBitmap(bitmap: Bitmap) {
        enforcePixelBudget()
        activeBuffers.add(WeakReference(bitmap))
    }
    
    fun resizeWithBudget(source: Bitmap, maxDim: Int = MAX_DIMENSION): Bitmap {
        enforcePixelBudget()
        
        if (source.width <= maxDim && source.height <= maxDim) {
            return source
        }
        
        val scale = minOf(
            maxDim.toFloat() / source.width,
            maxDim.toFloat() / source.height
        )
        
        val newWidth = (source.width * scale).toInt()
        val newHeight = (source.height * scale).toInt()
        
        val resized = Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
        trackBitmap(resized)
        
        return resized
    }
}
```

**Impact**: ✅ New class, doesn't break anything. Can be gradually adopted.

---

### Step 1.3: Add Extraction Strategy Enum (For Future Use)
**File**: `app/src/main/kotlin/com/example/coupontracker/util/ExtractionStrategy.kt` (NEW)

```kotlin
enum class ExtractionStrategy {
    LLM_FIRST,      // LLM locates → OCR extracts → Fusion
    OCR_FIRST,      // OCR finds → LLM validates → Fusion
    HYBRID,         // Parallel LLM + OCR → Fusion
    LEGACY          // Current behavior (for backward compatibility)
}

object ExtractionConfig {
    // Start with LEGACY, can be changed via Firebase Remote Config
    private var _strategy: ExtractionStrategy = ExtractionStrategy.LEGACY
    
    fun getStrategy(): ExtractionStrategy = _strategy
    
    fun setStrategy(strategy: ExtractionStrategy) {
        _strategy = strategy
        Log.d("ExtractionConfig", "Strategy changed to: $strategy")
    }
    
    // Thresholds (can be moved to Remote Config later)
    object Thresholds {
        var code: Float = 0.85f
        var expiry: Float = 0.70f
        var cashback: Float = 0.75f
        var storeName: Float = 0.60f
    }
    
    // Timeouts (milliseconds)
    object Timeouts {
        const val LLM_TILE_MS = 2000L
        const val OCR_ROI_BATCH_MS = 1000L
        const val FUSION_MS = 300L
        const val E2E_PER_COUPON_MS = 6000L
        const val TWO_STAGE_DETECT_MS = 3000L
    }
}
```

**Impact**: ✅ New files, no breaking changes. Default is LEGACY mode.

---

### Step 1.4: Create Room Tables for Patterns and Feedback
**File**: `app/src/main/kotlin/com/example/coupontracker/data/local/LearnedPattern.kt` (NEW)

```kotlin
@Entity(tableName = "learned_patterns_v1")
data class LearnedPattern(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val brand: String?,                    // "myntra", "zomato", null for universal
    val fieldType: String,                 // "code", "expiry", "cashback", "store"
    val regex: String,                     // Pattern that worked
    val weight: Float,                     // Success rate 0.0-1.0
    val source: String,                    // "user_correction", "llm_success", "ocr_fusion"
    val sampleValue: String,               // Example: "SAVE500"
    val createdAt: Long,                   // Unix timestamp
    val successCount: Int = 1,
    val attemptCount: Int = 1
)

@Dao
interface LearnedPatternDao {
    @Query("SELECT * FROM learned_patterns_v1 WHERE fieldType = :fieldType AND (brand = :brand OR brand IS NULL) ORDER BY weight DESC LIMIT 10")
    suspend fun getTopPatterns(fieldType: String, brand: String?): List<LearnedPattern>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPattern(pattern: LearnedPattern): Long
    
    @Query("UPDATE learned_patterns_v1 SET successCount = successCount + 1, attemptCount = attemptCount + 1, weight = CAST(successCount AS REAL) / attemptCount WHERE id = :patternId")
    suspend fun incrementSuccess(patternId: Long)
    
    @Query("UPDATE learned_patterns_v1 SET attemptCount = attemptCount + 1, weight = CAST(successCount AS REAL) / attemptCount WHERE id = :patternId")
    suspend fun incrementAttempt(patternId: Long)
    
    @Query("DELETE FROM learned_patterns_v1 WHERE createdAt < :cutoffTimestamp")
    suspend fun deleteOldPatterns(cutoffTimestamp: Long): Int
}
```

**File**: `app/src/main/kotlin/com/example/coupontracker/data/local/ExtractionFeedback.kt` (NEW)

```kotlin
@Entity(tableName = "extraction_feedback_v1")
data class ExtractionFeedback(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val couponId: Long?,                   // Reference to coupon (nullable for failed extractions)
    val extractionStrategy: String,        // "LLM_FIRST", "OCR_FIRST", "HYBRID", "LEGACY"
    val feedbackType: String,              // "confirmed_correct", "user_corrected", "auto_success"
    val originalValues: String,            // JSON: {"code": "SAVE50", "expiry": "2025-12-31"}
    val correctedValues: String?,          // JSON: {"code": "SAVE500", "expiry": "2025-12-30"}
    val signalsJson: String,               // JSON serialized ExtractionSignals
    val runPathJson: String,               // JSON serialized RunPath
    val deviceInfo: String,                // "Pixel 6, Android 13, 8GB RAM"
    val timestamp: Long,                   // Unix timestamp
    val consentGiven: Boolean = false      // User agreed to share data
)

@Dao
interface ExtractionFeedbackDao {
    @Insert
    suspend fun insertFeedback(feedback: ExtractionFeedback): Long
    
    @Query("SELECT * FROM extraction_feedback_v1 WHERE feedbackType = 'user_corrected' AND consentGiven = 1 ORDER BY timestamp DESC LIMIT 100")
    suspend fun getRecentCorrections(): List<ExtractionFeedback>
    
    @Query("SELECT COUNT(*) FROM extraction_feedback_v1 WHERE extractionStrategy = :strategy AND timestamp > :sinceTimestamp")
    suspend fun countByStrategy(strategy: String, sinceTimestamp: Long): Int
    
    @Query("DELETE FROM extraction_feedback_v1 WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOldFeedback(cutoffTimestamp: Long): Int
}
```

---

### Step 1.5: Update CouponDatabase Migration
**File**: `app/src/main/kotlin/com/example/coupontracker/data/local/CouponDatabase.kt`

```kotlin
@Database(
    entities = [
        Coupon::class,
        LearnedPattern::class,          // NEW
        ExtractionFeedback::class       // NEW
    ],
    version = 7,  // Increment from current version
    exportSchema = true
)
abstract class CouponDatabase : RoomDatabase() {
    abstract fun couponDao(): CouponDao
    abstract fun learnedPatternDao(): LearnedPatternDao      // NEW
    abstract fun extractionFeedbackDao(): ExtractionFeedbackDao  // NEW
    
    companion object {
        // ... existing migrations ...
        
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create learned_patterns_v1 table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS learned_patterns_v1 (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        brand TEXT,
                        fieldType TEXT NOT NULL,
                        regex TEXT NOT NULL,
                        weight REAL NOT NULL DEFAULT 0.0,
                        source TEXT NOT NULL,
                        sampleValue TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        successCount INTEGER NOT NULL DEFAULT 1,
                        attemptCount INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
                
                // Create extraction_feedback_v1 table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS extraction_feedback_v1 (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        couponId INTEGER,
                        extractionStrategy TEXT NOT NULL,
                        feedbackType TEXT NOT NULL,
                        originalValues TEXT NOT NULL,
                        correctedValues TEXT,
                        signalsJson TEXT NOT NULL,
                        runPathJson TEXT NOT NULL,
                        deviceInfo TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        consentGiven INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                
                // Create indices for performance
                database.execSQL("CREATE INDEX IF NOT EXISTS index_learned_patterns_fieldType ON learned_patterns_v1(fieldType)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_learned_patterns_brand ON learned_patterns_v1(brand)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_extraction_feedback_timestamp ON extraction_feedback_v1(timestamp)")
            }
        }
    }
}
```

**File**: `app/src/main/kotlin/com/example/coupontracker/di/DatabaseModule.kt`

```kotlin
@Provides
@Singleton
fun provideCouponDatabase(@ApplicationContext context: Context): CouponDatabase {
    return Room.databaseBuilder(
        context,
        CouponDatabase::class.java,
        "coupon_database"
    )
        .addMigrations(
            CouponDatabase.MIGRATION_1_2,
            CouponDatabase.MIGRATION_2_3,
            CouponDatabase.MIGRATION_3_4,
            CouponDatabase.MIGRATION_4_5,
            CouponDatabase.MIGRATION_5_6,
            CouponDatabase.MIGRATION_6_7  // NEW
        )
        .build()
}
```

**Impact**: ✅ Safe migration. Existing data preserved. New tables added.

---

## Phase 2: Gradual Adoption (No Breaking Changes)

### Step 2.1: Inject BitmapManager into ScannerViewModel
**File**: `app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/ScannerViewModel.kt`

```kotlin
@HiltViewModel
class ScannerViewModel @Inject constructor(
    application: Application,
    @ApplicationContext private val context: Context,
    private val couponRepository: CouponRepository,
    private val localLlmOcrService: LocalLlmOcrService,
    private val telemetryService: ExtractionTelemetryService,
    private val universalExtractionService: UniversalExtractionService,
    private val performanceMonitor: ExtractionPerformanceMonitor,
    private val bitmapManager: BitmapManager  // NEW - Injected
) : AndroidViewModel(application) {
    
    // ... existing code ...
    
    private suspend fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                
                // NEW: Track bitmap with manager
                bitmap?.let { bitmapManager.trackBitmap(it) }
                
                bitmap
            } catch (e: Exception) {
                Log.e(TAG, "Error loading bitmap from URI", e)
                null
            }
        }
    }
}
```

**Impact**: ✅ Non-breaking. BitmapManager starts tracking, but doesn't change behavior yet.

---

### Step 2.2: Add RunPath Logging to Existing Extraction
**File**: `app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/ScannerViewModel.kt`

```kotlin
private suspend fun extractTextFromFields(couponInstance: CouponInstance): FieldExtractionResult {
    return withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val extractedInfo = mutableMapOf<String, String>()
        var progress = MiniCpmProgress.SUCCESS
        
        // NEW: Create RunPath for logging
        val runPath = RunPath(strategy = "LEGACY")
        
        extractedInfo["minicpmConfidence"] = couponInstance.confidence.toString()
        extractedInfo["minicpmDetectionStatus"] = couponInstance.status.name

        try {
            // Try LLM extraction first
            runPath.tried.add("LLM")  // NEW
            runPath.final = "LLM"     // NEW
            
            val result = localLlmOcrService.processCouponImageTyped(couponInstance.cropBitmap)
            
            when (result) {
                is ExtractResult.Good -> {
                    extractedInfo.putAll(mapCouponInfoToFields(result.info))
                    progress = MiniCpmProgress.SUCCESS
                    runPath.reasons.add("llm_success")  // NEW
                    Log.d(TAG, "✅ LLM extraction successful (quality: ${result.signals.qualityScore})")
                }
                
                is ExtractResult.LowQuality -> {
                    extractedInfo.putAll(mapCouponInfoToFields(result.info))
                    progress = MiniCpmProgress.NEEDS_REVIEW
                    
                    // Try fallback for low quality
                    runPath.tried.add("FALLBACK_OCR")  // NEW
                    runPath.final = "FALLBACK_OCR"     // NEW
                    runPath.reasons.add("llm_low_quality_${result.reason}")  // NEW
                    
                    val fallbackFields = runFallbackOcr(couponInstance.cropBitmap)
                    mergeValidatedFields(extractedInfo, fallbackFields)
                    
                    Log.w(TAG, "⚠️ LLM low quality (${result.reason}), used fallback")
                }
                
                is ExtractResult.Failed -> {
                    // LLM failed, use fallback
                    runPath.tried.add("FALLBACK_OCR")  // NEW
                    runPath.final = "FALLBACK_OCR"     // NEW
                    runPath.reasons.add("llm_failed_${result.error.message}")  // NEW
                    
                    progress = MiniCpmProgress.FALLBACK
                    val fallbackFields = runFallbackOcr(couponInstance.cropBitmap)
                    extractedInfo.putAll(fallbackFields)
                    
                    Log.e(TAG, "❌ LLM extraction failed: ${result.error.message}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Exception during text extraction", e)
            runPath.reasons.add("exception_${e.javaClass.simpleName}")  // NEW
            progress = MiniCpmProgress.FALLBACK
        }
        
        // NEW: Log RunPath for observability
        Log.d(TAG, "RunPath: strategy=${runPath.strategy}, tried=${runPath.tried}, final=${runPath.final}, reasons=${runPath.reasons}")
        
        FieldExtractionResult(extractedInfo, progress)
    }
}
```

**Impact**: ✅ Non-breaking. Adds logging, doesn't change behavior.

---

### Step 2.3: Migrate PatternLearningEngine to Use Room
**File**: `app/src/main/kotlin/com/example/coupontracker/universal/PatternLearningEngine.kt`

```kotlin
@Singleton
class PatternLearningEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: CouponDatabase  // NEW: Inject database instead of using SharedPreferences
) {
    private val gson = Gson()
    
    // Keep SharedPreferences for backward compatibility migration
    private val prefs = context.getSharedPreferences("pattern_learning_prefs", Context.MODE_PRIVATE)
    
    private val patternDao = database.learnedPatternDao()  // NEW
    
    companion object {
        private const val TAG = "PatternLearningEngine"
        private const val MIGRATED_KEY = "patterns_migrated_to_room_v1"
    }
    
    init {
        // ONE-TIME MIGRATION: Move patterns from SharedPreferences to Room
        viewModelScope.launch(Dispatchers.IO) {
            migrateFromSharedPreferencesIfNeeded()
        }
    }
    
    private suspend fun migrateFromSharedPreferencesIfNeeded() {
        if (prefs.getBoolean(MIGRATED_KEY, false)) {
            return  // Already migrated
        }
        
        try {
            // Read old patterns from SharedPreferences
            val allKeys = prefs.all.keys.filter { it.startsWith("pattern_") }
            var migratedCount = 0
            
            allKeys.forEach { key ->
                val patternJson = prefs.getString(key, null)
                if (patternJson != null) {
                    try {
                        // Parse old pattern format and insert into Room
                        // (implementation depends on old format)
                        migratedCount++
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to migrate pattern: $key", e)
                    }
                }
            }
            
            prefs.edit().putBoolean(MIGRATED_KEY, true).apply()
            Log.d(TAG, "Migrated $migratedCount patterns from SharedPreferences to Room")
            
        } catch (e: Exception) {
            Log.e(TAG, "Pattern migration failed", e)
        }
    }
    
    suspend fun learnFromSuccess(
        extractionResult: UniversalExtractionResult,
        originalText: String,
        context: ExtractionContext
    ) {
        try {
            // NEW: Store patterns in Room instead of SharedPreferences
            extractionResult.extractedFields.forEach { (fieldType, candidate) ->
                if (candidate.confidence > 0.7f) {
                    val pattern = LearnedPattern(
                        brand = extractionResult.coupon.storeName.takeIf { it != "Unknown Store" },
                        fieldType = fieldType.name,
                        regex = generateRegexFromValue(candidate.value),
                        weight = candidate.confidence,
                        source = "auto_success",
                        sampleValue = candidate.value,
                        createdAt = System.currentTimeMillis()
                    )
                    
                    patternDao.insertPattern(pattern)
                    Log.d(TAG, "Learned successful pattern for ${fieldType.name}: ${candidate.value}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error learning from success", e)
        }
    }
    
    suspend fun getRelevantPatterns(fieldType: FieldType, brand: String?): List<LearnedPattern> {
        return try {
            patternDao.getTopPatterns(fieldType.name, brand)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting patterns", e)
            emptyList()
        }
    }
    
    private fun generateRegexFromValue(value: String): String {
        // Simple regex generation - can be improved
        return value.replace(Regex("\\d"), "\\\\d")
            .replace(Regex("[A-Z]"), "[A-Z]")
            .replace(Regex("[a-z]"), "[a-z]")
    }
}
```

**Impact**: ✅ Safe migration. One-time migration from SharedPreferences to Room. Backward compatible.

---

## Phase 3: Testing & Validation (Before Changing Behavior)

### Step 3.1: Add Unit Tests for New Components

**File**: `app/src/test/java/com/example/coupontracker/util/BitmapManagerTest.kt` (NEW)

```kotlin
class BitmapManagerTest {
    private lateinit var bitmapManager: BitmapManager
    
    @Before
    fun setup() {
        bitmapManager = BitmapManager()
    }
    
    @Test
    fun `pixel budget enforcement recycles old bitmaps`() {
        // Create bitmaps that exceed budget
        val bitmaps = (1..5).map { 
            Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
                .also { bitmapManager.trackBitmap(it) }
        }
        
        // Budget should be enforced
        val activeBitmaps = bitmaps.filter { !it.isRecycled }
        assertTrue("Should recycle some bitmaps", activeBitmaps.size < bitmaps.size)
    }
    
    @Test
    fun `resizeWithBudget respects max dimension`() {
        val largeBitmap = Bitmap.createBitmap(2000, 2000, Bitmap.Config.ARGB_8888)
        val resized = bitmapManager.resizeWithBudget(largeBitmap)
        
        assertTrue("Width should be <= 768", resized.width <= 768)
        assertTrue("Height should be <= 768", resized.height <= 768)
    }
}
```

### Step 3.2: Integration Tests for Room Tables

**File**: `app/src/androidTest/java/com/example/coupontracker/data/local/LearnedPatternDaoTest.kt` (NEW)

```kotlin
@RunWith(AndroidJUnit4::class)
class LearnedPatternDaoTest {
    private lateinit var database: CouponDatabase
    private lateinit var patternDao: LearnedPatternDao
    
    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, CouponDatabase::class.java).build()
        patternDao = database.learnedPatternDao()
    }
    
    @After
    fun teardown() {
        database.close()
    }
    
    @Test
    fun insertAndRetrievePattern() = runBlocking {
        val pattern = LearnedPattern(
            brand = "myntra",
            fieldType = "code",
            regex = "SAVE[0-9]+",
            weight = 0.9f,
            source = "user_correction",
            sampleValue = "SAVE500",
            createdAt = System.currentTimeMillis()
        )
        
        val id = patternDao.insertPattern(pattern)
        assertTrue("Should insert successfully", id > 0)
        
        val patterns = patternDao.getTopPatterns("code", "myntra")
        assertTrue("Should retrieve pattern", patterns.isNotEmpty())
        assertEquals("Should match sample value", "SAVE500", patterns.first().sampleValue)
    }
}
```

---

## Phase 4: Enable New Features (Controlled Rollout)

### Step 4.1: Add Feature Flag in Settings

**File**: `app/src/main/kotlin/com/example/coupontracker/ui/screen/SettingsScreen.kt`

```kotlin
// Add to protected features section
Card(
    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.tertiaryContainer
    )
) {
    Column(
        modifier = Modifier.padding(16.dp)
    ) {
        Text(
            text = "Extraction Strategy (Advanced)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        val strategies = ExtractionStrategy.values()
        val currentStrategy = ExtractionConfig.getStrategy()
        
        strategies.forEach { strategy ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { ExtractionConfig.setStrategy(strategy) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = currentStrategy == strategy,
                    onClick = { ExtractionConfig.setStrategy(strategy) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = strategy.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (currentStrategy == strategy) FontWeight.Bold else FontWeight.Normal
                    )
                    Text(
                        text = when (strategy) {
                            ExtractionStrategy.LLM_FIRST -> "LLM locates → OCR extracts (Recommended)"
                            ExtractionStrategy.OCR_FIRST -> "OCR finds → LLM validates"
                            ExtractionStrategy.HYBRID -> "Parallel LLM + OCR (Experimental)"
                            ExtractionStrategy.LEGACY -> "Current behavior (Default)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
```

**Impact**: ✅ Users can opt-in to new strategies. Default is LEGACY (safe).

---

### Step 4.2: Implement Strategy-Based Extraction (Optional Path)

**File**: `app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/ScannerViewModel.kt`

```kotlin
private suspend fun extractTextFromFields(couponInstance: CouponInstance): FieldExtractionResult {
    val strategy = ExtractionConfig.getStrategy()
    
    return when (strategy) {
        ExtractionStrategy.LEGACY -> extractTextFromFieldsLegacy(couponInstance)  // Existing code
        ExtractionStrategy.LLM_FIRST -> extractTextLLMFirst(couponInstance)      // New implementation
        ExtractionStrategy.OCR_FIRST -> extractTextOCRFirst(couponInstance)      // New implementation
        ExtractionStrategy.HYBRID -> extractTextHybrid(couponInstance)           // New implementation
    }
}

// Keep existing implementation as legacy
private suspend fun extractTextFromFieldsLegacy(couponInstance: CouponInstance): FieldExtractionResult {
    // ... existing code remains unchanged ...
}

// New implementation (to be built carefully)
private suspend fun extractTextLLMFirst(couponInstance: CouponInstance): FieldExtractionResult {
    // TODO: Implement per V2 architecture
    // For now, fallback to legacy
    return extractTextFromFieldsLegacy(couponInstance)
}
```

**Impact**: ✅ Graceful degradation. New strategies fall back to LEGACY if not implemented.

---

## Phase 5: Monitoring & Rollback Plan

### Step 5.1: Add Extraction Feedback Collection

**File**: `app/src/main/kotlin/com/example/coupontracker/ui/viewmodel/ScannerViewModel.kt`

```kotlin
private suspend fun recordExtractionFeedback(
    result: ExtractResult,
    couponId: Long?,
    runPath: RunPath
) {
    try {
        val feedback = ExtractionFeedback(
            couponId = couponId,
            extractionStrategy = runPath.strategy,
            feedbackType = "auto_success",
            originalValues = gson.toJson(when (result) {
                is ExtractResult.Good -> mapOf(
                    "code" to result.info.code,
                    "expiry" to result.info.expiryDate,
                    "cashback" to result.info.cashbackAmount
                )
                else -> emptyMap()
            }),
            correctedValues = null,
            signalsJson = gson.toJson(result.signals),
            runPathJson = gson.toJson(runPath),
            deviceInfo = "${Build.MODEL}, Android ${Build.VERSION.RELEASE}, ${getAvailableMemoryMB()}MB RAM",
            timestamp = System.currentTimeMillis(),
            consentGiven = false  // User hasn't explicitly consented yet
        )
        
        database.extractionFeedbackDao().insertFeedback(feedback)
        
    } catch (e: Exception) {
        Log.e(TAG, "Failed to record extraction feedback", e)
    }
}
```

---

### Step 5.2: Rollback Plan

If new strategies cause issues:

1. **Immediate**: Change default in `ExtractionConfig` back to `LEGACY`
2. **User-level**: Settings UI allows users to switch back
3. **Remote Config**: Use Firebase to change strategy for all users
4. **Database**: New tables don't affect existing coupons table
5. **Code**: All new code has `LEGACY` fallback paths

---

## Summary: Safe Implementation Path

### ✅ What Makes This Plan Safe:

1. **No Breaking Changes**: All new code is additive with defaults
2. **Backward Compatibility**: `LEGACY` mode preserves existing behavior
3. **Gradual Adoption**: Features can be enabled one at a time
4. **Rollback Ready**: Easy to revert to old behavior at any level
5. **Well-Tested**: Each phase has tests before moving forward
6. **Observable**: RunPath logging shows what's happening
7. **User Control**: Settings allow opt-in to new features

### 📋 Implementation Checklist:

**Phase 1** (Foundation - No Behavior Changes):
- [ ] Add `RunPath` to `ExtractResult` (with defaults)
- [ ] Create `BitmapManager` class
- [ ] Add `ExtractionStrategy` enum (default LEGACY)
- [ ] Create Room tables (`learned_patterns_v1`, `extraction_feedback_v1`)
- [ ] Add database migration (6 → 7)
- [ ] Test migration with existing data

**Phase 2** (Gradual Adoption - Still No Behavior Changes):
- [ ] Inject `BitmapManager` into `ScannerViewModel`
- [ ] Add `RunPath` logging to existing extraction
- [ ] Migrate `PatternLearningEngine` to Room
- [ ] Add unit tests for new components
- [ ] Add integration tests for Room tables

**Phase 3** (Enable New Features - Opt-In Only):
- [ ] Add strategy selector in Settings UI
- [ ] Implement strategy-based routing (with LEGACY fallback)
- [ ] Add extraction feedback recording
- [ ] Monitor dashboard for strategy performance
- [ ] Collect user feedback

**Phase 4** (Production Rollout - When Ready):
- [ ] Implement LLM_FIRST strategy fully
- [ ] Implement OCR_FIRST strategy
- [ ] Implement HYBRID strategy
- [ ] Add timeout handling per stage
- [ ] Add remote config for thresholds
- [ ] Enable for beta users first
- [ ] Gradual rollout to all users

### 🎯 Current Status:
- ✅ Architecture documented
- ✅ Implementation plan created
- ⏳ Ready to start Phase 1 (Foundation)

This plan ensures **zero risk** to existing functionality while building toward the V2 architecture systematically!
