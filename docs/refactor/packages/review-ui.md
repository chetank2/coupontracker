# Review UI Package

## Problem

Review flows for single and multi-coupon extraction are spread across scanner,
multi-coupon screens, forms, and ViewModels. Review needs to be a first-class UI
package because uncertain extraction is normal.

## Target Structure

`ui/review` owns review screens, review UI state, field confidence display,
per-coupon edit events, save decisions, and batch review state.

## Solution

Feed review with extraction results from `ExtractCouponUseCase`. Review lets the
user inspect fields, edit values, drop bad coupons, and save accepted coupons
through `SaveCouponUseCase`. It must preserve crop image, OCR evidence, and
field provenance where available.

## Files

Current files include `ui/review/MultiCouponReviewScreen.kt`,
`ui/review/MultiCouponReviewViewModel.kt`, `ui/review/MultiCouponPreviewScreen.kt`,
`ui/components/UnifiedCouponForm.kt`, and scanner batch review code.

## Tests

Test accept, edit, reject, save all, save selected, empty extraction, and
low-confidence field rendering.

## Risks

Review can become a passive confirmation screen that hides uncertainty. Batch
flows can save duplicates if dedupe state is lost.

## Definition Of Done

Review owns all extracted-coupon confirmation UI, preserves evidence, and saves
only user-accepted coupons through the save use case.
