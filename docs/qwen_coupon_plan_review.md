# Review of Qwen-Based Coupon Extraction Plan

## Overview

The shared plan proposes using Qwen vision-language models in combination with OCR to extract structured coupon data from application screenshots. The screenshot example contains multiple coupons with repeated visual patterns (e.g., "Collect Now" buttons) that need to be segmented and transformed into structured JSON records.

## Strengths

1. **Leverages Vision-Language Capabilities** – Recommending Qwen2.5-VL variants for direct image understanding is appropriate because they can natively interpret layout and text in screenshots, potentially reducing OCR errors for clear sections.  
2. **Structured Prompting** – The plan includes a clear system prompt specifying the required JSON schema, which helps maintain consistency in the extracted fields.  
3. **Fallback to Text-Only Models** – Offering an offline alternative (Qwen2.5-Text-1.5B-GGUF + OCR) supports on-device scenarios where network calls are not possible.  
4. **Segmentation Strategy** – Splitting OCR text using repeated UI elements such as "Collect Now" is a practical heuristic for isolating individual coupons before LLM parsing.  
5. **Normalization Tips** – Advising currency and percentage normalization and providing date format hints are valuable techniques to improve extraction accuracy.

## Potential Gaps & Suggestions

1. **OCR Noise Handling** – The plan mentions pre-cleaning but does not specify a concrete pipeline for de-noising OCR output (e.g., removing banner labels like "Expires today" separately from coupon bodies). Adding regex-based cleaners or dictionary-driven filters would help.  
2. **Layout Variants** – Coupons may appear in grids, carousels, or modals without "Collect Now". Consider additional segmentation cues (e.g., bounding boxes from OCR, keyword-driven clustering, or layout analysis) to handle other templates.  
3. **Confidence Scoring** – Incorporating confidence thresholds from OCR and LLM outputs (such as verifying required fields or adding a "confidence" field) will make downstream validation easier.  
4. **Error Recovery** – The plan could outline retry logic (e.g., re-running OCR with different preprocessing or prompting Qwen with full screenshot context) when required fields are missing.  
5. **Latency Considerations** – For mobile deployments, profiling the combined OCR + Qwen inference latency is essential; caching model weights and batching coupon blocks can minimize delays.  
6. **Evaluation Metrics** – Define precision/recall metrics on labeled screenshots to quantify extraction accuracy, and include unit tests to verify schema compliance.

## Conclusion

The proposed approach is solid and aligns with Qwen's capabilities. Extending the plan with more robust preprocessing, segmentation fallback strategies, and post-extraction validation will improve reliability, especially across diverse coupon layouts and device constraints.
