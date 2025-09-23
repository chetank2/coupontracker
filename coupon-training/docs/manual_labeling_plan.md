# Coupon Annotation Gap Recovery Plan

This checklist helps curators fill the missing fields that keep the pattern generator from emitting richer templates.

## 1. Prioritize Samples

Run `python3 scripts/list_annotation_gaps.py` (see below) to export `reports/annotation_gaps.csv`. Sort the file by the `missing_fields` column and focus on rows with multiple missing values first (especially where the store + discount + expiry are all empty).

## 2. Label Using the Web UI

1. Launch the web UI annotator:
   ```bash
   python run_web_ui.py
   ```
2. Open http://localhost:5001 and switch to the *Training* tab.
3. Use *Load Existing Annotation* to import one of the images listed in `annotation_gaps.csv`.
4. Draw or adjust bounding boxes for:
   - Store name / Brand
   - Coupon code
   - Discount amount
   - Expiry date
   - Partner / Offer description
5. Enter the textual value for every bounding box in the side panel so the JSON gains real field values.
6. Save the annotation; the updated JSON will land back under `data/annotated/`.

## 3. Field-Specific Tips

- **Store**: record the merchant brand exactly as it appears (e.g., `Swiggy`, `Myntra`).
- **Partner / Description**: capture a short phrase that explains which offer or platform it applies to (e.g., `Valid on Swiggy Instamart`).
- **Discount**: normalise as `40% OFF`, `Flat ₹200`, or `₹200 Cashback`.
- **Expiry Date**: keep the original format if legible (`30-06-2025`, `30 Jun 2025`).
- **Coupon Code**: uppercase alphanumeric with no spaces.
- **Min Order**: if the art mentions a minimum spend, capture it as `Min ₹500`.

## 4. Batch the Work

- Aim for at least 20 fully labelled coupons per pass (store + discount + code + expiry).
- Save frequently; the CLI will detect modified JSON files for the next training run.

## 5. Rerun Training + Evaluation

Once a new batch is labelled:
```bash
python3 train_model.py --input-dir data/annotated --epochs 20
```
Review `train_model.log` and `models/model_metadata.json` for accuracy trends. Commit both the JSON updates and the new model metadata so the Android app can pull the richer model.

## 6. Tracking Progress

- Re-run the `scripts/list_annotation_gaps.py` helper to ensure the count of missing fields drops steadily.
- Use `git diff --stat` to verify how many annotations were improved.

## Helper Script

The repository already includes `scripts/list_annotation_gaps.py` to regenerate `reports/annotation_gaps.csv`. Run it whenever you need a fresh progress snapshot.
