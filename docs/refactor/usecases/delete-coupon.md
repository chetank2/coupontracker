# DeleteCoupon Use Case

## Problem

Deletion needs consistent behavior across home, detail, and cleanup flows. UI
code should not know repository deletion details or reminder cancellation rules.

## Target Structure

`domain/usecase/DeleteCouponUseCase` accepts coupon identifiers and delegates to
repository and scheduling collaborators.

## Current Implementation

`DeleteCouponUseCase` currently accepts a `Coupon` object and delegates directly
to `CouponRepository.deleteCoupon(coupon)`. It does not yet validate by id,
soft-delete, return a UI result, or cancel reminder/work dependencies.

## Solution

The use case should validate the target, delete or soft-delete according to the
current product decision, and cancel dependent reminders or background jobs. It
should return an explicit result for UI undo/error handling if the product adds
that capability.

## Files

Current files include `domain/usecase/DeleteCouponUseCase.kt`,
`data/repository/CouponRepository.kt`, `HomeViewModel`, `DetailViewModel`, and
worker/reminder scheduling files.

## Tests

Current minimum test: assert the passed coupon is delegated to the repository
delete call.

Target tests: test delete success, missing coupon, repository failure, and
reminder/job cleanup using fakes.

## Risks

Leaving background work after deletion can notify users about removed coupons.
Direct DAO access from UI would bypass product rules.

## Definition Of Done

All deletion flows use the use case, dependent work is cleaned up, and UI gets a
clear success or failure result.
