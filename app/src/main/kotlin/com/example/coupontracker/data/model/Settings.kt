package com.example.coupontracker.data.model

data class Settings(
    val sortOrder: SortOrder = SortOrder.EXPIRY_DATE,
    val notificationsEnabled: Boolean = true,
    val notificationTiming: Int = 1,
    val darkMode: Boolean = false
) 