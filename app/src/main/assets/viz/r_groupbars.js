// grouped vertical bars — data: { unit, series: ["Sales","Net profit"], items: [{ label, values: [a, b] }] }
R.groupbars = function (d, h) {
  const items = d.items || [], series = d.series || [], s = svg(h), cols = [C.blue, C.green, C.saffron, C.violet];
  if (!items.length) return;
  const all = items.flatMap(i => i.values), lo = Math.min(0, d3.min(all)), hi = Math.max(0, d3.max(all));
  const x0 = d3.scaleBand().domain(items.map(i => i.label)).range([M.l, W() - M.r]).padding(0.22);
  const x1 = d3.scaleBand().domain(d3.range(series.length)).range([0, x0.bandwidth()]).padding(0.08);
  const y = d3.scaleLinear().domain([lo, hi]).nice().range([h - M.b, M.t + 14]);
  const money = (d.unit || "").indexOf("₹") >= 0, yf = v => (money ? "₹" : "") + (Math.abs(v) >= 1e5 ? (v / 1e5).toFixed(1).replace(/\.0$/, "") + "L" : Math.abs(v) >= 1000 ? (v / 1000).toFixed(v % 1000 ? 1 : 0).replace(/\.0$/, "") + "K" : v);
  axes(s, x0, y, h, { yFmt: yf, yLabel: d.unit ? `Amount (${d.unit})` : "Value", xLabel: d.xLabel || "Period" });
  const g = s.selectAll("g.i").data(items).join("g").attr("class", "i").attr("transform", i => `translate(${x0(i.label)},0)`);
  g.selectAll("rect").data(i => i.values.map((v, k) => ({ v, k, label: i.label }))).join("rect").attr("x", p => x1(p.k)).attr("width", x1.bandwidth()).attr("rx", 3)
    .attr("fill", p => cols[p.k % cols.length]).attr("y", y(0)).attr("height", 0)
    .on("click", (e, p) => showTip(`${p.label} · ${series[p.k]} <b>${money ? "₹" : ""}${Math.round(p.v).toLocaleString("en-IN")}${d.unit ? " " + d.unit.replace("₹", "").trim() : ""}</b>`, e.pageX, e.pageY))
    .transition().duration(T).delay((p, i) => i * 50).attr("y", p => y(Math.max(0, p.v))).attr("height", p => Math.max(1, Math.abs(y(p.v) - y(0))));
  legend(s, series.map((k, i) => [k, cols[i % cols.length], false]), M.t + 6);
};
