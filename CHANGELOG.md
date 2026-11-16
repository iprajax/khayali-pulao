# Changelog

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
