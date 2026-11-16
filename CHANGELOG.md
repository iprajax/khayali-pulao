# Changelog

## 0.7.0

- D3.js visual cards for every Kite read and calculator (bundled, offline WebView); interactive gallery on the website.
- Personas (Analyst, Financial planner, Research) with disclaimers; English-only, professional tone in chat.
- Kite: /chart with price history (5y fixed — Kite has no weekly interval), live price with last-close fallback, exchange-aware quotes, holdings preloaded for tools and @mentions.
- Context-curated pills (live holdings + last result + model); exact follow-up re-runs.
- Number integrity: user-typed % pinned to tool args; replies use the tool's exact summary.
- Response timing + step-by-step details accordion; native markdown (tables, lists, code).
- Bento home + navigation drawer + Settings screen; comparison rates updated to Nov 2026 with "Reset to current rates".

## 0.6.1

- Kite MCP: adapted to Zerodha's consolidated tools (`portfolio`, `orders`, `market`… with a `mode`); read-only now enforced per (tool, mode) — every place/modify/cancel/delete/create/logout mode is refused.
- Kite login returns via an HTTPS callback page (works even when Android freezes the app in the background).
- Follow-up pills re-run the last calculation exactly (e.g. "At 15% a year") instead of asking the model to guess.

## 0.6.0

- **Ask Khayali Pulao** — on-device AI chat (Gemma 4 E2B via LiteRT-LM) with tool calling over every calculator, settings and screens; Gemini-style motion; in the app and in the bubble sheet ("✨").
- One-time model download from Hugging Face: resumable, Wi-Fi option, ETA/speed, SHA-256 verified; delete anytime.
- Visual result cards (growth curve, FD bars, goal ring, tax stack, holdings) built from tool output; number guard removes any ₹ figure the model didn't get from a tool.
- Understands "10 lakh / 1.5 crore / 50k".
- /commands (`/holdings`, `/pnl`, `/fd`, `/goal`, `/tax`, `/clear`, `/help`…), @mentions of your Kite holdings, and Share-as-image for any answer.
- Kite connection now uses MCP OAuth 2.1 (PKCE, loopback redirect) — fixes the 401 from mcp.kite.trade.
- No demo data in the chat; it uses your real portfolio once Kite is connected.
- Optional **Connect Kite** (Zerodha's official Kite MCP, read-only, order tools blocked) with a "Kite connected" pill.
- Privacy: network only for the model download and the optional Kite connection; nothing is uploaded (tests + CI enforce it).

## 0.4.0

- Targets Android 16 (API 36) for Google Play; predictive back.
- In-app prominent disclosure before enabling the Accessibility service.
- Interactive web playground on the project site (same maths, tested against the app).

## 0.3.0 — first public release

- Floating bubble over Zerodha Kite; tap a holding, a watchlist stock or the Invested/Current card.
- **Move %** — ladder (0.5% steps to 5%, then 7, 10, 12, 15, 20…), slider, ± steps, target price, from avg or LTP.
- **vs FD** — your real CAGR and real (inflation-adjusted) gain vs savings, FD, PPF, gold, Nifty and inflation; all rates editable.
- **Spend it** — in-hand after LTCG (₹1.25L exemption) / STCG, STT & DP charges; FD income, months of expenses, 4% rule.
- **Goal**, **Years** (compounding), **Regret** ("agar tab liya hota").
- Pulao maths in desi units + your own dream unit. Tone: Serious / Light / Masala.
- Demo mode with fictional data.
- On-device only: zero permissions, no backup, guarded by tests and CI.
