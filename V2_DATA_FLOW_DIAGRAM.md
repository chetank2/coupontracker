# V2 Architecture - Complete Data Flow & Extraction Pipeline
## With Critical Fixes Applied (Reference Counting + Strategy Persistence)

---

## 1. High-Level Entry Points

```mermaid
graph TB
    subgraph "User Entry Points"
        A1[Camera Capture]
        A2[Gallery Selection]
        A3[Shared Image]
        A4[Manual Entry]
        A5[Batch Upload]
    end
    
    A1 --> B[MainActivity Intent Handler]
    A2 --> B
    A3 --> B
    A4 --> C[ManualEntryScreen]
    A5 --> D[BatchScannerScreen]
    
    B --> E{Intent Type?}
    E -->|Single Image| F[ScannerScreen]
    E -->|Multiple Images| D
    E -->|Text/URL| C
    
    F --> G[ScannerViewModel]
    D --> H[BatchScannerViewModel]
    C --> I[ManualEntryViewModel]
    
    G --> J[Extraction Pipeline]
    H --> J
    I --> K[URL Processor]
    
    style A1 fill:#e1f5ff
    style A2 fill:#e1f5ff
    style A3 fill:#e1f5ff
    style A4 fill:#e1f5ff
    style A5 fill:#e1f5ff
```

---

## 2. Extraction Strategy Selection (V2 - With Persistence Fix)

```mermaid
graph TB
    subgraph "Strategy Configuration"
        SC1[App Startup]
        SC2[Settings UI]
        SC3[Remote Config Optional]
    end
    
    SC1 --> EC[ExtractionConfig.init context]
    SC2 --> EC
    SC3 --> EC
    
    EC --> SP[SharedPreferences: extraction_strategy]
    SP -->|Load| ES{Current Strategy?}
    
    ES -->|Default| LEGACY[LEGACY Mode]
    ES -->|User Selected| LLM_FIRST[LLM_FIRST Mode]
    ES -->|User Selected| OCR_FIRST[OCR_FIRST Mode]
    ES -->|User Selected| HYBRID[HYBRID Mode]
    
    LEGACY --> EP[Extraction Pipeline]
    LLM_FIRST --> EP
    OCR_FIRST --> EP
    HYBRID --> EP
    
    SC2 -->|User Changes Strategy| PERSIST[ExtractionConfig.setStrategy]
    PERSIST -->|Immediately Save| SP
    
    style PERSIST fill:#90EE90
    style SP fill:#FFD700
    style ES fill:#87CEEB
```

---

## 3. Bitmap Memory Management (V2 - With Reference Counting Fix)

```mermaid
graph TB
    subgraph "Bitmap Lifecycle"
        IMG[Image URI] --> LOAD[loadBitmapFromUri]
        LOAD --> BM[BitmapManager]
        
        BM --> TRACK[trackBitmap bitmap]
        TRACK --> CHECK{Already Tracked?}
        
        CHECK -->|Yes| INC[Increment refCount]
        CHECK -->|No| NEW[Create ManagedBitmap refCount=1]
        
        INC --> BUDGET[enforcePixelBudgetInternal]
        NEW --> BUDGET
        
        BUDGET --> CALC[Calculate Total Pixels]
        CALC --> OVER{Over Budget?}
        
        OVER -->|No| CONT[Continue]
        OVER -->|Yes| FREE[Find Unreferenced Bitmaps]
        
        FREE --> SORT[Sort by Age Oldest First]
        SORT --> REC[Recycle Until Under Budget]
        REC --> LOG[Log Freed Pixels]
        LOG --> CONT
        
        CONT --> USE[Bitmap In Use]
        USE --> REL[releaseBitmap bitmap]
        
        REL --> DEC[Decrement refCount]
        DEC --> ZERO{refCount == 0?}
        
        ZERO -->|Yes| RECYCLE[bitmap.recycle]
        ZERO -->|No| KEEP[Keep Alive]
        
        RECYCLE --> REMOVE[Remove from managedBitmaps]
    end
    
    style TRACK fill:#90EE90
    style BUDGET fill:#FFD700
    style FREE fill:#FFA500
    style RECYCLE fill:#FF6347
    style KEEP fill:#87CEEB
```

