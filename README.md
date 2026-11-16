<p align="center">
  <img src="docs/media/icon.png" width="112" alt="Khayali Pulao icon: a bowl of pulao whose steam becomes a rising chart">
</p>

<h1 align="center">Khayali Pulao</h1>

<p align="center">
  <b>A daydream calculator that floats on top of Zerodha Kite.</b><br>
  Tap a holding and see what it would be worth at +0.5%, +5%, +15%, 2× — whether you beat an FD — and what the profit actually buys, in hand after tax.
</p>

<p align="center">
  <a href="https://github.com/iprajax/khayali-pulao/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/iprajax/khayali-pulao/actions/workflows/ci.yml/badge.svg"></a>
  <img alt="Android 9+" src="https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Uploads: none" src="https://img.shields.io/badge/uploads-none-5282EB">
  <img alt="On-device only" src="https://img.shields.io/badge/data-never%20leaves%20your%20phone-67AD5B">
  <a href="LICENSE"><img alt="MIT" src="https://img.shields.io/badge/license-MIT-lightgrey"></a>
</p>

<p align="center">
  <a href="https://iprajax.github.io/khayali-pulao/">Website</a> ·
  <a href="https://github.com/iprajax/khayali-pulao/releases">Download APK</a> ·
  <a href="https://iprajax.github.io/khayali-pulao/#try">Try it in your browser</a> ·
  <a href="PRIVACY.md">Privacy</a> ·
  <a href="SECURITY.md">Security</a>
</p>

---

> **ख़याली पुलाव (khayali pulao)** — literally *"imaginary pulao"*: rice you cook only in your head.
> It's how Hindi says *daydreaming* or *building castles in the air*. Every investor does it — this app just does the maths properly.

<p align="center">
  <img src="docs/media/demo.gif" width="300" alt="Demo: tapping a fictional holding opens a sheet with Move %, vs FD, Spend it and Goal tabs">
  <br><sub>Recorded in the in-app <b>Demo</b> — every name and number is fictional.</sub>
</p>

## ✨ Ask Khayali Pulao — on-device AI chat

<table>
  <tr>
    <td><img src="docs/screens/chat-greeting.png" width="250" alt="Chat greeting"></td>
    <td><img src="docs/screens/chat-growth.png" width="250" alt="Chat answer with a compounding chart card"></td>
  </tr>
</table>

- **Gemma 4 E2B runs on your phone** (Google LiteRT-LM, GPU with CPU fallback). Downloaded once from Hugging Face (2.6 GB, SHA-256 verified, resumable, Wi-Fi option); then chat works offline.
- **Tool calling over every calculator** — the model never does maths itself. Each answer comes with a **visual result card** drawn from the tool's exact numbers: growth curve, FD comparison bars, goal ring, tax breakdown, before/after bars, holdings.
- **Number guard** — any ₹ figure the model writes that doesn't match a tool result is removed, so you never see an invented number.
- Understands **"10 lakh", "1.5 crore", "50k"**. Changes settings ("set my FD rate to 7%") and opens screens.
- In the app and in the bubble sheet over Kite (✨), where it already knows the stock you tapped.
- **/commands** — `/holdings` `/pnl` `/positions` `/margins` `/orders` `/mf` (instant cards from Kite, no AI needed), `/fd 3` `/goal 50 lakh` `/grow` `/move 15` `/tax` `/clear` `/help`.
- **@mentions** — type `@` to pick one of your Kite holdings: *"What if @INFY goes up 20%?"*
- **Share any answer** as an image (card + text, branded) to WhatsApp, X, Instagram… — with a warning before sharing real amounts.
- Gemini-style motion: flowing gradient greeting, thinking shimmer, word-by-word fade-in, glowing input, follow-up pills.

### 🔗 Connect Kite (optional, read-only)

Tap **Connect Kite** in the chat to let it read your real holdings, positions, margins and orders through **Zerodha's official [Kite MCP](https://github.com/zerodha/kite-mcp-server)** (`mcp.kite.trade`), using standard MCP OAuth 2.1 (PKCE + a one-time loopback redirect on the phone). You log in on Zerodha's own page in your browser — if you're already logged in to Kite in Chrome it's usually a one-tap "Authorize". The app **only allows read tools**: every order/GTT tool is blocked in code (and tested), on top of Zerodha's hosted server excluding them. Your Kite data comes from Zerodha to your phone and is used only by the on-device model.

