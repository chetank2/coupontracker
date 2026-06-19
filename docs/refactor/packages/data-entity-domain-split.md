# Data Entity And Domain Split

## Problem

`data/model/Coupon.kt` currently acts as persistence entity, domain model, and UI
source in many places. Splitting it incorrectly can break Room schema or lose
fields.

## Target Structure

`data/entity` owns Room entities. `domain/model` owns pure domain models.
`data/mapper` converts between entities and domain models. Repository
implementations hide Room details behind domain repository contracts.

## Solution

Do the split in one migration-safe pass. Preserve table names, column names,
indices, converters, default values, and migrations. Introduce `CouponEntity`
with existing Room annotations, introduce domain `Coupon`, add mappers, update
repositories, then update UI/use-case imports to domain models.

## Files

Current files include `data/model/Coupon.kt`, `CashbackInfo.kt`, `CouponField.kt`,
`Settings.kt`, `data/local/CouponDao.kt`, `CouponDatabase.kt`, `Converters.kt`,
`data/repository/CouponRepository.kt`, and `CouponRepositoryImpl.kt`.

## Tests

Add mapper tests and Room schema/migration tests. Repository tests should prove
domain models round-trip without changing persisted schema.

## Risks

Room table or column drift can lose user data. Enum/default changes can alter
existing rows. UI imports may accidentally keep using entity types.

## Definition Of Done

Room schema is unchanged unless intentionally migrated, domain code no longer
depends on Room annotations, and mapper/repository tests cover round trips.