---

## 4. Primary Extraction Pipeline (Strategy-Driven)

```mermaid
graph TB
    subgraph "Extraction Entry"
        E1[ScannerViewModel.scanImage]
        E2[BatchScannerViewModel.processImages]
    end
    
    E1 --> BM1[BitmapManager.trackBitmap]
    E2 --> BM1
    
    BM1 --> STRAT{Get Strategy}
    
    STRAT -->|LEGACY| LEGACY_PATH[Legacy Extraction]
    STRAT -->|LLM_FIRST| LLM_PATH[LLM-First Pipeline]
    STRAT -->|OCR_FIRST| OCR_PATH[OCR-First Pipeline]
    STRAT -->|HYBRID| HYBRID_PATH[Hybrid Pipeline]
    
    subgraph "LEGACY Path Current"
        LEGACY_PATH --> TS1[TwoStageDetector]
        TS1 --> STAGE1[Stage 1: Detect Coupon Instances]
        STAGE1 --> STAGE2[Stage 2: Detect Fields in Each Coupon]
        STAGE2 --> VAL1[CouponInstanceValidator]
        VAL1 --> LLM1[LocalLlmOcrService per Instance]
        LLM1 --> FUSION1[LlmOcrFusionService]
        FUSION1 --> PERSIST1[Persist to Room]
    end
    
    subgraph "LLM_FIRST Path V2 Ready"
        LLM_PATH --> LLM2[LLM Locates Field ROIs]
        LLM2 --> OCR2[OCR Extracts Text from ROIs]
        OCR2 --> FUSION2[Fusion Decides]
        FUSION2 --> PATTERN2[Pattern Learning]
        PATTERN2 --> PERSIST2[Persist to Room]
    end
    
    subgraph "OCR_FIRST Path V2 Ready"
        OCR_PATH --> OCR3[OCR Finds Text Regions]
        OCR3 --> LLM3[LLM Validates & Labels]
        LLM3 --> FUSION3[Fusion Decides]
        FUSION3 --> PATTERN3[Pattern Learning]
        PATTERN3 --> PERSIST3[Persist to Room]
    end
    
    subgraph "HYBRID Path V2 Ready"
        HYBRID_PATH --> PARALLEL[Parallel LLM + OCR]
        PARALLEL --> LLM4[LLM Branch]
        PARALLEL --> OCR4[OCR Branch]
        LLM4 --> FUSION4[Fusion Arbitrates]
        OCR4 --> FUSION4
        FUSION4 --> PATTERN4[Pattern Learning]
        PATTERN4 --> PERSIST4[Persist to Room]
    end
    
    PERSIST1 --> BM_REL1[BitmapManager.releaseBitmap]
    PERSIST2 --> BM_REL2[BitmapManager.releaseBitmap]
    PERSIST3 --> BM_REL3[BitmapManager.releaseBitmap]
    PERSIST4 --> BM_REL4[BitmapManager.releaseBitmap]
    
    style LEGACY_PATH fill:#87CEEB
    style LLM_PATH fill:#90EE90
    style OCR_PATH fill:#FFD700
    style HYBRID_PATH fill:#FFA500
```

---

## 5. Two-Stage Detection (LEGACY Path Detail)

```mermaid
graph TB
    subgraph "Two-Stage Detector"
        IN[Input Bitmap] --> BM_TRACK[BitmapManager.trackBitmap]
        BM_TRACK --> S1[Stage 1 Model: Detect Coupon Instances]
        
        S1 --> BOXES[Bounding Boxes for Each Coupon]
        BOXES --> CROP{For Each Coupon}
        
        CROP --> BM_CROP[BitmapManager.cropWithBudget]
        BM_CROP --> CROP_IMG[Cropped Coupon Bitmap]
        
        CROP_IMG --> S2[Stage 2 Model: Detect Fields]
        S2 --> FIELDS[Field Boxes: code expiry amount store]
        
        FIELDS --> VAL[CouponInstanceValidator]
        VAL --> CHECK{Valid Instance?}
        
        CHECK -->|Yes| INST[Create CouponInstance]
        CHECK -->|No| SKIP[Skip Overlapping/Invalid]
        
        INST --> EXTRACT[Extract Per-Coupon]
        SKIP --> NEXT[Next Coupon]
        
        EXTRACT --> BM_REL[BitmapManager.releaseBitmap cropped]
    end
    
    style BM_TRACK fill:#90EE90
    style BM_CROP fill:#90EE90
    style BM_REL fill:#FF6347
```

