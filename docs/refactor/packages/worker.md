# Worker Package

## Problem

Background work is split between `worker` and `work`, with some enqueue logic in
repositories, analytics, ViewModels, and boot receivers. Workers should execute
background tasks without owning product rules.

## Target Structure

`worker` owns WorkManager workers and enqueue helpers. Workers depend on use
cases or small repository contracts. Scheduling policies and unique work names
are centralized.

## Solution

Move `work/CouponReminderWorker.kt` into `worker` in a dedicated slice, updating
manifest/Hilt/receiver imports. Keep worker bodies thin: load inputs, call use
case/repository, report success/retry/failure. Place enqueue helpers near the
worker so callers do not duplicate constraints or work names.

## Files

Current files include `worker/VerifyCouponWorker.kt`,
`CouponNotificationWorker.kt`, `OfflineRetrainingWorker.kt`, `ReminderWorker.kt`,
`StoreNameEvidenceWorker.kt`, `work/CouponReminderWorker.kt`,
`receiver/BootCompletedReceiver.kt`, and scheduler/repository enqueue callers.

## Tests

Use unit tests for enqueue helper inputs and worker decision logic where
possible. Add integration-style WorkManager tests for critical reminder and
verification flows if the project has support.

## Risks

Renaming or moving workers can break Hilt worker factories, unique work chains,
boot scheduling, or notification permissions.

## Definition Of Done

All workers live under one package, enqueue policies are centralized, workers
call use cases instead of embedding business rules, and imports compile.

