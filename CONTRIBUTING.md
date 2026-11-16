# Contributing

Thanks for wanting to cook some khayali pulao! 🍚

## Build

Requirements: JDK 17 and the Android SDK (platform 35). Point `local.properties` at your SDK (`sdk.dir=…`) or set `ANDROID_HOME`.

```bash
./gradlew testDebugUnitTest      # all unit tests
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Ground rules

- **Never add a permission or any network code.** `OnDeviceOnlyTest` and CI will fail — on purpose.
- **Never make the overlay click anything in Kite** beyond scrolling and the navigation tabs.
- **Keep the maths pure and tested.** Calculations live in `Projection.kt`, `Dreams.kt` and `Stock.kt` (no Android imports) — add a JUnit test for every change.
- **No personal data in the repo.** Use fictional names and numbers in tests, issues and screenshots. For visuals, use the in-app **Demo** screen — never screenshot a real Kite account.
- **Not investment advice.** Copy should inform, not recommend. The default tone is *Light*; *Serious* must stay plain.

## Project layout

| File | What |
|---|---|
| `KhayaliService.kt` | Accessibility service: bubble, tap layer, reading Kite's rows, tab pass-through |
| `DreamSheet.kt` | The bottom sheet: Move %, vs FD, Spend it, Goal, Years, Regret |
| `Stock.kt` | Kite row parser + Indian number formatting |
| `Projection.kt`, `Dreams.kt` | Pure maths: moves, goals, compounding, FD/benchmarks, tax & charges, pulao maths |
| `Prefs.kt`, `SettingsPanel.kt` | Every user-controllable value |
| `DemoActivity.kt` | Fictional portfolio for trying the app without Kite |
| `UiKit.kt` | Kite-like palette and view helpers |