---

## 6. LLM + OCR Fusion (All Paths)

```mermaid
graph TB
    subgraph "Fusion Service"
        LLM_IN[LLM Result] --> PARSE[Parse JSON Schema]
        OCR_IN[OCR Text Spans] --> EXTRACT[Extract Clean Tokens]
        
        PARSE --> LLM_FIELDS[LLM Fields + Confidence]
        EXTRACT --> OCR_FIELDS[OCR Fields + Confidence]
        
        LLM_FIELDS --> FUSE{Per-Field Fusion}
        OCR_FIELDS --> FUSE
        
        FUSE --> CODE_FUSE[Fuse Coupon Code]
        FUSE --> EXP_FUSE[Fuse Expiry Date]
        FUSE --> AMT_FUSE[Fuse Cashback Amount]
        FUSE --> STORE_FUSE[Fuse Store Name]
        
        CODE_FUSE --> CODE_VAL[Universal Code Validation]
        CODE_VAL --> CODE_RANK[Rank by Pattern + Context]
        CODE_RANK --> CODE_FINAL[Best Code]
        
        EXP_FUSE --> DATE_PARSE[IndianDateParser multi-format]
        DATE_PARSE --> DATE_CONF[Calculate Confidence]
        DATE_CONF --> DATE_FINAL[Best Date]
        
        AMT_FUSE --> CURR_PARSE[IndianCurrencyParser]
        CURR_PARSE --> TYPE_DETECT[Detect % vs ₹]
        TYPE_DETECT --> AMT_FINAL[Typed Cashback]
        
        STORE_FUSE --> BRAND_DETECT[Brand Detection]
        BRAND_DETECT --> STORE_FINAL[Store Name]
        
        CODE_FINAL --> RESULT[Fused CouponInfo]
        DATE_FINAL --> RESULT
        AMT_FINAL --> RESULT
        STORE_FINAL --> RESULT
        
        RESULT --> POLICY{Confidence Policy}
        POLICY -->|High| GOOD[ExtractResult.Good]
        POLICY -->|Medium| LOW[ExtractResult.LowQuality]
        POLICY -->|Low| FAIL[ExtractResult.Failed]
    end
    
    style CODE_VAL fill:#90EE90
    style DATE_PARSE fill:#90EE90
    style CURR_PARSE fill:#90EE90
    style GOOD fill:#90EE90
    style LOW fill:#FFD700
    style FAIL fill:#FF6347
```

---

## 7. Pattern Learning & Feedback Loop

```mermaid
graph TB
    subgraph "Self-Learning System"
        SUCCESS[Successful Extraction] --> LEARN1[PatternLearningEngine.learnFromSuccess]
        CORRECTION[User Correction] --> LEARN2[PatternLearningEngine.learnFromCorrection]
        
        LEARN1 --> EXTRACT_PAT[Extract Pattern from Value]
        LEARN2 --> EXTRACT_PAT
        
        EXTRACT_PAT --> RECORD[recordPattern]
        
        RECORD --> CHECK{Pattern Exists?}
        
        CHECK -->|Yes| UPDATE[Update Stats in SharedPrefs]
        CHECK -->|No| CREATE[Create New Pattern in SharedPrefs]
        
        UPDATE --> CONF[Recalculate Confidence]
        CREATE --> CONF
        
        CONF --> SAVE[saveLearnedPatterns to SharedPrefs]
        
        SAVE --> MIGRATE{Migration to Room?}
        MIGRATE -->|First Run| ROOM_MIG[Migrate All Patterns to Room]
        MIGRATE -->|Already Migrated| SKIP_MIG[Skip Migration]
        
        ROOM_MIG --> ROOM_DB[(Room: learned_patterns_v1)]
        
        ROOM_DB --> QUERY[Future: Query Patterns from Room]
        SAVE --> QUERY_OLD[Current: Query from SharedPrefs]
    end
    
    subgraph "Feedback Collection"
        UI[ExtractionFeedbackDialog] --> CONFIRM[User Confirms Correct]
        UI --> CORRECT[User Corrects Fields]
        
        CONFIRM --> FB1[ScannerViewModel.confirmExtractionCorrect]
        CORRECT --> FB2[ScannerViewModel.submitExtractionCorrection]
        
        FB1 --> MON1[ExtractionPerformanceMonitor.recordUserFeedback]
        FB2 --> MON1
        
        MON1 --> ROOM_FB[(Room: extraction_feedback_v1)]
    end
    
    style SAVE fill:#FFD700
    style ROOM_MIG fill:#87CEEB
    style ROOM_DB fill:#90EE90
    style QUERY_OLD fill:#FFA500
```

