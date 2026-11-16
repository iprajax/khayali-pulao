# Security

Khayali Pulao uses an Android **Accessibility service** on top of a trading app, so its safety properties matter more than usual. These are the guarantees the code is built around — please report anything that breaks them.

## Design guarantees

1. **No network.** Zero permissions requested; `INTERNET` / `ACCESS_NETWORK_STATE` are explicitly removed in the manifest. A unit test (`OnDeviceOnlyTest`) fails if a permission is added, if backup is re-enabled, or if networking APIs (`java.net`, OkHttp, Retrofit, Ktor, sockets, WebView) appear in the source. CI additionally checks the built APK has zero permissions.
2. **Never trades.** While the bubble is on, a full-screen layer swallows touches so they cannot reach Kite's Buy/Sell buttons. The only actions ever sent to Kite are `ACTION_SCROLL_*` and `ACTION_CLICK` on nodes whose description matches Kite's navigation-tab pattern (see `KiteParser.isNavigationTab`, covered by tests that assert Buy/Sell/Exit never match).
3. **Kite only.** Overlays are shown only while `com.zerodha.kite3` is the top application window.
4. **No data at rest beyond settings.** No holdings are persisted; preferences are excluded from cloud backup and device transfer.
5. **Release builds** are minified and strip debug logging.

## Reporting a vulnerability

Please **don't** open a public issue for security problems. Use GitHub's private vulnerability reporting
(*Security → Report a vulnerability*) on this repository. Include steps to reproduce, but **never include screenshots or logs containing your holdings, balances or account details**.

## Supported versions

Only the latest release receives fixes.
