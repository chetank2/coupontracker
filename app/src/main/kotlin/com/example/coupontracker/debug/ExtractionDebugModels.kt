package com.example.coupontracker.debug

import com.example.coupontracker.util.ExtractResult
import com.example.coupontracker.util.RunPath
import com.example.coupontracker.ui.viewmodel.MiniCpmProgress
import com.example.coupontracker.ui.viewmodel.FieldExtractionResult
import com.example.coupontracker.universal.UniversalExtractionResult
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Enum describing high level extraction components we want to surface to developers.
 */
enum class ExtractionComponent(val displayName: String) {
    DETECTOR("Detection"),
    OCR("OCR"),
    LLM("LLM"),
    FUSION("Fusion"),
    VALIDATION("Validation")
}

/**
 * Status of a stage score – helps color the UI.
 */
enum class StageStatus {
    HEALTHY,
    DEGRADED,
    FAILED
}

/**
 * Score for an individual extraction component.
 */
data class ExtractionStageScore(
    val component: ExtractionComponent,
    val score: Int,
    val status: StageStatus,
    val notes: List<String> = emptyList()
)

/**
 * Snapshot representing the full diagnostic view for a single extraction.
 * This is kept lightweight because the feature is temporary and in-memory only.
 */
data class ExtractionDebugSnapshot(
    val overallScore: Int,
    val stageScores: List<ExtractionStageScore>,
    val primaryCause: ExtractionComponent? = null,
    val notes: List<String> = emptyList(),
    val source: String = "unknown",
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun scoreFor(component: ExtractionComponent): ExtractionStageScore? =
        stageScores.firstOrNull { it.component == component }
}

/**
 * Central scorer that translates existing telemetry into developer-friendly diagnostics.
 * Heuristics intentionally err on the side of over-communicating since this is a debug-only feature.
 */
object ExtractionDebugScorer {

    private const val HEALTHY_THRESHOLD = 75
    private const val DEGRADED_THRESHOLD = 45

    private fun statusFor(score: Int): StageStatus = when {
        score >= HEALTHY_THRESHOLD -> StageStatus.HEALTHY
        score >= DEGRADED_THRESHOLD -> StageStatus.DEGRADED
        else -> StageStatus.FAILED
    }

    fun fromLlmResult(result: ExtractResult, source: String): ExtractionDebugSnapshot {
        return when (result) {
            is ExtractResult.Good -> buildSnapshot(
                overall = result.signals.qualityScore,
                stageEntries = listOf(
                    ExtractionStageScore(
                        component = ExtractionComponent.DETECTOR,
                        score = 80,
                        status = StageStatus.HEALTHY,
                        notes = listOf("Detector not consulted – assumed healthy")
                    ),
                    ExtractionStageScore(
                        component = ExtractionComponent.OCR,
                        score = (result.signals.fieldConfidences["ocrCoverage"]?.times(100)?.toInt())
                            ?: 70,
                        status = statusFor((result.signals.fieldConfidences["ocrCoverage"]?.times(100)?.toInt()) ?: 70),
                        notes = listOf("Derived from OCR coverage heuristic")
                    ),
                    ExtractionStageScore(
                        component = ExtractionComponent.LLM,
                        score = result.signals.qualityScore,
                        status = statusFor(result.signals.qualityScore),
                        notes = listOf("LLM quality score from signals")
                    ),
                    ExtractionStageScore(
                        component = ExtractionComponent.FUSION,
                        score = 65,
                        status = statusFor(65),
                        notes = listOf("Fusion assumed nominal")
                    )
                ),
                primary = null,
                notes = listOf(
                    "LLM succeeded with quality ${result.signals.qualityScore}",
                    "Stage=${result.signals.stage}"
                ),
                source = source
            )
            is ExtractResult.LowQuality -> {
                val culprit = when (result.signals.stage) {
                    com.example.coupontracker.util.ExtractionStage.LLM -> ExtractionComponent.LLM
                    com.example.coupontracker.util.ExtractionStage.MLKIT,
                    com.example.coupontracker.util.ExtractionStage.TFLITE -> ExtractionComponent.OCR
                    com.example.coupontracker.util.ExtractionStage.TWO_STAGE_DETECTION -> ExtractionComponent.DETECTOR
                    else -> ExtractionComponent.FUSION
                }
                buildSnapshot(
                    overall = result.signals.qualityScore,
                    stageEntries = listOf(
                        ExtractionStageScore(
                            component = ExtractionComponent.DETECTOR,
                            score = if (culprit == ExtractionComponent.DETECTOR) 30 else 75,
                            status = statusFor(if (culprit == ExtractionComponent.DETECTOR) 30 else 75)
                        ),
                        ExtractionStageScore(
                            component = ExtractionComponent.OCR,
                            score = if (culprit == ExtractionComponent.OCR) 35 else 65,
                            status = statusFor(if (culprit == ExtractionComponent.OCR) 35 else 65)
                        ),
                        ExtractionStageScore(
                            component = ExtractionComponent.LLM,
                            score = if (culprit == ExtractionComponent.LLM) 25 else 60,
                            status = statusFor(if (culprit == ExtractionComponent.LLM) 25 else 60),
                            notes = listOf("Low quality output: ${result.reason}")
                        ),
                        ExtractionStageScore(
                            component = ExtractionComponent.FUSION,
                            score = 45,
                            status = statusFor(45)
                        )
                    ),
                    primary = culprit,
                    notes = listOf("LLM returned low quality (${result.reason})"),
                    source = source
                )
            }
            is ExtractResult.Failed -> {
                val culprit = when (result.stage) {
                    com.example.coupontracker.util.ExtractionStage.LLM -> ExtractionComponent.LLM
                    com.example.coupontracker.util.ExtractionStage.MLKIT,
                    com.example.coupontracker.util.ExtractionStage.TFLITE -> ExtractionComponent.OCR
                    com.example.coupontracker.util.ExtractionStage.TWO_STAGE_DETECTION -> ExtractionComponent.DETECTOR
                    com.example.coupontracker.util.ExtractionStage.REGEX -> ExtractionComponent.FUSION
                }
                buildSnapshot(
                    overall = 0,
                    stageEntries = listOf(
                        ExtractionStageScore(
                            component = ExtractionComponent.DETECTOR,
                            score = if (culprit == ExtractionComponent.DETECTOR) 10 else 70,
                            status = statusFor(if (culprit == ExtractionComponent.DETECTOR) 10 else 70)
                        ),
                        ExtractionStageScore(
                            component = ExtractionComponent.OCR,
                            score = if (culprit == ExtractionComponent.OCR) 10 else 60,
                            status = statusFor(if (culprit == ExtractionComponent.OCR) 10 else 60)
                        ),
                        ExtractionStageScore(
                            component = ExtractionComponent.LLM,
                            score = if (culprit == ExtractionComponent.LLM) 5 else 55,
                            status = statusFor(if (culprit == ExtractionComponent.LLM) 5 else 55),
                            notes = listOf("Failure: ${result.error.message ?: "unknown"}")
                        ),
                        ExtractionStageScore(
                            component = ExtractionComponent.FUSION,
                            score = 30,
                            status = statusFor(30)
                        )
                    ),
                    primary = culprit,
                    notes = listOf("Stage failure propagated from ${result.stage}", result.error.message ?: ""),
                    source = source
                )
            }
        }
    }

