// bubble scatter — data: { xLabel, yLabel, xUnit ("%" | ""), yUnit, sizeLabel ("Bubble = weight"), legend?: [[label, color]],
//   refs?: [{ axis: "x" | "y", value, label }], points: [{ label, x, y, size?, color? ("green" | "red" | …), note? }] }
// Both axes are named and print their values; bubble area follows `size`; tap a bubble to read it.
R.scatter = function (d, h) {
  const s = svg(h), pts = (d.points || []).filter(p => isFinite(p.x) && isFinite(p.y)), refs = d.refs || [];
  if (!pts.length) { s.append("text").attr("class", "lbl").attr("x", W() / 2).attr("y", h / 2).attr("text-anchor", "middle").text("Not enough price history to plot"); return; }
  const span = (vals, zero) => { let lo = d3.min(vals), hi = d3.max(vals); if (zero) { lo = Math.min(0, lo); hi = Math.max(0, hi); } const pad = (hi - lo || Math.abs(hi) || 1) * 0.14; return [lo - (zero && lo === 0 ? 0 : pad), hi + pad]; };
  const xs = pts.map(p => p.x).concat(refs.filter(r => r.axis === "x").map(r => r.value)), ys = pts.map(p => p.y).concat(refs.filter(r => r.axis === "y").map(r => r.value));
  const top = M.t + (d.legend ? 22 : 12), right = W() - M.r - 6;
  const x = d3.scaleLinear().domain(span(xs, d.xZero !== false)).nice().range([M.l + 8, right]), y = d3.scaleLinear().domain(span(ys, false)).nice().range([h - M.b - 6, top]);
  const uf = u => v => (v < 0 ? "−" : "") + (Math.abs(v) >= 100 ? Math.round(Math.abs(v)) : +Math.abs(v).toFixed(1)) + (u || "");
  axes(s, x, y, h, { yFmt: uf(d.yUnit), xFmt: uf(d.xUnit), xTicks: W() < 400 ? 4 : 6, yLabel: d.yLabel || "Y", xLabel: d.xLabel || "X" });
  if (d.sizeLabel) s.append("text").attr("class", "axl").attr("x", 2).attr("y", h - 4).text(d.sizeLabel);
  if (d.legend) { let lx = M.l; d.legend.forEach(([k, c]) => { s.append("circle").attr("cx", lx + 5).attr("cy", M.t + 3).attr("r", 4.5).attr("fill", C[c] || c);
    const t = s.append("text").attr("class", "lbl").attr("x", lx + 13).attr("y", M.t + 7).text(k); lx += 25 + t.node().getComputedTextLength(); }); }
  // zero line and reference lines
  const [y0, y1] = y.domain(); if (y0 < 0 && y1 > 0) s.append("line").attr("x1", M.l).attr("x2", W() - M.r).attr("y1", y(0)).attr("y2", y(0)).attr("stroke", C.muted).attr("stroke-opacity", .7);
  refs.forEach(r => { const v = r.axis === "x", p = v ? x(r.value) : y(r.value);
    s.append("line").attr("x1", v ? p : M.l).attr("x2", v ? p : W() - M.r).attr("y1", v ? top - 6 : p).attr("y2", v ? h - M.b : p).attr("stroke", C.saffron).attr("stroke-dasharray", "4 4").attr("opacity", .8);
    if (r.label) s.append("text").attr("class", "tag").attr("fill", C.saffron).attr("x", v ? Math.min(p + 4, W() - M.r - r.label.length * 5.6) : W() - M.r - 2).attr("y", v ? top + 2 : p - 4).attr("text-anchor", v ? "start" : "end").text(r.label); });
  const maxS = d3.max(pts, p => p.size || 0) || 1, rr = d3.scaleSqrt().domain([0, maxS]).range([4, W() < 400 ? 15 : 18]);
  const rad = p => p.size ? rr(p.size) : 6, col = p => C[p.color] || p.color || C.blue, num = (v, u) => uf(u)(+v.toFixed(2));
  const order = pts.slice().sort((a, b) => rad(b) - rad(a));       // small bubbles on top so every one can be tapped
  s.selectAll("circle.p").data(order).join("circle").attr("class", "p").attr("cx", p => x(p.x)).attr("cy", p => y(p.y)).attr("fill", col).attr("fill-opacity", .55).attr("stroke", col).attr("stroke-width", 1.5).attr("r", 0)
    .on("click", (e, p) => { e.stopPropagation(); showTip(`<b>${p.label}</b><br>${d.xShort || d.xLabel || "x"} ${num(p.x, d.xUnit)} · ${d.yShort || d.yLabel || "y"} ${num(p.y, d.yUnit)}${p.note ? "<br>" + p.note : ""}`, Math.min(e.pageX, W() - 200), e.pageY - 30); })
    .transition().duration(T).delay((p, i) => i * 40).attr("r", rad);
  // labels: try right, left, above, below of each bubble; keep the first spot that stays inside and clear of other labels and bubbles
  const boxes = order.map(p => { const r = rad(p); return [x(p.x) - r, y(p.y) - r, x(p.x) + r, y(p.y) + r, p]; }), hit = (a, b) => a[0] < b[2] && a[2] > b[0] && a[1] < b[3] && a[3] > b[1];
  order.slice().sort((a, b) => rad(b) - rad(a)).forEach(p => { const t = p.label.length > 10 ? p.label.slice(0, 9) + "…" : p.label, w = t.length * 6.1 + 2, r = rad(p), px0 = x(p.x), py0 = y(p.y);
    const spots = [[px0 + r + 3, py0 - 6], [px0 - r - 3 - w, py0 - 6], [px0 - w / 2, py0 - r - 14], [px0 - w / 2, py0 + r + 2]];
    const ok = spots.map(([a, b]) => [a, b, a + w, b + 12]).find(bx => bx[0] >= M.l + 1 && bx[2] <= W() - 1 && bx[1] >= top - 8 && bx[3] <= h - M.b - 1 && !boxes.some(o => o[4] !== p && hit(bx, o)));
    if (!ok) return; boxes.push([...ok, null]);
    s.append("text").attr("class", "val").style("font-size", "10px").style("pointer-events", "none").attr("x", ok[0] + 1).attr("y", ok[1] + 10).text(t).attr("opacity", 0).transition().delay(T).duration(250).attr("opacity", 1); });
};

