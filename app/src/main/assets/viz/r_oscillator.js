// oscillator pane: a line in a fixed (0–100) or signed range, with shaded zones, threshold lines, and an optional
// signal line and histogram — data:
// { t: [..dates], line: [..], name, color, min, max, digits, suffix, yLabel, xLabel,
//   signal: [..], signalName, hist: [..], histName,
//   zones: [{ from, to, color, label }], thresholds: [{ value, label }] }
(function () {
  const col = k => C[k] || k || C.blue;
  function leg(s, items, y) {
    let x0 = 2, yy = y;
    items.forEach(([k, c, kind]) => {
      const g = s.append("g"), t = g.append("text").attr("class", "lbl").attr("x", 18).text(k), w = 28 + t.node().getComputedTextLength();
      if (x0 + w > W() - 2 && x0 > 2) { x0 = 2; yy += 14; }
      g.attr("transform", `translate(${x0},${yy})`);
      if (kind === "box") { g.append("rect").attr("y", -9).attr("width", 6).attr("height", 9).attr("rx", 1).attr("fill", C.green).attr("opacity", .7); g.append("rect").attr("x", 8).attr("y", -9).attr("width", 6).attr("height", 9).attr("rx", 1).attr("fill", C.red).attr("opacity", .7); }
      else g.append("line").attr("x2", 14).attr("y1", -3).attr("y2", -3).attr("stroke", c).attr("stroke-width", 2);
      x0 += w;
    });
    return yy;
  }
  R.oscillator = function (d, h) {
    const s = svg(h), L = d.line || [], G = d.signal, B = d.hist, t = (d.t || []).map(v => new Date(v));
    const pts = t.map((tt, i) => ({ t: tt, v: L[i], g: G ? G[i] : null, b: B ? B[i] : null })).filter(p => !isNaN(p.t)), main = pts.filter(p => p.v != null);
    if (main.length < 2) { s.append("text").attr("class", "lbl").attr("x", W() / 2).attr("y", h / 2).attr("text-anchor", "middle").text("Not enough history"); return; }
    const c = col(d.color), th = d.thresholds || [], zones = d.zones || [], fixed = d.min != null && d.max != null;
    const items = [[d.name || "Value", c, "line"]]; if (G) items.push([d.signalName || "Signal", C.saffron, "line"]); if (B) items.push([d.histName || "Histogram", C.muted, "box"]);
    const top = (items.length > 1 ? leg(s, items, 24) : 12) + 12;
    let lo = d.min, hi = d.max;
    if (!fixed) { let all = main.map(p => p.v).concat(th.map(q => q.value)); if (G) all = all.concat(pts.filter(p => p.g != null).map(p => p.g)); if (B) all = all.concat(pts.filter(p => p.b != null).map(p => p.b), [0]);
      lo = d3.min(all); hi = d3.max(all); const pad = (hi - lo || 1) * 0.12; lo -= pad; hi += pad; }
    const x = d3.scaleTime().domain(d3.extent(pts, p => p.t)).range([M.l, W() - M.r]), y = d3.scaleLinear().domain([lo, hi]).range([h - M.b, top]);
    const dg = d.digits == null ? 1 : d.digits, sfx = d.suffix || "", f = v => (v < 0 ? "−" : "") + Math.abs(v).toFixed(dg) + sfx;
    axes(s, x, y, h, { yFmt: v => (v < 0 ? "−" : "") + (+Math.abs(v).toFixed(dg)) + sfx, yTicks: 5, xFmt: dateFmt(pts), xTicks: Math.max(2, Math.floor((W() - M.l) / 70)), yLabel: d.yLabel || "Value", xLabel: d.xLabel || "Date" });
    zones.forEach(z => { const a = y(Math.min(hi, z.to)), b = y(Math.max(lo, z.from));
      s.append("rect").attr("x", M.l).attr("width", W() - M.r - M.l).attr("y", a).attr("height", Math.max(1, b - a)).attr("fill", col(z.color)).attr("opacity", 0).transition().duration(T).attr("opacity", .15);
      if (z.label) s.append("text").attr("class", "tag").attr("fill", col(z.color)).attr("x", M.l + 6).attr("y", a + 12).text(z.label); });
    if (!fixed && lo < 0 && hi > 0) s.append("line").attr("x1", M.l).attr("x2", W() - M.r).attr("y1", y(0)).attr("y2", y(0)).attr("stroke", C.muted);
    if (B) { const bars = pts.filter(p => p.b != null), bw = Math.max(1, (W() - M.l - M.r) / pts.length - 1);
      s.selectAll("rect.hb").data(bars).join("rect").attr("class", "hb").attr("x", p => x(p.t) - bw / 2).attr("width", bw).attr("fill", p => p.b >= 0 ? C.green : C.red).attr("opacity", .6)
        .attr("y", y(0)).attr("height", 0).transition().duration(T).attr("y", p => y(Math.max(0, p.b))).attr("height", p => Math.max(.5, Math.abs(y(p.b) - y(0)))); }
    th.forEach(q => { const yy = y(q.value);
      s.append("line").attr("x1", M.l).attr("x2", W() - M.r).attr("y1", yy).attr("y2", yy).attr("stroke", C.muted).attr("stroke-dasharray", "4 3");
      if (q.label) s.append("text").attr("class", "tag").attr("fill", C.muted).attr("x", W() - M.r - 3).attr("y", yy - 4 < top + 9 ? yy + 12 : yy - 4).attr("text-anchor", "end").attr("stroke", "#1E2A38").attr("stroke-width", 3).style("paint-order", "stroke").text(q.label).attr("data-th", 1); });
    if (G) drawLine(s, pts.filter(p => p.g != null), x, y, p => p.g, C.saffron, false).attr("stroke-width", 1.5);
    drawLine(s, main, x, y, p => p.v, c, false);
    s.selectAll("text[data-th]").raise();
    const end = main[main.length - 1];
    s.append("circle").attr("cx", x(end.t)).attr("cy", y(end.v)).attr("r", 0).attr("fill", c).attr("stroke", "#fff").attr("stroke-width", 1).transition().duration(400).delay(800).attr("r", 3.5);
    const rule = s.append("line").attr("y1", top).attr("y2", h - M.b).attr("stroke", C.muted).attr("stroke-dasharray", "2 3").style("opacity", 0);
    const dot = s.append("circle").attr("r", 4).attr("fill", c).attr("stroke", "#fff").attr("stroke-width", 1).style("opacity", 0);
    s.append("rect").attr("width", W()).attr("height", h).attr("fill", "transparent").on("pointermove pointerdown", e => {
      const p = main[d3.bisector(q => q.t).center(main, x.invert(d3.pointer(e)[0]))];
      rule.attr("x1", x(p.t)).attr("x2", x(p.t)).style("opacity", 1); dot.attr("cx", x(p.t)).attr("cy", y(p.v)).style("opacity", 1);
      let html = `${d3.timeFormat("%d %b %Y")(p.t)} · ${d.name || "Value"} <b>${f(p.v)}</b>`;
      if (p.g != null) html += `<br>${d.signalName || "Signal"} ${f(p.g)}`; if (p.b != null) html += ` · ${d.histName || "Histogram"} ${f(p.b)}`;
      showTip(html, x(p.t) < W() / 2 ? x(p.t) + 12 : x(p.t) - 200, y(p.v));
    }).on("pointerleave", () => { dot.style("opacity", 0); rule.style("opacity", 0); hideTip(); });
  };
})();
