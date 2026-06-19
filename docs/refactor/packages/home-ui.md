# Home UI Package

## Problem

Home owns the coupon list experience but can easily absorb filtering, sorting,
deletion, reminder, and repository logic.

## Target Structure

`ui/home` owns `HomeScreen`, `HomeViewModel`, list UI state, filter/sort UI
models, and home events. Business operations call use cases or repository-backed
domain queries.

## Solution

Home subscribes to coupon list state, maps domain coupons to card models, and
routes user actions to details, scanner, delete, share, or settings. Filtering
and sorting should be typed and testable.

## Files

Current implementation files include `ui/home/HomeScreen.kt` and
`ui/home/HomeViewModel.kt`. Related files include legacy fragment/navigation
bridges, `ui/components/CouponCard.kt`,
`ui/model/CouponUiMapper.kt`, and `data/SortOrder.kt`.

## Tests

Test empty state, loaded state, search/filter/sort, delete action, navigation
events, and stale/expired coupon display.

## Risks

List UI can duplicate repository filtering or format data differently from
details and share flows.

## Definition Of Done

Home renders list state, delegates business actions, and uses shared coupon UI
models/components.
