// two stacked areas over years — data: { series: ["Invested","Growth"], colors?: ["blue","green"], step?: bool, xLabel, yLabel,
//   points: [{ x: years, a: lower band, b: upper band }] }
// step = true draws each point as a flat block covering the year that ends at x (per-year amounts, e.g. a loan's principal and interest).
R.stackarea = function (d, h) {
  const pts = (d.points || []).map(p => ({ x: +p.x, a: Math.max(0, +p.a || 0), b: Math.max(0, +p.b || 0) })).filter(p => !isNaN(p.x)).sort((p, q) => p.x - q.x), s = svg(h);
  if (!pts.length || !d3.max(pts, p => p.a + p.b)) { s.append("text").attr("class", "lbl").attr("x", W() / 2).attr("y", h / 2).attr("text-anchor", "middle").text("Nothing to plot"); return; }
  const names = d.series || ["A", "B"], cols = (d.colors || ["blue", "green"]).map(c => C[c] || c), step = !!d.step;
  const x0 = step ? pts[0].x - 1 : pts[0].x, x1 = pts[pts.length - 1].x > x0 ? pts[pts.length - 1].x : x0 + 1, top = M.t + 16;
  const x = d3.scaleLinear().domain([x0, x1]).range([M.l, W() - M.r]);
  const y = d3.scaleLinear().domain([0, d3.max(pts, p => p.a + p.b)]).nice().range([h - M.b, top]);
  const span = x1 - x0, maxT = Math.max(2, Math.floor((W() - M.l - M.r) / 44)), every = [1, 2, 5, 10, 20, 25, 50].find(k => span / k <= maxT) || 100;
  const ticks = d3.range(Math.ceil(x0), Math.floor(x1) + 1).filter(v => (v - Math.ceil(x0)) % every === 0);
  axes(s, x, y, h, { xValues: ticks, xFmt: v => v, yLabel: d.yLabel || "Amount (₹)", xLabel: d.xLabel || "Years" });
  const line = step ? [{ ...pts[0], x: x0 }].concat(pts) : pts, curve = step ? d3.curveStepBefore : d3.curveMonotoneX;
  const bands = [[p => 0, p => p.a], [p => p.a, p => p.a + p.b]];
  const clip = s.append("defs").append("clipPath").attr("id", "sa-clip").append("rect").attr("x", M.l).attr("y", 0).attr("height", h).attr("width", 0);
  clip.transition().duration(T + 200).attr("width", W() - M.l);
  const g = s.append("g").attr("clip-path", "url(#sa-clip)");
  bands.forEach(([lo, hi], k) => {
    g.append("path").datum(line).attr("fill", cols[k]).attr("fill-opacity", 0.55).attr("d", d3.area().x(p => x(p.x)).y0(p => y(lo(p))).y1(p => y(hi(p))).curve(curve));
    g.append("path").datum(line).attr("fill", "none").attr("stroke", cols[k]).attr("stroke-width", 1.6).attr("d", d3.line().x(p => x(p.x)).y(p => y(hi(p))).curve(curve));
  });
  legend(s, names.slice(0, 2).map((k, i) => [k, cols[i], false]), top - 8);
  const last = pts[pts.length - 1];
  if (!step) s.append("text").attr("class", "tag").attr("fill", C.text).attr("x", W() - M.r).attr("y", Math.max(top + 2, y(last.a + last.b) - 6)).attr("text-anchor", "end").text(short(last.a + last.b));
  const rule = s.append("line").attr("y1", top).attr("y2", h - M.b).attr("stroke", C.muted).attr("stroke-dasharray", "2 3").style("opacity", 0);
  const dots = [0, 1].map(k => s.append("circle").attr("r", 3.5).attr("fill", cols[k]).attr("stroke", "#fff").attr("stroke-width", 1).style("opacity", 0));
  const yr = v => (Math.round(v * 10) / 10).toString();
  s.append("rect").attr("width", W()).attr("height", h).attr("fill", "transparent").on("pointermove pointerdown", e => {
    const v = x.invert(d3.pointer(e)[0]);
    const i = step ? Math.min(pts.length - 1, Math.max(0, d3.bisector(p => p.x).left(pts, v))) : d3.bisector(p => p.x).center(pts, v), p = pts[i];
    const cx = step ? x(p.x - 0.5) : x(p.x);
    rule.attr("x1", cx).attr("x2", cx).style("opacity", 1);
    dots[0].attr("cx", cx).attr("cy", y(p.a)).style("opacity", 1); dots[1].attr("cx", cx).attr("cy", y(p.a + p.b)).style("opacity", 1);
    showTip(`Year ${yr(p.x)} · total <b>${short(p.a + p.b)}</b><br><span style="color:${cols[0]}">●</span> ${names[0]} <b>${short(p.a)}</b><br><span style="color:${cols[1]}">●</span> ${names[1]} <b>${short(p.b)}</b>`, Math.max(0, Math.min(cx, W() - 180)), y(p.a + p.b) - 28);
  }).on("pointerleave", () => { rule.style("opacity", 0); dots.forEach(c => c.style("opacity", 0)); hideTip(); });
};
