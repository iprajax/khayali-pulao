/* Interactive "Try it" playground for the landing page. All maths from khayali.js; runs locally. */
(function () {
  "use strict";
  const K = window.Khayali;
  const { Fmt, Levels, Projector, Dreams, Bench, Costs, Pulao } = K;
  const $ = (s, el = document) => el.querySelector(s);
  const h = (tag, attrs = {}, ...kids) => {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (k === "class") e.className = v;
      else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
      else if (v !== false && v != null) e.setAttribute(k, v === true ? "" : v);
    }
    for (const kid of kids.flat()) if (kid != null && kid !== false) e.append(kid.nodeType ? kid : document.createTextNode(kid));
    return e;
  };
  const cls = (v) => (v >= 0 ? "pos" : "neg");

  // ---- state (fictional defaults) ----
  const S = {
    kind: "HOLDING",
    holding: { name: "INFRA", qty: 250, avg: 1200, ltp: 1450 },
    quote: { name: "CHAI CO", ltp: 658.2, mode: "AMOUNT", amount: 10000, shares: 10 },
    portfolio: { invested: 300000, current: 362500 },
    tab: "MOVE", base: "LTP", pct: 10, held: 2, spendSrc: "TODAY",
    goal: null, cagr: 12, years: 10, regret: null, tone: "LIGHT", compact: false, taxOn: true,
    r: { fd: 6.5, savings: 2.7, ppf: 7.1, gold: 10, nifty: 12, inflation: 5, expense: 50000 },
  };
  const gains = Levels.parse(K.DEFAULT_GAINS), losses = Levels.parse(K.DEFAULT_LOSSES);
  const ladder = Levels.ladder(gains, losses, true);
  const money = (v) => Fmt.money(v, S.compact), sMoney = (v) => Fmt.signedMoney(v, S.compact), rs = (v) => "₹" + money(v);

  function stockKey() { return S.kind; }
  function holdingObj() { const x = S.holding; return { qty: x.qty, avg: x.avg, invested: x.qty * x.avg, ltp: x.ltp }; }
  function dream(p) {
    if (S.kind === "HOLDING") return Projector.holding(holdingObj(), S.base, p);
    if (S.kind === "WATCH") return Projector.quote({ ltp: S.quote.ltp }, S.quote.mode, S.quote.mode === "AMOUNT" ? S.quote.amount : S.quote.shares, p);
    return Projector.portfolio(S.portfolio, S.base, p);
  }
  const taxKind = () => Costs.kindFor(S.kind === "WATCH" ? null : S.held, "LTCG");
  function inHandLine(gain, sell) {
    const k = taxKind(), net = Costs.inHand(gain, sell, k);
    const lbl = k === "LTCG" ? "LTCG 12.5% over ₹1.25L" : k === "STCG" ? "STCG 20%" : "30% slab";
    return `≈ ${sMoney(net)} in hand if sold — after ${lbl}, STT & charges`;
  }
  const fun = (amt) => (S.tone === "SERIOUS" ? null : Pulao.describe(amt));

  // ---- inputs panel ----
  function inputsPanel() {
    const box = $("#play-inputs"); box.innerHTML = "";
    const seg = (opts, cur, set) => h("div", { class: "seg" }, opts.map(([v, l]) => h("button", { class: v === cur ? "on" : "", onclick: () => { set(v); render(); } }, l)));
    const num = (label, val, set, step = "any") => h("label", { class: "field" }, h("span", {}, label),
      h("input", { type: "number", inputmode: "decimal", step, value: val, oninput: (e) => { const v = parseFloat(e.target.value); if (!isNaN(v) && v >= 0) { set(v); render(false); } } }));
    box.append(h("div", { class: "lbl" }, "Daydream about"));
    box.append(seg([["HOLDING", "A holding"], ["WATCH", "Watchlist stock"], ["PORTFOLIO", "Portfolio"]], S.kind, (v) => { S.kind = v; S.regret = null; S.goal = null; if (v === "PORTFOLIO" && S.tab === "REGRET") S.tab = "MOVE"; }));
    if (S.kind === "HOLDING") box.append(num("Quantity", S.holding.qty, (v) => (S.holding.qty = v)), num("Avg. price ₹", S.holding.avg, (v) => (S.holding.avg = v)), num("LTP ₹", S.holding.ltp, (v) => (S.holding.ltp = v)));
    else if (S.kind === "WATCH") {
      box.append(num("LTP ₹", S.quote.ltp, (v) => (S.quote.ltp = v)));
      box.append(seg([["AMOUNT", "Invest ₹"], ["SHARES", "Buy N shares"]], S.quote.mode, (v) => (S.quote.mode = v)));
      box.append(S.quote.mode === "AMOUNT" ? num("Amount ₹", S.quote.amount, (v) => (S.quote.amount = v)) : num("Shares", S.quote.shares, (v) => (S.quote.shares = v)));
    } else box.append(num("Invested ₹", S.portfolio.invested, (v) => (S.portfolio.invested = v)), num("Current ₹", S.portfolio.current, (v) => (S.portfolio.current = v)));
    box.append(h("div", { class: "lbl" }, "Tone"), seg([["SERIOUS", "Serious"], ["LIGHT", "Light"], ["MASALA", "Masala 🌶️"]], S.tone, (v) => (S.tone = v)));
    box.append(h("label", { class: "check" }, h("input", { type: "checkbox", checked: S.compact, onchange: (e) => { S.compact = e.target.checked; render(); } }), " Short numbers (L / Cr)"));
    const det = h("details", { class: "assume" }, h("summary", {}, "Assumptions (all editable)"),
      num("Bank FD % / yr", S.r.fd, (v) => (S.r.fd = v)), num("Savings % / yr", S.r.savings, (v) => (S.r.savings = v)),
      num("PPF % / yr", S.r.ppf, (v) => (S.r.ppf = v)), num("Gold % / yr (assumed)", S.r.gold, (v) => (S.r.gold = v)),
      num("Nifty 50 % / yr (assumed)", S.r.nifty, (v) => (S.r.nifty = v)), num("Inflation % / yr", S.r.inflation, (v) => (S.r.inflation = v)),
      num("Monthly expenses ₹", S.r.expense, (v) => (S.r.expense = v)));
    box.append(det);
    box.append(h("p", { class: "tiny" }, "Fictional defaults. Everything is calculated in your browser — nothing is sent anywhere."));
  }

  // ---- sheet ----
  function header() {
    const d0 = dream(0);
    let title, tag, meta, pnl = null;
    if (S.kind === "HOLDING") { const x = holdingObj(); title = S.holding.name; tag = "HOLDING"; meta = `Qty. ${Fmt.amount(x.qty)} • Avg. ${Fmt.price(x.avg)} • LTP ${Fmt.price(x.ltp)}`; pnl = d0.now - d0.cost; }
    else if (S.kind === "WATCH") { title = S.quote.name; tag = "NSE"; meta = `LTP ${Fmt.price(S.quote.ltp)} • ${Fmt.amount(d0.qty)} shares`; }
    else { title = "My portfolio"; tag = "PORTFOLIO"; meta = `Invested ${money(S.portfolio.invested)} • Current ${money(S.portfolio.current)}`; pnl = S.portfolio.current - S.portfolio.invested; }
    const pp = pnl == null ? null : d0.cost ? (pnl / d0.cost) * 100 : 0;
    return h("div", { class: "sh-head" },
      h("div", { class: "row" }, h("b", { class: "sh-title" }, title), h("span", { class: "tag" }, tag)),
      h("div", { class: "row" }, h("span", { class: "muted" }, meta), pnl == null ? null : h("span", { class: cls(pnl) }, `${sMoney(pnl)} (${Fmt.pct1(pp)})`)));
  }
  function tabs() {
    const list = [["MOVE", "Move %"], ["VSFD", "vs FD"], ["SPEND", "Spend it"], ["GOAL", "Goal"], ["YEARS", "Years"], ["REGRET", "Regret"]].filter(([k]) => !(k === "REGRET" && S.kind === "PORTFOLIO"));
    return h("div", { class: "tabs", role: "tablist" }, list.map(([k, l]) => h("button", { role: "tab", "aria-selected": String(S.tab === k), class: S.tab === k ? "on" : "", onclick: () => { S.tab = k; render(); } }, l)));
  }
  const chip = (label, on, click, neg) => h("button", { class: "chip" + (on ? " on" : "") + (neg ? " neg" : ""), onclick: click }, label);
  const quipEl = (txt) => (txt ? h("div", { class: "quip" }, txt) : null);

  function moveTab() {
    const d = dream(S.pct);
    const out = [];
    if (S.kind !== "WATCH") out.push(h("div", { class: "seg" }, [["AVG", S.kind === "PORTFOLIO" ? "From invested" : "From my avg"], ["LTP", S.kind === "PORTFOLIO" ? "From current" : "From LTP"]]
      .map(([v, l]) => h("button", { class: S.base === v ? "on" : "", onclick: () => { S.base = v; render(); } }, l))));
    const priceLine = S.kind === "PORTFOLIO" ? `from ${S.base === "AVG" ? "invested" : "current"} ${rs(d.basePrice)}`
      : S.kind === "HOLDING" ? `₹${Fmt.price(d.price)} per share • from ${S.base === "AVG" ? "avg" : "LTP"} ${Fmt.price(d.basePrice)}`
      : `₹${Fmt.price(d.price)} per share • ${Fmt.amount(d.qty)} shares`;
    const vsGain = S.kind === "WATCH" ? d.profit : d.vsNow;
    const bigEl = h("div", { class: "big " + cls(S.pct) }, Fmt.pct(S.pct));
    const valEl = h("div", { class: "fig" }, rs(d.value));
    const pnlEl = h("div", { class: "fig " + cls(d.profit) }, `${sMoney(d.profit)} (${Fmt.pct1(d.profitPct)})`);
    const live = () => { // cheap update while dragging; full render on release
      const x = dream(S.pct);
      bigEl.textContent = Fmt.pct(S.pct); bigEl.className = "big " + cls(S.pct);
      valEl.textContent = rs(x.value);
      pnlEl.textContent = `${sMoney(x.profit)} (${Fmt.pct1(x.profitPct)})`; pnlEl.className = "fig " + cls(x.profit);
    };
    out.push(h("div", { class: "card" },
      h("div", { class: "muted" }, "If it moves"),
      bigEl,
      h("div", { class: "muted" }, priceLine),
      h("hr"),
      h("div", { class: "pair" }, h("div", {}, h("div", { class: "muted" }, "Would be worth"), valEl),
        h("div", { class: "r" }, h("div", { class: "muted" }, "P&L"), pnlEl)),
      S.kind === "WATCH" ? (d.qty <= 0 ? h("div", { class: "muted" }, "Amount too small for 1 share") : null) : h("div", { class: "muted " + (d.vsNow < 0 ? "neg" : "") }, `${sMoney(d.vsNow)} vs today's ${rs(d.now)}`),
      S.taxOn && d.profit > 0 ? h("div", { class: "muted" }, inHandLine(d.profit, d.value)) : null,
      fun(vsGain) ? h("div", { class: "saffron" }, fun(vsGain)) : null,
      quipEl(Pulao.quip(d.profit, d.profitPct, S.tone))));
    const slider = h("input", { type: "range", min: -50, max: 100, step: 0.5, value: S.pct, "aria-label": "Move percent",
      oninput: (e) => { S.pct = parseFloat(e.target.value); live(); }, onchange: () => render(false) });
    out.push(h("div", { class: "slider" },
      h("button", { class: "step", "aria-label": "Previous step", onclick: () => { S.pct = Math.max(-50, Levels.next(gains, losses, S.pct, false)); render(); } }, "−"),
      slider,
      h("button", { class: "step", "aria-label": "Next step", onclick: () => { S.pct = Math.min(100, Levels.next(gains, losses, S.pct, true)); render(); } }, "+")));
    out.push(h("label", { class: "field inline" }, h("span", {}, S.kind === "PORTFOLIO" ? "Target value ₹" : "Target price ₹"),
      h("input", { type: "number", inputmode: "decimal", placeholder: "e.g. 1800", onchange: (e) => { const v = parseFloat(e.target.value); if (v > 0) { S.pct = Projector.pctForTarget(v, dream(0).basePrice); render(); } } })));
    out.push(h("div", { class: "chips" }, [...ladder].sort((a, b) => a - b).concat([50, 100]).map((l) => chip(l === 100 ? "2×" : Fmt.pct(l), Math.abs(l - S.pct) < 1e-9, () => { S.pct = l; render(); }, l < 0))));
    const maxAbs = Math.max(...ladder.map(Math.abs));
    const vsToday = S.kind !== "WATCH" && S.base === "LTP";
    out.push(h("table", { class: "ladder" },
      h("thead", {}, h("tr", {}, h("th", {}, "Move"), S.kind === "PORTFOLIO" ? null : h("th", {}, "Price"), h("th", {}, ""), h("th", { class: "r" }, vsToday ? "Value • vs today" : "Value • P&L"))),
      h("tbody", {}, ladder.map((l) => {
        const x = dream(l), g = vsToday ? x.vsNow : x.profit, w = Math.min(1, Math.abs(l) / maxAbs) * 50;
        return h("tr", { class: Math.abs(l - S.pct) < 1e-9 ? "sel" : "", onclick: () => { S.pct = l; render(); } },
          h("td", { class: cls(l) }, Fmt.pct(l)), S.kind === "PORTFOLIO" ? null : h("td", {}, Fmt.price(x.price)),
          h("td", { class: "barcell" }, h("span", { class: "bar " + cls(l), style: `width:${w}%;${l >= 0 ? "left:50%" : "right:50%"}` })),
          h("td", { class: "r" }, money(x.value), h("br"), h("small", { class: cls(g) }, sMoney(g))));
      }))));
    return out;
  }

  function vsFdTab() {
    const out = [];
    if (S.kind === "WATCH") {
      const amt = dream(0).cost, n = S.years;
      out.push(h("div", { class: "muted" }, `If ${S.quote.name} grows at`), h("div", { class: "chips" }, [8, 12, 15, 20, 25].map((r) => chip(r + "% / yr", r === S.cagr, () => { S.cagr = r; render(); }))));
      out.push(h("div", { class: "muted" }, "for"), h("div", { class: "chips" }, [1, 3, 5, 10, 20].map((y) => chip(y + " yr", y === n, () => { S.years = y; render(); }))));
      const mine = Dreams.compound(amt, S.cagr, n), fd = Dreams.compound(amt, S.r.fd, n);
      out.push(h("div", { class: "card" }, h("div", { class: "verdict " + (mine >= fd ? "pos" : "neg") }, `${rs(amt)} → ${rs(mine)} in ${n} yr`),
        h("div", { class: "muted" }, mine >= fd ? `${rs(mine - fd)} more than a ${Fmt.rate(S.r.fd)}% FD` : `An FD would win by ${rs(fd - mine)}`),
        benchTable(amt, n, mine, true)));
      return out;
    }
    const inv = S.kind === "HOLDING" ? holdingObj().qty * holdingObj().avg : S.portfolio.invested;
    const cur = S.kind === "HOLDING" ? holdingObj().qty * holdingObj().ltp : S.portfolio.current;
    out.push(h("div", { class: "muted" }, "How long have you held it?"), h("div", { class: "chips" }, [0.25, 0.5, 1, 2, 3, 5, 10].map((y) => chip(y < 1 ? Math.round(y * 12) + " mo" : y + " yr", y === S.held, () => { S.held = y; render(); }))));
    const fd = Dreams.compound(inv, S.r.fd, S.held), diff = cur - fd, cagr = Bench.cagr(inv, cur, S.held), real = Bench.real(cur, S.r.inflation, S.held);
    const emoji = S.tone === "SERIOUS" ? "" : diff >= 0 ? " 🎉" : " 😬";
    out.push(h("div", { class: "card" },
      h("div", { class: "verdict " + cls(diff) }, diff >= 0 ? `You beat a ${Fmt.rate(S.r.fd)}% FD by ${rs(diff)}${emoji}` : `A ${Fmt.rate(S.r.fd)}% FD would have given ${rs(-diff)} more${emoji}`),
      quipEl(Pulao.fdLine(diff >= 0, S.tone)),
      h("div", { class: "muted" }, `Your ${rs(inv)} → ${rs(cur)}` + (cagr != null ? ` • ${Fmt.pct1(cagr)} a year` : "")),
      h("div", { class: "muted" }, `In today's money (after ${Fmt.rate(S.r.inflation)}% inflation): ${rs(real)} (${sMoney(real - inv)} real gain)`),
      benchTable(inv, S.held, cur, false)));
    return out;
  }
  function benchTable(amt, years, mine, future) {
    return h("table", { class: "bench" }, h("thead", {}, h("tr", {}, h("th", {}, `Same ${rs(amt)} in`), h("th", { class: "r" }, "Would be"), h("th", { class: "r" }, "You vs it"))),
      h("tbody", {}, Bench.list(S.r).filter((b) => !(future && b.name === "Inflation")).map((b) => {
        const v = Dreams.compound(amt, b.rate, years), d = mine - v;
        return h("tr", {}, h("td", {}, `${b.emoji} ${b.name}`, h("br"), h("small", { class: "muted" }, `${Fmt.rate(b.rate)}% a year`)), h("td", { class: "r" }, money(v)), h("td", { class: "r " + cls(d) }, sMoney(d)));
      })));
  }

  function spendTab() {
    const d = dream(S.pct);
    const srcs = S.kind === "WATCH" ? [["DREAM", "Dream profit"], ["WHOLE", "Whole value"]] : [["TODAY", "Today's profit"], ["DREAM", "Dream profit"], ["WHOLE", "Whole value"]];
    if (!srcs.some(([k]) => k === S.spendSrc)) S.spendSrc = srcs[0][0];
    const [gain, sell] = S.spendSrc === "TODAY" ? [d.now - d.cost, d.now] : S.spendSrc === "DREAM" ? [d.profit, d.value] : [d.value, d.value];
    const isProfit = S.spendSrc !== "WHOLE";
    const net = isProfit && gain > 0 ? Costs.inHand(gain, sell, taxKind()) : gain;
    const ideas = [];
    const idea = (e, v, t, c = "") => ideas.push(h("div", { class: "idea" }, h("span", { class: "em" }, e), h("div", {}, h("div", { class: "iv " + c }, v), h("div", { class: "muted" }, t))));
    const q = Pulao.quip(gain, d.cost > 0 ? (gain / d.cost) * 100 : 0, S.tone);
    if (net <= 0) idea("—", "₹0", "Nothing to spend yet. Try Dream profit or a bigger Move %.");
    else {
      if (q) idea(S.tone === "MASALA" ? "🤭" : "💬", q, S.tone === "MASALA" ? "says the khayali pulao" : "a thought", "saffron");
      const m = S.r.expense;
      idea("🧾", rs((net * S.r.fd) / 100 / 12) + " / month", `interest if parked in a ${Fmt.rate(S.r.fd)}% FD (before tax on interest)`, "pos");
      idea("🛋️", net / m < 12 ? (net / m).toFixed(1) + " months" : Dreams.years(net / m / 12), `of your ₹${money(m)} / month expenses covered`);
      idea("🌴", rs((net * 0.04) / 12) + " / month", "for life with the 4% rule — spend 4% a year, let the rest grow");
      idea("📈", rs(Dreams.compound(net, S.cagr, S.years)), `if left to compound at ${S.cagr}% for ${S.years} years`);
      if (S.tone !== "SERIOUS") for (const it of [...Pulao.ITEMS].reverse()) { const n = Math.floor(net / it.price); if (n >= 1) idea(it.emoji, Fmt.amount(n), n === 1 ? it.one : it.many); }
    }
    return [h("div", { class: "seg" }, srcs.map(([k, l]) => h("button", { class: S.spendSrc === k ? "on" : "", onclick: () => { S.spendSrc = k; render(); } }, l))),
      S.spendSrc === "DREAM" ? h("div", { class: "muted" }, `Dream profit uses your Move % pick: ${Fmt.pct(S.pct)}`) : null,
      h("div", { class: "card" }, h("div", { class: "muted" }, "Amount"), h("div", { class: "big " + (isProfit ? cls(gain) : "") }, isProfit ? sMoney(gain) : rs(gain)),
        isProfit && gain > 0 ? h("div", { class: "muted" }, inHandLine(gain, sell).replace("≈ ", "")) : null, h("hr"), ideas)];
  }

  function goalTab() {
    const now = dream(0).now;
    if (S.goal == null) S.goal = Dreams.niceCeil(now * 2);
    const goal = S.goal, need = Dreams.pctToGoal(now, goal), mult = goal / now;
    const rows = goal <= now ? [] : [8, 12, 15, 20, 25].map((r) => { const y = Dreams.yearsToGoal(now, goal, r); return h("tr", {}, h("td", {}, r + "% a year"), h("td", { class: "r" }, y == null ? "—" : "~" + Dreams.years(y))); });
    const ltp = S.kind === "HOLDING" ? S.holding.ltp : S.kind === "WATCH" ? S.quote.ltp : null;
    return [h("label", { class: "field" }, h("span", {}, "My dream amount ₹"), h("input", { type: "number", value: goal, onchange: (e) => { const v = parseFloat(e.target.value); if (v > 0) { S.goal = v; render(); } } })),
      h("div", { class: "chips" }, Dreams.goalChips(now).map((g) => chip("₹" + Fmt.money(g, true), Math.abs(g - goal) < 0.5, () => { S.goal = g; render(); }))),
      h("div", { class: "card" }, h("div", { class: "muted" }, `To turn ${rs(now)} into ${rs(goal)}`),
        goal <= now ? h("div", { class: "big pos" }, "Already there 🎉") : h("div", { class: "big pos" }, Fmt.pct1(need)),
        goal <= now ? null : h("div", { class: "muted" }, ltp ? `LTP must reach ₹${Fmt.price(ltp * mult)} • ${mult.toFixed(2)}× from today` : `Your portfolio has to grow ${mult.toFixed(2)}×`),
        goal <= now || !fun(goal - now) ? null : h("div", { class: "saffron" }, fun(goal - now)),
        rows.length ? h("table", { class: "bench" }, h("tbody", {}, rows)) : null)];
  }

  function yearsTab() {
    const now = dream(0).now, cost = dream(0).cost, r = S.cagr, n = S.years, fv = Dreams.compound(now, r, n);
    const yLbl = h("b", { class: "blue" }, n + (n === 1 ? " year" : " years"));
    const fvEl = h("div", { class: "big" }, rs(fv));
    return [h("div", { class: "muted" }, "Yearly return"), h("div", { class: "chips" }, [8, 10, 12, 15, 18, 20, 25, 30].map((x) => chip(x + "%", x === r, () => { S.cagr = x; render(); }))),
      h("div", { class: "row" }, h("span", { class: "muted" }, "Years"), yLbl),
      h("input", { type: "range", min: 1, max: 30, step: 1, value: n, "aria-label": "Years",
        oninput: (e) => { S.years = parseInt(e.target.value, 10); yLbl.textContent = S.years + (S.years === 1 ? " year" : " years"); fvEl.textContent = rs(Dreams.compound(now, r, S.years)); },
        onchange: () => render(false) }),
      h("div", { class: "card" }, h("div", { class: "muted" }, `In ${n} ${n === 1 ? "year" : "years"} at ${r}% a year, it could be worth`), fvEl,
        h("div", { class: "pos" }, `${(fv / now).toFixed(2)}× • ${sMoney(fv - now)} vs today` + (S.kind !== "WATCH" ? ` • ${sMoney(fv - cost)} vs invested` : "")),
        h("div", { class: "muted" }, "Doubles roughly every " + Dreams.years(Dreams.doublingYears(r))),
        fun(fv - now) ? h("div", { class: "saffron" }, fun(fv - now)) : null,
        h("table", { class: "bench" }, h("tbody", {}, [1, 3, 5, 10, 15, 20, 25, 30].map((y) => { const v = Dreams.compound(now, r, y); return h("tr", { class: y === n ? "sel" : "", onclick: () => { S.years = y; render(); } }, h("td", {}, y + " yr"), h("td", { class: "pos" }, (v / now).toFixed(2) + "×"), h("td", { class: "r" }, money(v))); }))))];
  }

  function regretTab() {
    const ref = S.kind === "HOLDING" ? S.holding.avg : S.quote.ltp, ltp = S.kind === "HOLDING" ? S.holding.ltp : S.quote.ltp;
    if (S.regret == null) S.regret = Math.round(ref * 0.8 * 100) / 100;
    const past = S.regret;
    const qty = S.kind === "HOLDING" ? S.holding.qty : S.quote.mode === "AMOUNT" ? Math.floor(S.quote.amount / past) : S.quote.shares;
    const rg = Dreams.regret(qty, past, ltp), real = S.kind === "HOLDING" ? holdingObj().qty * (S.holding.ltp - S.holding.avg) : null;
    return [h("div", { class: "sub2" }, "Agar tab liya hota…"),
      h("label", { class: "field inline" }, h("span", {}, "I could have bought at ₹"), h("input", { type: "number", value: past, onchange: (e) => { const v = parseFloat(e.target.value); if (v > 0) { S.regret = v; render(); } } })),
      h("div", { class: "chips" }, [10, 20, 30, 50].map((o) => chip(`${Fmt.price(ref * (1 - o / 100))} (−${o}%)`, false, () => { S.regret = ref * (1 - o / 100); render(); }))),
      h("div", { class: "card" }, h("div", { class: "muted" }, `${Fmt.amount(qty)} shares at ₹${Fmt.price(past)}, valued at today's LTP ₹${Fmt.price(ltp)}`),
        h("div", { class: "pair" }, h("div", {}, h("div", { class: "muted" }, "Would have paid"), h("div", { class: "fig" }, rs(rg.paid))), h("div", { class: "r" }, h("div", { class: "muted" }, "Worth today"), h("div", { class: "fig" }, rs(rg.now)))),
        h("hr"), h("div", { class: "fig " + cls(rg.gain) }, `${sMoney(rg.gain)} (${Fmt.pct1(rg.gainPct)})`),
        real != null ? h("div", { class: "muted" }, `${sMoney(rg.gain - real)} more than your real P&L of ${sMoney(real)}`) : null,
        fun(real != null ? rg.gain - real : rg.gain) ? h("div", { class: "saffron" }, fun(real != null ? rg.gain - real : rg.gain)) : null)];
  }

  function render(rebuildInputs = true) {
    if (rebuildInputs) inputsPanel();
    const sheet = $("#play-sheet"); const y = sheet.scrollTop;
    sheet.innerHTML = "";
    const body = { MOVE: moveTab, VSFD: vsFdTab, SPEND: spendTab, GOAL: goalTab, YEARS: yearsTab, REGRET: regretTab }[S.tab]();
    sheet.append(h("div", { class: "sh-bar" }, h("img", { src: "media/icon.png", alt: "" }), h("b", {}, "Khayali Pulao")), header(), tabs(), h("div", { class: "sh-body" }, body),
      h("div", { class: "tiny c" }, "🔒 Calculated in your browser · not advice"));
    sheet.scrollTop = y;
  }

  // Re-render without destroying the range input mid-drag
  let raf = 0;
  const _render = render;
  render = function (rebuild = true) { cancelAnimationFrame(raf); raf = requestAnimationFrame(() => _render(rebuild)); };

  // Feature cards deep-link into the playground: <a data-try="GOAL">
  window.KhayaliPlay = {
    go(tab, kind) {
      if (kind) S.kind = kind;
      if (tab === "REGRET" && S.kind === "PORTFOLIO") S.kind = "HOLDING";
      S.tab = tab; _render(true);
      document.getElementById("try").scrollIntoView({ behavior: "smooth", block: "start" });
    },
  };
  document.addEventListener("click", (e) => {
    const a = e.target.closest("[data-try]");
    if (a) { e.preventDefault(); window.KhayaliPlay.go(a.dataset.try, a.dataset.kind); }
  });
  document.addEventListener("DOMContentLoaded", () => { if ($("#play-sheet")) _render(true); });
})();
