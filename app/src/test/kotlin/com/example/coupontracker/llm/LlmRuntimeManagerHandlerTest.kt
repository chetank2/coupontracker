package com.example.coupontracker.llm

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LlmRuntimeManagerHandlerTest {

    private lateinit var context: Context
    private lateinit var llmRuntimeManager: LlmRuntimeManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        llmRuntimeManager = LlmRuntimeManager.getInstance(context)
    }

    @Test
    fun `scheduleAutoUnloadCheck only keeps one pending runnable`() {
        val scheduleMethod = llmRuntimeManager.javaClass.getDeclaredMethod("scheduleAutoUnloadCheck").apply {
            isAccessible = true
        }

        val shadowLooper = Shadows.shadowOf(Looper.getMainLooper())
        val scheduler = shadowLooper.scheduler

        // Invoke twice and ensure only one runnable is queued
        scheduleMethod.invoke(llmRuntimeManager)
        assertEquals(1, scheduler.size())

        scheduleMethod.invoke(llmRuntimeManager)
        assertEquals(1, scheduler.size())

        // Simulate releasing the model and ensure callbacks are cleared
        val referenceCountField = llmRuntimeManager.javaClass.getDeclaredField("referenceCount").apply {
            isAccessible = true
        }
        val referenceCount = referenceCountField.get(llmRuntimeManager) as AtomicInteger
        referenceCount.set(1)

        llmRuntimeManager.releaseModel()
        assertEquals(0, scheduler.size())
    }
}
