# Privacy policy

**Short version: your portfolio, your questions and your voice never leave your phone.** The internet is used only for things you switch on yourself: the AI model download (Hugging Face), market history from NSE's public archive and the strategy knowledge bank from the project site (when you ask for them), a read-only Kite connection (Zerodha's MCP), and web lookups (DuckDuckGo search, Yahoo Finance prices) that send search words only.

Khayali Pulao is a calculator. It runs on your Android device ("on the edge"). There is no Khayali Pulao server: the developer receives nothing.

## What the app can access

| Access | Why | Limits |
|---|---|---|
| Android **Accessibility service** | To read the numbers Kite already shows on screen (quantity, average price, LTP, invested / current) when you tap a row, and to draw the floating bubble. | Acts only while Zerodha Kite (`com.zerodha.kite3`) is the app in front. It never types, never taps Buy/Sell and never places orders. The only action it ever sends to Kite is a scroll, and a click on Kite's own **navigation tabs** (bottom bar, Holdings/Positions, watchlist groups). |
| Internet: model download | To download the optional AI model **once** (Gemma 4 E2B, ~2.6 GB) from `huggingface.co` via Android's DownloadManager. | Nothing is uploaded. The file is checked against a pinned SHA-256 before use. |
| Internet: market history | When you ask (`/data`, or a command that needs it), to download NSE's public daily price and index-close files from `nsearchives.nseindia.com`, and the sector indices' member lists from NSE Indices (`www.niftyindices.com`), straight to the phone. | Nothing is uploaded; only the file for each day is requested. The data stays in a private database on the phone and is never redistributed. Delete it in Models & data. |
| Internet: knowledge bank | When you ask (`/screens`, or Models & data), to download a 289 KB file of community stock-screen rules from the project site (`iprajax.github.io`) via Android's DownloadManager. | Nothing is uploaded. The file is checked against a pinned SHA-256 before use. |
| Internet: web lookups (optional, off by default) | Only after you tap **Allow web access** in the chat: search `html.duckduckgo.com` for news, results and dates; read prices from `query1.finance.yahoo.com`; read the public market-news RSS feeds of Economic Times, Mint, Business Standard, BusinessLine and CNBC-TV18; read SEBI's What's New list (`www.sebi.gov.in`) and NSE's company filings (`/news`, `/sebi`, `/radar`, `/why`, `/pulse`). | Only the company name or your search words are sent. Rupee amounts, quantities, e-mail addresses and long digit runs are removed from every query in code, so holdings are never sent. No cookies; a generic user agent with no device model; the search view may load the results page and nothing else. These services see your IP address and the search words, as with any web search. Links open in your browser only when you tap them. Switch it off in Settings → AI chat. |
| Kite connection (optional) | Only if you tap **Connect Kite**: reads your holdings, positions, margins and orders via Zerodha's official Kite MCP (`mcp.kite.trade`) so the chat can use real numbers. | You log in on Zerodha's page; the app never sees your password. Read-only: every order/GTT tool is blocked in code. Your profile's email, phone and PAN are never passed to the model. Session expires daily; Disconnect clears it. |
| Microphone (optional, asked when you first tap 🎤) | Live voice: you speak, see your words as live captions, and hear the answer. | Your speech is turned into text by Android's **on-device** recognizer (`SpeechRecognizer.createOnDeviceSpeechRecognizer`, provided by Android System Intelligence in Private Compute Core). The app never receives or stores your audio and never uses a cloud recognizer; if on-device recognition isn't available, voice stays off. Answers are read aloud with an offline voice only. Turn the permission off any time in Settings → Apps → Khayali Pulao. |
| Notifications and a background job (optional, for price alerts) | Only if you set a price alert (`/alert`): Android asks once for notifications, and a scheduled job checks your alerts every few hours — fetching the evening's NSE file if it's new — then notifies you. | Your alerts are stored on the phone and checked there; the job only downloads NSE's public file. Remove alerts with `/alert clear` and the job stops. |
| AI chat | Answers your questions on-device with the app's own calculators. | Runs entirely on the phone (LiteRT-LM). Questions, answers and Kite's numbers are never sent to any AI service. |

## What the app stores

- Your own settings (FD rate, inflation, ladder steps, tone, bubble size, your custom dream unit…).
- The holding periods **you type in**, keyed by the stock's name, so "vs FD" and LTCG/STCG work.
- The **current chat thread** (your questions, the answers and their cards — which can include your holdings if Kite is connected), so it is still there when you come back. **New chat** deletes it.
- The chat's **memory notes**: short notes about your goals and preferences, and lessons from its own mistakes. Secrets, ID numbers and buy/sell instructions are refused. Read, delete or erase them under **Memory**, or switch memory off.
- If you connect Kite: the login token and your tickers (for @mentions). **Disconnect** deletes them.

The overlay on Kite itself stores nothing it reads from the screen.
All of the above lives in the app's private storage on your phone and is **excluded from Android cloud backup and phone-to-phone transfer** (`allowBackup="false"` + data-extraction rules).

## What the app does not do

No login, no account, no analytics, no crash reporting, no ads, no trackers. The only third-party code is Google's on-device runtime (LiteRT-LM), Gson and Kotlin coroutines; none of them send data.

## Deleting your data

Uninstall the app, or Android Settings → Apps → Khayali Pulao → Storage → Clear data.

## Contact

Open an issue on this repository.
