# Publish Readiness Notes

## Files to edit before release
- `keystore.properties` from `keystore.properties.example`
- `app/src/main/assets/privacy_policy.txt`
- `config/version.properties`
- package name and app branding if the current development identity is not final

## Hosted assets you still need outside the repo
- public privacy policy URL
- support contact in the app listing and privacy policy
- store screenshots
- feature graphic / marketing art
- developer account setup for Play Console and Indus

## Review-sensitive copy
- Describe the app as a personal coupon organizer and reminder tool.
- State that OCR and local extraction can be wrong and require user review.
- Do not imply guaranteed coupon validity, merchant acceptance, cashback, winnings, earnings, or savings.
- Keep developer contact details visible in both the hosted privacy policy and app listing.

## Build outputs
- Play: signed `.aab`
- Indus: upload the artifact type currently accepted by the developer portal for your app listing flow