---

## 8. Data Persistence Flow

```mermaid
graph TB
    subgraph "Coupon Data Flow"
        EXTRACT[Extraction Complete] --> COUPON[Create Coupon Entity]
        
        COUPON --> URI[Image URI]
        URI --> PERSIST_URI[UriPersistenceManager.persistUri]
        PERSIST_URI --> COPY[Copy to App Private Storage]
        COPY --> FILE[Internal File Path]
        
        COUPON --> TYPED[Typed Cashback Fields]
        TYPED --> TYPE{Cashback Type?}
        TYPE -->|Percent| PCT[cashbackType=PERCENT]
        TYPE -->|Amount| AMT[cashbackType=AMOUNT]
        TYPE -->|Text| TXT[cashbackType=TEXT]
        
        PCT --> FIELDS[All Coupon Fields]
        AMT --> FIELDS
        TXT --> FIELDS
        
        FILE --> FIELDS
        
        FIELDS --> DAO[CouponDao.insert]
        DAO --> ROOM[(Room: coupons table v7)]
        
        ROOM --> MIGRATION{Migration Needed?}
        MIGRATION -->|v6→v7| MIG67[MIGRATION_6_7: Add cashback fields]
        MIGRATION -->|Already v7| SKIP[Skip]
        
        MIG67 --> BACKFILL[Backfill cashbackType from amount]
        BACKFILL --> INDEXED[Indexed by brand category expiry]
    end
    
    subgraph "Query & Display"
        ROOM --> HOME[HomeViewModel.loadCoupons]
        HOME --> FILTER{Apply Filters}
        
        FILTER -->|Category| CAT_FILTER[Case-Insensitive Category Match]
        FILTER -->|Search| SEARCH_FILTER[Store/Code Search]
        FILTER -->|Status| STATUS_FILTER[Active/Expired/All]
        
        CAT_FILTER --> SORT{Apply Sort}
        SEARCH_FILTER --> SORT
        STATUS_FILTER --> SORT
        
        SORT -->|Alphabetical| ALPHA[Sort by storeName COLLATE NOCASE]
        SORT -->|Expiry| EXP_SORT[Sort by expiryDate ASC]
        SORT -->|Amount| AMT_SORT[Sort by cashbackValueNum DESC]
        
        ALPHA --> DISPLAY[EnhancedCouponCard]
        EXP_SORT --> DISPLAY
        AMT_SORT --> DISPLAY
        
        DISPLAY --> CASHBACK[getCashbackDisplayText]
        CASHBACK --> RENDER{Render Typed Cashback}
        RENDER -->|PERCENT| SHOW_PCT[75% OFF]
        RENDER -->|AMOUNT| SHOW_AMT[₹1500 OFF]
        RENDER -->|TEXT| SHOW_TXT[Flat Discount]
    end
    
    style PERSIST_URI fill:#90EE90
    style ROOM fill:#FFD700
    style CASHBACK fill:#87CEEB
```

---

## 9. Performance Monitoring & Telemetry

