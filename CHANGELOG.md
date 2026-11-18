# Changelog

## 0.10.0

Built from a survey of 19 finance apps and AI agents (Perplexity Finance, Robinhood Cortex, Public, Bloomberg, Fiscal.ai, Koyfin, TradingView, Seeking Alpha, Tickertape, INDmoney, Groww, Zerodha, Moneycontrol Pro, ET Markets and others), taking the features Indian investors use most that can run privately on a phone.

- **Talk to it — live voice, on the phone.** Tap 🎤: speak, watch your words appear as live captions, hear the answer read back with each word lit as it's spoken; tap the orb to interrupt, ✕ to end. Speech is recognised by Android's on-device recognizer only (no cloud fallback — if it isn't available, voice stays off), answers use an offline voice, and your words go through the same chat, so commands, plans and cards all work by voice. The microphone is asked for only when you first tap 🎤.
- **`/why SYMBOL` — why a stock moved.** The day's move split into market (beta × Nifty 50), sector (its NSE sector index vs the Nifty) and the stock's own part, then the evidence ranked: volume against its usual, the move against its own volatility, 52-week levels, streaks, and — with web on — its filings and the news that names it. On every ticker's wheel, and the first step of "what's happening with X".
- **`/news` — market news, folded and linked.** Five outlets (Economic Times, Mint, Business Standard, BusinessLine, CNBC-TV18) read as public RSS, folded into one story per event, tone-tagged with a finance word list, and linked to tickers through NSE's full list of 2,632 companies plus common short forms (RIL, SBI, L&T…). Headlines that read as buy/sell tips are never relayed. `/news SYMBOL` shows the stories that name a stock.
- **`/radar` — filings radar.** Two days of NSE company filings (1,200+ on a typical day pair) sorted into results, orders and deals, ratings, management changes, pledge and takeover, insider trading, legal and regulatory, and more — your stocks first — plus takeover (SAST) and credit-rating actions. Tap one to open the document.
- **`/sebi` — the regulator's latest.** SEBI's What's New list: circulars, orders, press releases and consultation papers, by type.
- **`/mood` — market mood index.** A 0–100 fear-greed gauge from six parts of your own NSE data (breadth, trend, momentum, highs vs lows, calm, advancers), each shown, with 60 days of history.
- **`/pulse` — the market on one screen.** Mood gauge, Nifty and breadth, every sector today, hidden patterns, and (with web on) the headlines that matter and SEBI's latest. "Why did the market move?" plans now start here.
- **`/find` — a screener in plain words.** "above 200 day near 52 week high volume spike", "oversold above 200 day", "up 30% in a year low volatility": the card shows the checks it understood and the matches, over every liquid stock in your history (about 30 ms).
- **`/alert` — price alerts that work with the app closed.** `/alert INFY above 1800`, `/alert TCS below 3000`, `/alert SBIN moves 5%`. Checked on the phone against each close — while the chat is open, and by an Android background job after the evening's NSE file — with a notification (asked for when you set your first alert).
- **`/redflags` — surveillance lists.** NSE's ASM (long and short term), GSM and ESM lists checked against your holdings and watched stocks, with what each means.
- **`/healthscore` — portfolio health, 0–100.** Diversification, the largest holding and sector, beta, volatility, distance from highs, the year against the Nifty and any red flags — each part shown on a gauge.
- **`/harvest` — tax-loss harvesting.** Holdings in loss, how much they could offset of gains you've booked, the tax that could save (20% short-term, 12.5% long-term above ₹1.25 lakh) and the days left to 31 March.
- **`/positioning` — who's long and short.** FII, DII, Pro and Client positions in index futures and options from NSE's daily participant file, over recent weeks.
- **`/concall SYMBOL` — earnings calls.** A company's call transcripts, audio and analyst-meet filings with NSE, one tap from the document.
- **Live voice speaks AI answers as they're written**, a sentence at a time, so the first words come quickly; sentences with figures wait for the final, checked answer.
- A native gauge block for cards; the website replay gains a "Talk to it" scene.

## 0.9.4

- **Sector member lists retry until they arrive** — a failed fetch was recorded as done and wouldn't retry for 30 days, leaving sector cards without gainers and losers. Now it retries whenever none are stored. Checked on a phone: 18 of 18 lists, and `/sector PSU Bank 1w` shows its members' breadth, gainers and the wheel on each.
- `/sector` with no sector named shows every sector instead of asking.
- The wheel's centre fits long symbols (BANKINDIA, BAJAJFINSV) instead of cutting them off.

## 0.9.3

- **Fixed: one tap could delete your market history.** After `/data`, a suggestion labelled "📦 Models & data" actually ran `/data delete`, with no confirmation. Deleting is now never one tap: delete actions are no longer offered as suggestions, `/data delete` asks first (with the size) and only "Yes, delete it" deletes, and every data action is labelled for what it does. Tested.

## 0.9.2

- **Every ticker opens the wheel, in any reply** — calendar, IPOs, holdings, web results and every terminal card, plus stock labels inside charts. Cards are wired as they appear, so new cards get it automatically; a label must be a stock the phone knows, so headings never trigger it.
- **A more compact wheel** — a smaller ring, tighter icons and labels, the same quiet style everywhere (the website replay matches).

## 0.9.1

- **One-tap setup** — `/setup` (also the first starter in an empty chat, and ⋮ → Setup): pick the AI model, three years of market history, the knowledge bank, web lookups and Kite, then **Set up everything** starts it all at once. One life cycle — not started → running → needs you → done (or failed, with retry) — and each step's status is read from the real download states, so it picks up where it was after a restart.
- **Every ticker is a wheel** — tap or long-press any stock and its actions open on a ring around your finger: Trend, Chart, News, Events, Quote, Deep dive, Ask. Quiet by design: the chat blurs behind a frosted disc, thin line icons, a glass centre, a calm fade and a light haptic tick.
- **Sector analysis from official NSE indices** — `/sectorboard [1d|1w|1m|3m|6m|ytd|1y|3y|5y|date range]`: all 18 NSE sector indices with 1D to 5Y returns, CAGR and P/E, sorted by the period you pick; tap a sector for `/sector NAME [period]`: every period and CAGR, P/E · P/B · dividend yield, a year against the Nifty 50, its members' breadth, gainers and losers (each ticker tappable). Index closes come from NSE's daily index file and members from NSE Indices' published lists, fetched by the phone with the market history (index history is backfilled once for days already downloaded). The brief now shows the week's leading and lagging sectors, and "why did the market move" plans compare every sector.
- Pills for every command read in plain words (e.g. "Bulk and block deals · GROWW").

## 0.9.0

**Private by design, and it shows its working**
- Every reply has a **How this was made** panel: why you're seeing it, which data and method made each part (sources, history span, as-of date, parameters, limits), how the written text was made, a privacy line saying what — if anything — was sent, and the timing.
- **Refresh schedule**, timed by the phone's clock in Indian time and run while the chat is open: today's NSE closing prices after 7 pm on trading days; a Saturday gap-fill and file check; a monthly refresh of the stock universe; a quarterly database tidy-up; a yearly roll of the history window. See it, with last and next runs and Run now, in ⋮ → Models & data.
- Faster: the chat opens without database work on the main thread, and daily updates extend the analysis snapshot with just the new days.
- Fixed: the chat could crash on open; a test now guards the cause (initialisation order).

**A harness that plans, not just looks up**
- Broad questions become a visible plan of 3-5 steps that run in order, each with its own card, ticking ✓ as they finish: your day ("what should I do today?"), one stock ("should I buy INFY?", "what's happening with ZEEL"), your portfolio, why the market moved, which strategy to follow, whether to apply for an IPO. Later steps use what earlier ones found — news on the stock the scan flagged, picks of the rule that tested best. A wrap-up says what it shows and what to do next; steps that need Kite, web or a download are skipped with the fix. Buy/sell questions carry a plain note that the app doesn't make calls.
- Every suggested next step is tappable: commands mentioned in answers, notes and takeaways (`/screen momentum`, `/data 3y`), the brief's Next steps, strategy rows (open today's picks), the playbook list, and the wrap-up's action links. Gemma is asked to write its own suggestions the same way.
- Tickers are tappable everywhere — movers, rows, bars, takeaways and answer text: trend & risk, chart, news, events, quote, a deep-dive plan, or ask about it.
- Guided parameters: tap any command in the `/` list; if it needs nothing it runs, otherwise the chat asks for each parameter in turn (stock, period, amount, strategy…) with choices to tap, and runs when the last is picked. Optional parameters offer "Run now".
- An empty chat opens with task-driven starters, personalised with your own stocks: plan my day, what's happening with your top holding, review my portfolio (or connect Kite), find a strategy, this week's events, quality companies with low debt.
- Pills read as actions ("🩺 Check BLISSGVS's trend") and run the exact command.

