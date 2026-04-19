package com.example.coupontracker.extraction.multi

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CouponRegionPipelineInstrumentedTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var pipeline: CouponRegionPipeline

    @Test
    fun extractsAtLeastOneCouponFromFixture() = runBlocking {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val bitmap = BitmapFactory.decodeStream(ctx.assets.open("multi_coupon_fixture.png"))
        // Whole-image path bypasses HybridCouponDetector — it exercises just
        // OCR + DEFAULT extraction adapter, which is what we can wire into
        // Hilt today without dragging in MultiEngineOCR.
        val coupons = pipeline.extractWhole(bitmap)
        assertTrue("pipeline must produce at least one coupon", coupons.isNotEmpty())
        assertTrue(
            "result must be capped",
            coupons.size <= MultiCouponLimits.MAX_COUPONS_PER_SCREENSHOT
        )
    }
}
