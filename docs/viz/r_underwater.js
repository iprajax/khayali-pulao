// underwater (drawdown) chart: area below zero showing the fall from the running peak — data:
// { t: [..dates], dd: [0 or negative %], marks: { peak, trough, recovered (date or absent) }, yLabel, xLabel }
(function () {
  R.underwater = function (d, h) {
    const s = svg(h), D = d.dd || [], pts = (d.t || []).map((v, i) => ({ t: new Date(v), v: +D[i] })).filter(p => !isNaN(p.t) && !isNaN(p.v));
    if (pts.length < 2) { s.append("text").attr("class", "lbl").attr("x", W() / 2).attr("y", h / 2).attr("text-anchor", "middle").text("Not enough price history"); return; }
    const top = 42, bottom = h - M.b, lo = pts.reduce((a, b) => b.v < a.v ? b : a), minV = Math.min(-1, lo.v * 1.12);
    const x = d3.scaleTime().domain(d3.extent(pts, p => p.t)).range([M.l, W() - M.r]), y = d3.scaleLinear().domain([minV, 0]).range([bottom, top]);
    const f = v => v < 0 ? "−" + Math.abs(v).toFixed(1) + "%" : "0%";
    axes(s, x, y, h, { yFmt: v => (v < 0 ? "−" : "") + Math.abs(v) + "%", xFmt: dateFmt(pts), xTicks: Math.max(2, Math.floor((W() - M.l) / 70)), yLabel: d.yLabel || "Below running peak (%)", xLabel: d.xLabel || "Date" });
    // the deepest episode: from its peak to its recovery (or to the latest date)
    const mk = d.marks || {}, pk = mk.peak ? new Date(mk.peak) : null, rc = mk.recovered ? new Date(mk.recovered) : null, lastT = pts[pts.length - 1].t;
    if (pk) { const xa = x(pk), xb = x(rc || lastT);
      s.append("rect").attr("x", xa).attr("width", Math.max(1, xb - xa)).attr("y", top).attr("height", bottom - top).attr("fill", C.red).attr("opacity", .09);
      s.append("line").attr("x1", xa).attr("x2", xa).attr("y1", top - 16).attr("y2", bottom).attr("stroke", C.muted).attr("stroke-dasharray", "2 3");
      const pe = xa > M.l + 34; s.append("text").attr("class", "tag").attr("fill", C.muted).attr("x", pe ? xa - 4 : xa + 4).attr("y", top - 18).attr("text-anchor", pe ? "end" : "start").text("Peak");
      if (rc) s.append("line").attr("x1", xb).attr("x2", xb).attr("y1", top - 6).attr("y2", bottom).attr("stroke", C.green).attr("stroke-dasharray", "2 3");
      const re = xb > W() - 96; s.append("text").attr("class", "tag").attr("fill", rc ? C.green : C.saffron).attr("x", re ? xb - 4 : xb + 4).attr("y", top - 6).attr("text-anchor", re ? "end" : "start").text(rc ? "Recovered" : "Not yet recovered"); }
    const area = y1 => d3.area().x(p => x(p.t)).y0(y(0)).y1(y1).curve(d3.curveMonotoneX);
    s.append("path").datum(pts).attr("fill", C.red).attr("opacity", .3).attr("d", area(() => y(0))).transition().duration(T).attr("d", area(p => y(p.v)));
    s.append("path").datum(pts).attr("fill", "none").attr("stroke", C.red).attr("stroke-width", 1.5).attr("d", d3.line().x(p => x(p.t)).y(() => y(0)).curve(d3.curveMonotoneX))
      .transition().duration(T).attr("d", d3.line().x(p => x(p.t)).y(p => y(p.v)).curve(d3.curveMonotoneX));
    s.append("line").attr("x1", M.l).attr("x2", W() - M.r).attr("y1", y(0)).attr("y2", y(0)).attr("stroke", C.muted);
    if (lo.v < 0) { s.append("circle").attr("cx", x(lo.t)).attr("cy", y(lo.v)).attr("r", 3.5).attr("fill", C.red).attr("stroke", "#fff").attr("stroke-width", 1);
      const lt = s.append("text").attr("class", "tag").attr("fill", C.text).attr("y", y(lo.v) + 14).attr("text-anchor", "middle").attr("stroke", "#1E2A38").attr("stroke-width", 3).style("paint-order", "stroke").text(`${f(lo.v)} · ${d3.timeFormat("%d %b %Y")(lo.t)}`), half = lt.node().getComputedTextLength() / 2 + 2;
      lt.attr("x", Math.min(Math.max(x(lo.t), M.l + half), W() - M.r - half)); }
    const rule = s.append("line").attr("y1", top).attr("y2", bottom).attr("stroke", C.muted).attr("stroke-dasharray", "2 3").style("opacity", 0);
    const dot = s.append("circle").attr("r", 4).attr("fill", C.red).attr("stroke", "#fff").attr("stroke-width", 1).style("opacity", 0);
    s.append("rect").attr("width", W()).attr("height", h).attr("fill", "transparent").on("pointermove pointerdown", e => {
      const p = pts[d3.bisector(q => q.t).center(pts, x.invert(d3.pointer(e)[0]))];
      rule.attr("x1", x(p.t)).attr("x2", x(p.t)).style("opacity", 1); dot.attr("cx", x(p.t)).attr("cy", y(p.v)).style("opacity", 1);
      showTip(`${d3.timeFormat("%d %b %Y")(p.t)} · <b>${p.v < 0 ? f(p.v) + " below peak" : "at a new high"}</b>`, x(p.t) < W() / 2 ? x(p.t) + 12 : x(p.t) - 190, y(p.v) + 30);
    }).on("pointerleave", () => { dot.style("opacity", 0); rule.style("opacity", 0); hideTip(); });
  };
})();