**Personal advisor on your own market data**
- `/data` — models and data on this phone in one place, with a live progress card (bar, days done, time left, Stop) while it downloads, each with an explicit life cycle (not installed → downloading → verifying → building → ready → update available → updating; paused; failed). Download 1, 3 or 5 years of NSE daily history (about 25 MB a year), update, stop or delete.
- The history comes straight from NSE's public archive (nsearchives.nseindia.com) to your phone, in both of NSE's file formats (the original one to 5 Jul 2024 and the newer UDiFF one since 8 Jul 2024). It is never redistributed: every user's phone fetches its own copy. Prices are chained from NSE's adjusted previous close, so splits and bonuses don't look like crashes. ETFs and fund units are left out.
- `/brief` — the action brief, also triggered by "what should I do today?" or "how can I make profit?": market regime and breadth, the week's biggest moves, hidden patterns, your holdings' trend and concentration (if Kite is connected), what each strategy flags now with its backtest, and a list of things worth reviewing. It says what the data and each rule show — never buy or sell.
- `/regime`, `/anomalies` (volume spikes, outsized moves, streaks, breakouts — each against the stock's own history), `/strategies` (14 rules backtested side by side from one start date: 12-1 and 6-month momentum, near 52-week high, low volatility, trend following, pullback in an uptrend, breakout on volume, steady compounders, low beta, accumulation, RSI pullback, no lottery tickets, largest traded, weekly reversal), `/screen <rule>`, `/health SYMBOL` (or your holdings), `/learn <topic>` (the strategy playbook, searched by keywords on the phone).
- The on-device model is told to run `/brief` and the strategy commands for "what to do" questions, and remembered notes (goal, horizon, risk) shape which rules the brief points to.
- Errors now come with buttons that fix them (for example `/data 3y` when there isn't enough history), alongside Try again.
- Backtests under a year show their total return, not a stretched yearly rate. All 14 run in parallel and are cached; the history opens from a compact snapshot instead of the database.
- `/learn` on its own lists the whole playbook: 14 strategies that run on your data and 11 strategy families that need company financials (quality compounders, GARP, magic formula, Piotroski, coffee can, debt-free, dividend yield, promoter conviction, institutional buying, Graham value, earnings turnaround), each with the thresholds most used across 500 public stock screens.
- Easier to read: each card's read-out is a short, labelled takeaway (tap for the full text) with rises in green and falls in red; strategy rows say plainly whether a rule beat or lagged the market.
- **Models & data** (chat menu ⋮): the AI model, market history and the knowledge bank in one place — status, size, live progress, what needs each one, and download / update / delete. Everything beyond the app itself is downloaded only when asked, the same way as the AI model (Android's download manager, checked by sha256).
- **Dependencies:** commands declare what they need. Asking for `/brief` without market history, or `/screens` without the knowledge bank, shows what's needed, how big it is and where it comes from, with a Download button and live progress — then runs what you asked.
- **The knowledge bank is no longer bundled:** it's published on the project site (`docs/knowledge/screens-v1.kb`, 289 KB) and downloaded on demand.
- **Suggestions while typing:** plain words bring up action-first questions ("What should I do today?", "What needs my attention in my holdings?"), each running one exact command, ranked by what's already set up.
- **The brief ends with Next steps:** concrete actions tied to the numbers above (review a flagged holding, reduce concentration, see the best rule's picks, find out why a stock moved), and its pills follow them.
- Plain-language commands: short requests such as "show my holdings", "what's on the calendar tomorrow" or "remember that I prefer index funds" run the matching command directly. The `/` suggestion list scrolls through every command, grouped.

**Research on a Mac (optional)**
- `research.py bank` / `find` embed a local knowledge bank of stock screens with EmbeddingGemma 2 and search it by meaning ("cheap quality companies with no debt").
- `tools/market/fetch.py` downloads your own copy of NSE daily data (resumable; holidays remembered).
- `tools/research/research.py` turns every liquid stock's month-end situation into a sentence, embeds it with Google's EmbeddingGemma 2 (downloaded by your Mac from Hugging Face) and lets you ask in plain words — "breakout to a new high on heavy volume" — for the closest past situations and how they went the following month. Uses turbovec when installed, otherwise exact search.

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
