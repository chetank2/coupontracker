package com.example.coupontracker.benchmark

import org.json.JSONObject

data class GoldenSetSample(
    val id: String,
    val imagePath: String,
    val imageSha256: String,
    val brand: String,
    val expected: JSONObject,
    val replayJson: String
)
