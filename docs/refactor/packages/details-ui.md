# Details UI Package

## Problem

Details code is split between legacy fragment/screen locations and new
`ui/details` files. The package needs one owner for detail state, edits,
sharing, deleting, and cleanup actions.

## Target Structure

`ui/details` owns `CouponDetailScreen`, `DetailViewModel`, detail UI state,
events, and mappers specific to the details view. Fragment hosts remain only as
navigation adapters until XML navigation is retired.

## Solution

Route actions through use cases: save for edits, delete for removal, share for
share payload, and clean for cleanup. The screen renders coupon state,
confidence, evidence warnings, and loading/error states.

## Files

Current files include `ui/details/CouponDetailScreen.kt`,
`ui/details/DetailViewModel.kt`, `ui/fragment/DetailFragment.kt`,
`ui/navigation/AppNavigation.kt`, and shared components in `ui/components`.

## Tests

Add ViewModel tests for load, edit, save, delete, share, cleanup, and error
states. Navigation changes require fragment/route verification.

## Risks

Moving details without Safe Args and route updates can break navigation. Keeping
duplicate detail screens can cause behavior drift.

## Definition Of Done

One details package owns the screen and ViewModel, all actions call use cases,
and legacy hosts only bridge navigation.

