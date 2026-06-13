# Google Play Release Checklist

Last updated: 2026-04-24

## Already handled in repo
- Release builds require signed artifacts via `RELEASE_KEYSTORE_*` properties or `keystore.properties`.
- `compileSdk` and `targetSdk` are set to 35.
- Release builds use the production network security config, while localhost cleartext remains debug-only.
- The app includes an in-app privacy policy screen and a configurable hosted privacy policy URL via `PRIVACY_POLICY_URL`.
- The app includes a configurable public website URL via `WEBSITE_URL`.

## Still required before first Play submission
- Replace the package name if `com.example.coupontracker` is not the final public application ID.
- Publish `docs/index.html` and `docs/privacy.html` publicly, for example with GitHub Pages from the `/docs` folder.
- Set the Play Console website field to `https://chetank2.github.io/coupontracker/`.
- Set the Play Console privacy policy field to `https://chetank2.github.io/coupontracker/privacy.html`.
- If you use another domain, build with matching `WEBSITE_URL` and `PRIVACY_POLICY_URL` Gradle properties.
- Create and protect the real release keystore.
- Build a signed app bundle:
  - `cp keystore.properties.example keystore.properties`
  - fill in the values
  - run `./gradlew :app:bundleRelease`
- Capture final store assets:
  - app icon
  - feature graphic
  - phone screenshots
  - short and full descriptions
- Complete Play Console declarations:
  - App content
  - Data safety
  - Content rating
  - Target audience
  - Ads declaration if applicable
- If this is a new personal developer account created after 2023-11-13, run the required closed test before production access.

## Suggested Data Safety baseline for this app
Review against shipped behavior before submitting.

- Data collected: app info and performance may be declared as not collected if nothing leaves device; coupon content and images should not be declared as collected if they remain entirely on device.
- Data shared: none, unless you add third-party analytics, crash reporting, or remote APIs.
- Security practices:
  - data encrypted in transit for model downloads
  - user can request deletion by deleting coupons, clearing app data, or uninstalling

## Commands
- Debug build: `./gradlew :app:assembleDebug`
- Release bundle: `./gradlew :app:bundleRelease`
- Release APK: `./gradlew :app:assembleRelease`
- Release bundle with custom public links:
  - `./gradlew :app:bundleRelease -PWEBSITE_URL=https://your-domain.example/ -PPRIVACY_POLICY_URL=https://your-domain.example/privacy.html`
