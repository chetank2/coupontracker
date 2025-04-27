package com.example.coupontracker.ui.viewmodel

import com.example.coupontracker.data.model.Settings
import com.example.coupontracker.data.model.SortOrder
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.data.repository.SettingsRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@ExperimentalCoroutinesApi
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var couponRepository: CouponRepository
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        settingsRepository = mockk(relaxed = true)
        couponRepository = mockk(relaxed = true)
        
        every { settingsRepository.getSettings() } returns flowOf(
            Settings(
                sortOrder = SortOrder.EXPIRY_DATE,
                notificationsEnabled = true,
                notificationTiming = 1,
                darkMode = false
            )
        )
        
        viewModel = SettingsViewModel(settingsRepository, couponRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `updateSortOrder calls repository updateSortOrder`() = runTest {
        // When
        viewModel.updateSortOrder(SortOrder.NAME)
        
        // Advance the dispatcher to execute pending coroutines
        advanceUntilIdle()
        
        // Then
        coVerify { settingsRepository.updateSortOrder(SortOrder.NAME) }
    }

    @Test
    fun `updateNotificationsEnabled calls repository updateNotificationsEnabled`() = runTest {
        // When
        viewModel.updateNotificationsEnabled(false)
        
        // Advance the dispatcher to execute pending coroutines
        advanceUntilIdle()
        
        // Then
        coVerify { settingsRepository.updateNotificationsEnabled(false) }
    }

    @Test
    fun `updateDarkModeEnabled calls repository updateDarkMode`() = runTest {
        // When
        viewModel.updateDarkModeEnabled(true)
        
        // Advance the dispatcher to execute pending coroutines
        advanceUntilIdle()
        
        // Then
        coVerify { settingsRepository.updateDarkMode(true) }
    }

    @Test
    fun `clearAllData calls repository deleteAllCoupons`() = runTest {
        // When
        viewModel.clearAllData()
        
        // Advance the dispatcher to execute pending coroutines
        advanceUntilIdle()
        
        // Then
        coVerify { couponRepository.deleteAllCoupons() }
    }
} 