## What's new in 0.7

- **Interactive D3 answers** — every result is a native card with an animated chart (holdings treemap + P&L, price history with scrubbing, compounding curve vs inflation, FD/PPF/gold/Nifty bars, goal ring, tax split, margins donut, orders timeline). Same renderer runs live on the [website](https://iprajax.github.io/khayali-pulao/#visuals). d3 is bundled; the chart WebView blocks all network.
- **Personas** — 📊 Analyst · 🧭 Financial planner · 🔎 Research. Same exact tools; different focus. Educational only — never buy/sell/hold calls; not a SEBI-registered adviser.
- **Kite MCP, all read methods** — holdings, positions, margins, profile, mutual funds, orders/trades, live price (falls back to last close when the market is shut), price history. Read-only enforced per *(tool, mode)*.
- **Context pills** — starters built from your live holdings (biggest, best, worst, concentration) and follow-ups from the last result; exact re-runs like "at 15% a year".
- **Exact numbers** — percentages you type are pinned to the tool call; when a calculator ran, the reply shows its exact summary and only number-free insight from the model.
- **Response details** — tap ⏱ for total time, time to first word, tokens/s, GPU/CPU and every tool step.
- **New home** — bento dashboard + left drawer; settings on their own screen; comparison rates refreshed (Nov 2026).

## What it does

| | |
|---|---|
| **Move %** | What if it moves +0.5% … +5% … +15% … 2×? A ladder (0.5% steps to 5%, then 7, 10, 12, 15, 20…), slider, ± steps that walk your own levels, and *target price → %*. From your average price or today's LTP. |
| **vs FD** | Did you actually beat a bank FD? Your real yearly return (CAGR) and inflation-adjusted gain vs savings, FD, PPF, gold, Nifty and inflation. Every rate is yours to set. |
| **Spend it** | Profit *in hand* after LTCG (₹1.25 L exemption) or STCG, STT and DP charges — then what it means: FD interest per month, months of your expenses covered, 4%-rule income, 10-year compounding. |
| **Goal** | "I want ₹2 Cr." How much it must rise, the LTP needed, and roughly how many years at 8–25% a year. |
| **Years** | Compounding: today's value after 1–30 years at the return you pick. |
| **Regret** | *Agar tab liya hota* — what if you'd bought cheaper? |
| **Whole portfolio** | Tap Kite's Invested / Current card to daydream everything at once. |
| **Pulao maths** | Gains in desi units — cutting chais ☕, Ballia → Sealdah tickets on Bhrigu Express 🚆, family trips to Ballia on Pawan Express 🚂, a bhains for the village 🐃, a plot in Ballia 🌾 or Jhumri Telaiya 🏡 — plus your own dream unit. |
| **Tone** | *Serious* (numbers only) · *Light* (calm one-liners, default) · *Masala* (FD wale uncle has opinions). |

## Screenshots

<table>
  <tr>
    <td><img src="docs/screens/move.png" width="220" alt="Move %"></td>
    <td><img src="docs/screens/vs-fd.png" width="220" alt="vs FD"></td>
    <td><img src="docs/screens/spend.png" width="220" alt="Spend it"></td>
    <td><img src="docs/screens/spend-list.png" width="220" alt="Spend it — what it buys"></td>
  </tr>
  <tr>
    <td align="center"><sub>Move %</sub></td><td align="center"><sub>vs FD</sub></td>
    <td align="center"><sub>Spend it — in hand</sub></td><td align="center"><sub>…and what it buys</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screens/goal.png" width="220" alt="Goal"></td>
    <td><img src="docs/screens/years.png" width="220" alt="Years"></td>
    <td><img src="docs/screens/watchlist-goal.png" width="220" alt="Watchlist stock"></td>
    <td><img src="docs/screens/settings.png" width="220" alt="Settings"></td>
  </tr>
  <tr>
    <td align="center"><sub>Goal</sub></td><td align="center"><sub>Years</sub></td>
    <td align="center"><sub>Watchlist stock</sub></td><td align="center"><sub>Everything is configurable</sub></td>
  </tr>
</table>

<sub>All screenshots come from the in-app Demo with fictional data. In real use the same sheet opens on top of Kite.</sub>

## 🔒 Nothing leaves your phone

- **No uploads.** Network is used for exactly two optional things: the one-time AI model download from Hugging Face (SHA-256 checked) and, only if you tap Connect Kite, Zerodha's official Kite MCP (read-only). Tests and CI fail if any other network code, address or permission appears.
- **On-device AI.** The chat runs Gemma 4 on your phone — your questions and Kite's numbers never leave it.
- **Reads, never trades.** It reads the numbers Kite already shows, only while Kite is open. While the bubble is on, your taps can't reach Kite's Buy/Sell; the only things passed through are scrolling and Kite's navigation tabs.
- **Keeps nothing of yours.** Only your settings and the hold periods you type — excluded from cloud backup and phone-to-phone transfer.
- **No login, analytics, ads or trackers.** No third-party SDKs at all.

Details: [PRIVACY.md](PRIVACY.md) · [SECURITY.md](SECURITY.md)

## Install

1. Download `khayali-pulao-<version>.apk` from [Releases](https://github.com/iprajax/khayali-pulao/releases) and install it (allow *Install unknown apps* for your browser/files app).
2. Open **Khayali Pulao → Turn it on** → Accessibility → Installed apps → *Khayali Pulao* → On.
   *Android 13+: if it's greyed out, go to Settings → Apps → Khayali Pulao → ⋮ → Allow restricted settings.*
3. Open Kite. Tap the 🍚 bubble (blue ring = on), then tap any holding, watchlist stock or the Invested card.

No Kite? Tap **Try the demo** on the home screen.

## How it works

```
 Kite (unchanged)                    Khayali Pulao (on-device)
 ───────────────                     ──────────────────────────
 Holdings row  ── accessibility ──►  KiteParser  ──►  Projector / Dreams / Costs  ──►  DreamSheet
 "INFRA, quantity 250, average       (regex on the       (pure Kotlin maths,            (Kite-styled
  price 1,200.00, … last traded       row's text)          unit-tested)                  bottom sheet)
  price 1,450.00"
```

Kite exposes each row to Android accessibility as plain text. Khayali Pulao parses that text, does the maths locally, and draws its own sheet as an accessibility overlay. No screenshots, no OCR, no network.

## Build from source

```bash
git clone https://github.com/iprajax/khayali-pulao.git && cd khayali-pulao
./gradlew testDebugUnitTest     # maths, parser, tax/charges and on-device-only guards
./gradlew assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and Android SDK 35. Release signing reads `KP_KEYSTORE`, `KP_KEYSTORE_PASSWORD`, `KP_KEY_ALIAS`, `KP_KEY_PASSWORD` from the environment — keys never live in the repo.

## FAQ

**Is this made by Zerodha?** No. It's an independent, open-source side project. It doesn't use any Zerodha API, login or servers — it only reads what the Kite app already shows on your own screen.

**Why an Accessibility service?** It's the only on-device way to read another app's on-screen text without screenshots or network access. The service is scoped to Kite, read-only, and can't place orders.

**Which AI model, and how big?** Gemma 4 E2B (Apache-2.0) via Google's LiteRT-LM. 2.6 GB download, ~0.8 GB RAM on GPU. You can delete it from the chat menu.

**Can the AI place trades?** No. It has no trading tools, and the Kite connection allows read-only calls only — both are enforced in code and covered by tests.

**Is it on the Play Store?** Not currently — Google Play restricts Accessibility API use to accessibility tools, so it's distributed as an APK on GitHub.

**Are the tax and charges exact?** No — they're estimates (12.5% LTCG above ₹1.25 L, 20% STCG, 0.1% STT, ₹15.34 DP charge, small exchange/SEBI/GST). Gold and Nifty returns are assumed long-run averages you can change.

## Disclaimer

Khayali Pulao is a **daydream**, not investment, tax or financial advice. Projections are hypothetical and markets can go down as well as up. "Zerodha" and "Kite" are trademarks of Zerodha Broking Ltd., used here only to describe compatibility; this project is not affiliated with or endorsed by Zerodha.

## Credits

App icon and illustrations generated with ChatGPT image generation, then edited. Colours follow Kite's dark theme so the overlay feels at home.

## License

[MIT](LICENSE)
