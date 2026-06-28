# AI Model Package

## Problem

Model adapters currently sit under extraction-oriented packages even though AI
cleanup and verification must remain separate from capture. Model selection,
roles, metadata, and runtime errors need a dedicated owner.

## Target Structure

Future target: `ai/model` owns model interfaces, adapters, selectors, metadata,
roles, and runtime configuration. `ai/cleanup` and `ai/verification` consume
those model interfaces. Capture pipelines may request retry/verification only
through explicit boundaries.

Current state: no `ai/` package exists. Model-related code remains under
`extraction/model`, `llm`, `ml`, `model`, `runtime`, and `verification`.

## Solution

Move `extraction/model/*` into `ai/model` in one Hilt-aware slice. Keep adapter
interfaces stable, update imports, and provide temporary compatibility aliases
only if needed. Model roles should distinguish text cleanup, vision retry, and
verification instead of a single generic model path.

## Files

Current files include `extraction/model/CouponExtractionModel.kt`,
`ModelSelector.kt`, `ModelStrategyConfig.kt`, `QwenTextCouponModel.kt`,
`QwenVlmCouponModel.kt`, `GemmaTextCouponModel.kt`, `GemmaVisionCouponModel.kt`,
`MiniCpmVlmCouponModel.kt`, `ReplayCouponModel.kt`, `ui/modelsettings/ModelImportViewModel.kt`,
and Hilt modules that bind models.

## Tests

Test model selector behavior, invalid model handling, replay model fixtures,
role selection, and cleanup/verification callers after import updates.

## Risks

Changing package names can break Hilt bindings and tests. Moving models into
capture code would violate OCR-first capture.

## Definition Of Done

AI model code lives under `ai/model` only after the dedicated migration slice;
until then current model packages remain intact. Hilt bindings compile, model
settings still work, capture remains OCR-first, and model callers use
role-specific interfaces.
