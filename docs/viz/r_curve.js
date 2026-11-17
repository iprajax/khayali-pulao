// one or two lines over a numeric x axis — data: { xLabel, yLabel, yFmt: "pct" | "num" | "price", zero?: true,
//   series: [{ name, color?: "blue" | "saffron" | …, points: [[x, y], …] }], markers?: [{ x, label, color? }] }  (marker = optional vertical line)
R.curve = function (d, h) {
  const series = (d.series || []).map((sr, i) => ({ name: sr.name || "", col: C[sr.color] || [C.blue, C.saffron, C.green, C.violet][i % 4],
    pts: (sr.points || []).map(p => ({ t: +p[0], v: +p[1] })).filter(p => isFinite(p.t) && isFinite(p.v)).sort((a, b) => a.t - b.t) })).filter(sr => sr.pts.length);
  const s = svg(h), w = W(), all = series.flatMap(sr => sr.pts);
  if (all.length < 2) { s.append("text").attr("class", "lbl").attr("x", w / 2).attr("y", h / 2).attr("text-anchor", "middle").text("Not enough data to draw"); return; }
  const marks = (d.markers || (d.marker ? [d.marker] : [])).filter(m => m && isFinite(m.x));
  const cmp = v => { const a = Math.abs(v), f = (x, u) => x.toFixed(x < 100 && x % 1 ? 1 : 0).replace(/\.0$/, "") + u;
    return (v < 0 ? "−" : "") + (a >= 1e7 ? f(a / 1e7, "Cr") : a >= 1e5 ? f(a / 1e5, "L") : a >= 1e3 ? f(a / 1e3, "K") : f(a, "")); };
  const yf = d.yFmt === "pct" ? v => (+v.toFixed(1)) + "%" : d.yFmt === "price" ? v => "₹" + cmp(v) : cmp;
  const yFull = d.yFmt === "pct" ? v => v.toFixed(2) + "%" : d.yFmt === "price" ? v => "₹" + v.toLocaleString("en-IN", { maximumFractionDigits: 2 }) : v => Math.round(v).toLocaleString("en-IN");
  const xf = v => (+v).toLocaleString("en-IN", { maximumFractionDigits: 2 });
  const multi = series.length > 1, top = M.t + (multi ? 22 : 8) + (marks.length ? 12 : 0);
  let [x0, x1] = d3.extent(all, p => p.t); marks.forEach(m => { x0 = Math.min(x0, m.x); x1 = Math.max(x1, m.x); });
  const [y0, y1] = d3.extent(all, p => p.v), padY = (y1 - y0) * 0.08 || Math.abs(y1) * 0.05 || 1;
  const x = d3.scaleLinear().domain([x0, x1]).range([M.l, w - M.r]);
  const y = d3.scaleLinear().domain([d.zero ? Math.min(0, y0) : Math.max(y0 >= 0 ? 0 : -Infinity, y0 - padY), y1 + padY]).nice(4).range([h - M.b, top]);
  axes(s, x, y, h, { yFmt: yf, yLabel: d.yLabel || "Value", xLabel: d.xLabel || "", xTicks: Math.max(2, Math.min(5, Math.floor((w - M.l) / 70))), xFmt: xf });
  // vertical markers, labels staggered so two close ones stay readable
  marks.slice().sort((a, b) => a.x - b.x).forEach((m, i) => { const mx = x(m.x), col = C[m.color] || C.text, right = mx > w - 90;
    s.append("line").attr("x1", mx).attr("x2", mx).attr("y1", top - 4).attr("y2", h - M.b).attr("stroke", col).attr("stroke-width", 1.5).attr("stroke-dasharray", "4 3");
    s.append("text").attr("class", "tag").attr("fill", col).attr("x", mx + (right ? -4 : 4)).attr("y", top - 8 + (i % 2) * 11 - (marks.length > 1 ? 6 : 0)).attr("text-anchor", right ? "end" : "start").text(`${m.label || ""} ${xf(m.x)}`.trim()); });
  series.forEach(sr => { drawLine(s, sr.pts, x, y, p => p.v, sr.col);
    if (sr.pts.length <= 30) s.selectAll(null).data(sr.pts).join("circle").attr("cx", p => x(p.t)).attr("cy", p => y(p.v)).attr("r", 0).attr("fill", sr.col).style("pointer-events", "none").transition().duration(300).delay((p, i) => 300 + i * 20).attr("r", 2.2); });
  if (multi) legend(s, series.map(sr => [sr.name, sr.col, false]), M.t + 8);
  // tap anywhere: read every line at the nearest x
  const xs = Array.from(new Set(all.map(p => p.t))).sort((a, b) => a - b), cur = s.append("line").attr("y1", top).attr("y2", h - M.b).attr("stroke", C.muted).attr("stroke-width", 1).attr("opacity", 0).style("pointer-events", "none");
  const dots = s.append("g").style("pointer-events", "none");
  s.append("rect").attr("x", M.l).attr("y", top).attr("width", w - M.l - M.r).attr("height", h - M.b - top).attr("fill", "transparent").on("click", e => {
    const mx = x.invert(d3.pointer(e)[0]), k = xs.reduce((a, b) => Math.abs(b - mx) < Math.abs(a - mx) ? b : a, xs[0]);
    const hit = series.map(sr => ({ sr, p: sr.pts.find(q => q.t === k) })).filter(q => q.p);
    cur.attr("x1", x(k)).attr("x2", x(k)).attr("opacity", .7);
    dots.selectAll("circle").data(hit).join("circle").attr("cx", x(k)).attr("cy", q => y(q.p.v)).attr("r", 4).attr("fill", q => q.sr.col).attr("stroke", "#1E2A38").attr("stroke-width", 1.5);
    showTip(`<b>${xf(k)}</b> · ` + hit.map(q => `${multi ? q.sr.name + " " : ""}${yFull(q.p.v)}`).join(" · "), Math.max(4, Math.min(e.pageX - 110, w - 256)), e.pageY); });
};
