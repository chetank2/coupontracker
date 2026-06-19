# Training And Annotation Knowledge

Use this page for training data, annotation tools, the web training interface,
mobile PWA annotation, generated reports, and Android model asset preparation.

## Current Principle

Training data must match the Android extraction contract.

If annotation labels, exported model outputs, or field schemas drift from the
Android app, model improvements will create runtime bugs.

## Current Docs

- [Training report](../../generated_report/training_report.json)
- [Mac extraction harness plan](../../superpowers/plans/2026-04-26-mac-extraction-harness.md)
- [Mac extraction harness design](../../superpowers/specs/2026-04-26-mac-extraction-harness-design.md)
- [Coupon extraction baseline](../../superpowers/plans/2026-04-18-coupon-extraction-baseline.md)
- [Schema v2 and device tiers](../../superpowers/plans/2026-04-19-schema-v2-and-device-tiers.md)
- [Schema v2 enablement](../../superpowers/plans/2026-04-19-schema-v2-enablement.md)
- [Device tiers](../../extraction/device_tiers.md)
- [Model strategy](../../extraction/model_strategy.md)

## Historical Docs

Use for history only:

- [Coupon training India README](../../archive/coupon-training_README_INDIA.md)
- [Coupon training standardized process](../../archive/coupon-training_README_STANDARDIZED_PROCESS.md)
- [Coupon training data collection README](../../archive/coupon-training_data_collection_README.md)
- [Coupon training web UI README](../../archive/coupon-training_web_ui_README.md)
- [Mobile coupon trainer README](../../archive/mobile-coupon-trainer_README.md)
- [Create production model](../../archive/CREATE_PRODUCTION_MODEL.md)
- [Multi coupon model delivery](../../archive/docs_multi_coupon_model_delivery.md)

## Data Rules

Annotation should preserve:

- source image,
- crop/region coordinates,
- OCR text,
- field values,
- field evidence,
- device/source app context when available,
- accepted/rejected status.

Do not store only final cleaned values when debugging extraction quality. The
app needs evidence to learn from failures.

## Schema Rules

Training labels should align with Android fields:

- store,
- offer/description,
- redeem code,
- expiry,
- amount/payment terms,
- confidence,
- needs review/attention,
- evidence source.

When Android field semantics change, update training/annotation docs in the
same change.

## Offline Annotation Rules

For PWA/offline annotation:

- document IndexedDB schema changes,
- version service worker cache behavior,
- test offline create/edit/export,
- test online sync/export after offline edits.

## Model Asset Rules

Before shipping a trained/exported asset to Android:

- verify format compatibility,
- verify model size and device tier,
- run sample extraction against known coupons,
- document expected input/output contract,
- update model history.
