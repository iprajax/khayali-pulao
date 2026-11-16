/*
 * Khayali Pulao — the maths, ported 1:1 from the Android app
 * (app/src/main/kotlin/com/khayali/pulao/{Stock,Projection,Dreams}.kt).
 * Pure functions only; tested in docs/khayali.test.mjs against the Kotlin test values.
 * Runs entirely in your browser — nothing is sent anywhere.
 */
(function (root) {
  "use strict";

  // ---------- Indian number formatting (1,23,45,678) ----------
  function group(intPart) {
    if (intPart.length <= 3) return intPart;
    const last3 = intPart.slice(-3);
    const rest = intPart.slice(0, -3).split("").reverse().join("")
      .match(/.{1,2}/g).join(",").split("").reverse().join("");
    return rest + "," + last3;
  }
  function format(v, decimals) {
    const s = Math.abs(v).toFixed(decimals);
    const [i, f] = s.split(".");
    const neg = v < 0 && /[1-9]/.test(s);
    return (neg ? "-" : "") + group(i) + (decimals > 0 ? "." + f : "");
  }
  const sign = (v) => (v < 0 ? "−" : "+");
  const Fmt = {
    price: (v) => format(v, 2),
    amount: (v) => format(v, 0),
    signed: (v) => sign(v) + format(Math.abs(v), 0),
    money(v, compact) {
      const a = Math.abs(v);
      if (!compact || a < 1e5) return format(v, 0);
      const s = (a >= 1e7 ? (a / 1e7).toFixed(2) + " Cr" : (a / 1e5).toFixed(2) + " L").replace(".00 ", " ");
      return (v < 0 ? "-" : "") + s;
    },
    signedMoney: (v, compact) => sign(v) + Fmt.money(Math.abs(v), compact),
    pct(p) {
      const a = Math.abs(p);
      const body = a % 1 === 0 ? String(a) : a.toFixed(1);
      return (p < 0 ? "−" : p > 0 ? "+" : "") + body + "%";
    },
    pct1: (p) => sign(p) + Math.abs(p).toFixed(1) + "%",
    rate: (v) => v.toFixed(2).replace(/0+$/, "").replace(/\.$/, ""),
  };

  // ---------- Levels ----------
  const DEFAULT_GAINS = "0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4, 4.5, 5, 7, 10, 12, 15, 20, 25, 30";
  const DEFAULT_LOSSES = "0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4, 4.5, 5, 7, 10, 12, 15, 20";
  const Levels = {
    parse(text) {
      const out = [];
      for (const t of String(text).split(/[,\s;\n]+/)) {
        const n = parseFloat(t.replace("%", ""));
        if (!isNaN(n) && /^-?\d*\.?\d+%?$/.test(t.trim())) {
          const a = Math.abs(n);
          if (a > 0 && a <= 1000 && !out.includes(a)) out.push(a);
        }
      }
      return out.sort((a, b) => a - b);
    },
    ladder(gains, losses, showLosses) {
      const g = gains.length ? gains : Levels.parse(DEFAULT_GAINS);
      return [...g].sort((a, b) => b - a).concat(showLosses ? [...losses].sort((a, b) => a - b).map((x) => -x) : []);
    },
    next(gains, losses, pct, up) {
      const stops = [...new Set([...gains, ...losses.map((x) => -x), 0])].sort((a, b) => a - b);
      const eps = 1e-9;
      if (up) { const f = stops.find((s) => s > pct + eps); return f !== undefined ? f : pct + 5; }
      const l = [...stops].reverse().find((s) => s < pct - eps); return l !== undefined ? l : pct - 5;
    },
  };

  // ---------- Projection ----------
  function dream(pct, basePrice, price, qty, cost, now, value) {
    const profit = value - cost;
    return {
      pct, basePrice, price, qty, cost, now, value, profit,
      vsNow: value - now,
      profitPct: cost !== 0 ? (profit / cost) * 100 : 0,
    };
  }
  const Projector = {
    holding(h, base, pct) { // h: {qty, avg, invested, ltp}
      const bp = base === "AVG" ? h.avg : h.ltp;
      const price = bp * (1 + pct / 100);
      return dream(pct, bp, price, h.qty, h.invested, h.qty * h.ltp, h.qty * price);
    },
    quote(q, mode, input, pct) { // q: {ltp}
      const qty = mode === "SHARES" ? input : q.ltp > 0 ? Math.floor(input / q.ltp) : 0;
      const cost = qty * q.ltp;
      const price = q.ltp * (1 + pct / 100);
      return dream(pct, q.ltp, price, qty, cost, cost, qty * price);
    },
    portfolio(p, base, pct) { // p: {invested, current}
      const bv = base === "AVG" ? p.invested : p.current;
      const v = bv * (1 + pct / 100);
      return dream(pct, bv, v, 1, p.invested, p.current, v);
    },
    pctForTarget: (target, basePrice) => (basePrice > 0 ? (target / basePrice - 1) * 100 : 0),
  };

  // ---------- Dreams ----------
  const Dreams = {
    yearsToGoal(now, goal, rate) {
      if (now <= 0) return null;
      if (goal <= now) return 0;
      if (rate <= 0) return null;
      return Math.log(goal / now) / Math.log(1 + rate / 100);
    },
    compound: (now, rate, years) => now * Math.pow(1 + rate / 100, years),
    doublingYears: (rate) => (rate <= 0 ? null : Math.log(2) / Math.log(1 + rate / 100)),
    pctToGoal: (now, goal) => (now > 0 ? (goal / now - 1) * 100 : 0),
    niceCeil(v) {
      if (v <= 0) return 0;
      const mag = Math.pow(10, Math.floor(Math.log10(v)));
      for (const m of [1, 1.5, 2, 2.5, 3, 4, 5, 7.5, 10]) if (m * mag >= v - 1e-6) return m * mag;
      return 10 * mag;
    },
    goalChips(now) {
      const out = [];
      for (const m of [1.25, 1.5, 2, 3, 5, 10]) { const c = Dreams.niceCeil(now * m); if (c > now && !out.includes(c)) out.push(c); }
      return out;
    },
    regret(qty, past, ltp) {
      const paid = qty * past, now = qty * ltp, gain = now - paid;
      return { qty, paid, now, gain, gainPct: paid > 0 ? (gain / paid) * 100 : 0 };
    },
    years(v) {
      if (v < 1 / 12) return "under a month";
      if (v < 1) return Math.ceil(v * 12) + " months";
      return v.toFixed(1).replace(/\.0$/, "") + " years";
    },
  };

  const Bench = {
    list: (r) => [
      { name: "Savings account", emoji: "🏦", rate: r.savings },
      { name: "Bank FD", emoji: "🧾", rate: r.fd },
      { name: "PPF", emoji: "📮", rate: r.ppf },
      { name: "Gold", emoji: "🪙", rate: r.gold },
      { name: "Nifty 50", emoji: "📈", rate: r.nifty },
      { name: "Inflation", emoji: "🛒", rate: r.inflation },
    ],
    cagr: (start, end, years) => (start <= 0 || end <= 0 || years <= 0 ? null : (Math.pow(end / start, 1 / years) - 1) * 100),
    real: (value, inflation, years) => value / Math.pow(1 + inflation / 100, years),
  };

  const Costs = {
    LTCG_EXEMPT: 125000,
    sellCharges(sell) {
      if (sell <= 0) return 0;
      const stt = sell * 0.001, txn = sell * 0.0000297, sebi = (sell * 10) / 1e7, gst = (txn + sebi) * 0.18;
      return stt + txn + sebi + gst + 15.34;
    },
    tax(gain, kind) {
      if (gain <= 0) return 0;
      if (kind === "LTCG") return Math.max(0, gain - Costs.LTCG_EXEMPT) * 0.125;
      if (kind === "STCG") return gain * 0.2;
      return gain * 0.3;
    },
    inHand(gain, sell, kind) { const taxable = gain - Costs.sellCharges(sell); return taxable - Costs.tax(taxable, kind); },
    kindFor: (heldYears, fallback) => (heldYears == null ? fallback : heldYears >= 1 ? "LTCG" : "STCG"),
  };

  // ---------- Pulao maths & tone ----------
  const ITEMS = [
    { one: "cutting chai", many: "cutting chais", emoji: "☕", price: 15 },
    { one: "plate of biryani", many: "plates of biryani", emoji: "🍛", price: 300 },
    { one: "Ballia → Sealdah ticket on Bhrigu Express", many: "Ballia → Sealdah tickets on Bhrigu Express", emoji: "🚆", price: 1200 },
    { one: "family trip to Ballia on Pawan Express (6 people, 3AC)", many: "family trips to Ballia on Pawan Express (6 people, 3AC)", emoji: "🚂", price: 18000 },
    { one: "bhains for the village", many: "bhainses for the village", emoji: "🐃", price: 90000 },
    { one: "plot in Ballia", many: "plots in Ballia", emoji: "🌾", price: 600000 },
    { one: "plot in Jhumri Telaiya", many: "plots in Jhumri Telaiya", emoji: "🏡", price: 1200000 },
    { one: "big fat Indian wedding", many: "big fat Indian weddings", emoji: "💍", price: 5000000 },
    { one: "Mumbai 1BHK (Virar side)", many: "Mumbai 1BHKs (Virar side)", emoji: "🏢", price: 12000000 },
    { one: "private island (khayali)", many: "private islands (khayali)", emoji: "🏝️", price: 500000000 },
  ];
  const Pulao = {
    ITEMS,
    items: (custom) => [...ITEMS, ...(custom ? [custom] : [])].sort((a, b) => a.price - b.price),
    describe(amount, custom) {
      const a = Math.abs(amount), all = Pulao.items(custom), base = all[0];
      if (a < base.price) return null;
      const fits = all.slice(1).filter((i) => i.price <= a);
      const big = fits.length ? fits.reduce((x, y) => (y.price > x.price ? y : x)) : null;
      const count = (n, it) => Fmt.amount(n) + " " + (n === 1 ? it.one : it.many);
      const parts = [];
      if (big) parts.push(count(Math.floor(a / big.price), big) + " " + big.emoji);
      parts.push(count(Math.floor(a / base.price), base) + " " + base.emoji);
      return "≈ " + parts.join("  ·  ") + (amount < 0 ? " less" : "");
    },
    light(gain, pct) {
      if (gain < 0 && pct <= -20) return "A deep dip — but it's a paper loss until you sell.";
      if (gain < 0) return "A dip, not a verdict. Prices move both ways.";
      if (gain < 500) return "A small start. Small gains compound.";
      if (pct < 5) return "Steady — still close to fixed-deposit territory.";
      if (pct < 15) return "Solid — ahead of most savings options.";
      if (pct < 30) return "A strong move. Good time to revisit your plan, calmly.";
      if (pct < 100) return "An excellent run. This is where a written plan helps most.";
      return "Doubled — remember, it's notional until you sell.";
    },
    masala(gain, pct) {
      if (gain < 0 && pct <= -20) return "Notional loss hai, dil pe mat lo. Dil bhi notional hai. 💔";
      if (gain < 0) return "Relax — it's only a loss if you sell. Or open Kite. 🙈";
      if (gain < 500) return "Itne mein ek cutting chai aur do Parle-G. Party kab hai? ☕";
      if (pct < 5) return "FD wale uncle abhi bhi has rahe hain. 👴";
      if (pct < 15) return "Sharma ji ke bete se thoda kam, par chalega. 📉📈";
      if (pct < 30) return "Ab Ballia mein plot ki baat shuru karo. 🌾";
      if (pct < 100) return "Rishtedaar ab tips maangne aayenge. Phone silent pe rakho. 📵";
      return "Paisa double! Warren Buffett is taking notes. (He isn't.) 🧓";
    },
    quip(gain, pct, tone) {
      if (tone === "SERIOUS") return null;
      return tone === "MASALA" ? Pulao.masala(gain, pct) : Pulao.light(gain, pct);
    },
    fdLine(beat, tone) {
      if (tone === "SERIOUS") return null;
      if (tone === "LIGHT") return beat ? "Ahead of a fixed deposit — the extra risk has paid off so far."
        : "A fixed deposit would have done better over this period. Worth knowing.";
      return beat ? "FD wale uncle chupchaap apni passbook dekh rahe hain. 👴📒" : "FD wale uncle: \"Maine kaha tha na?\" 👴☝️";
    },
  };

  const api = { Fmt, Levels, Projector, Dreams, Bench, Costs, Pulao, DEFAULT_GAINS, DEFAULT_LOSSES };
  if (typeof module !== "undefined" && module.exports) module.exports = api; else root.Khayali = api;
})(typeof window !== "undefined" ? window : globalThis);
