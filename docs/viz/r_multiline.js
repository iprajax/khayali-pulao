// several lines on one value axis (e.g. symbols rebased to 100), with a legend and end values — data:
// { t: [..dates], series: [{ name, v: [..], color }], base: 100, yLabel, xLabel }
(function () {
  const cols = [C.blue, C.saffron, C.green, C.pink];
  function leg(s, items, y) {
    let x0 = 2, yy = y;
    items.forEach(([k, c]) => {
      const g = s.append("g"), t = g.append("text").attr("class", "lbl").attr("x", 18).text(k), w = 28 + t.node().getComputedTextLength();
      if (x0 + w > W() - 2 && x0 > 2) { x0 = 2; yy += 14; }
      g.attr("transform", `translate(${x0},${yy})`); g.append("line").attr("x2", 14).attr("y1", -3).attr("y2", -3).attr("stroke", c).attr("stroke-width", 2.5);
      x0 += w;
    });
    return yy;
  }
  R.multiline = function (d, h) {
    const s = svg(h), t = (d.t || []).map(v => new Date(v));
    const series = (d.series || []).map((sr, i) => ({ name: sr.name, c: C[sr.color] || cols[i % cols.length], pts: (sr.v || []).map((v, k) => ({ t: t[k], v })).filter(p => p.v != null && p.t && !isNaN(p.t)) })).filter(sr => sr.pts.length > 1);
    if (!series.length) { s.append("text").attr("class", "lbl").attr("x", W() / 2).attr("y", h / 2).attr("text-anchor", "middle").text("Not enough price history"); return; }
    const top = leg(s, series.map(sr => [sr.name, sr.c]), 24) + 12, mr = 44, bottom = h - M.b, all = series.flatMap(sr => sr.pts), base = d.base;
    const x = d3.scaleTime().domain(d3.extent(all, p => p.t)).range([M.l, W() - mr]);
    const y = d3.scaleLinear().domain(d3.extent(all.map(p => p.v).concat(base != null ? [base] : []))).nice().range([bottom, top]);
    const f = v => (+v).toFixed(Math.abs(v) >= 1000 ? 0 : 1);
    const r0 = M.r; M.r = mr;
    try { axes(s, x, y, h, { yFmt: v => v, xFmt: dateFmt(series[0].pts), xTicks: Math.max(2, Math.floor((W() - M.l - mr) / 70)), yLabel: d.yLabel || "Value", xLabel: d.xLabel || "Date" }); } finally { M.r = r0; }
    if (base != null) { s.append("line").attr("x1", M.l).attr("x2", W() - mr).attr("y1", y(base)).attr("y2", y(base)).attr("stroke", C.muted).attr("stroke-dasharray", "4 3"); }
    series.forEach(sr => drawLine(s, sr.pts, x, y, p => p.v, sr.c, false));
    // end values in the right margin, nudged apart so they never overlap
    const ends = series.map(sr => ({ sr, p: sr.pts[sr.pts.length - 1] })).map(e => ({ ...e, y: y(e.p.v) })).sort((a, b) => a.y - b.y);
    ends.forEach((e, i) => { e.ly = i ? Math.max(e.y + 4, ends[i - 1].ly + 12) : Math.max(e.y + 4, top + 4); });
    for (let i = ends.length - 1, lim = bottom; i >= 0; i--) { if (ends[i].ly > lim) ends[i].ly = lim; lim = ends[i].ly - 12; }
    ends.forEach(e => { s.append("circle").attr("cx", x(e.p.t)).attr("cy", e.y).attr("r", 3).attr("fill", e.sr.c);
      s.append("text").attr("class", "tag").attr("fill", e.sr.c).attr("x", W() - mr + 6).attr("y", e.ly).text(f(e.p.v)); });
    const rule = s.append("line").attr("y1", top).attr("y2", bottom).attr("stroke", C.muted).attr("stroke-dasharray", "2 3").style("opacity", 0);
    const ref = series[0].pts;
    s.append("rect").attr("width", W()).attr("height", h).attr("fill", "transparent").on("pointermove pointerdown", e => {
      const tt = ref[d3.bisector(q => q.t).center(ref, x.invert(d3.pointer(e)[0]))].t;
      rule.attr("x1", x(tt)).attr("x2", x(tt)).style("opacity", 1);
      let html = d3.timeFormat("%d %b %Y")(tt);
      series.forEach(sr => { const p = sr.pts[d3.bisector(q => q.t).center(sr.pts, tt)]; html += `<br><span style="color:${sr.c}">●</span> ${sr.name} <b>${f(p.v)}</b>`; });
      showTip(html, x(tt) < W() / 2 ? x(tt) + 12 : x(tt) - 150, top + 44);
    }).on("pointerleave", () => { rule.style("opacity", 0); hideTip(); });
  };
})();