```mermaid
graph TB
    subgraph "Extraction Monitoring"
        ATTEMPT[Extraction Attempt] --> RECORD[ExtractionPerformanceMonitor.recordExtractionAttempt]
        
        RECORD --> METHOD{Extraction Method}
        METHOD -->|LLM_ONLY| M1[Track LLM Stats]
        METHOD -->|OCR_ONLY| M2[Track OCR Stats]
        METHOD -->|FUSION| M3[Track Fusion Stats]
        METHOD -->|UNIVERSAL| M4[Track Universal Stats]
        METHOD -->|TRADITIONAL_OCR| M5[Track Traditional Stats]
        
        M1 --> SESSION[Session Stats Map]
        M2 --> SESSION
        M3 --> SESSION
        M4 --> SESSION
        M5 --> SESSION
        
        SESSION --> COUNT{Attempt Count % 10?}
        COUNT -->|Yes| PERSIST_NOW[persistSessionStats]
        COUNT -->|No| MEMORY[Keep in Memory]
        
        PERSIST_NOW --> PREFS[SharedPreferences: method_stats]
        
        MEMORY --> DASH_READ[Dashboard Read]
        DASH_READ --> FORCE_PERSIST[Force persistSessionStats]
        FORCE_PERSIST --> PREFS
        
        PREFS --> METRICS[Calculate Metrics]
        METRICS --> SUCCESS_RATE[Success Rate %]
        METRICS --> AVG_CONF[Average Confidence]
        METRICS --> AVG_TIME[Average Time ms]
        
        SUCCESS_RATE --> DISPLAY[ExtractionDashboard]
        AVG_CONF --> DISPLAY
        AVG_TIME --> DISPLAY
    end
    
    subgraph "RunPath Tracking"
        EXTRACT_START[Extraction Start] --> PATH[Create RunPath]
        PATH --> STRAT_LOG[Log strategy]
        STRAT_LOG --> STAGES[Log tried stages]
        STAGES --> FINAL[Log final stage]
        FINAL --> REASONS[Log decision reasons]
        
        REASONS --> TELEM[ExtractionTelemetryService.trackRunPath]
        TELEM --> ANALYTICS[Analytics Events]
    end
    
    style FORCE_PERSIST fill:#90EE90
    style PREFS fill:#FFD700
    style DISPLAY fill:#87CEEB
```

---

## 10. Error Handling & Fallbacks

```mermaid
graph TB
    subgraph "Extraction Error Handling"
        START[Start Extraction] --> TRY[Try Primary Method]
        
        TRY --> SUCCESS{Success?}
        SUCCESS -->|Yes| GOOD[ExtractResult.Good]
        SUCCESS -->|No| TIMEOUT{Timeout?}
        
        TIMEOUT -->|Yes| LOG_TO[Log Timeout]
        TIMEOUT -->|No| CONF_CHECK{Confidence?}
        
        CONF_CHECK -->|High| GOOD
        CONF_CHECK -->|Medium| LOW_Q[ExtractResult.LowQuality]
        CONF_CHECK -->|Low| FALLBACK1{Try Universal?}
        
        FALLBACK1 -->|Yes| UNIV[UniversalExtractionService]
        FALLBACK1 -->|No| FALLBACK2[Try Traditional OCR]
        
        UNIV --> UNIV_RES{Universal Success?}
        UNIV_RES -->|Yes| GOOD
        UNIV_RES -->|No| FALLBACK2
        
        FALLBACK2 --> OCR[ML Kit Text Recognition]
        OCR --> OCR_RES{OCR Success?}
        
        OCR_RES -->|Yes| LOW_Q
        OCR_RES -->|No| FAILED[ExtractResult.Failed]
        
        LOG_TO --> FALLBACK2
        
        FAILED --> UI_ERR[Show Error UI]
        LOW_Q --> UI_REVIEW[Show Needs Review]
        GOOD --> UI_SUCCESS[Navigate to Form/Success]
    end
    
    subgraph "Bitmap Error Handling"
        BM_LOAD[Load Bitmap] --> BM_ERR{Error?}
        BM_ERR -->|OOM| BM_BUDGET[Enforce Budget + Retry]
        BM_ERR -->|IO Error| BM_FAIL[Return null]
        BM_ERR -->|OK| BM_OK[Return Bitmap]
        
        BM_BUDGET --> BM_RECYCLE[Recycle Unreferenced]
        BM_RECYCLE --> BM_RETRY[Retry Load]
        BM_RETRY --> BM_ERR
    end
    
    style GOOD fill:#90EE90
    style LOW_Q fill:#FFD700
    style FAILED fill:#FF6347
    style BM_RECYCLE fill:#FFA500
```

