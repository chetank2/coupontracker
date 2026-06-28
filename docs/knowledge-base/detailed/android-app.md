# Android App Architecture Knowledge

Use this page for Android screens, navigation, Room data, workers, settings,
and user-facing coupon flows.

## Current Principle

UI collects intent. Domain/use cases coordinate behavior. Extraction and model
packages own coupon understanding. Data packages own persistence.

Avoid placing product logic directly in composables, fragments, or activity
classes.

## Current Code Areas

- `app/src/main/kotlin/com/example/coupontracker/ui/home`
- `app/src/main/kotlin/com/example/coupontracker/ui/details`
- `app/src/main/kotlin/com/example/coupontracker/ui/review`
- `app/src/main/kotlin/com/example/coupontracker/ui/settings`
- `app/src/main/kotlin/com/example/coupontracker/ui/modelsettings`
- `app/src/main/kotlin/com/example/coupontracker/ui/navigation`
- `app/src/main/kotlin/com/example/coupontracker/domain/usecase`
- `app/src/main/kotlin/com/example/coupontracker/data/local`
- `app/src/main/kotlin/com/example/coupontracker/data/model`
- `app/src/main/kotlin/com/example/coupontracker/data/preferences`
- `app/src/main/kotlin/com/example/coupontracker/data/repository`
- `app/src/main/kotlin/com/example/coupontracker/worker`
- `app/src/main/kotlin/com/example/coupontracker/work`

Current Room code is in `data/local` and `data/model`. A future `data/db`
package is a target only; do not move Room classes there without a dedicated
migration-safe split.

Current WorkManager code is split between `worker` and `work`; `worker` is the
target home, but `work/CouponReminderWorker.kt` still exists.

## Current Docs

- [Implementation status](../../IMPLEMENTATION_STATUS.md)
- [Project knowledge diary](../../PROJECT_KNOWLEDGE_DIARY.md)
- [Refactor README](../../refactor/README.md)
- [Refactor architecture](../../refactor_architecture.md)
- [Home UI package](../../refactor/packages/home-ui.md)
- [Details UI package](../../refactor/packages/details-ui.md)
- [Review UI package](../../refactor/packages/review-ui.md)
- [Settings UI package](../../refactor/packages/settings-ui.md)
- [Model settings UI package](../../refactor/packages/model-settings-ui.md)
- [Worker package](../../refactor/packages/worker.md)

## Current User Flows

### Scan Or Import Coupon

Expected path:

```text
image selected/captured
-> card or region detection
-> OCR/model extraction
-> validation
-> review/save
```

Do not skip the review/validation behavior when confidence is low.

### Review Coupon

The review surface must show fields in a way that allows correction before
final trust.

Important fields:

- store,
- offer/description,
- code,
- expiry,
- amount/payment context,
- confidence/attention state.

### Saved Coupon Details

Details should show the saved state. It should not silently re-run extraction
or change fields without an explicit user or worker action.

### Settings And Model Settings

Settings should expose operational state, not hide it:

- model availability,
- OCR/model strategy,
- debug flags when available,
- storage/privacy behavior.

## Data Rules

- Room entities are persistence details.
- Domain models are app behavior contracts.
- UI models are display contracts.
- Mappers should be explicit.
- Schema changes need migration thinking and tests.

## Worker Rules

Workers can clean, verify, or refresh coupons, but they must respect protected
field merge policy.

Do not let a background worker turn uncertain data into trusted data without
evidence.

## Tests To Add For New Bugs

- use case tests for save/delete/share behavior,
- repository tests for mapping and persistence rules,
- ViewModel tests for navigation/result state,
- worker tests for verification/cleanup merge behavior.
