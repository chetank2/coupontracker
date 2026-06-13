# Indus Appstore Release Checklist

Last updated: 2026-05-06

## Already handled in repo
- Signed release configuration is supported through `keystore.properties` or Gradle properties.
- The app has an in-app privacy policy screen and supports a hosted privacy policy URL.

## Still required before submission
- Complete developer verification in the Indus developer platform.
- Publish `docs/index.html` and `docs/privacy.html` publicly, for example with GitHub Pages from the `/docs` folder.
- Set the app website to `https://chetank2.github.io/coupontracker/`.
- Set the privacy policy URL to `https://chetank2.github.io/coupontracker/privacy.html`.
- Confirm the app listing includes developer contact details:
  - Developer: Chetan K / chetank2
  - Support contact: `https://github.com/chetank2/coupontracker/issues`
- Build the signed release artifact required by the Indus portal for your submission flow.
- Prepare metadata:
  - app title
  - concise description
  - screenshots
  - icon
  - target audience / content rating
  - support contact details
  - clear limitation text that coupon extraction is OCR-based, may be wrong, and does not guarantee coupon validity or savings
- Verify that all permissions map to visible product features:
  - camera
  - media/image access
  - notifications
  - network for model download

## Notes
- Indus developer policy requires accurate metadata, privacy disclosures, and identity/business verification.
- Re-check the current accepted artifact type in the portal during upload. Their policy explicitly references APKs, but the portal experience may evolve.
