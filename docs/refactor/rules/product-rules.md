# Product Rules

## Problem

CouponTracker succeeds only if users can capture, review, save, find, clean, and
share real coupon data without hidden model guesses. Refactor work must protect
that product contract.

## Target Structure

Product behavior should be expressed in use cases and pipeline rules, with UI
packages exposing states and actions rather than duplicating business decisions.
Persistence remains in data repositories, and background verification remains in
workers or AI verification adapters.

## Solution

- Capture must be fast, local, and OCR-first.
- Multi-coupon screenshots must be segmented into card crops before per-coupon
  field extraction.
- Saved coupons must preserve user edits and original evidence where available.
- Cleanup may normalize fields but must not invent critical values absent from
  OCR evidence.
- Review screens must expose uncertainty instead of hiding low confidence fields.
- Deletion must be explicit and repository-driven.
- Sharing must use a stable formatted representation, not UI text scraped from a
  composable or fragment.

## Files

Important files include `ScannerViewModel`, `MultiCouponReviewViewModel`,
`HomeViewModel`, `DetailViewModel`, `CouponRepository`, `domain/usecase/*`,
`util/OcrEvidenceValidator.kt`, `worker/VerifyCouponWorker.kt`, and extraction
pipeline classes.

## Tests

Cover save/delete/share use cases with repository fakes. Cover extraction with
fixture OCR text and multi-coupon crop tests. Regression tests should assert no
placeholder store, description, code, or expiry is promoted when evidence exists.

## Risks

Refactors can accidentally make AI cleanup part of capture, skip review for
uncertain coupons, discard image evidence, or normalize user edits back to model
output.

## Definition Of Done

User-facing flows still capture, review, save, delete, share, and clean coupons
with the same or better evidence handling, and product rules are enforced outside
rendering code.

