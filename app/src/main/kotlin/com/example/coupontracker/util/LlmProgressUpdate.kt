package com.example.coupontracker.util

data class LlmProgressUpdate(
    val stage: LlmProgressStage,
    val percent: Int?,
    val message: String
)
