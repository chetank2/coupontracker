# Extraction Discrepancy Report – 22 Oct 2025

## Summary of Reported Issues
The user-provided screenshots highlight multiple coupons whose extracted metadata inside the app does not match the source coupon artwork:

1. **Myntra email ("MISSEDYOU")** – The source art is branded Myntra with a ₹300 flat discount, yet the stored coupon shows the merchant as "NOW" with an unknown expiry.
2. **boAt Scratch Card ("BTXSGHW83N4R")** – The scratch card advertises boAt, but the saved coupon again shows the merchant as "Now" even though the code and discount text were captured.
3. **Leaf Bass Wireless Reward ("CREDBASS")** – The voucher still has 13 days left in the source screenshot, while the extracted result shows an explicit date of 7 Oct 2025 and marks the coupon as expired.
4. **OTTPlay Subscription Offer ("OTTPHONEBUFF")** – The source card lists 31 May 2025 as the expiry, yet the extracted entry marks it as expired immediately after import.

These failures mirror the mis-classification problems that the new progressive extraction plan was designed to eliminate.

## Root-Cause Analysis

### 1. LLM Pass Is Not Taking Effect
`ProgressiveExtractionService` is supposed to start with the MiniCPM/Qwen LLM pass and only fall back to pattern-based heuristics if the AI result is missing or low confidence.【F:app/src/main/kotlin/com/example/coupontracker/extraction/ProgressiveExtractionService.kt†L114-L235】 However, `LocalLlmOcrService.processCouponImage()` throws whenever the model is unavailable (for example, when the 4.9 GB package is not downloaded) or when inference fails/times out, and immediately drops into the `fallbackToTraditionalOCR()` routine.【F:app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt†L301-L423】【F:app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt†L1061-L1109】 The fallback path reuses the legacy `TextExtractor` heuristics, which were the original source of the brand/date mistakes. As long as the device never completes a real LLM inference, the "new" pipeline effectively behaves exactly like the legacy heuristic stack.

### 2. Heuristic Store Detection Still Prefers Generic Words
When the fallback path runs, the store name comes from `TextExtractor.extractStoreName()`, which ranks candidates by frequency and position but only filters out a small list of stop-words.【F:app/src/main/kotlin/com/example/coupontracker/util/TextExtractor.kt†L144-L189】【F:app/src/main/kotlin/com/example/coupontracker/util/TextExtractor.kt†L1143-L1147】 Words such as "now" are not in that stop-word list, so repeated UI phrases like "Shop Now" or "Scratch Now" often outrank the true brand name. If no strong candidate is found, `DefaultFieldProvider` finally fills the gap with the first non-empty OCR line, which again tends to be "Now" or similar CTA text.【F:app/src/main/kotlin/com/example/coupontracker/extraction/DefaultFieldProvider.kt†L16-L33】【F:app/src/main/kotlin/com/example/coupontracker/util/OcrTextCleaner.kt†L214-L222】 This explains why both the Myntra and boAt coupons were saved as "Now".

### 3. Relative Expiry Dates Depend on Screenshot Metadata
Structured expiry parsing converts phrases such as "Expires in 13 days" into an absolute date using the screenshot timestamp when available, but falls back to the device’s current time when that metadata is missing.【F:app/src/main/kotlin/com/example/coupontracker/extraction/StructuredFieldExtractor.kt†L334-L377】 On devices where capture metadata cannot be read, old screenshots immediately compute to past dates, causing the saved entries to show as expired even when the original voucher still has remaining validity.

## Why the New Plan Appears Ineffective
The progressive pipeline _is_ wired up, but it still depends on a successful on-device LLM call to replace the heuristic results. In the user’s logs/screenshots the LLM never won, either because:
- the MiniCPM/Qwen model bundle was not installed or failed to load (`isServiceAvailable()` check fails); or
- inference timed out or returned mock output, triggering the `fallbackToTraditionalOCR()` path.

In both cases the downstream stages consume the legacy heuristic output, so the user sees exactly the same merchant misidentification and expiry-date errors that existed before the plan was created. Until the LLM path consistently returns high-confidence results, the pipeline cannot correct these cases.

## Recommended Next Steps
1. **Verify the LLM runtime on the affected device** – confirm the model is downloaded, loaded, and passes the warm-up self-test before scanning coupons.
2. **Harden heuristic fallbacks** – extend the store-name stop-word list (e.g., block "now", "today", "details") so the default provider does not overwrite the real brand when LLM is unavailable.
3. **Persist capture timestamps** – extract EXIF metadata or allow the user to supply the capture date so relative expiries are computed relative to the original screenshot, not the import date.

Addressing the LLM availability will allow the new plan to deliver the expected improvements; tightening the fallback logic prevents the regression when the AI stage is unavailable.
