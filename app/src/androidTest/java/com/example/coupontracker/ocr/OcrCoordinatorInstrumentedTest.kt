package com.example.coupontracker.ocr

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
class OcrCoordinatorInstrumentedTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var engine: OcrEngine

    @Test
    fun recognizesTextFromGoldenSetFixture() = runBlocking {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val stream = ctx.assets.open("multi_coupon_fixture.png")
        val bitmap = BitmapFactory.decodeStream(stream)
        val text = engine.recognize(bitmap)
        assertTrue("expected non-empty text from coordinator", text.isNotBlank())
    }
}
