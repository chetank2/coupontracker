# Refactor Architecture

The clean target architecture uses the user's top-level structure with a more
detailed extraction package:

```text
ui/
  home/
  details/
  review/
  settings/
  modelsettings/
  scanner/

data/
  db/
  repository/
  mapper/
  preferences/

domain/
  model/
  repository/
  usecase/

extraction/
  crop/
  ocr/
  rules/
  pipeline/
  validation/
  merge/

ai/
  model/
  cleanup/
  verification/

worker/
```

## Current Refactor Status

- `extraction/merge` now owns pure JSON merge/dedupe helpers.
- `domain/usecase` now contains thin use-case seams for extraction, cleanup,
  save, delete, and share flows.
- Existing Room-backed `Coupon` remains in `data/model` until a dedicated
  entity/domain mapper pass can preserve schema compatibility.
- Existing legacy fragments and route declarations remain in place until a
  dedicated navigation pass updates XML Safe Args and Compose route imports.

## Next Safe Slices

1. Move model adapters from `extraction/model` to `ai/model`, updating Hilt
   `ModelModule` and tests in one pass.
2. Move OCR interfaces from root `ocr` into `extraction/ocr`, keeping adapters
   for existing imports.
3. Split scanner UI state from `ScannerViewModel` before moving scanner files.
4. Extract `CouponEntity`, domain `Coupon`, and `CouponMapper` in one
   migration-safe Room pass.

