# Multi-Coupon Model Delivery

The trained multi-coupon detector artifacts are significantly larger than the
rest of the Android project and are distributed outside of the public Git
repository. The Android app now ships with placeholder assets under
`app/src/main/assets/models/multi_coupon/` so the build can proceed without
TensorFlow Lite errors. Replace the placeholders with the production binaries
before shipping.

## Artifact Overview

| Stage | File name | Description |
|-------|-----------|-------------|
| Stage 1 | `stage1_coupon_detector.tflite` | YOLOv8-based coupon instance detector. |
| Stage 2 | `stage2_field_detector.tflite` | YOLOv8-based coupon field detector. |
| Manifest | `manifest.json` | Configuration consumed by `TwoStageDetector`. |

## How to Obtain the Trained Models

1. **Internal artifact store** – Download the latest `multi_coupon_models_v*.zip`
   bundle from the release portal and extract it into
   `app/src/main/assets/models/multi_coupon/`.
2. **Re-train locally** – Run the end-to-end helper script, which will generate
   demo data, train both stages, and copy the resulting TFLite models into the
   Android assets directory:

   ```bash
   ./complete_multi_coupon_pipeline.sh
   ```

   Ensure the host has the Python dependencies listed in
   `requirements.txt` installed (notably `ultralytics`, `opencv-python`, and
   `onnxruntime`).

After either option the placeholder `.tflite` files can be deleted or
overwritten. The manifest shipped in the repository already lists the filenames
expected by `TwoStageDetector`.

## Verification

After the trained artifacts are in place run:

```bash
./gradlew :app:assembleDebug
```

and watch `adb logcat` while launching the scanner. The `TwoStageDetector`
initialization log should confirm the models were loaded without warnings.
