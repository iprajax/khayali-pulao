# Publishing to Google Play — checklist

Everything Play asks for is in this folder. Steps marked **you** need your Google account / a browser.

## 0. Before you start
- [ ] **you** — Google Play developer account (one-time US$25, identity verification) at https://play.google.com/console. Personal accounts created after Nov 2023 must run a **closed test with 12 testers for 14 days** before production access.
- [x] Target API 36 (Android 16) — required for new apps. Done (`app/build.gradle.kts`).
- [x] Prominent disclosure for the Accessibility API — `Disclosure.kt`, shown before enabling.
- [x] Zero permissions, no network, no SDKs — Data safety answer is "No data collected".

## 1. Create an upload key (once, keep it safe, never commit it)
```bash
keytool -genkeypair -v -keystore ~/keys/khayali-upload.jks -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000
```
Back it up somewhere safe (password manager). Play App Signing holds the real app-signing key; this is only the upload key.

## 2. Build the signed App Bundle (.aab)
```bash
export KP_KEYSTORE=~/keys/khayali-upload.jks KP_KEY_ALIAS=upload
read -s KP_KEYSTORE_PASSWORD; export KP_KEYSTORE_PASSWORD; export KP_KEY_PASSWORD=$KP_KEYSTORE_PASSWORD
./gradlew clean bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

## 3. Play Console
1. **Create app** → name `Khayali Pulao: Stock Daydream`, App, Free, accept declarations.
2. **Store listing** → paste from [`listing.md`](listing.md); upload `graphics/` and `screenshots/`.
3. **App content** → answers in [`data-safety.md`](data-safety.md); Accessibility API → [`accessibility-declaration.md`](accessibility-declaration.md) (needs the video).
4. **Testing → Closed testing** → upload the `.aab`, add 12+ testers (Google Group or emails), roll out, keep it running 14 days.
5. **Apply for production** → answer the questions → upload to Production → Review.

## 4. GitHub release (APK for everyone else)
```bash
./gradlew assembleRelease     # with the KP_* env vars set → app-release.apk (signed)
gh release create v0.4.0 app/build/outputs/apk/release/app-release.apk#khayali-pulao-0.4.0.apk \
  --title "Khayali Pulao 0.4.0" --notes-file CHANGELOG.md
```
Use the **same** upload key for GitHub APKs only if you're fine with that; otherwise users installing from GitHub can't later update from Play (signatures differ) — they'd uninstall first.

## Risks to know
- Google may reject non-accessibility use of the Accessibility API even with the disclosure. GitHub Releases remain the fallback.
- Trademark: the listing avoids "Zerodha"/"Kite" in the title and icon and states non-affiliation.
