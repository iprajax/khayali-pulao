# Security

Khayali Pulao uses an Android **Accessibility service** on top of a trading app, so its safety properties matter more than usual. These are the guarantees the code is built around — please report anything that breaks them.

## Design guarantees

1. **No upload, one download.** The only permission is `INTERNET`, used solely by Android's DownloadManager to fetch the AI model from `huggingface.co` (pinned file + SHA-256; a mismatching file is deleted, never loaded). `OnDeviceOnlyTest` fails if any other permission is added, if backup is re-enabled, if any networking API (`java.net`, OkHttp, Retrofit, Ktor, sockets, WebView) appears, or if any other URL is hard-coded. CI checks the built APK requests only `INTERNET`.
2. **Never trades.** While the bubble is on, a full-screen layer swallows touches so they cannot reach Kite's Buy/Sell buttons. The only actions ever sent to Kite are `ACTION_SCROLL_*` and `ACTION_CLICK` on nodes whose description matches Kite's navigation-tab pattern (see `KiteParser.isNavigationTab`, covered by tests that assert Buy/Sell/Exit never match).
3. **Kite only.** Overlays are shown only while `com.zerodha.kite3` is the top application window.
4. **No data at rest beyond settings.** No holdings are persisted; preferences are excluded from cloud backup and device transfer.
5. **Release builds** are minified and strip debug logging.
7. **Kite MCP is read-only by construction.** `KiteData.READ_ONLY` is the only list of tools the client will send; `place_order`, `modify_order`, `cancel_order` and all GTT writes are rejected before any request (`KiteDataTest`). Network code is allowed only in `KiteMcpClient.kt`, only to `mcp.kite.trade`.
6. **AI tools are sandboxed.** The on-device model can only call the tools in `AppTools.kt` (calculators, validated settings changes, opening app screens). There is no tool that can tap Kite, buy, sell or place orders — a test asserts it.

## Reporting a vulnerability

Please **don't** open a public issue for security problems. Use GitHub's private vulnerability reporting
(*Security → Report a vulnerability*) on this repository. Include steps to reproduce, but **never include screenshots or logs containing your holdings, balances or account details**.

## Supported versions

Only the latest release receives fixes.
