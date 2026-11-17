// dated events on a time axis
// data: { points: [{ t: "2026-11-16", label, value?, side?: "buy" | "sell", lane? }], lanes?: ["Announcements", …], yLabel?, xLabel?, buy?: "Bought", sell?: "Sold" }
//   with lanes  → one row per lane, a dot per event on its date
//   without     → a stem and dot per event: height = value in ₹ (sells drawn below zero)
// An array (the Kite orders / trades list) is passed on to the built-in list drawn by viz.html.
(function () {
  const list = R.timeline, day = d3.timeParse("%Y-%m-%d"), nice = d3.timeFormat("%d %b %Y");
  R.timeline = function (d, h) {
    if (Array.isArray(d)) return list(d, h);
    const pts = (d.points || []).map(p => ({ ...p, t: day(String(p.t || "").slice(0, 10)) })).filter(p => p.t).sort((a, b) => a.t - b.t), s = svg(h);
    if (!pts.length) { s.append("text").attr("class", "lbl").attr("x", W() / 2).attr("y", h / 2).attr("text-anchor", "middle").text("No dated entries"); return; }
    const span = pts[pts.length - 1].t - pts[0].t, pad = Math.max(864e5, span * 0.05);
    const dom = [new Date(+pts[0].t - pad), new Date(+pts[pts.length - 1].t + pad)];
    // a few days only: one tick per day that has an entry, with the year; otherwise let the span choose the format
    const few = span <= 6 * 864e5, days = few ? Array.from(new Set(pts.map(p => +p.t))).slice(0, 4).map(v => new Date(v)) : null;
    const fmt = few ? d3.timeFormat("%d %b '%y") : dateFmt([{ t: dom[0] }, { t: dom[1] }]);
    const tip = (e, p, extra) => showTip(`<b>${nice(p.t)}</b>${extra ? " · " + extra : ""}<br>${String(p.label || "").slice(0, 44)}`, e.pageX, e.pageY);

    if (d.lanes && d.lanes.length) {
      const lanes = d.lanes, cols = [C.blue, C.saffron, C.violet, C.green, C.pink, C.muted], top = 16, bot = 38;
      const L = Math.min(124, 14 + 6.3 * d3.max(lanes, k => k.length));
      const x = d3.scaleTime().domain(dom).range([L + 6, W() - M.r]), y = d3.scaleBand().domain(lanes).range([top, h - bot]);
      const gx = s.append("g").attr("transform", `translate(0,${h - bot})`).call((days ? d3.axisBottom(x).tickValues(days) : d3.axisBottom(x).ticks(W() < 420 ? 3 : 5)).tickFormat(fmt).tickSize(4));
      gx.select(".domain").attr("stroke", C.line); gx.selectAll("line").attr("stroke", C.line); gx.selectAll("text").attr("class", "lbl");
      s.append("text").attr("class", "axl").attr("x", 2).attr("y", 9).text(d.yLabel || "Type");
      s.append("text").attr("class", "axl").attr("x", W() - M.r).attr("y", h - 4).attr("text-anchor", "end").text(d.xLabel || "Date");
      lanes.forEach((k, i) => { const cy = y(k) + y.bandwidth() / 2;
        s.append("line").attr("x1", L).attr("x2", W() - M.r).attr("y1", cy).attr("y2", cy).attr("stroke", C.line).attr("stroke-opacity", .6).attr("stroke-dasharray", "2 3");
        s.append("text").attr("class", "lbl").attr("x", 0).attr("y", cy + 4).style("fill", cols[i % cols.length]).text(k); });
      s.selectAll("circle.e").data(pts.filter(p => lanes.indexOf(p.lane) >= 0)).join("circle").attr("class", "e").attr("cx", p => x(p.t)).attr("cy", p => y(p.lane) + y.bandwidth() / 2)
        .attr("fill", p => cols[lanes.indexOf(p.lane) % cols.length]).attr("fill-opacity", .8).attr("stroke", "#1E2A38").attr("stroke-width", 1).attr("r", 0)
        .on("click", (e, p) => { e.stopPropagation(); tip(e, p, p.lane); })
        .transition().duration(400).delay((p, i) => i * 25).attr("r", Math.max(4, Math.min(7, y.bandwidth() / 3)));
      return;
    }

    const sign = p => (p.side === "sell" ? -1 : 1) * Math.abs(p.value || 0), col = p => p.side === "sell" ? C.red : p.side === "buy" ? C.green : C.blue;
    const lo = Math.min(0, d3.min(pts, sign)), hi = Math.max(0, d3.max(pts, sign)), room = ((hi - lo) || 1) * 0.1;
    const x = d3.scaleTime().domain(dom).range([M.l + 6, W() - M.r - 6]), y = d3.scaleLinear().domain([lo < 0 ? lo - room : 0, hi > 0 ? hi + room : 0]).nice().range([h - M.b, M.t + 16]);
    axes(s, x, y, h, { xTicks: W() < 420 ? 3 : 5, xValues: days, xFmt: fmt, yLabel: d.yLabel || "Value (₹)", xLabel: d.xLabel || "Date" });
    s.append("line").attr("x1", M.l).attr("x2", W() - M.r).attr("y1", y(0)).attr("y2", y(0)).attr("stroke", C.muted).attr("stroke-opacity", .8);
    const g = s.selectAll("g.p").data(pts).join("g").attr("class", "p");
    g.append("line").attr("x1", p => x(p.t)).attr("x2", p => x(p.t)).attr("y1", y(0)).attr("y2", y(0)).attr("stroke", col).attr("stroke-width", 2).attr("stroke-opacity", .7)
      .transition().duration(T).delay((p, i) => i * 30).attr("y2", p => y(sign(p)));
    g.append("circle").attr("cx", p => x(p.t)).attr("cy", y(0)).attr("r", 5).attr("fill", col).attr("stroke", "#1E2A38").attr("stroke-width", 1)
      .on("click", (e, p) => { e.stopPropagation(); tip(e, p, (p.side === "sell" ? d.sell || "Sold" : p.side === "buy" ? d.buy || "Bought" : "") + " " + short(Math.abs(p.value || 0))); })
      .transition().duration(T).delay((p, i) => i * 30).attr("cy", p => y(sign(p)));
    const sides = [["buy", d.buy || "Bought", C.green], ["sell", d.sell || "Sold", C.red]].filter(k => pts.some(p => p.side === k[0]));
    if (sides.length) legend(s, sides.map(k => [k[1], k[2], false]), M.t + 8);
  };
})();
