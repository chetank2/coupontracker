# OfferText Plan Review – codex/remove-all-offertext-references-x67m2r

## Summary
- The branch completes the core runtime work to drop the legacy `offerText` column, remove it from the entity model, and enforce the slimmed extraction contract at both schema and LLM parsing layers.
- Key documentation cleanup items from the deprecation plan remain outstanding, leaving prominent guides that still instruct engineers to reason about `offerText` payloads.

## Items Completed
1. **Data model and schema updated** – The `Coupon` entity no longer exposes an `offerText` property, and the v7→v8 Room migration rebuilds the table without that column while keeping the historical data intact.【F:app/src/main/kotlin/com/example/coupontracker/data/model/Coupon.kt†L7-L75】【F:app/src/main/kotlin/com/example/coupontracker/data/local/CouponDatabase.kt†L220-L269】
2. **Canonical extraction contract enforced** – The extraction schema now only permits `storeName`, `description`, `redeemCode`, and `expiryDate`, and both the JSON validator and LLM post-processing strip any extra keys while normalizing `couponCode` into `redeemCode`.【F:app/src/main/kotlin/com/example/coupontracker/schema/CouponSchema.kt†L17-L114】【F:app/src/main/kotlin/com/example/coupontracker/util/CouponJsonValidator.kt†L24-L88】【F:app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt†L520-L553】
3. **Relative expiry handled via capture metadata** – When coupons only specify “expires in N days,” the parser now converts that into a concrete date using the screenshot timestamp, aligning with the plan’s structured-field requirements.【F:app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt†L724-L763】

## Gaps vs. Plan
1. **Documentation purge unfinished** – Multiple high-visibility troubleshooting docs still contain JSON samples and guidance built around `offerText`, contrary to the plan’s directive to remove those references from documentation and training material.【F:docs/OFFER_TEXT_DEPRECATION_PLAN.md†L42-L45】【F:PROGRESSIVE_EXTRACTION_COMPLETE.md†L219-L247】【F:FIX_REQUIRED_FIELDS_KAPIVA_2025-10-11.md†L23-L69】
2. **Historical schema snapshots still advertise `offerText`** – Room schema versions 6 and 7 under `app/schemas/...` continue to list the deprecated column. If the intent is to keep only post-removal schemas in tree, these files will need regeneration or pruning to avoid confusing downstream tooling during plan verification.【F:app/schemas/com.example.coupontracker.data.local.CouponDatabase/6.json†L9-L73】【F:app/schemas/com.example.coupontracker.data.local.CouponDatabase/7.json†L9-L73】

## Recommendation
Finish the documentation and artifact cleanup called out in the deprecation plan so future contributors and automated checks no longer see `offerText` examples or schema definitions. Once those files are updated (or archived), the branch will meet the plan end-to-end.
