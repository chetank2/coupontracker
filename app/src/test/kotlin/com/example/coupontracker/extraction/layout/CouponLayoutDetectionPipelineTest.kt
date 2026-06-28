package com.example.coupontracker.extraction.layout

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.coupontracker.data.preferences.SecurePreferencesManager
import com.example.coupontracker.extraction.model.CouponExtractionModel
import com.example.coupontracker.extraction.model.ModelExtractionResult
import com.example.coupontracker.extraction.model.ModelMode
import com.example.coupontracker.extraction.model.ModelRole
import com.example.coupontracker.extraction.model.ModelSelector
import com.example.coupontracker.extraction.model.ModelStrategyConfig
import com.example.coupontracker.extraction.model.RawVisionExtractionModel
import com.example.coupontracker.extraction.region.CouponRegionizer
import com.example.coupontracker.extraction.region.CouponRegionizerConfig
import com.example.coupontracker.ml.HybridCouponDetector
import com.example.coupontracker.ml.ScreenshotClassifier
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CouponLayoutDetectionPipelineTest {

    @Test
    fun `existing fallback regions are preferred over earlier vlm detection`() = runBlocking {
        val vlm = FixedDetector(
            name = "vlm",
            detection = CouponLayoutDetection(
                cards = listOf(card(Rect(0, 0, 300, 400), sourceIndex = 0)),
                source = LayoutDetectionSource.VLM,
                confidence = 0.9f
            )
        )
        val heuristic = FixedDetector(
            name = "heuristic",
            detection = CouponLayoutDetection(
                cards = listOf(
                    card(Rect(0, 0, 300, 190), sourceIndex = 0),
                    card(Rect(0, 210, 300, 400), sourceIndex = 1)
                ),
                source = LayoutDetectionSource.HEURISTIC,
                confidence = 0.8f
            )
        )
        val pipeline = CouponLayoutDetectionPipeline(
            detectors = listOf(vlm, heuristic),
            validator = CouponLayoutValidator(
                CouponLayoutValidationConfig(minCardAreaPx = 100)
            )
        )

        val result = pipeline.detect(
            bitmap = bitmap(),
            context = LayoutDetectionContext(
                screenshotType = ScreenshotClassifier.ScreenshotType.MULTI_COUPON_APP,
                ocrText = "two coupon cards",
                fallbackRegions = listOf(
                    fallbackRegion(Rect(0, 0, 300, 190), 0),
                    fallbackRegion(Rect(0, 210, 300, 400), 1)
                )
            )
        )

        assertEquals(LayoutDetectionSource.HEURISTIC, result.source)
        assertEquals(
            listOf(Rect(0, 0, 300, 190), Rect(0, 210, 300, 400)),
            result.cards.map { it.bounds }
        )
    }

    @Test
    fun `vlm detection is used when no fallback regions are available`() = runBlocking {
        val vlm = FixedDetector(
            name = "vlm",
            detection = CouponLayoutDetection(
                cards = listOf(card(Rect(0, 0, 300, 400))),
                source = LayoutDetectionSource.VLM,
                confidence = 0.9f
            )
        )
        val pipeline = CouponLayoutDetectionPipeline(
            detectors = listOf(vlm),
            validator = CouponLayoutValidator(
                CouponLayoutValidationConfig(minCardAreaPx = 100)
            )
        )

        val result = pipeline.detect(
            bitmap = bitmap(),
            context = LayoutDetectionContext(
                screenshotType = ScreenshotClassifier.ScreenshotType.MULTI_COUPON_APP,
                ocrText = "coupon card"
            )
        )

        assertEquals(LayoutDetectionSource.VLM, result.source)
        assertEquals(1, result.cards.size)
    }

    @Test
    fun `vlm detector parses raw layout json without coupon schema enforcement`() = runBlocking {
        val model = RawLayoutModel(
            rawJson = """
                {
                  "cards": [
                    {
                      "box": { "x": 12, "y": 20, "width": 120, "height": 90 },
                      "completeness": "complete",
                      "confidence": 0.91,
                      "visibleFields": ["merchant", "offer", "code"]
                    }
                  ],
                  "confidence": 0.91
                }
            """.trimIndent()
        )
        val config = mockk<ModelStrategyConfig>()
        every { config.modeFor(ModelRole.LOW_CONFIDENCE_RETRY) } returns ModelMode.VLM_GEMMA
        val detector = VlmCouponLayoutDetector(
            modelSelector = ModelSelector(setOf(model), config)
        )

        val result = detector.detectLayout(
            bitmap = bitmap(),
            context = LayoutDetectionContext(
                screenshotType = ScreenshotClassifier.ScreenshotType.MULTI_COUPON_APP,
                ocrText = "coupon anchors"
            )
        )

        assertEquals(LayoutDetectionSource.VLM, result.source)
        assertEquals(listOf(Rect(12, 20, 132, 110)), result.cards.map { it.bounds })
    }

    @Test
    fun `vlm detector parses normalized vision layout schema`() = runBlocking {
        val model = RawLayoutModel(
            rawJson = """
                {
                  "layoutState": "MULTI_CARD",
                  "confidence": 0.88,
                  "cards": [
                    {
                      "active": true,
                      "foreground": true,
                      "layoutState": "COMPLETE",
                      "confidence": 0.91,
                      "bounds": { "x": 0.10, "y": 0.20, "w": 0.80, "h": 0.35 }
                    }
                  ]
                }
            """.trimIndent()
        )
        val config = mockk<ModelStrategyConfig>()
        every { config.modeFor(ModelRole.LOW_CONFIDENCE_RETRY) } returns ModelMode.VLM_GEMMA
        val detector = VlmCouponLayoutDetector(
            modelSelector = ModelSelector(setOf(model), config)
        )

        val result = detector.detectLayout(
            bitmap = bitmap(width = 1000, height = 2000),
            context = LayoutDetectionContext(
                screenshotType = ScreenshotClassifier.ScreenshotType.MULTI_COUPON_APP,
                ocrText = "coupon anchors"
            )
        )

        assertEquals(LayoutDetectionSource.VLM, result.source)
        assertEquals(listOf(Rect(100, 400, 900, 1100)), result.cards.map { it.bounds })
    }

    @Test
    fun `vlm detector skips when Gemma verifier is disabled`() = runBlocking {
        val prefs = mockk<SecurePreferencesManager>()
        every { prefs.isGemmaVisionVerifierEnabled() } returns false
        val config = mockk<ModelStrategyConfig>()
        val model = RawLayoutModel(rawJson = """{"cards":[]}""")
        val detector = VlmCouponLayoutDetector(
            modelSelector = ModelSelector(setOf(model), config),
            securePreferencesManager = prefs
        )

        val result = detector.detectLayout(
            bitmap = bitmap(),
            context = LayoutDetectionContext(
                screenshotType = ScreenshotClassifier.ScreenshotType.MULTI_COUPON_APP,
                ocrText = "coupon anchors"
            )
        )

        assertEquals(emptyList<CouponCardRegion>(), result.cards)
        assertEquals(listOf("gemma_vision_verifier_disabled"), result.diagnostics.rejectedReasons)
        verify(exactly = 0) { config.modeFor(any()) }
    }

    @Test
    fun `heuristic detector preserves regionizer mode for downstream extraction`() = runBlocking {
        val detector = HeuristicCouponLayoutDetector(regionizer())
        val result = detector.detectLayout(
            bitmap = bitmap(),
            context = LayoutDetectionContext(
                screenshotType = ScreenshotClassifier.ScreenshotType.MULTI_COUPON_APP,
                ocrText = "two coupon cards",
                fallbackRegions = listOf(
                    fallbackRegion(Rect(0, 0, 300, 190), 0),
                    fallbackRegion(Rect(0, 210, 300, 400), 1)
                )
            )
        )

        assertEquals(2, result.cards.size)
        assertEquals(
            listOf(CouponRegionizer.RegionMode.MULTI_GRID, CouponRegionizer.RegionMode.MULTI_GRID),
            result.cards.map { it.regionMode }
        )
    }

    @Test
    fun `heuristic detector does not fabricate reward modal crop from copy`() = runBlocking {
        val detector = HeuristicCouponLayoutDetector(
            regionizer(
                rewardDropPhrases = listOf("scratch")
            )
        )

        val result = detector.detectLayout(
            bitmap = bitmap(width = 1080, height = 2400),
            context = LayoutDetectionContext(
                screenshotType = ScreenshotClassifier.ScreenshotType.MULTI_COUPON_APP,
                ocrText = """
                    Scratch & win
                    Old background card
                    lenskart
                    Free Home Eye
                    NO CODE NEEDED
                    Book Now
                    I'll use it later!
                """.trimIndent()
            )
        )

        assertEquals(1, result.cards.size)
        assertEquals(CouponRegionizer.RegionMode.REWARD, result.cards.single().regionMode)
        assertEquals(Rect(0, 0, 1080, 2400), result.cards.single().bounds)
    }

    private class FixedDetector(
        override val name: String,
        private val detection: CouponLayoutDetection
    ) : CouponLayoutDetector {
        override suspend fun detectLayout(
            bitmap: Bitmap,
            context: LayoutDetectionContext
        ): CouponLayoutDetection = detection
    }

    private class RawLayoutModel(
        private val rawJson: String
    ) : CouponExtractionModel, RawVisionExtractionModel {
        override val mode: ModelMode = ModelMode.VLM_GEMMA

        override suspend fun extractFromText(
            ocrText: String,
            prompt: String,
            grammar: String?
        ): ModelExtractionResult {
            throw UnsupportedOperationException("vision only")
        }

        override suspend fun extractFromImage(
            image: Bitmap,
            ocrText: String?,
            prompt: String
        ): ModelExtractionResult {
            return ModelExtractionResult(
                canonicalJson = "{}",
                latencyMs = 1L,
                usedFallback = false
            )
        }

        override suspend fun extractRawFromImage(
            image: Bitmap,
            ocrText: String?,
            prompt: String
        ): ModelExtractionResult {
            return ModelExtractionResult(
                canonicalJson = rawJson,
                latencyMs = 1L,
                usedFallback = false
            )
        }
    }

    private fun bitmap(width: Int = 300, height: Int = 400): Bitmap {
        val bitmap = mockk<Bitmap>()
        every { bitmap.width } returns width
        every { bitmap.height } returns height
        return bitmap
    }

    private fun card(bounds: Rect, sourceIndex: Int = 0): CouponCardRegion {
        return CouponCardRegion(
            bounds = bounds,
            completeness = CardCompleteness.COMPLETE,
            confidence = 0.8f,
            sourceIndex = sourceIndex
        )
    }

    private fun fallbackRegion(bounds: Rect, index: Int): HybridCouponDetector.CouponRegion {
        return HybridCouponDetector.CouponRegion(
            boundingBox = bounds,
            ocrText = "coupon $index",
            confidence = 0.8f,
            source = HybridCouponDetector.DetectionSource.FUSED,
            regionIndex = index
        )
    }

    private fun regionizer(
        rewardDropPhrases: List<String> = emptyList()
    ): CouponRegionizer {
        return CouponRegionizer(
            CouponRegionizerConfig(
                globalCrop = CouponRegionizerConfig.GlobalCrop(topPct = 0f, bottomPct = 0f),
                poster = CouponRegionizerConfig.PosterConfig(focusTopPct = 0.32f, focusHeightPct = 0.56f),
                reward = CouponRegionizerConfig.RewardConfig(dropPhrases = rewardDropPhrases),
                grid = CouponRegionizerConfig.GridConfig(minCardWidthPx = 100, minGapPx = 0, maxCols = 3),
                mapOverlay = CouponRegionizerConfig.MapOverlayConfig(brightnessThresh = 0.85f, overlayMinAreaPct = 0.2f)
            )
        )
    }
}
