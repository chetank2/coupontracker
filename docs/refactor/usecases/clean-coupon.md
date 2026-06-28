# CleanCoupon Use Case

## Problem

AI cleanup can blur into capture and may overwrite OCR-backed values or user
edits. Cleanup needs a separate explicit use case with evidence checks.

## Target Structure

`domain/usecase/CleanCouponUseCase` loads the saved coupon, sends only allowed
evidence to the cleanup/model-verification layer, validates the cleaned result,
and persists accepted changes through the repository. A future `ai/cleanup`
package may own that layer; it does not currently exist.

## Solution

Cleanup is opt-in or background verification after capture. It may normalize
description, merchant, code, expiry, and amount only when OCR evidence or user
confirmation supports the value. It records low-confidence or rejected fields as
review feedback instead of silently saving them.

## Files

Current files include `domain/usecase/CleanCouponUseCase.kt`,
`worker/VerifyCouponWorker.kt`, `util/OcrEvidenceValidator.kt`,
`extraction/model/*`, future `ai/cleanup/*`, and repository methods in
`data/repository`.

## Tests

Test accepted cleanup, rejected hallucinated values, preservation of user edits,
worker enqueue behavior, and repository update calls.

## Risks

Cleanup can corrupt saved coupons if it treats model output as truth or lacks
field-level provenance.

## Definition Of Done

Cleanup is separate from capture, evidence validation is enforced, user edits are
preserved, and accepted changes are auditable.
