# Live baseline (Qwen text mode, on-device)

Not yet generated. Run on an Android device or emulator with the Qwen model
installed:

```
./gradlew :app:connectedBenchmarkAndroidTest -PcouponBenchmark=live
```

This executes the same `GoldenSetBenchmarkTest` logic but with
`QwenTextCouponModel` instead of `ReplayCouponModel`, writes the aggregate
markdown to `build/reports/goldenset/live.md` on the device, pulls it back,
and overwrites this file. Commit the resulting diff to establish the live
baseline. Subsequent CI live runs compare against it.
