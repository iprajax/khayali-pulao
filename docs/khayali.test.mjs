// Cross-checks the website calculator against the Android app's unit-test values.
// Run: node docs/khayali.test.mjs
import { createRequire } from "node:module";
import assert from "node:assert/strict";
const require = createRequire(import.meta.url);
const K = require("./khayali.js");
const { Fmt, Levels, Projector, Dreams, Bench, Costs, Pulao } = K;

const near = (a, b, eps = 1e-6) => assert.ok(Math.abs(a - b) <= eps, `${a} != ${b}`);
let n = 0; const t = (name, fn) => { fn(); n++; };

const infra = { qty: 250, avg: 1200, invested: 300000, ltp: 1450 };

t("format", () => {
  assert.equal(Fmt.amount(12345678), "1,23,45,678");
  assert.equal(Fmt.amount(999), "999");
  assert.equal(Fmt.signed(654321.4), "+6,54,321");
  assert.equal(Fmt.signed(-5000), "−5,000");
  assert.equal(Fmt.price(1450.5), "1,450.50");
  assert.equal(Fmt.money(512000, true), "5.12 L");
  assert.equal(Fmt.money(500000, true), "5 L");
  assert.equal(Fmt.money(12345678, true), "1.23 Cr");
  assert.equal(Fmt.signedMoney(-12345678, true), "−1.23 Cr");
  assert.equal(Fmt.pct(-2.5), "−2.5%");
  assert.equal(Fmt.pct1(16.16), "+16.2%");
});
t("projection", () => {
  const a = Projector.holding(infra, "AVG", 10); near(a.price, 1320); near(a.profit, 30000);
  const l = Projector.holding(infra, "LTP", 10); near(l.price, 1595); near(l.vsNow, 36250); near(l.profit, 98750);
  const q = Projector.quote({ ltp: 205.29 }, "AMOUNT", 10000, 10); assert.equal(q.qty, 48); near(q.profitPct, 10);
  near(Projector.pctForTarget(1500, 1200), 25);
  const p = Projector.portfolio({ invested: 300000, current: 362500 }, "LTP", 10); near(p.value, 398750); near(p.vsNow, 36250);
});
t("levels", () => {
  assert.deepEqual(Levels.parse("1, 3,abc; 2.5 -5, 3, 0, 5%"), [1, 2.5, 3, 5]);
  const g = Levels.parse(K.DEFAULT_GAINS), l = Levels.parse(K.DEFAULT_LOSSES);
  assert.equal(Levels.next(g, l, 5, true), 7); assert.equal(Levels.next(g, l, 11.3, true), 12);
  assert.equal(Levels.next(g, l, 30, true), 35); assert.equal(Levels.next(g, l, -20, false), -25);
});
t("dreams", () => {
  near(Dreams.yearsToGoal(100, 200, 12), 6.116, 0.001);
  assert.deepEqual(Dreams.goalChips(12345678), [2e7, 2.5e7, 4e7, 7.5e7, 1.5e8]);
  near(Dreams.regret(250, 960, 1450).gainPct, 51.04, 0.01);
  assert.equal(Dreams.years(6.116), "6.1 years");
});
t("bench & costs", () => {
  near(Bench.cagr(300000, 362500, 1), 20.83, 0.01);
  near(Costs.sellCharges(1000000), 1000 + 29.7 + 1 + 5.526 + 15.34, 0.01);
  near(Costs.tax(200000, "LTCG"), 9375);
  assert.equal(Costs.kindFor(0.5, "LTCG"), "STCG");
});
t("pulao", () => {
  assert.equal(Pulao.describe(15), "≈ 1 cutting chai ☕");
  assert.equal(Pulao.describe(1500000), "≈ 1 plot in Jhumri Telaiya 🏡  ·  1,00,000 cutting chais ☕");
  assert.equal(Pulao.describe(3000), "≈ 2 Ballia → Sealdah tickets on Bhrigu Express 🚆  ·  200 cutting chais ☕");
  assert.equal(Pulao.quip(500000, 16, "SERIOUS"), null);
  assert.ok(Pulao.quip(500000, 16, "MASALA").includes("Ballia"));
});
console.log(`khayali.js: ${n} groups passed`);
