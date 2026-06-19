# Model Settings UI Package

## Problem

Model import and model selection affect AI cleanup and verification, but capture
must remain OCR-first. Model settings need explicit boundaries so users do not
mistake model setup for required capture behavior.

## Target Structure

`ui/modelsettings` owns model import, model status, active cleanup model
selection, download/import validation state, and error display.

## Solution

UI calls model-management use cases or `ai/model` services. It displays model
availability, role, size, validation status, and cleanup capability. It does not
invoke capture extraction or route scanner behavior directly.

## Files

Current files include `ui/modelsettings/ModelImportViewModel.kt`,
`ui/modelsettings/LicenseGateScreen.kt`, `extraction/model/*`,
`util/ModelMetadataReader.kt`, `model/*`, `llm/*`, and
future `ai/model/*`.

## Tests

Test import state transitions, invalid model rejection, role selection, storage
errors, and capture independence.

## Risks

Model settings can accidentally reconfigure capture to model-first extraction or
hardcode model paths outside model management.

## Definition Of Done

Model settings manage model availability only, capture remains OCR-first, and
model paths/roles are owned by `ai/model`.
