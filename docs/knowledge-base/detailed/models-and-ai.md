# Models And Local AI Knowledge

Use this page for Qwen, Gemma, MiniCPM, local model runtime, model cleanup,
vision verification, and model download behavior.

## Current Principle

Models are assistants, not authorities.

Model output can propose, normalize, clean, or verify fields, but final saved
coupon data must still pass deterministic validation.

## Current Code Areas

- `app/src/main/kotlin/com/example/coupontracker/extraction/model`
- `app/src/main/kotlin/com/example/coupontracker/extraction/merge`
- `app/src/main/kotlin/com/example/coupontracker/llm`
- `app/src/main/kotlin/com/example/coupontracker/llm/gemma`
- `app/src/main/kotlin/com/example/coupontracker/model`
- `app/src/main/kotlin/com/example/coupontracker/prompt`
- `app/src/main/kotlin/com/example/coupontracker/runtime`
- `app/src/main/kotlin/com/example/coupontracker/verification`
- `app/src/main/kotlin/com/example/coupontracker/worker`

There is no current `app/src/main/kotlin/com/example/coupontracker/ai`
package. `ai/` is a future consolidation target; current AI/model code remains
under `llm`, `ml`, `extraction/model`, `model`, `runtime`, and
`verification`.

## Current Docs

- [LLM integration](../../LLM_INTEGRATION.md)
- [Gemma mobile reader plan](../../gemma_mobile_reader_plan.md)
- [LLM extraction plan](../../llm_extraction_plan.md)
- [Qwen coupon plan review](../../qwen_coupon_plan_review.md)
- [Model strategy](../../extraction/model_strategy.md)
- [VLM retry](../../extraction/vlm_retry.md)
- [Model history](../../model_history/history.json)
- [LLM integration test report](../../llm_integration_test_report.json)
- [Project knowledge diary](../../PROJECT_KNOWLEDGE_DIARY.md)

## Current Rules

### Model Contract

Model integrations must define:

- input image/text scope,
- expected JSON shape,
- timeout behavior,
- confidence policy,
- merge policy,
- fallback behavior.

Never let unparsed free text directly update coupon fields.

### Vision Verification

`VISION_VERIFIED` must mean that core fields were actually supported by vision.

Do not upgrade a coupon to `VISION_VERIFIED` when:

- the model failed,
- the model returned unrelated text,
- only OCR fallback filled the important fields,
- store/offer/code/expiry are not supported by same-region evidence.

### Protected Fields

Protected fields should not be overwritten by weak model output:

- store name,
- redeem code,
- expiry date,
- meaningful offer/description.

Model cleanup may improve formatting, but it must not replace a strong value
with a generic value.

### Date Handling

Models may return readable expiry text such as:

- `Jun 30`
- `Expires in 29 days`
- `29 days left`

The app must normalize to canonical dates before saving.

## Known Failure Classes

### Random Vision Output

Symptoms:

- Gemma returns unrelated brands,
- description comes from a different coupon,
- expiry is omitted or written in non-canonical format.

Universal fix:

- crop first,
- validate model JSON,
- normalize expiry,
- merge only supported fields,
- keep `needsAttention = true` when vision evidence is weak.

### Mock Or Placeholder Output

Symptoms:

- tests pass but device behavior fails,
- model output looks structured but is not from real inference.

Universal fix:

- separate mock runtime from real runtime,
- make logs identify runtime type,
- never mark real verification from mock output.

### Model Download Or Load Failures

Symptoms:

- model not found,
- native library failure,
- timeout,
- disabled vision path.

Universal fix:

- expose load state in settings,
- log model path and runtime status,
- provide deterministic OCR fallback,
- never hide failure behind a success label.

## Historical Docs

Use for history only:

- [MiniCPM build guide](../../archive/MINICPM_BUILD_GUIDE.md)
- [MiniCPM implementation](../../archive/MINICPM_IMPLEMENTATION.md)
- [Mock vs real inference](../../archive/MOCK_VS_REAL_INFERENCE.md)
- [Qwen2 model upgrade](../../archive/QWEN2_MODEL_UPGRADE.md)
- [MLC deployment guide](../../archive/docs_MLC_DEPLOYMENT_GUIDE.md)
- [Real inference guide](../../archive/implementation/REAL_INFERENCE_GUIDE.md)
- [Vision implementation complete](../../archive/implementation/VISION_IMPLEMENTATION_COMPLETE.md)

## Tests To Add For New Bugs

- model JSON parser tests for every accepted output shape,
- expiry normalizer tests for new date strings,
- merge policy tests when model and OCR disagree,
- runtime state tests for disabled/missing model cases.