---

## 11. Complete End-to-End Flow (Single Coupon)

```mermaid
sequenceDiagram
    participant User
    participant UI as ScannerScreen
    participant VM as ScannerViewModel
    participant BM as BitmapManager
    participant Config as ExtractionConfig
    participant TSD as TwoStageDetector
    participant LLM as LocalLlmOcrService
    participant Fusion as LlmOcrFusionService
    participant Parser as IndianDateParser/CurrencyParser
    participant DAO as CouponDao
    participant Room as Room Database
    
    User->>UI: Tap Camera Button
    UI->>VM: captureImage()
    VM->>BM: trackBitmap(bitmap)
    BM->>BM: refCount = 1
    
    VM->>Config: getStrategy()
    Config-->>VM: LEGACY (default)
    
    VM->>TSD: detectMultiCoupons(bitmap)
    TSD->>BM: cropWithBudget(x,y,w,h)
    BM->>BM: Check budget, create crop
    BM->>BM: trackBitmap(crop), refCount=1
    BM-->>TSD: cropped bitmap
    
    TSD->>TSD: Stage 1: Detect instances
    TSD->>TSD: Stage 2: Detect fields
    TSD-->>VM: List<CouponInstance>
    
    VM->>LLM: processCouponImageTyped(crop)
    LLM->>LLM: MiniCPM inference
    LLM-->>VM: LLM Result (JSON)
    
    VM->>Fusion: fuseResults(llm, ocr)
    Fusion->>Parser: parseExpiryDate(text)
    Parser-->>Fusion: Date + Confidence
    Fusion->>Parser: parseAmount(text)
    Parser-->>Fusion: Amount + Type
    Fusion-->>VM: Fused CouponInfo
    
    VM->>VM: Create Coupon entity
    VM->>DAO: insert(coupon)
    DAO->>Room: INSERT INTO coupons
    Room-->>DAO: couponId
    DAO-->>VM: Success
    
    VM->>BM: releaseBitmap(crop)
    BM->>BM: refCount--
    BM->>BM: refCount==0, recycle()
    
    VM->>BM: releaseBitmap(original)
    BM->>BM: refCount--
    BM->>BM: refCount==0, recycle()
    
    VM-->>UI: Navigate to CouponFormScreen
    UI-->>User: Show extracted coupon
```

---

## Summary

### **Critical Components (V2 with Fixes)**:

1. ✅ **BitmapManager**: Reference counting prevents crashes
2. ✅ **ExtractionConfig**: Strategy persists across restarts
3. ✅ **TwoStageDetector**: Multi-coupon detection with memory management
4. ✅ **LlmOcrFusionService**: ROI-guided fusion with real OCR
5. ✅ **IndianDateParser**: Multi-format IST-first date parsing
6. ✅ **IndianCurrencyParser**: Handles thousand separators
7. ✅ **Typed Cashback**: Percent vs Amount vs Text
8. ✅ **Room Database**: v7 schema with migrations
9. ⚠️ **PatternLearningEngine**: Works via SharedPrefs (Room ready but deferred)
10. ✅ **Performance Monitoring**: Telemetry with dashboard

### **Data Flow Highlights**:

- **Entry**: Camera/Gallery → MainActivity → ScannerScreen
- **Memory**: BitmapManager tracks all bitmaps with refcounting
- **Strategy**: User-selectable, persisted, loaded on startup
- **Detection**: Two-stage ML models find coupons and fields
- **Extraction**: LLM + OCR fusion with confidence scoring
- **Parsing**: Multi-format dates, Indian currency, typed cashback
- **Persistence**: Room DB with URI copying to app storage
- **Learning**: Pattern engine learns from success/corrections
- **Monitoring**: Performance stats with dashboard UI
- **Fallbacks**: Universal → Traditional OCR → Manual entry

### **Status**: ✅ **Production Ready with V2 Fixes**
