# Changelog

## 0.8.0

**Chat lifecycle**
- The chat is now one explicit state machine (`ChatFlow`): typing, pills, /commands, @mentions, retries and reconnects all go through the same transitions. Unit-tested.
- Fixed: `/holdings` failing after the phone had been asleep. Kite logins expire daily and pooled connections die during sleep; the app now detects an expired login (amber "reconnect" pill), retries dropped connections, refreshes the token when the server allows it, keeps the command you typed and runs it as soon as you are back.
- Fixed: the model being unloaded (idle or low memory) left a dead conversation. Conversations are now rebuilt from the saved thread; the engine is never unloaded under a running answer; a turn that fails before its first word is retried once.
- /fd, /goal, /grow, /move and /tax are run by the app itself — exact, instant, and they work before the model is downloaded.
- The thread (with its cards) is saved on the phone and restored when you return. New chat clears it.
- A malformed tool call from the model is retried with a corrective note instead of failing; "my biggest / best / worst holding" is resolved by the app.

**Market calendar, IPOs and grey-market premium (opt-in web)**
- `/calendar` — the next 7 days on NSE (or `today`, `tomorrow`, `month`, a date): results, dividends, bonus issues, stock splits, buybacks, rights issues, AGMs, IPO open and close dates, and market holidays. Your own holdings are starred and listed first (matched on the phone, never sent). Chart: events per day by kind, then the agenda day by day.
- `/events INFY` — one company's dated events; `/results tomorrow` — who reports that day (NSE + Moneycontrol's results calendar).
- `/ipo` — open and upcoming IPOs with bid windows, price bands, lot, allotment and listing dates, plus recent listings.
- `/gmp NAME` — an IPO's grey-market premium, its day-by-day trend, the range it has traded in, and the listing price it implies, with the issue's life cycle (upcoming → bidding → closed → allotment → listing). Always labelled as an unofficial, unregulated indication.

**Market terminal — 72 commands**
- Market: `/report` (indices tape, key figures, breadth, sectors, Nifty gainers and losers with sparklines, FII/DII flows, your stocks, and a written read-out), `/preopen`, `/sectormovers`, `/heatmap`, `/indices`, `/sectors`, `/movers`, `/breadth`, `/active`, `/status`, `/highs`, `/bands`, `/volume`, `/fiidii`, `/deals`, `/oispurts`, `/etfs`, `/sgb`.
- Stock: `/quote`, `/flow` (results Sankey), `/shareholding` (streamgraph), `/mindmap`, `/scorecard` (radar), `/peers`, `/history`, `/insider`, `/announcements`, `/filings`, and technicals `/ta` `/rsi` `/macd` `/drawdown` `/returns` `/seasonality` `/compare` `/levels` `/volatility`.
- Portfolio: `/watch` (stocks you looked at, with sparklines), `/xray` (sunburst), `/pnlflow` (Sankey), `/risk`, `/correlation`, `/riskreturn`, `/stress`, `/income`, `/rebalance`, `/foliomap`.
- Derivatives: `/optionchain`, `/pcr`, `/maxpain`, `/ivsmile`, `/straddle`, `/oichange`, `/expiries`.
- Global & macro: `/global`, `/fx`, `/commodities`, `/gold`, `/crypto`, `/vix`, `/inflation`, `/gdp`, `/yields`.
- Funds & planning: `/fund`, `/fundcompare`, `/sipreal` (an SIP on the fund's real NAV history, with XIRR), `/sip`, `/stepup`, `/swp`, `/emi`, `/retire`, `/lumpsum`.
- Native, colour-coded blocks for readability on a phone: ticker tape, sparkline mover rows with change pills, sector bars, stat tiles and breadth bars; cards use the full chat width, and the summary sits inside the card instead of being repeated below it.
- A plug-in system for market features: each turns public, no-login data into one chart card; the model can call any of them through a single `terminal` tool, and short plain questions ("show me the heatmap") run them directly.
- `/heatmap [index]` — every stock in an index as a tile sized by traded value and coloured by today's move; your holdings marked.
- `/flow SYMBOL` — the latest quarter as a money-flow Sankey (income → staff, other expenses, depreciation, interest → tax → net profit) read from the company's XBRL results filing, with a 5-quarter income and profit trend.

**Chat**
- `/memory` shows what the chat remembers inside the conversation; add notes from the Memory screen too.
- `/compact`, and automatic compaction when the model's context is nearly full: the thread is folded into a short summary built from the calculators' exact figures, so long chats stay fast.
- Follow-up pills are written by the model after reflecting on the conversation (go deeper / test the opposite case / connect to your goal) and include your remembered goal where relevant.
- Layout fits side navigation bars and cut-outs in landscape.

**Web, with sources (opt-in)**
- One-time, in-chat permission; the switch is in Settings. Off by default.
- `/price`, `/chart` without Kite, `/events`, `/results`, `/news`, `/web`, and a `web` tool for the model: DuckDuckGo search and a public quote feed (Yahoo Finance, may be delayed).
- Answers cite numbered, tappable sources. A sentence whose figures are not in the sources is removed; if nothing survives, the snippets are quoted instead.
- Only topic words leave the phone: amounts, quantities, e-mail addresses and long digit runs are stripped from every query in code. No cookies, generic user agent, nothing but the results page may load.

**Memory (on the phone only)**
- A playbook of small notes in the style of agentic context engineering: generate (the best notes go into context), reflect (rules plus a model pass on a throw-away copy of the conversation), curate (merge near-duplicates, refuse secrets/IDs/buy-sell calls, drop the weakest at the cap). Regenerating an answer counts against the notes it used.
- `/remember`, `/memory` (read, delete, erase, switch off), `/forget`.

**Charts**
- Every chart names both axes and prints their values. New: candlesticks with volume, what-if curve, goal paths, tax waterfall, allocation donut, company-events calendar.

**Fixes from a full audit**
- ₹ quick-amount chips and edits of grouped numbers ("50,000") were ignored; a live price tick closed Settings; Regret / vs FD / Goal showed stale prices while "LIVE" blinked; crash on Android 9–10 (insets API); Back did not close the drawer on Android 16; leaked animator and chat session; double model verification; "Doubled" quip on Whole value; first option never shown as selected; "Already there" for a ₹0 goal; numbered lists rendering "1. 1. 1."; same symbol on NSE and BSE treated as one stock; overlays not rebuilt on fold/rotate; download-manager and callback-URI crashes; `100 L` instead of `1 Cr`; `−0`.
- CI now runs Android lint.

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