// heat grid (the scatter's categorical sibling: one cell per pair) — data: { rows: [labels], cols: [labels], cells: [[numbers]], min, max, unit ("correlation"),
//   xLabel?, yLabel?, digits? } — the same shape as the "matrix" renderer. Every cell prints its value; colour runs from blue (min) through grey (0) to saffron (max).
R.heatgrid = function (d, h) {
  const s = svg(h), rows = d.rows || [], cols = d.cols || [], cells = d.cells || [], dg = d.digits == null ? 2 : d.digits;
  if (!rows.length || !cols.length || !cells.length) { s.append("text").attr("class", "lbl").attr("x", W() / 2).attr("y", h / 2).attr("text-anchor", "middle").text("Not enough price history to compare"); return; }
  const flat = cells.flat().filter(v => v != null && isFinite(v)), lo = d.min == null ? Math.min(0, d3.min(flat)) : d.min, hi = d.max == null ? Math.max(0, d3.max(flat)) : d.max;
  const colour = d3.scaleLinear().domain([lo, 0, hi]).range([C.blue, "#3a4656", C.saffron]).clamp(true);
  const clip = (t, n) => t.length > n ? t.slice(0, n - 1) + "…" : t, f = v => (v < 0 ? "−" : "") + Math.abs(v).toFixed(dg);
  const probe = s.append("text").attr("class", "lbl"); let L = 0; rows.forEach(r => { probe.text(clip(r, 10)); L = Math.max(L, probe.node().getComputedTextLength()); }); probe.remove();
  L = Math.min(L, W() * 0.3) + 8;
  const top = 62, legH = 36, cw = (W() - L - 22) / cols.length, ch = Math.max(12, Math.min(34, (h - top - legH) / rows.length));
  s.append("text").attr("class", "axl").attr("x", 2).attr("y", 9).text((d.yLabel || "Row") + " ↓");
  s.append("text").attr("class", "axl").attr("x", W() - 1).attr("y", 9).attr("text-anchor", "end").text((d.xLabel || "Column") + " →");
  // column names slant up from each column so eight of them fit side by side
  cols.forEach((c, j) => s.append("text").attr("class", "lbl").style("font-size", "10px").attr("transform", `translate(${L + (j + .5) * cw - 2},${top - 6}) rotate(-38)`).text(clip(c, 7)));
  rows.forEach((r, i) => s.append("text").attr("class", "lbl").attr("x", L - 6).attr("y", top + i * ch + ch / 2 + 3.5).attr("text-anchor", "end").text(clip(r, 10)));
  const data = rows.flatMap((r, i) => cols.map((c, j) => ({ r, c, i, j, v: (cells[i] || [])[j] }))).filter(c => c.v != null && isFinite(c.v));
  const g = s.selectAll("g.c").data(data).join("g").attr("class", "c").attr("transform", c => `translate(${L + c.j * cw},${top + c.i * ch})`);
  g.append("rect").attr("width", cw - 2).attr("height", ch - 2).attr("rx", 3).attr("fill", c => c.r === c.c ? "transparent" : colour(c.v)).attr("stroke", c => c.r === c.c ? C.line : "none").attr("opacity", 0)
    .on("click", (e, c) => showTip(c.r === c.c ? `<b>${c.r}</b><br>with itself: ${f(c.v)}` : `<b>${c.r} × ${c.c}</b><br>${d.unit || "value"} ${f(c.v)}`, Math.min(e.pageX, W() - 190), e.pageY - 30))
    .transition().duration(350).delay(c => c.i * 35 + c.j * 15).attr("opacity", 1);
  if (cw >= 26 && ch >= 14) g.append("text").attr("x", (cw - 2) / 2).attr("y", (ch - 2) / 2 + 3.5).attr("text-anchor", "middle").style("font-size", "9.5px").style("pointer-events", "none")
    .style("fill", c => c.r === c.c ? C.dim : "#fff").text(c => f(c.v));
  // colour scale with its values, and what the number is
  const lw = Math.min(170, W() - 150), lx = L + (cols.length * cw - lw) / 2, ly = top + rows.length * ch + 16, grad = s.append("defs").append("linearGradient").attr("id", "hgg");
  [[0, colour(lo)], [-lo / ((hi - lo) || 1), "#3a4656"], [1, colour(hi)]].forEach(([o, c]) => grad.append("stop").attr("offset", Math.max(0, Math.min(1, o))).attr("stop-color", c));
  s.append("rect").attr("x", lx).attr("y", ly - 7).attr("width", lw).attr("height", 7).attr("rx", 3).attr("fill", "url(#hgg)");
  s.append("text").attr("class", "lbl").attr("x", lx - 6).attr("y", ly).attr("text-anchor", "end").text(f(lo));
  s.append("text").attr("class", "lbl").attr("x", lx + lw + 6).attr("y", ly).text((hi > 0 ? "+" : "") + f(hi));
  if (d.unit) s.append("text").attr("class", "axl").attr("x", lx + lw / 2).attr("y", ly + 14).attr("text-anchor", "middle").text(d.unit);
};
