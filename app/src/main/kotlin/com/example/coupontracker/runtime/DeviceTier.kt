package com.example.coupontracker.runtime

enum class DeviceTier {
    LOW_END,   // ≤3 GB RAM, lowRamDevice true, severe thermal, or battery saver
    MID,       // default
    HIGH_END,  // >6 GB RAM, models present, not thermally throttled
    DEVELOPER  // overridden for benchmarks; VLM primary
}
