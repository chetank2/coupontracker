# Data Safety Draft

Review this against the final shipped app and any SDKs before submitting.

## Intended answers
- Does the app collect or share user data off device as part of normal coupon extraction?
  - No
- Are coupon screenshots uploaded to your servers for extraction?
  - No
- Are model files downloaded from the network?
  - Yes, only when the user explicitly requests model download
- Is data encrypted in transit?
  - Yes, model downloads are limited to HTTPS
- Can users delete data?
  - Yes, by deleting coupons, deleting model files, clearing app data, restoring over data, or uninstalling

## Permissions you should be ready to justify
- `CAMERA`: scan coupon images
- `READ_MEDIA_IMAGES` / legacy read permissions: import screenshots
- `POST_NOTIFICATIONS`: coupon expiry reminders
- `RECEIVE_BOOT_COMPLETED`: reschedule reminders after reboot
- `INTERNET` and `ACCESS_NETWORK_STATE`: optional on-device model download and connectivity checks

## Things that would change this form
- Adding crash reporting
- Adding analytics
- Adding remote OCR or remote LLM inference
- Adding cloud sync or account login
