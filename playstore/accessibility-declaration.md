# AccessibilityService declaration — Play Console answers

> Play Console → **Policy and programs → App content → Sensitive permissions and APIs → Accessibility API**.
> Khayali Pulao is **not** an accessibility tool, so `isAccessibilityTool` is **not** set and the app shows an in-app prominent disclosure (`Disclosure.kt`) before sending the user to Android's Accessibility settings.

## Is your app an accessibility tool?
**No.**

## Describe the core functionality that uses the AccessibilityService API
```
Khayali Pulao is an on-device projection calculator for stock investors. When the user turns on the floating bubble and taps a row in their broker app (Zerodha Kite), the AccessibilityService reads that row's on-screen text (stock name, quantity, average price, last traded price, or the invested/current totals) and shows a sheet with what-if projections: value at price moves, comparison with a fixed deposit, goals, compounding and estimated in-hand amount after tax.

The service:
- runs only while the Kite app (com.zerodha.kite3) is the foreground app;
- draws a floating bubble and results sheet as an accessibility overlay;
- performs only two actions in Kite: scrolling the list when the user swipes, and opening Kite's own navigation tabs when the user taps them;
- never taps Buy/Sell, never enters text and never places orders;
- does not collect, store, log or transmit any data read from the screen. The app's only network use is a one-time download of an on-device AI model; nothing is uploaded.
```

## Why can't this be done another way?
```
Android provides no API for one app to read another app's on-screen values. A broker API would require the user's trading credentials and a network connection, which this app deliberately avoids. Screenshots/OCR would need screen-capture permission and be less private. The AccessibilityService lets the app read only the specific text the user taps, on device, with no network.
```

## Prominent disclosure
- **Where:** Home screen → "Turn it on" → dialog *"Khayali Pulao uses the Accessibility Service"* (source: `app/src/main/kotlin/com/khayali/pulao/Disclosure.kt`).
- **Shown:** before the user is taken to Accessibility settings, every time the service is off.
- **Consent:** "Agree & open settings" (consent) / "No thanks" (decline — app stays usable via Demo).
- **Content:** what is read, why, what it does on screen, that nothing is collected/stored/shared, how to turn it off.

## Video (required) — record this, upload unlisted to YouTube, paste the link
Record on the phone with `adb shell screenrecord` or the Android screen recorder, **using the Demo and a Kite account with no real holdings visible** (or blur them):
1. Fresh install → open Khayali Pulao (service off) → tap **Turn it on** → show the full disclosure (scroll slowly).
2. Tap **No thanks** → show the app still works (Try the demo).
3. Tap **Turn it on** again → **Agree & open settings** → Accessibility → Installed apps → Khayali Pulao → On.
4. Open Kite → tap the bubble → tap a row → the sheet appears. Tap a tab. Turn the bubble off.
5. Show Settings → Apps → Khayali Pulao → Permissions: **only "Internet" (for the model download)**.

⚠️ Do **not** show a real portfolio in the video. Use an account view with fictional/blurred values, or record steps 1–3 and 5 on device and step 4 with the in-app Demo plus the bubble.

## Expect
Extended review (can take days to weeks). Google may still reject a non-accessibility use; if so, the app stays available on GitHub Releases.
