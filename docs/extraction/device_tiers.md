# Device tiers

## Tier definitions

| tier | gate | DEFAULT mode | LOW_CONFIDENCE_RETRY mode |
|---|---|---|---|
| LOW_END | RAM ≤ 3GB, `lowRamDevice`, battery saver, thermal ≥ SEVERE | TEXT_QWEN | TEXT_QWEN (no retry) |
| MID | fallthrough | TEXT_QWEN | TEXT_QWEN (no retry) |
| HIGH_END | RAM ≥ 6GB & native lib loaded | TEXT_QWEN | VLM_QWEN if mmproj else TEXT_GEMMA if present else TEXT_QWEN |
| DEVELOPER | manual only (debug builds) | anything | VLM_QWEN |

## Runtime capability probed

- `totalRamMb`, `availableRamMb` — from `ActivityManager.getMemoryInfo`
- `isLowRamDevice` — from `ActivityManager`
- `isBatterySaver` — from `PowerManager`
- `thermalStatus` — from `PowerManager.currentThermalStatus` (API 29+)
- `qwenModelPresent`, `gemmaTextModelPresent`, `mmprojPresent` — file presence in `filesDir`
- `nativeLibraryLoaded` — `System.loadLibrary("mlc_llm_jni")` succeeded

## Promotion / demotion

The policy re-runs on every app start. Users can switch battery saver on
mid-session — the next session detects it and drops to LOW_END. Changing
device tier invalidates the cached `ModelSelector` adapter choice only
indirectly (the selector reads `ModelStrategyConfig` on every `select(...)`
call, so no cache to invalidate).

## Testing a specific tier

To simulate LOW_END on a HIGH_END device, set battery saver mode manually
in Android settings and restart the app. The startup log line should read
`Device tier applied: LOW_END`.

## Application wiring

Plan 7 shipped the probe and the policy but did NOT add the
`DeviceTierPolicy.apply(...)` call to `CouponTrackerApplication.onCreate()`.
That is a follow-up — wire it like:

```kotlin
val capability = deviceCapabilityProbe.probe()
DeviceTierPolicy.apply(capability, modelStrategyConfig)
Log.i(TAG, "Device tier applied: ${DeviceTierPolicy.tierFor(capability)}")
```

Until that wiring lands, defaults from `ModelStrategyConfig` are used
unchanged regardless of the device tier.
