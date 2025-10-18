package com.example.coupontracker.util

enum class LlmProgressStage {
    PREPARING,
    WARMING_UP,
    OCR,
    PROMPTING,
    INFERENCE,
    PARSING,
    VALIDATING,
    COMPLETE,
    FAILED
}
