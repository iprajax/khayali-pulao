/* The chart wall: every chart the app draws, all on screen at once, on fictional data.
   Each tile is the app's own renderer (viz/viz.html, d3 + plug-ins) in a frame, loaded when it scrolls near view. */
(function () {
  "use strict";
  var seed = 7; function rnd() { seed = (seed * 9301 + 49297) % 233280; return seed / 233280; }
  function days(n, from) { var out = [], d = new Date(from || "2026-05-18"); for (var i = 0; i < n; i++) { out.push(new Date(+d + i * 864e5).toISOString().slice(0, 10)); } return out; }
  function walk(n, start, drift, vol) { var v = [], x = start; for (var i = 0; i < n; i++) { x = x * (1 + drift + (rnd() - 0.5) * vol); v.push(+x.toFixed(2)); } return v; }
  function sma(a, k) { return a.map(function (_, i) { if (i + 1 < k) return null; var s = 0; for (var j = i + 1 - k; j <= i; j++) s += a[j]; return +(s / k).toFixed(2); }); }

    var pts = []; for (var i = 0; i < 250; i++) pts.push({ t: new Date(2025, 10, 17 + i).toISOString(), c: 1200 + i * 1.1 + Math.sin(i / 9) * 45 + Math.sin(i / 3) * 12 });
    var ohlc = []; (function () { var p = 1380, seed = 7; function rnd() { seed = (seed * 9301 + 49297) % 233280; return seed / 233280; }
      for (var i = 0; i < 42; i++) { var o = p, c = p * (1 + (rnd() - 0.47) * 0.035), h = Math.max(o, c) * (1 + rnd() * 0.01), l = Math.min(o, c) * (1 - rnd() * 0.01);
        ohlc.push({ t: new Date(2026, 9, 6 + i).toISOString().slice(0, 10), o: o, h: h, l: l, c: c, v: Math.round(200000 + rnd() * 700000) }); p = c; } })();
    var S = {
      mindmap: ["INFRA · MIND MAP", "Infra Builders", "Price, results, ownership and events in one tree · tap a topic to fold it · fictional", 300, { name: "INFRA", sub: "Infra Builders", children: [
        { name: "Price", color: "blue", children: [{ name: "Last", value: "₹1,450", tone: "up" }, { name: "Today", value: "+1.2%", tone: "up" }, { name: "52-week", value: "₹980 – ₹1,728" }] },
        { name: "Results", color: "green", children: [{ name: "Sales Q2", value: "₹4,072 Cr" }, { name: "Profit Q2", value: "₹789 Cr", tone: "up" }, { name: "Profit y/y", value: "+1.7%", tone: "up" }] },
        { name: "Ownership", color: "violet", children: [{ name: "Promoters", value: "13.9%" }, { name: "Public", value: "85.9%" }] },
        { name: "Events", color: "saffron", children: [{ name: "Dividend", value: "3 Nov" }, { name: "Next results", value: "Jan" }] }] }],
      stream: ["INFRA · SHAREHOLDING", "Promoters 13.9%", "Who owns the company, quarter by quarter · fictional", 230, { unit: "%", yLabel: "Share of equity (%)", xLabel: "Quarter", series: ["Promoters", "Institutions", "Public"],
        points: [["2025-03-31", 14.4, 52.1, 33.5], ["2025-06-30", 14.3, 52.9, 32.8], ["2025-09-30", 14.2, 53.6, 32.2], ["2025-12-31", 14.1, 54.0, 31.9], ["2026-03-31", 14.0, 54.8, 31.2], ["2026-06-30", 13.9, 55.3, 30.8], ["2026-09-30", 13.9, 55.9, 30.2]].map(function (p) { return { t: p[0], values: p.slice(1) }; }) }],
      radar: ["INFRA · SCORECARD", "62 / 100", "Five measures scored 0–100 against fixed bands · fictional", 280, { max: 100, ringLabel: "score", axes: [
        { label: "1-yr return", score: 70, value: "+21%" }, { label: "Low volatility", score: 55, value: "24%" }, { label: "Small drawdown", score: 48, value: "−18%" }, { label: "Trend", score: 80, value: "> 200-DMA" }, { label: "Dividend yield", score: 57, value: "1.8%" }] }],
      butterfly: ["NIFTY · OPTION CHAIN · OPEN INTEREST", "Spot 25,328", "Puts left, calls right, by strike · dashed line = spot · fictional", 300, { left: "Puts", right: "Calls", unit: "lakh", spot: 25328, yLabel: "Strike", xLabel: "Open interest (lakh)",
        rows: [25000, 25100, 25200, 25300, 25400, 25500, 25600].map(function (k, i) { return { strike: k, left: [62, 48, 55, 40, 21, 12, 7][i], right: [9, 14, 22, 38, 51, 66, 44][i] }; }) }],
      sunburst: ["YOUR PORTFOLIO · X-RAY", "₹5,88,320", "Inner ring: gainers and losers · outer ring: each stock · fictional", 300, { centre: "Portfolio value", unit: "₹", inner: "group", outer: "stock", measure: "share of portfolio value",
        root: { name: "Portfolio", children: [{ name: "Gainers", color: "green", children: [{ name: "INFRA", value: 362500 }, { name: "BALLIA", value: 120000 }] }, { name: "Losers", color: "red", children: [{ name: "CHAICO", value: 65820 }, { name: "JHUMRI", value: 40000 }] }] } }],
      matrix: ["INFRA · SEASONALITY", "Best month: April", "Monthly returns by year · fictional", 260, { cols: ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"], unit: "%", xLabel: "Month", yLabel: "Year",
        rows: [{ label: "2024", values: [2.1,-1.3,3.4,4.8,-0.6,1.2,2.9,-2.2,0.4,1.8,-1.1,2.6] }, { label: "2025", values: [-0.8,1.9,-2.7,5.1,0.9,-1.4,3.3,1.1,-0.5,2.2,0.7,-0.9] }, { label: "2026", values: [1.4,0.6,-1.8,3.9,2.4,0.3,-1.2,2.8,1.6,-0.4,0.9,null] }],
        footer: { label: "Avg", values: [0.9,0.4,-0.4,4.6,0.9,0,1.7,0.6,0.5,1.2,0.2,0.9] }, partial: { row: "2026", col: 10 } }],
      sparkgrid: ["WORLD MARKETS · 1 MONTH", "8 of 10 up", "Each panel: last value, change and its own range · fictional", 300, { cols: 2, items: ["S&P 500","Nasdaq","FTSE","DAX","Nikkei","Nifty 50"].map(function (n, j) {
        var v = [], t = [], x = 100 + j * 7; for (var i = 0; i < 22; i++) { x = x * (1 + Math.sin(i * 0.7 + j) * 0.008 + 0.002); v.push(+x.toFixed(2)); t.push(new Date(2026, 9, 17 + i).toISOString().slice(0, 10)); }
        return { label: n, t: t, v: v, change: (v[v.length - 1] / v[0] - 1) * 100 }; }) }],
      stackarea: ["SIP · ₹10,000 A MONTH · 12% · 15 YEARS", "₹50.5 lakh", "₹18 lakh invested grows to ₹50.5 lakh · illustration", 230, { series: ["Invested", "Growth"], xLabel: "Years", yLabel: "Value (₹)",
        points: (function () { var out = [], bal = 0; for (var y = 1; y <= 15; y++) { for (var m = 0; m < 12; m++) bal = (bal + 10000) * (1 + 0.12 / 12); out.push({ x: y, a: 120000 * y, b: Math.round(bal - 120000 * y) }); } return out; })() }],
      sankey: ["INFRA · Q2 FY27 RESULTS · MONEY FLOW", "₹789 Cr net profit", "on sales of ₹4,072 Cr · net margin 19.4% · fictional company", 300, { unit: "₹ Cr", nodes: [
        { id: "sales", label: "Sales", col: 0, color: "blue" }, { id: "other", label: "Other inc.", col: 0, color: "violet" }, { id: "income", label: "Income", col: 1, color: "blue" },
        { id: "staff", label: "Staff", col: 2, color: "saffron" }, { id: "oth", label: "Other exp.", col: 2, color: "saffron" }, { id: "pbt", label: "Pre-tax", col: 2, color: "green" },
        { id: "tax", label: "Tax", col: 3, color: "red" }, { id: "profit", label: "Net profit", col: 3, color: "green" }],
        links: [{ from: "sales", to: "income", value: 4072 }, { from: "other", to: "income", value: 170 }, { from: "income", to: "staff", value: 1910 }, { from: "income", to: "oth", value: 1242 },
          { from: "income", to: "pbt", value: 1090 }, { from: "pbt", to: "tax", value: 301 }, { from: "pbt", to: "profit", value: 789 }] }],
      treemap: ["PULAO 30 · HEATMAP · 30 STOCKS", "19 up · 11 down", "Tile size: traded value · colour: today's change · ★ your holding", 300, { items: (function () {
        var n = ["INFRA","CHAICO","BALLIA","JHUMRI","PULAO","THALI","KADHAI","TADKA","MASALA","BIRYANI","ROTI","DAL","SABZI","LASSI","CHAI","SAMOSA","JALEBI","PARATHA","IDLI","DOSA","VADA","UPMA","POHA","KHEER","HALWA","LADDU","BARFI","PEDA","RABRI","KULFI"], out = [], seed = 11;
        function r() { seed = (seed * 9301 + 49297) % 233280; return seed / 233280; }
        n.forEach(function (x) { out.push({ label: x, value: (2 + r() * 88) * 1e8, change: (r() - 0.4) * 8, price: Math.round(100 + r() * 3900), mine: x === "INFRA" }); }); return out; })() }],
      agenda: ["MARKET CALENDAR · NEXT 7 DAYS", "48 events", "Results, dividends, splits, bonus, AGMs, IPOs and holidays, by day · fictional counts", 210, { from: "2026-11-17", to: "2026-11-24", today: "2026-11-17", events: (function () {
        var plan = { "2026-11-17": { Dividend: 6, Results: 1, "Board meeting": 2 }, "2026-11-18": { Dividend: 6, Results: 3, "Board meeting": 3, Buyback: 2, "IPO opens": 1 }, "2026-11-19": { Results: 2, Dividend: 4, "IPO opens": 1, Bonus: 1 },
          "2026-11-20": { Dividend: 3, "IPO closes": 1, "Stock split": 1 }, "2026-11-21": { Dividend: 2, "AGM / EGM": 1 }, "2026-11-23": { Dividend: 2, "IPO closes": 1, "Rights issue": 1 }, "2026-11-24": { "Market holiday": 1 } }, out = [];
        Object.keys(plan).forEach(function (d) { Object.keys(plan[d]).forEach(function (k) { for (var i = 0; i < plan[d][k]; i++) out.push({ date: d, kind: k }); }); }); return out; })() }],
      gmp: ["PULAO FOODS · MAIN BOARD IPO · GREY MARKET", "₹13  +4.8%", "Unofficial premium by date — it fell from ₹40 · fictional issue", 210, { yLabel: "GMP (₹ a share)", points: [
        { t: "2026-11-11", c: 40 }, { t: "2026-11-12", c: 36 }, { t: "2026-11-13", c: 31 }, { t: "2026-11-14", c: 27 }, { t: "2026-11-15", c: 15 }, { t: "2026-11-16", c: 13 }, { t: "2026-11-17", c: 13 }] }],
      holdings: ["YOUR KITE HOLDINGS · 4", "₹5,88,320", "+₹68,320 (+13.1%) on ₹5,20,000", 330, { holdings: [
        { symbol: "INFRA", current: 362500, pnl: 62500, pnl_percent: 20.8 }, { symbol: "BALLIA", current: 120000, pnl: 20000, pnl_percent: 20 },
        { symbol: "CHAICO", current: 65820, pnl: -4180, pnl_percent: -6 }, { symbol: "JHUMRI", current: 40000, pnl: -10000, pnl_percent: -20 }] }],
      allocation: ["ALLOCATION · SHARE OF PORTFOLIO VALUE", "61.6% in INFRA", "4 holdings · largest first", 190, { items: [
        { label: "INFRA", value: 362500 }, { label: "BALLIA", value: 120000 }, { label: "CHAICO", value: 65820 }, { label: "JHUMRI", value: 40000 }] }],
      history: ["INFRA · 1Y", "₹1,450.00", "+21.0% · price (₹) by date · drag to read a day", 230, { points: pts }],
      candles: ["INFRA · 1M · OPEN-HIGH-LOW-CLOSE", "₹" + ohlc[ohlc.length - 1].c.toFixed(2), "Daily candles with volume · tap a candle", 250, { points: ohlc }],
      scenario: ["IF IT MOVES −20%", "₹2,90,000", "−₹10,000 (−3.3%) on ₹3,00,000 · value at every move from −30% to +30%", 210, { today: 362500, cost: 300000, move: -20, value: 290000, from_avg: false }],
      growth: ["COMPOUNDING", "₹3,10,585", "₹1,00,000 at 12% a year · 10 years · 3.11× (dashed: today's money)", 220, { start: 100000, rate: 12, years: 10, inflation: 6.1 }],
      versus: ["YOU VS THE ALTERNATIVES", "+9.9% a year", "₹3,00,000 → ₹3,62,500 in 2 yrs · ahead of a 6.45% FD", 210, { items: [
        { label: "You", value: 362500 }, { label: "Bank FD", value: 339989 }, { label: "PPF", value: 340815 }, { label: "Gold", value: 383070 },
        { label: "Nifty 50", value: 372968 }, { label: "Inflation", value: 337731 }] }],
      paths: ["GOAL · ₹1 CRORE", "27.6× from today", "Value by year at 8%, 12%, 15% and 20% a year · dots mark where each reaches the goal", 230, { now: 362500, goal: 10000000, rates: [8, 12, 15, 20] }],
      waterfall: ["IN HAND IF YOU SELL · LTCG", "₹3,62,000", "of ₹4,00,000 profit · charges and tax taken off step by step", 210, { gain: 400000, in_hand: 362000, tax: 34000, charges: 4000 }],
      calendar: ["INFRA · COMPANY EVENTS", "Results · 15 Jan 2027", "Dividends from the quote feed · dated events from cited web results", 290, { today: "2026-11-17", events: [
        { date: "2026-06-10", kind: "Dividend", title: "Dividend ₹25 a share", source: 0 }, { date: "2026-07-24", kind: "AGM", title: "Annual general meeting held", source: 2 },
        { date: "2026-10-22", kind: "Board meeting", title: "Board met to approve Q2 results", source: 1 }, { date: "2026-11-03", kind: "Dividend", title: "Dividend ₹26 a share", source: 0 },
        { date: "2027-01-15", kind: "Results", title: "Q3 results expected", source: 3, upcoming: true }] }],
      margins: ["MARGINS · EQUITY", "₹2,50,000", "available to trade", 160, { net: 250000, used: 80000, cash: 180000 }],
      orders: ["ORDERS TODAY · 3", "3 orders", "1 complete · 1 open · 1 rejected", 160, { orders: [
        { symbol: "INFRA", side: "BUY", qty: 10, price: 1450, status: "COMPLETE", time: "2026-11-17 09:21:00" },
        { symbol: "CHAICO", side: "SELL", qty: 5, price: 0, status: "OPEN", time: "2026-11-17 10:02:00" },
        { symbol: "TCS", side: "BUY", qty: 1, price: 2405, status: "REJECTED", time: "2026-11-17 11:15:00" }] }],
    };

    // ---- the rest of the app's charts (fictional data) ----
    var t130 = days(130), px = walk(130, 1200, 0.0012, 0.03), sd = (function () { var m = sma(px, 20); return px.map(function (p, i) { if (i < 19) return null; var s = 0; for (var j = i - 19; j <= i; j++) s += Math.pow(px[j] - m[i], 2); return Math.sqrt(s / 20); }); })();
    var rsi = (function () { var out = [], g = 0, l = 0; for (var i = 1; i < px.length; i++) { var c = px[i] - px[i - 1]; g = (g * 13 + Math.max(c, 0)) / 14; l = (l * 13 + Math.max(-c, 0)) / 14; out.push(l === 0 ? 100 : +(100 - 100 / (1 + g / l)).toFixed(1)); } out.unshift(50); return out; })();
    var e12 = [], e26 = [], a = px[0], b = px[0]; px.forEach(function (p) { a = p * 2 / 13 + a * 11 / 13; b = p * 2 / 27 + b * 25 / 27; e12.push(a); e26.push(b); });
    var macd = e12.map(function (x, i) { return +(x - e26[i]).toFixed(2); }), sig = (function () { var s = macd[0]; return macd.map(function (m) { s = m * 0.2 + s * 0.8; return +s.toFixed(2); }); })();
    var peak = -1, dd = px.map(function (p) { peak = Math.max(peak, p); return +((p / peak - 1) * 100).toFixed(2); });
    var X = {
      divbars: ["Sectors today", "/sectors", 250, { unit: "%", xLabel: "Today's change (%)", yLabel: "Sector", legend: ["Up", "Down"], items: [["Realty", 1.96], ["PSU Bank", 1.83], ["Auto", 1.14], ["Media", 1.10], ["Metal", 0.62], ["Bank", 0.41], ["Pharma", 0.18], ["FMCG", -0.12], ["IT", -0.44], ["Energy", -0.71]].map(function (r) { return { label: r[0], value: r[1] }; }) }],
      donut: ["Market breadth", "/breadth", 190, { centre: "2.16", centreLabel: "adv per decline", nameLabel: "Direction", valueLabel: "Stocks", items: [{ label: "Advances", value: 2121, color: "green" }, { label: "Declines", value: 981, color: "red" }, { label: "Unchanged", value: 118, color: "muted" }] }],
      netbars: ["FII and DII net flows", "/fiidii", 200, { unit: "₹ Cr", yLabel: "Net bought (₹ Cr)", xLabel: "Investor group", items: [{ label: "FII/FPI", value: 4865 }, { label: "DII", value: -3519 }, { label: "Combined", value: 1346 }] }],
      timeline: ["Insider trades", "/insider INFRA", 200, { yLabel: "Value (₹)", xLabel: "Trade date", buy: "Bought", sell: "Sold", points: [["2026-08-04", 2.1e7, "buy"], ["2026-08-19", -1.4e8, "sell"], ["2026-09-02", 6e6, "buy"], ["2026-09-23", -3.2e7, "sell"], ["2026-10-14", 1.2e7, "buy"], ["2026-11-06", -8e6, "sell"]].map(function (p) { return { t: p[0], value: p[1], side: p[2], label: p[2] === "buy" ? "Director" : "Promoter group" }; }) }],
      gauge: ["52-week range", "/quote INFRA", 130, { title: "52-week range", axis: "Price (₹)", min: 980, max: 1728, value: 1142, minLabel: "52-week low", maxLabel: "52-week high", valueLabel: "Last", marks: [{ label: "200-day avg", value: 1188 }] }],
      overlay: ["Moving averages & Bollinger", "/ta INFRA", 240, { t: t130, price: px, name: "INFRA", unit: "₹", yLabel: "Price (₹)", xLabel: "Date",
        lines: [{ name: "20-day avg", color: "saffron", v: sma(px, 20) }, { name: "50-day avg", color: "violet", v: sma(px, 50) }],
        band: { name: "Bollinger band", upper: sma(px, 20).map(function (m, i) { return m == null ? null : +(m + 2 * sd[i]).toFixed(2); }), lower: sma(px, 20).map(function (m, i) { return m == null ? null : +(m - 2 * sd[i]).toFixed(2); }) } }],
      oscillator: ["RSI (14)", "/rsi INFRA", 200, { t: t130, line: rsi, name: "RSI", color: "blue", min: 0, max: 100, digits: 1, yLabel: "RSI (0–100)", xLabel: "Date",
        zones: [{ from: 70, to: 100, color: "red", label: "Above 70" }, { from: 0, to: 30, color: "green", label: "Below 30" }], thresholds: [{ value: 70, label: "70" }, { value: 30, label: "30" }] }],
      macd: ["MACD (12, 26, 9)", "/macd INFRA", 200, { t: t130, line: macd, name: "MACD", color: "blue", signal: sig, signalName: "Signal", hist: macd.map(function (m, i) { return +(m - sig[i]).toFixed(2); }), histName: "Histogram", digits: 1, yLabel: "MACD (₹)", xLabel: "Date" }],
      underwater: ["Drawdown from peak", "/drawdown INFRA", 200, { t: t130, dd: dd, yLabel: "Below running peak (%)", xLabel: "Date" }],
      multiline: ["Compare, rebased to 100", "/compare INFRA CHAICO 6m", 220, { t: t130, base: 100, yLabel: "Rebased value (start = 100)", xLabel: "Date", series: [
        { name: "INFRA", color: "blue", v: px.map(function (p) { return +(p / px[0] * 100).toFixed(2); }) }, { name: "CHAICO", color: "saffron", v: walk(130, 100, 0.0006, 0.026) }] }],
      curve: ["Max pain", "/maxpain", 210, { xLabel: "Expiry level (strike)", yLabel: "Writers' pay-out", yFmt: "num", series: [{ name: "Pay-out", color: "saffron", points: [24400, 24600, 24800, 25000, 25200, 25300, 25400, 25600, 25800, 26000, 26200].map(function (k) { return [k, Math.round(Math.pow(Math.abs(k - 25300) / 100, 1.6) * 9 + 4)]; }) }],
        markers: [{ x: 25300, label: "Max pain 25,300", color: "saffron" }, { x: 25375, label: "Spot 25,375" }] }],
      ivsmile: ["Implied volatility smile", "/ivsmile", 210, { xLabel: "Strike", yLabel: "Implied volatility (%)", yFmt: "pct", series: [
        { name: "Calls", color: "blue", points: [25000, 25200, 25400, 25600, 25800, 26000, 26200].map(function (k, i) { return [k, [16, 12.5, 10.2, 11.4, 14.2, 18.1, 22.6][i]]; }) },
        { name: "Puts", color: "saffron", points: [24400, 24600, 24800, 25000, 25200, 25400].map(function (k, i) { return [k, [26, 21.5, 17.8, 14.4, 11.9, 10.7][i]]; }) }], markers: [{ x: 25375, label: "Spot" }] }],
      scatter: ["Risk vs return", "/riskreturn", 230, { xLabel: "Volatility (% a year)", yLabel: "Return over 1 year (%)", xUnit: "%", yUnit: "%", sizeLabel: "Bubble = weight", refs: [{ axis: "y", value: 0, label: "" }],
        points: [["INFRA", 24, 21, 37, "green"], ["CHAICO", 31, -8, 12, "red"], ["BALLIA", 19, 11, 24, "green"], ["JHUMRI", 36, -18, 9, "red"], ["Portfolio", 18, 9, 0, "saffron"]].map(function (p) { return { label: p[0], x: p[1], y: p[2], size: p[3] || 6, color: p[4] }; }) }],
      heatgrid: ["Correlation", "/correlation", 220, { rows: ["INFRA", "CHAICO", "BALLIA", "JHUMRI"], cols: ["INFRA", "CHAICO", "BALLIA", "JHUMRI"], min: -1, max: 1, unit: "correlation",
        cells: [[1, 0.29, 0.13, 0.19], [0.29, 1, 0.23, 0.20], [0.13, 0.23, 1, 0.77], [0.19, 0.20, 0.77, 1]] }],
      yearbars: ["India CPI inflation", "/inflation", 210, { unit: "%", yLabel: "CPI inflation (% a year)", xLabel: "Year", avg: 5.6, avgLabel: "10-year average", items: [["'16", 4.9], ["'17", 3.3], ["'18", 3.9], ["'19", 3.7], ["'20", 6.6], ["'21", 5.1], ["'22", 6.7], ["'23", 5.6], ["'24", 5.0], ["'25", 4.1]].map(function (r) { return { label: r[0], value: r[1] }; }) }],
      balance: ["Withdrawal plan (SWP)", "/swp 50 lakh 30000 8%", 210, { x: "year", unit: "inr", xLabel: "Years from start", yLabel: "Balance (₹)", marker: { value: 5000000, label: "Starting corpus" },
        series: [{ name: "Balance", color: "green", points: (function () { var out = [], b = 5e6; for (var y = 0; y <= 25; y++) { out.push([y, Math.round(b)]); for (var m = 0; m < 12; m++) b = b * (1 + 0.08 / 12) - 30000; } return out; })() }] }],
      groupbars: ["Quarterly results trend", "/flow INFRA", 200, { unit: "₹ Cr", series: ["Income", "Net profit"], items: [["Q2 FY26", 3917, 776], ["Q3 FY26", 4027, 736], ["Q4 FY26", 3970, 798], ["Q1 FY27", 4083, 725], ["Q2 FY27", 4242, 789]].map(function (r) { return { label: r[0], values: [r[1], r[2]] }; }) }],
      listing: ["IPO: implied listing", "/gmp PULAO", 130, { issue: 272, expected: 285, low: 285, high: 312 }],
      ipo: ["IPO bid windows", "/ipo", 160, { today: "2026-11-17", ipos: [{ name: "Pulao Foods Limited", opens: "2026-11-18", closes: "2026-11-20", status: "Forthcoming" }, { name: "Thali Rentals Limited", opens: "2026-11-19", closes: "2026-11-23", status: "Forthcoming" }, { name: "Kadhai Coal Limited", opens: "2026-11-13", closes: "2026-11-17", status: "Open" }] }],
      percentbars: ["Grey-market premium", "/gmp", 140, { label: "Premium over issue price (%)", items: [{ label: "Thali Rentals", value: 10.2 }, { label: "Pulao Foods", value: 4.8 }, { label: "Kadhai Coal", value: 2.6 }, { label: "Masala Tech", value: -1.5 }] }],
    };
    // Which feature each of the gallery's original charts belongs to, and its command.
    var CMD = { sankey: "/flow INFRA", treemap: "/heatmap", agenda: "/calendar", gmp: "/gmp PULAO", holdings: "/holdings", allocation: "/xray", history: "/chart INFRA 1y", candles: "/chart INFRA 1m",
      scenario: "/move -20", growth: "/grow 1 lakh 12% 10y", versus: "/fd 3", paths: "/goal 1 crore", waterfall: "/tax", calendar: "/events INFRA", margins: "/margins", orders: "/orders",
      mindmap: "/mindmap INFRA", stream: "/shareholding INFRA", radar: "/scorecard INFRA", butterfly: "/optionchain", sunburst: "/xray", matrix: "/seasonality INFRA", sparkgrid: "/global", stackarea: "/sip 10000 12% 15y" };
    var TITLE = { sankey: "Results money flow", treemap: "Index heatmap", agenda: "Market calendar", gmp: "IPO grey-market trend", holdings: "Your holdings", allocation: "Allocation",
      history: "Price history", candles: "Candlesticks", scenario: "What-if curve", growth: "Compounding", versus: "You vs FD, PPF, gold, Nifty", paths: "Goal paths", waterfall: "Tax waterfall",
      calendar: "Company events", margins: "Margins", orders: "Today's orders", mindmap: "Company mind map", stream: "Shareholding stream", radar: "Scorecard radar",
      butterfly: "Option chain", sunburst: "Portfolio X-ray", matrix: "Seasonality", sparkgrid: "World markets", stackarea: "SIP growth" };
    var GROUPS = [
      ["Market", ["treemap", "divbars", "donut", "agenda", "netbars", "percentbars", "ipo", "listing", "gmp"]],
      ["Stock", ["sankey", "groupbars", "mindmap", "stream", "radar", "gauge", "history", "candles", "timeline", "calendar"]],
      ["Technicals", ["overlay", "oscillator", "macd", "underwater", "multiline", "matrix"]],
      ["Portfolio", ["holdings", "sunburst", "scatter", "heatgrid", "scenario", "waterfall", "margins", "orders"]],
      ["Options", ["butterfly", "curve", "ivsmile"]],
      ["Global, funds & planning", ["sparkgrid", "yearbars", "stackarea", "balance", "growth", "versus", "paths"]],
    ];
    var TYPE = { gmp: "history", macd: "oscillator", ivsmile: "curve" };

    function tile(key) {
      var x = X[key], s = S[key], title, cmd, h, data, sub = "";
      if (x) { title = x[0]; cmd = x[1]; h = x[2]; data = x[3]; } else if (s) { title = TITLE[key] || key; cmd = CMD[key] || ""; h = s[3]; data = s[4]; sub = s[2]; } else return null;
      var el = document.createElement("div"); el.className = "wall-tile card reveal";
      el.innerHTML = '<div class="wall-head"><span class="wall-title"></span><code class="wall-cmd"></code></div><div class="wall-sub"></div><iframe title="" loading="lazy"></iframe>';
      el.querySelector(".wall-title").textContent = title; el.querySelector(".wall-cmd").textContent = cmd; el.querySelector(".wall-sub").textContent = sub;
      var f = el.querySelector("iframe"); f.title = title; f.style.height = h + "px";
      f.dataset.spec = JSON.stringify({ type: TYPE[key] || key, height: h, data: data });
      return el;
    }
    var wall = document.getElementById("wall"); if (!wall) return;
    GROUPS.forEach(function (g) {
      var head = document.createElement("h3"); head.className = "wall-group reveal"; head.textContent = g[0] + " · " + g[1].length; wall.appendChild(head);
      var grid = document.createElement("div"); grid.className = "wall-grid"; wall.appendChild(grid);
      g[1].forEach(function (k) { var t = tile(k); if (t) grid.appendChild(t); });
    });
    // Load each chart when it comes near the screen; draw it once the frame is ready.
    var io = new IntersectionObserver(function (es) { es.forEach(function (e) {
      if (!e.isIntersecting) return; var f = e.target; io.unobserve(f);
      f.addEventListener("load", function () { try { f.contentWindow.render(JSON.parse(f.dataset.spec)); } catch (err) {} });
      f.src = "viz/viz.html";
    }); }, { rootMargin: "300px" });
    wall.querySelectorAll("iframe").forEach(function (f) { io.observe(f); });
    var n = wall.querySelectorAll(".wall-tile").length, c = document.getElementById("wall-count"); if (c) c.textContent = n;
})();
