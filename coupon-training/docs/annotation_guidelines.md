# Coupon Annotation Guidelines

This document defines the ontology and best practices for labeling coupon screenshots. Keep it open while annotating.

## Ontology (regions & fields)

| Category        | Description                                | Typical Content Examples                    |
|-----------------|--------------------------------------------|---------------------------------------------|
| `app_region`    | App branding / header strip                | Swiggy banner, GPay header                  |
| `store_region`  | Merchant or store name                     | Myntra, Amazon                              |
| `code_region`   | Redeemable coupon or voucher code          | `SAVE40`, `MYNTRA2025`                      |
| `benefit_region`| Discount / benefit text                    | `Flat 40% OFF`, `₹200 Cashback`             |
| `expiry_region` | Expiry statement or date                   | `Valid till 30 Jun 2025`                    |
| `terms_region`  | Fine print / terms & conditions            | `Applicable on orders above ₹999`           |

Each annotation should include bounding boxes for every visible category. If a field is missing, leave it blank—do **not** invent it.

## Labeling workflow

1. **Load the screenshot** in the annotation UI.
2. **Enable pre-annotation suggestions** (if available) to review OCR-based boxes. Accept or adjust as needed.
3. **Draw/adjust boxes** using the taxonomy table above.
4. **Fill field text** in the side panel exactly as seen (preserve case, numbers, ₹). Use `None` only when truly absent.
5. **Validate**: toggle the `Guidelines` pane in the UI to double-check style examples.
6. **Submit for review**. Another annotator can compare IoU; disagreements < 0.6 are escalated for adjudication.

## Style guide

- Align boxes tightly to the text; avoid excessive padding.
- For multi-line text, draw one box that covers the entire block.
- Capture currency symbols (₹, $, %) within the box and text field.
- Dates should be transcribed verbatim (`30/06/2025`, `30 Jun 2025`).
- If multiple codes exist, create separate `code_region` boxes for each.

## Metadata (per image)

Assign the following attributes via the Dataset Manager or UI metadata form:

- `source_type`: e.g., `android_screenshot`, `web_capture`, `marketing_asset`.
- `theme`: e.g., `dark`, `light`, `seasonal`, `flash_sale`.
- `language`: `en`, `hi`, `mr`, etc.

These fields are stored alongside the manifest for filtering and stratified validation.

## Reference Examples

Include curated reference images in `docs/reference/` with good/bad annotations. Annotators can open them directly from the Guidelines pane (UI work TBD).
