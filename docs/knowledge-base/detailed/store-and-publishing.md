# Store, Privacy, And Publishing Knowledge

Use this page for Play Console, Indus Appstore, privacy pages, data safety,
store listing, release readiness, and publishing claims.

## Current Principle

Store and privacy docs must describe the app that actually ships.

Do not claim cloud privacy, model behavior, verification behavior, or data
deletion behavior unless the current app implements it.

## Current Docs

- [Publish readiness](../../store/publish-readiness.md)
- [Play Console checklist](../../store/play-console-checklist.md)
- [Indus Appstore checklist](../../store/indus-appstore-checklist.md)
- [Data safety draft](../../store/data-safety-draft.md)
- [Store listing draft](../../store/store-listing-draft.md)
- [Privacy page](../../privacy.html)
- [Docs index page](../../index.html)

## Historical Docs

Use for history only:

- [Privacy guarantee](../../archive/PRIVACY_GUARANTEE.md)
- [Deployment guide](../../archive/DEPLOYMENT_GUIDE.md)
- [Create GitHub release now](../../archive/CREATE_GITHUB_RELEASE_NOW.md)
- [GitHub release setup](../../archive/GITHUB_RELEASE_SETUP.md)
- [Release notes v2.0.0](../../archive/releases/RELEASE_NOTES_v2.0.0.md)
- [Final delivery summary](../../archive/releases/FINAL_DELIVERY_SUMMARY.md)

## Publishing Rules

### Privacy

Confirm before publishing:

- what data is stored locally,
- what data leaves the device,
- whether images are uploaded,
- whether model downloads occur,
- whether logs contain coupon text.

### Store Listing

Store copy should be conservative:

- describe coupon capture and organization,
- describe local/offline behavior only if true,
- avoid claiming perfect extraction,
- mention review/correction when extraction may be uncertain.

### Screenshots

Screenshots should match current UI and behavior.

Do not use historical mockups as store screenshots unless they match the app.

### Release Checklist

Before release:

```bash
git diff --check
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleRelease
```

Then test install on a real device when available.
