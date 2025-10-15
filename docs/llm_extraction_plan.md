# Coupon Extraction Stability Plan

## Objectives
- Extract expiry date, description, store name, and other coupon fields accurately without regression when prompts or schemas change.
- Leverage large language models (LLMs) for flexible parsing while insulating downstream systems from prompt variability.
- Establish a feedback loop so extraction quality improves over time instead of requiring ad-hoc fixes.

## Guiding Principles
1. **Schema-first contracts** – Define a single canonical JSON schema for coupon data (using strict types for date, currency, etc.). Any model or rule-based extractor must target this schema, keeping downstream integration stable.
2. **Separation of concerns** – Use isolated modules for prompt construction, LLM inference, post-processing, and validation. Changes to one layer should not affect others.
3. **Deterministic validation** – Validate LLM output with deterministic rules (regular expressions, business logic). Reject or auto-correct invalid fields before they reach storage.
4. **Observability and regression testing** – Maintain golden datasets and automated evaluation to detect regressions when prompts or model versions change.

## Target Architecture
```
raw text/image -> preprocessing -> prompt builder -> LLM extraction -> structured JSON -> validators -> resolver -> storage/API
```

### 1. Preprocessing
- Normalize OCR results (remove duplicates, fix casing, standardize date formats like `MM/DD/YYYY` vs `DD MMM YYYY`).
- Detect coupon language and route to language-specific prompt templates if necessary.

### 2. Prompt Builder
- Maintain versioned prompt templates stored as files (e.g., `prompts/coupon_v1.txt`).
- Compose prompts programmatically: `system` message enforces schema, `user` message supplies coupon text, optional `few-shot` examples for tricky cases.
- Use template variables for toggling focus (expiry, description, store) without rewriting prompts.

### 3. LLM Extraction
- Prefer JSON mode or function calling (OpenAI/Anthropic/etc.) to force structured output.
- Enable `temperature = 0` to reduce randomness.
- Retry with fallback prompt variants when the model returns invalid JSON.

### 4. Validators
- Implement validators per field:
  - **Expiry date** – parse with multiple formats, ensure it is not in the past (unless historical coupons allowed), cross-check with text.
  - **Description** – require minimum length, reject when identical to store name.
  - **Store name** – fuzzy match against known list; if unknown, ensure it contains alphabetic words and not dates/prices.
- Keep validators declarative so they can be unit tested and reused.

### 5. Resolver Layer
- Combine validator feedback with LLM output:
  - If one field fails, re-prompt only for that field using a focused prompt (`"Given the coupon text, only return the store_name"`).
  - Cache intermediate results to avoid reprocessing the entire coupon.
  - Apply rule-based fallbacks (e.g., regex for expiry date) when LLM is uncertain.

### 6. Storage/API
- Store version metadata: prompt version, model version, validator version. This enables auditing and rollback when regressions occur.

## Implementation Plan
1. **Define schema and validation rules**
   - Create `schemas/coupon.json` with all required fields.
   - Implement validators in `app/src/main/java/.../validators` with unit tests covering positive/negative cases.

2. **Prompt management**
   - Introduce a `prompts/` directory with YAML/JSON metadata describing each prompt version.
   - Build a prompt loader that reads templates and substitutes variables.

3. **Extraction service wrapper**
   - Wrap LLM API calls in a service that handles retries, temperature, max tokens, and logging.
   - Expose a function `extractCouponFields(text, fields=None)` that optionally focuses on specific fields.

4. **Feedback loop & evaluation**
   - Assemble a labeled dataset (start with 100+ coupons) stored in `data/golden_coupons.jsonl`.
   - Write an evaluation script to compare extracted outputs with ground truth and produce metrics per field.
   - Run evaluation automatically (CI pipeline) when prompts, validators, or model configs change.

5. **Continuous learning**
   - Log all production extractions with validator outcomes.
   - Prioritize failed cases for human review and add to the golden dataset.
   - Optionally fine-tune or build smaller specialized models using accumulated data.

6. **Operational safeguards**
   - Add alerting when failure rates exceed thresholds.
   - Version-control prompt and validator changes; require review plus evaluation results before merging.
   - Provide a rollback mechanism (keep previous prompt/model versions available).

## Leveraging LLM Potential
- Use chain-of-thought internally (hidden) when requesting complex reasoning, but ensure final answer remains structured JSON.
- Explore multi-pass extraction: first pass to identify candidate fields, second pass to resolve conflicts.
- Consider ensemble approaches (LLM + deterministic regex + small classifier) with a resolver picking the best field.
- Use embeddings to match against known store names for better accuracy.

## Long-term Enhancements
- Train a lightweight classifier to predict confidence per field and trigger targeted re-prompts only when confidence is low.
- Experiment with vision-language models for cases where layout matters.
- Automate prompt evaluation by simulating coupon variants (synthetic data) to ensure resilience to format changes.

By enforcing modular boundaries, strong validation, and continuous evaluation, changes to one field (expiry date, description, store name) will not cascade into others. The LLM remains central for flexible extraction, but deterministic layers guarantee stability and precise data for downstream consumers.
