// price line with extra lines, an optional band, horizontal zones and levels — data:
// { t: ["2026-11-13", …], price: [..], name, unit: "₹" | "", yLabel, xLabel,
//   lines: [{ name, color, v: [.. or null] }], band: { name, upper: [..], lower: [..] },
//   zones: [{ lo, hi, color, label }], levels: [{ label, short, value, color, fit }] }
// A level with fit:false does not stretch the price axis; if it falls outside, it is listed at the edge as off scale.
(function () {
  const col = k => C[k] || k || C.blue;
  function leg(s, items, y) {
    let x0 = 2, yy = y;
    items.forEach(([k, c, kind]) => {
      const g = s.append("g"), t = g.append("text").attr("class", "lbl").attr("x", 18).text(k), w = 28 + t.node().getComputedTextLength();
      if (x0 + w > W() - 2 && x0 > 2) { x0 = 2; yy += 14; }
      g.attr("transform", `translate(${x0},${yy})`);
      if (kind === "box") g.append("rect").attr("y", -9).attr("width", 14).attr("height", 9).attr("rx", 2).attr("fill", c).attr("opacity", .4);
      else g.append("line").attr("x2", 14).attr("y1", -3).attr("y2", -3).attr("stroke", c).attr("stroke-width", 2);
      x0 += w;
    });
    return yy;
  }
  R.overlay = function (d, h) {
    const s = svg(h), P = d.price || [], t = (d.t || []).map(v => new Date(v));
    const pts = t.map((tt, i) => ({ t: tt, c: P[i], i })).filter(p => p.c != null && !isNaN(p.t));
    if (pts.length < 2) { s.append("text").attr("class", "lbl").attr("x", W() / 2).attr("y", h / 2).attr("text-anchor", "middle").text("Not enough price history"); return; }
    const lines = (d.lines || []).map(l => ({ name: l.name, c: col(l.color), v: l.v || [] })), band = d.band, zones = d.zones || [], levels = d.levels || [];
    const items = [[d.name || "Price", C.text, "line"]].concat(lines.map(l => [l.name, l.c, "line"]));
    if (band) items.push([band.name, C.blue, "box"]);
    zones.forEach(z => items.push([z.label, col(z.color), "box"]));
    const top = (items.length > 1 ? leg(s, items, 24) : 12) + 12, mr = levels.length ? 78 : M.r;
    let all = pts.map(p => p.c);
    lines.forEach(l => { all = all.concat(l.v.filter(v => v != null)); });
    if (band) all = all.concat((band.upper || []).filter(v => v != null), (band.lower || []).filter(v => v != null));
    zones.forEach(z => all.push(z.lo, z.hi)); levels.forEach(l => { if (l.fit !== false) all.push(l.value); });
    const x = d3.scaleTime().domain(d3.extent(pts, p => p.t)).range([M.l, W() - mr]);
    const y = d3.scaleLinear().domain(d3.extent(all)).nice().range([h - M.b, top]);
    const num = v => Math.abs(v) >= 1000 ? Math.round(v).toLocaleString("en-IN") : (+v).toFixed(Math.abs(v) < 100 ? 1 : 0), f = v => (d.unit || "") + num(v);
    const r0 = M.r; M.r = mr;
    try { axes(s, x, y, h, { yFmt: f, xFmt: dateFmt(pts), xTicks: Math.max(2, Math.floor((W() - M.l - mr) / 70)), yLabel: d.yLabel || "Price (₹)", xLabel: d.xLabel || "Date" }); } finally { M.r = r0; }
    const [y0, y1] = y.domain(), inside = v => v >= y0 && v <= y1;
    zones.forEach(z => { const a = y(Math.min(y1, z.hi)), b = y(Math.max(y0, z.lo));
      s.append("rect").attr("x", M.l).attr("width", W() - mr - M.l).attr("y", a).attr("height", Math.max(1, b - a)).attr("fill", col(z.color)).attr("opacity", 0).transition().duration(T).attr("opacity", .16); });
    if (band) s.append("path").datum(pts.filter(p => band.upper[p.i] != null && band.lower[p.i] != null)).attr("fill", C.blue).attr("opacity", 0)
      .attr("d", d3.area().x(p => x(p.t)).y0(p => y(band.lower[p.i])).y1(p => y(band.upper[p.i])).curve(d3.curveMonotoneX)).transition().duration(T).attr("opacity", .16);
    lines.forEach((l, k) => s.append("path").datum(pts.filter(p => l.v[p.i] != null)).attr("fill", "none").attr("stroke", l.c).attr("stroke-width", 1.5).attr("opacity", 0)
      .attr("d", d3.line().x(p => x(p.t)).y(p => y(l.v[p.i])).curve(d3.curveMonotoneX)).transition().duration(T).delay(150 + k * 120).attr("opacity", 1));
    // levels: dashed lines with their values in the right margin (labels are nudged apart); off-scale ones are named at the edge
    const on = levels.filter(l => inside(l.value)).map(l => ({ ...l, y: y(l.value) })).sort((a, b) => a.y - b.y), off = levels.filter(l => !inside(l.value));
    on.forEach(l => s.append("line").attr("x1", M.l).attr("x2", W() - mr).attr("y1", l.y).attr("y2", l.y).attr("stroke", col(l.color)).attr("stroke-width", 1.2).attr("stroke-dasharray", "5 3"));
    on.forEach((l, i) => { l.ly = i ? Math.max(l.y + 4, on[i - 1].ly + 11) : Math.max(l.y + 4, top + 4); });
    for (let i = on.length - 1, lim = h - M.b; i >= 0; i--) { if (on[i].ly > lim) on[i].ly = lim; lim = on[i].ly - 11; }
    on.forEach(l => s.append("text").attr("class", "tag").attr("fill", col(l.color)).attr("x", W() - mr + 5).attr("y", l.ly).text((l.short || l.label) + " " + num(l.value)));
    const hi = off.filter(l => l.value > y1), lo = off.filter(l => l.value < y0);
    // the off-scale notes step clear of any level line near the edge they sit on
    let hy = top + 11, by = h - M.b - 5;
    for (let k = 0; k < 12 && on.some(l => Math.abs(l.y - (hy - 4)) < 9); k++) hy += 6;
    for (let k = 0; k < 12 && on.some(l => Math.abs(l.y - (by - 4)) < 9); k++) by -= 6;
    hi.forEach((l, i) => s.append("text").attr("class", "lbl").attr("x", W() - mr - 4).attr("y", hy + i * 12).attr("text-anchor", "end").attr("stroke", "#1E2A38").attr("stroke-width", 3).style("paint-order", "stroke").text(`↑ ${l.label} ${f(l.value)} (off scale)`));
    lo.forEach((l, i) => s.append("text").attr("class", "lbl").attr("x", W() - mr - 4).attr("y", by - i * 12).attr("text-anchor", "end").attr("stroke", "#1E2A38").attr("stroke-width", 3).style("paint-order", "stroke").text(`↓ ${l.label} ${f(l.value)} (off scale)`));
    drawLine(s, pts, x, y, p => p.c, C.text, false);
    const rule = s.append("line").attr("y1", top).attr("y2", h - M.b).attr("stroke", C.muted).attr("stroke-dasharray", "2 3").style("opacity", 0);
    const dot = s.append("circle").attr("r", 4).attr("fill", C.text).attr("stroke", "#1E2A38").attr("stroke-width", 1.5).style("opacity", 0);
    s.append("rect").attr("width", W()).attr("height", h).attr("fill", "transparent").on("pointermove pointerdown", e => {
      const p = pts[d3.bisector(q => q.t).center(pts, x.invert(d3.pointer(e)[0]))];
      rule.attr("x1", x(p.t)).attr("x2", x(p.t)).style("opacity", 1); dot.attr("cx", x(p.t)).attr("cy", y(p.c)).style("opacity", 1);
      let html = `${d3.timeFormat("%d %b %Y")(p.t)} · <b>${f(p.c)}</b>`;
      lines.forEach(l => { if (l.v[p.i] != null) html += `<br><span style="color:${l.c}">●</span> ${l.name} ${f(l.v[p.i])}`; });
      if (band && band.upper[p.i] != null) html += `<br>Band ${f(band.lower[p.i])} – ${f(band.upper[p.i])}`;
      showTip(html, x(p.t) < W() / 2 ? x(p.t) + 12 : x(p.t) - 162, top + 44);
    }).on("pointerleave", () => { dot.style("opacity", 0); rule.style("opacity", 0); hideTip(); });
  };
})();
