// one or more lines of a balance over time, with an optional level marker — data:
//   { x: "year" | "date", unit: "inr" | "nav" | "num", xLabel, yLabel,
//     series: [{ name, color?: palette name, dashed?: bool, points: [[x, y], …] }],     x = years (number) or "YYYY-MM-DD"
//     marker?: { value, label },        a horizontal level: zero, a target, a starting amount
//     marks?: [{ x, label }] }          vertical moments, e.g. the retirement year
R.balance = function (d, h) {
  const isDate = d.x === "date", unit = d.unit || "inr", s = svg(h), palette = [C.blue, C.saffron, C.green, C.violet];
  const series = (d.series || []).map((q, i) => ({ name: q.name || "", dashed: !!q.dashed, col: C[q.color] || q.color || palette[i % 4],
    pts: (q.points || []).map(p => ({ t: isDate ? new Date(p[0]) : +p[0], v: +p[1] })).filter(p => !isNaN(+p.t) && !isNaN(p.v)).sort((a, b) => a.t - b.t) })).filter(q => q.pts.length >= 2);
  if (!series.length) { s.append("text").attr("class", "lbl").attr("x", W() / 2).attr("y", h / 2).attr("text-anchor", "middle").text("Not enough data to draw"); return; }
  // legend: one row when the names fit, otherwise one row per series (long fund names), each cut to the chart width
  const many = series.length > 1, room = W() - M.l - M.r, lg = s.append("g");
  const lw = many ? series.map(q => { const t = lg.append("text").attr("class", "lbl").text(q.name); let n = q.name;
    while (t.node().getComputedTextLength() > room - 20 && n.length > 4) { n = n.slice(0, -2); t.text(n + "…"); } q.label = t.text(); const w = t.node().getComputedTextLength(); t.remove(); return w + 26; }) : [];
  const rowsL = many && d3.sum(lw) > room ? series.length : 1;
  const top = M.t + (many ? 16 + (rowsL - 1) * 14 : 4), all = series.flatMap(q => q.pts), mk = d.marker && !isNaN(+d.marker.value) ? +d.marker.value : null;
  let lo = d3.min(all, p => p.v), hi = d3.max(all, p => p.v);
  if (mk != null) { lo = Math.min(lo, mk); hi = Math.max(hi, mk); }
  if (unit === "inr" && lo > 0) lo = 0;
  if (hi === lo) hi = lo + 1;
  const x = (isDate ? d3.scaleTime() : d3.scaleLinear()).domain(d3.extent(all, p => p.t)).range([M.l, W() - M.r]);
  const y = d3.scaleLinear().domain([lo, hi]).nice().range([h - M.b, top]);
  const num = v => Math.abs(v) >= 1000 ? Math.round(v).toLocaleString("en-IN") : (+v).toFixed(Math.abs(v) < 100 && v % 1 ? 1 : 0);
  const yFmt = unit === "inr" ? ax : unit === "nav" ? px : num;
  const val = v => unit === "inr" ? short(v) : unit === "nav" ? "₹" + (+v).toFixed(2) : (+v).toFixed(1);
  const xTicks = Math.max(2, Math.floor((W() - M.l - M.r) / 62)), o = { yFmt, yLabel: d.yLabel || "Balance (₹)", xLabel: d.xLabel || (isDate ? "Date" : "Years"), xTicks };
  if (isDate) o.xFmt = dateFmt(series[0].pts); else { o.xFmt = v => v; const a = x.domain()[0], b = x.domain()[1], every = [1, 2, 5, 10, 20, 25, 50].find(k => (b - a) / k <= xTicks) || 100;
    if (b - a >= 2) o.xValues = d3.range(Math.ceil(a), Math.floor(b) + 1).filter(v => (v - Math.ceil(a)) % every === 0); }
  axes(s, x, y, h, o);
  if (!many) { const q = series[0], grad = s.append("defs").append("linearGradient").attr("id", "bal-g").attr("x1", 0).attr("x2", 0).attr("y1", 0).attr("y2", 1);
    grad.append("stop").attr("offset", 0).attr("stop-color", q.col).attr("stop-opacity", 0.3); grad.append("stop").attr("offset", 1).attr("stop-color", q.col).attr("stop-opacity", 0);
    s.append("path").datum(q.pts).attr("fill", "url(#bal-g)").style("opacity", 0).attr("d", d3.area().x(p => x(p.t)).y0(y(Math.max(lo, y.domain()[0]))).y1(p => y(p.v)).curve(d3.curveMonotoneX)).transition().duration(T).delay(300).style("opacity", 1); }
  (d.marks || []).forEach(m => { const mx = x(isDate ? new Date(m.x) : +m.x); if (isNaN(mx) || mx < M.l || mx > W() - M.r) return;
    s.append("line").attr("x1", mx).attr("x2", mx).attr("y1", top).attr("y2", h - M.b).attr("stroke", C.muted).attr("stroke-dasharray", "3 3");
    s.append("text").attr("class", "tag").attr("fill", C.muted).attr("x", mx + (mx > W() - 70 ? -5 : 5)).attr("y", h - M.b - 6).attr("text-anchor", mx > W() - 70 ? "end" : "start").text(m.label || ""); });
  if (mk != null) { const my = y(mk);
    s.append("line").attr("x1", M.l).attr("x2", W() - M.r).attr("y1", my).attr("y2", my).attr("stroke", C.text).attr("stroke-opacity", .7).attr("stroke-dasharray", "4 4");
    if (d.marker.label) { // put the label on the side of the level, and above or below it, where the fewest lines pass
      const t = s.append("text").attr("class", "tag").attr("fill", C.text).text(d.marker.label), w = Math.min(t.node().getComputedTextLength(), room);
      const yAt = (q, X) => { const i = d3.bisector(p => x(p.t)).left(q.pts, X); if (i <= 0 || i >= q.pts.length) return null; const a = q.pts[i - 1], b = q.pts[i], f = (X - x(a.t)) / ((x(b.t) - x(a.t)) || 1); return y(a.v) + (y(b.v) - y(a.v)) * f; };
      const spots = [[1, 1], [0, 1], [1, 0], [0, 0]].map(([right, up]) => { const bx = right ? W() - M.r - w : M.l + 4, by = up ? my - 17 : my + 4;
        let hit = by < top - 2 || by + 13 > h - M.b ? 100 : 0; d3.range(0, 13).forEach(k => series.forEach(q => { const v = yAt(q, bx + w * k / 12); if (v != null && v > by - 4 && v < by + 17) hit++; }));
        (d.marks || []).forEach(m => { const mx = x(isDate ? new Date(m.x) : +m.x); if (mx > bx - 4 && mx < bx + w + 4) hit += 2; });
        return { right, up, hit }; }).sort((p, q) => p.hit - q.hit)[0];
      t.attr("x", spots.right ? W() - M.r : M.l + 4).attr("y", spots.up ? my - 6 : my + 14).attr("text-anchor", spots.right ? "end" : "start"); } }
  series.forEach(q => drawLine(s, q.pts, x, y, p => p.v, q.col, q.dashed));
  if (many) { let lx = M.l; series.forEach((q, i) => { const ly = M.t + 8 + (rowsL > 1 ? i * 14 : 0); if (rowsL > 1) lx = M.l;
    lg.append("line").attr("x1", lx).attr("x2", lx + 14).attr("y1", ly - 3).attr("y2", ly - 3).attr("stroke", q.col).attr("stroke-width", 2).attr("stroke-dasharray", q.dashed ? "3 3" : null);
    lg.append("text").attr("class", "lbl").attr("x", lx + 18).attr("y", ly).text(q.label); lx += lw[i]; }); }
  series.forEach(q => { const e = q.pts[q.pts.length - 1]; s.append("circle").attr("cx", x(e.t)).attr("cy", y(e.v)).attr("r", 3).attr("fill", q.col); });
  const rule = s.append("line").attr("y1", top).attr("y2", h - M.b).attr("stroke", C.muted).attr("stroke-dasharray", "2 3").style("opacity", 0);
  const dots = series.map(q => s.append("circle").attr("r", 4).attr("fill", q.col).attr("stroke", "#fff").attr("stroke-width", 1).style("opacity", 0));
  const xText = t => isDate ? d3.timeFormat("%d %b %Y")(t) : "Year " + (Math.round(t * 10) / 10);
  s.append("rect").attr("width", W()).attr("height", h).attr("fill", "transparent").on("pointermove pointerdown", e => {
    const t = x.invert(d3.pointer(e)[0]), b = d3.bisector(p => p.t), p0 = series[0].pts[b.center(series[0].pts, t)];
    const hits = series.map(q => q.pts[b.center(q.pts, p0.t)]);
    rule.attr("x1", x(p0.t)).attr("x2", x(p0.t)).style("opacity", 1);
    hits.forEach((p, i) => dots[i].attr("cx", x(p.t)).attr("cy", y(p.v)).style("opacity", 1));
    const body = many ? series.map((q, i) => `<span style="color:${q.col}">●</span> ${q.name.length > 22 ? q.name.slice(0, 21) + "…" : q.name} <b>${val(hits[i].v)}</b>`).join("<br>") : `<b>${val(hits[0].v)}</b>`;
    showTip(xText(p0.t) + (many ? "<br>" : " · ") + body, Math.max(0, Math.min(x(p0.t), W() - (many ? 225 : 170))), Math.min(...hits.map(p => y(p.v))) - (many ? 14 * series.length : 0));
  }).on("pointerleave", () => { rule.style("opacity", 0); dots.forEach(c => c.style("opacity", 0)); hideTip(); });
};
