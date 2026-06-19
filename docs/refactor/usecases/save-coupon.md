# SaveCoupon Use Case

## Problem

Saving can happen from scanner, review, manual entry, detail edits, and batch
flows. Repository calls need a single business boundary for validation,
deduping, timestamps, and reminder scheduling.

## Target Structure

`domain/usecase/SaveCouponUseCase` accepts a domain coupon draft or edited coupon
and delegates persistence to a domain repository contract implemented by data.

## Solution

Centralize pre-save validation, canonical field cleanup, duplicate checks, and
post-save side effects such as verification or reminders. Keep Room entity
conversion inside `data/mapper`, not in UI or use cases.

## Files

Current files include `domain/usecase/SaveCouponUseCase.kt`,
`data/repository/CouponRepository.kt`, `CouponRepositoryImpl.kt`,
`data/model/Coupon.kt`, review/scanner ViewModels, and future `data/mapper/*`.

## Tests

Use repository fakes to test insert, update, duplicate handling, invalid draft
rejection, and worker/reminder enqueue decisions.

## Risks

Saving directly from UI can bypass validation. Entity/domain confusion can break
Room schema or lose review metadata.

## Definition Of Done

All save flows call the use case, entity mapping is data-owned, and save behavior
is covered by focused unit tests.

