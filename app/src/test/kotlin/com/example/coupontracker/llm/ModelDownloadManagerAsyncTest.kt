package com.example.coupontracker.llm

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.coupontracker.util.SecurePreferencesManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.android.asCoroutineDispatcher
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ModelDownloadManagerAsyncTest {

    private lateinit var context: Context
    private lateinit var securePreferencesManager: SecurePreferencesManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        securePreferencesManager = SecurePreferencesManager(context)
        securePreferencesManager.setLlmModelDownloaded(true)
        securePreferencesManager.setLlmModelVersion("test-version")
    }

    @Test
    fun refreshModelStatus_runsVerificationOffMainThread() = runBlocking {
        val manager = ModelDownloadManager(context)
        val executedOnMain = CompletableDeferred<Boolean>()

        manager.setVerificationListener {
            executedOnMain.complete(Looper.myLooper() == Looper.getMainLooper())
        }

        val mainDispatcher = Handler(Looper.getMainLooper()).asCoroutineDispatcher()
        try {
            val job = async(mainDispatcher) {
                manager.refreshModelStatus(force = true)
            }
            job.await()
        } finally {
            mainDispatcher.close()
        }

        assertFalse(executedOnMain.await(), "Verification should run off the main thread")
    }

    @Test
    fun refreshModelStatus_cachesVerificationResult() = runBlocking {
        val manager = ModelDownloadManager(context)
        val invocationCount = AtomicInteger(0)

        manager.setVerificationListener {
            invocationCount.incrementAndGet()
        }

        manager.refreshModelStatus(force = true)
        manager.refreshModelStatus()

        assertEquals(1, invocationCount.get(), "Verification should be cached after the first refresh")
    }
}
