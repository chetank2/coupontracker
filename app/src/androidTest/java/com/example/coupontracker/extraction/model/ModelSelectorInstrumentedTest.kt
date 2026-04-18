package com.example.coupontracker.extraction.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ModelSelectorInstrumentedTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var selector: ModelSelector

    @Test
    fun defaultRoleResolvesToQwenText() {
        hiltRule.inject()
        val adapter = selector.select(ModelRole.DEFAULT)
        assertEquals(ModelMode.TEXT_QWEN, adapter.mode)
    }
}
