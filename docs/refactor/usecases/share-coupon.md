# ShareCoupon Use Case

## Problem

Sharing should produce consistent coupon text and avoid scraping rendered UI.
Different screens should not format share payloads differently.

## Target Structure

`domain/usecase/ShareCouponUseCase` builds a share payload from a domain coupon
or coupon id. Android intent creation stays at the UI/platform boundary.

## Solution

The use case formats store, offer description, code, expiry, amount, and terms
using domain-safe rules. It omits unknown values instead of emitting placeholders.
UI packages receive a plain payload and pass it to Android share APIs.

## Files

Current files include `domain/usecase/ShareCouponUseCase.kt`,
`DetailViewModel`, `CouponCard` actions, and any existing share helpers.

## Tests

Test formatting with full fields, missing optional fields, expired coupons,
unicode/currency content, and no placeholder leakage.

## Risks

Embedding share text in UI creates drift. Including low-confidence or invented
values can mislead users.

## Definition Of Done

All share actions use one formatter, placeholders are omitted, and UI handles
only platform dispatch.