    fun fromFieldExtraction(result: FieldExtractionResult, runPath: RunPath?): ExtractionDebugSnapshot {
        val llmScore = (result.qualityScore ?: when (result.miniCpmStatus) {
            MiniCpmProgress.SUCCESS -> 80
            MiniCpmProgress.NEEDS_REVIEW -> 55
            MiniCpmProgress.FALLBACK -> 35
        }).coerceIn(0, 100)
        val ocrFallback = runPath?.final?.uppercase(Locale.getDefault())?.contains("OCR") == true
        val ocrScore = if (ocrFallback) 50 else 75
        val detectionScore = if (result.fields.isEmpty()) 30 else 78
        val fusionScore = ((llmScore + ocrScore + detectionScore) / 3).coerceIn(20, 85)

        val culprit = when {
            result.miniCpmStatus == MiniCpmProgress.FALLBACK -> ExtractionComponent.LLM
            ocrScore < DEGRADED_THRESHOLD -> ExtractionComponent.OCR
            detectionScore < DEGRADED_THRESHOLD -> ExtractionComponent.DETECTOR
            else -> null
        }

        val stageEntries = listOf(
            ExtractionStageScore(
                component = ExtractionComponent.DETECTOR,
                score = detectionScore,
                status = statusFor(detectionScore),
                notes = listOf("Instances extracted: ${result.fields.size}")
            ),
            ExtractionStageScore(
                component = ExtractionComponent.OCR,
                score = ocrScore,
                status = statusFor(ocrScore),
                notes = listOf(if (ocrFallback) "Fallback OCR used" else "Primary OCR path")
            ),
            ExtractionStageScore(
                component = ExtractionComponent.LLM,
                score = llmScore,
                status = statusFor(llmScore),
                notes = buildList {
                    add("MiniCPM status: ${result.miniCpmStatus}")
                    result.qualityScore?.let { add("Reported quality: $it") }
                    result.fieldConfidences.takeIf { it.isNotEmpty() }?.let { confidences ->
                        val weakest = confidences.minByOrNull { it.value }
                        weakest?.let { add("Weakest field: ${it.key} ${(it.value * 100).toInt()}%") }
                    }
                }
            ),
            ExtractionStageScore(
                component = ExtractionComponent.FUSION,
                score = fusionScore,
                status = statusFor(fusionScore),
                notes = listOf("Run path: ${runPath?.strategy ?: "unknown"} → ${runPath?.final ?: "unknown"}")
            )
        )

        val overall = (llmScore + ocrScore + detectionScore + fusionScore) / 4

        return buildSnapshot(
            overall = overall,
            stageEntries = stageEntries,
            primary = culprit,
            notes = listOf("Processing time: ${runPath?.totalTimeMs ?: 0} ms"),
            source = "two_stage"
        )
    }

