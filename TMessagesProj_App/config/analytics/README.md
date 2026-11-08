# Easygram Firebase Analytics

The `afatDebug`, `afatRelease`, and `bundleAfatRelease` variants use this manifest as a variant overlay. It removes the shared Telegram manifest's Analytics deactivation flag and enables the measurement receiver and service. The Analytics SDK dependency lives in `TMessagesProj_App`; its version comes from the existing Firebase BoM exported by `TMessagesProj`.

Automatic collection includes first opens, sessions, engagement, app updates, and Activity screen views. Most Telegram screens share LaunchActivity, so this does not provide separate reports for individual chat/settings fragments or custom user actions. Advertising ID collection remains disabled. No custom events, Telegram user IDs, phone numbers, chat names, or message contents are supplied to Analytics by this integration.

The release configuration targets Firebase project `easygram-328cc`, Android package `io.github.webarmour.easygram`. Debug uses the separate `.beta` application registration. Other application modules and the standalone `.web` variant retain their existing configuration.

## Verify collection

1. Enable/link Google Analytics in the Firebase project's settings if it is not already enabled. This repository cannot establish the console-side link.
2. Build and install the updated APK, then launch the app.
3. For a test device, run `adb shell setprop debug.firebase.analytics.app io.github.webarmour.easygram`, restart the app, and inspect Firebase Analytics DebugView.
4. Disable debug mode afterward with `adb shell setprop debug.firebase.analytics.app .none.`. Use a developer traffic data filter in Analytics if test events should be excluded from reports and exports; see [Firebase DebugView documentation](https://firebase.google.com/docs/analytics/debugview).

Use Analytics reports for active users; this integration does not create Firebase Authentication users. Historical use before installing an Analytics-enabled build is not backfilled.
