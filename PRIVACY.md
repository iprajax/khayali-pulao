# Privacy policy

**Short version: nothing leaves your phone.** Optional network use: the AI model download (Hugging Face) and, if you choose, a read-only Kite connection (Zerodha's MCP).

Khayali Pulao is a calculator. It runs entirely on your Android device ("on the edge"), and it is built so that it *cannot* send your data anywhere.

## What the app can access

| Access | Why | Limits |
|---|---|---|
| Android **Accessibility service** | To read the numbers Kite already shows on screen (quantity, average price, LTP, invested / current) when you tap a row, and to draw the floating bubble. | Acts only while Zerodha Kite (`com.zerodha.kite3`) is the app in front. It never types, never taps Buy/Sell and never places orders. The only action it ever sends to Kite is a scroll, and a click on Kite's own **navigation tabs** (bottom bar, Holdings/Positions, watchlist groups). |
| Internet | To download the optional AI model **once** (Gemma 4 E2B, ~2.6 GB) from `huggingface.co` via Android's DownloadManager. | That is the only network use. Nothing is ever uploaded. The file is checked against a pinned SHA-256 before use. A unit test fails if any other network code or address appears. |
| Kite connection (optional) | Only if you tap **Connect Kite**: reads your holdings, positions, margins and orders via Zerodha's official Kite MCP (`mcp.kite.trade`) so the chat can use real numbers. | You log in on Zerodha's page; the app never sees your password. Read-only: every order/GTT tool is blocked in code. Your profile's email, phone and PAN are never passed to the model. Session expires daily; Disconnect clears it. |
| AI chat | Answers your questions on-device with the app's own calculators. | Runs entirely on the phone (LiteRT-LM). Questions, answers and Kite's numbers are never sent anywhere or saved. |

## What the app stores

- Your own settings (FD rate, inflation, ladder steps, tone, bubble size, your custom dream unit…).
- The holding periods **you type in**, keyed by the stock's name, so "vs FD" and LTCG/STCG work.

It does **not** store your holdings, balances, account ID or anything else read from Kite.
Stored settings live in the app's private storage on your phone and are **excluded from Android cloud backup and phone-to-phone transfer** (`allowBackup="false"` + data-extraction rules).

## What the app does not do

No login, no account, no analytics, no crash reporting, no ads, no trackers. The only third-party code is Google's on-device runtime (LiteRT-LM), Gson and Kotlin coroutines; none of them send data.

## Deleting your data

Uninstall the app, or Android Settings → Apps → Khayali Pulao → Storage → Clear data.

## Contact

Open an issue on this repository.