    fun fromUniversalResult(result: UniversalExtractionResult, ocrTextEmpty: Boolean): ExtractionDebugSnapshot {
        val ocrScore = if (ocrTextEmpty) 20 else (result.confidence * 100).toInt().coerceIn(30, 85)
        val fusionScore = (result.confidence * 100).toInt().coerceIn(30, 90)

        val culprit = when {
            !result.success -> ExtractionComponent.FUSION
            ocrScore < DEGRADED_THRESHOLD -> ExtractionComponent.OCR
            fusionScore < DEGRADED_THRESHOLD -> ExtractionComponent.FUSION
            else -> null
        }

        val stageEntries = listOf(
            ExtractionStageScore(
                component = ExtractionComponent.DETECTOR,
                score = 40,
                status = statusFor(40),
                notes = listOf("Detector bypassed in universal path")
            ),
            ExtractionStageScore(
                component = ExtractionComponent.OCR,
                score = ocrScore,
                status = statusFor(ocrScore),
                notes = listOf(if (ocrTextEmpty) "OCR text missing" else "OCR text length > 0")
            ),
            ExtractionStageScore(
                component = ExtractionComponent.LLM,
                score = 55,
                status = statusFor(55),
                notes = listOf("Semantic pattern scoring applied")
            ),
            ExtractionStageScore(
                component = ExtractionComponent.FUSION,
                score = fusionScore,
                status = statusFor(fusionScore),
                notes = listOf("Universal extractor confidence ${(result.confidence * 100).toInt()}" )
            )
        )

        val overall = (ocrScore + fusionScore + 40 + 55) / 4

        return buildSnapshot(
            overall = overall,
            stageEntries = stageEntries,
            primary = culprit,
            notes = listOf("Universal pipeline executed"),
            source = "universal"
        )
    }

    fun fromTraditionalOcr(fields: Map<String, String>): ExtractionDebugSnapshot {
        val detectionScore = 35
        val ocrScore = if (fields.isEmpty()) 15 else 60 + (fields.size * 4).coerceAtMost(20)
        val fusionScore = if (fields.isEmpty()) 20 else 45
        val culprit = if (fields.isEmpty()) ExtractionComponent.OCR else null

        val stageEntries = listOf(
            ExtractionStageScore(
                component = ExtractionComponent.DETECTOR,
                score = detectionScore,
                status = statusFor(detectionScore),
                notes = listOf("Pure OCR fallback")
            ),
            ExtractionStageScore(
                component = ExtractionComponent.OCR,
                score = ocrScore,
                status = statusFor(ocrScore),
                notes = listOf("Fields extracted: ${fields.keys}")
            ),
            ExtractionStageScore(
                component = ExtractionComponent.LLM,
                score = 30,
                status = statusFor(30),
                notes = listOf("LLM bypassed")
            ),
            ExtractionStageScore(
                component = ExtractionComponent.FUSION,
                score = fusionScore,
                status = statusFor(fusionScore),
                notes = listOf("Heuristic fusion applied")
            )
        )

        val overall = (detectionScore + ocrScore + 30 + fusionScore) / 4

        return buildSnapshot(
            overall = overall,
            stageEntries = stageEntries,
            primary = culprit,
            notes = listOf("Traditional OCR fallback"),
            source = "traditional_ocr"
        )
    }

    private fun buildSnapshot(
        overall: Int,
        stageEntries: List<ExtractionStageScore>,
        primary: ExtractionComponent?,
        notes: List<String>,
        source: String
    ): ExtractionDebugSnapshot {
        return ExtractionDebugSnapshot(
            overallScore = overall.coerceIn(0, 100),
            stageScores = stageEntries,
            primaryCause = primary,
            notes = notes.filter { it.isNotBlank() },
            source = source
        )
    }
}

/**
 * Simple in-memory repository storing the most recent debug snapshot per coupon ID.
 * Designed for rapid removal once diagnostics ship.
 */
@Singleton
class ExtractionDebugRepository @Inject constructor() {

    private val _snapshots = MutableStateFlow<Map<Long, ExtractionDebugSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<Long, ExtractionDebugSnapshot>> = _snapshots.asStateFlow()

    fun updateSnapshot(couponId: Long, snapshot: ExtractionDebugSnapshot) {
        _snapshots.update { current -> current + (couponId to snapshot) }
    }

    fun removeSnapshot(couponId: Long) {
        _snapshots.update { current -> current - couponId }
    }
}
