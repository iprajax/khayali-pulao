# Security

Khayali Pulao handles your portfolio and runs an AI on your phone (and, optionally, an Accessibility service on top of a trading app), so its safety properties matter more than usual. These are the guarantees the code is built around — please report anything that breaks them.

## Design guarantees

1. **No server, no upload.** There is no Khayali Pulao server. Permissions: `INTERNET`; `RECORD_AUDIO` for live voice (asked only when you tap 🎤); `POST_NOTIFICATIONS` for price alerts (asked when you set the first one). The app only ever fetches — it never sends your holdings, amounts, questions, chat or memory anywhere. Each use is switched on by you:

   | Host | Why | When |
   |---|---|---|
   | `huggingface.co` | The on-device AI model (pinned file + SHA-256; a mismatching file is deleted, never loaded) | When you download it |
   | `nsearchives.nseindia.com` | NSE's daily price and index-close files, fetched by the phone itself | `/data`, or the refresh schedule once installed |
   | `www.niftyindices.com` | NSE Indices' published member lists of the sector indices | With the market history, refreshed monthly |
   | `iprajax.github.io` | The strategy knowledge bank (pinned SHA-256) | `/screens` or Models & data |
   | `mcp.kite.trade` | Zerodha's Kite MCP, read-only | Only after Connect Kite |
   | `html.duckduckgo.com`, `query1.finance.yahoo.com`, NSE's website, `www.moneycontrol.com`, `www.investorgain.com`, `api.worldbank.org`, `api.mfapi.in` | Web lookups: search, prices, calendar, IPOs, macro and fund data — topic words or a symbol only; amounts, quantities and IDs are stripped first | Only with Web on |
   | `economictimes.indiatimes.com`, `www.livemint.com`, `www.business-standard.com`, `www.thehindubusinessline.com`, `www.cnbctv18.com`, `www.sebi.gov.in` | Public market-news RSS feeds and SEBI's What's New list (`/news`, `/sebi`, `/pulse`) — only the feed address is requested | Only with Web on |

   `OnDeviceOnlyTest` fails the build if another permission is added, if backup is re-enabled, if networking code appears outside `KiteMcpClient.kt` and `WebClient.kt` (plus Android's DownloadManager in `ModelManager.kt` and `FilePack.kt`), or if any URL is hard-coded instead of built from a named host. Terminal features can only read hosts on `Hosts.ALLOWED`. CI checks the built APK requests only `INTERNET`.
2. **Voice stays on the phone.** The microphone permission is asked only when you tap 🎤. Speech is recognised by Android's on-device recognizer only (`createOnDeviceSpeechRecognizer`); the cloud-capable recognizer and the recognition intent are never used, there is no fallback, and network errors are not retried another way. Answers are spoken with an offline voice only. `OnDeviceOnlyTest.voiceNeverLeavesThePhone` enforces this, and CI checks the APK requests only `INTERNET`, `RECORD_AUDIO` and `POST_NOTIFICATIONS`. The only background work is the price-alert job (JobScheduler), which exists only while you have alerts and only fetches NSE's public daily file.
3. **No analytics, no audit trail.** No analytics, crash reporting, ads or third-party SDKs; no account or login. Nothing is logged anywhere but the phone.
4. **Never trades.** While the bubble is on, a full-screen layer swallows touches so they cannot reach Kite's Buy/Sell buttons. The only actions ever sent to Kite are `ACTION_SCROLL_*` and `ACTION_CLICK` on nodes whose description matches Kite's navigation-tab pattern (see `KiteParser.isNavigationTab`, covered by tests that assert Buy/Sell/Exit never match). Overlays are shown only while `com.zerodha.kite3` is the top application window.
5. **Kite MCP is read-only by construction.** `KiteData.READ_ONLY` is the only list of tools the client will send; `place_order`, `modify_order`, `cancel_order` and all GTT writes are rejected before any request (`KiteDataTest`).
6. **AI tools are sandboxed.** The on-device model can only call the app's own tools — calculators, read-only Kite reads, opt-in web lookups and the app's commands. There is no tool that can tap Kite, buy, sell or place orders — a test asserts it. Figures the model writes that aren't in a tool result are removed.
7. **What is stored, and where.** Settings, the chat thread, memory notes, your NSE history and the knowledge bank live in private app storage, excluded from cloud backup and device transfer. Delete any of it in ⋮ → Models & data, Memory → Erase, or New chat.
8. **Every reply explains itself.** Each reply's *How this was made* panel shows its data sources, method and a privacy line saying what, if anything, was sent.
9. **Release builds** are signed, minified and strip debug logging.

## Reporting a vulnerability

Please **don't** open a public issue for security problems. Use GitHub's private vulnerability reporting
(*Security → Report a vulnerability*) on this repository. Include steps to reproduce, but **never include screenshots or logs containing your holdings, balances or account details**.

## Supported versions

Only the latest release receives fixes.
