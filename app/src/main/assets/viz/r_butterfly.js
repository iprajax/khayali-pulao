// back-to-back horizontal bars per strike — data: { left: "Puts", right: "Calls", unit, spot?, yLabel: "Strike", xLabel,
//   rows: [{ strike, left, right }] }  (rows are drawn highest strike on top, like a price axis; a dashed line marks spot)
R.butterfly = function (d, h) {
  const rows = (d.rows || []).filter(r => isFinite(r.strike)).slice().sort((a, b) => b.strike - a.strike), s = svg(h), w = W();
  if (!rows.length) { s.append("text").attr("class", "lbl").attr("x", w / 2).attr("y", h / 2).attr("text-anchor", "middle").text("No open interest to show"); return; }
  const cL = C.saffron, cR = C.blue, top = 30, bot = 36, mid = w / 2, gap = 27, pad = 8, n = rows.length, rh = (h - top - bot) / n;
  const num = v => { const a = Math.abs(v), f = (x, u) => x.toFixed(x < 10 && x % 1 ? 1 : 0).replace(/\.0$/, "") + u;
    return (v < 0 ? "−" : "") + (a >= 1e7 ? f(a / 1e7, "Cr") : a >= 1e5 ? f(a / 1e5, "L") : a >= 1e3 ? f(a / 1e3, "K") : Math.round(a)); };
  const full = v => Math.round(v).toLocaleString("en-IN"), kf = v => (+v).toLocaleString("en-IN");
  const hi = d3.max(rows, r => Math.max(r.left || 0, r.right || 0)) || 1;
  const xr = d3.scaleLinear().domain([0, hi]).nice(3).range([mid + gap, w - pad]), xl = d3.scaleLinear().domain(xr.domain()).range([mid - gap, pad]);
  // value axis, mirrored: both halves count up from the middle
  [xl, xr].forEach(x => { const g = s.append("g").attr("transform", `translate(0,${h - bot})`).call(d3.axisBottom(x).ticks(2).tickFormat(num).tickSize(-(h - top - bot)));
    g.select(".domain").remove(); g.selectAll("line").attr("stroke", C.line).attr("stroke-opacity", .5).attr("stroke-dasharray", "2 3"); g.selectAll("text").attr("class", "lbl").attr("dy", 8); });
  s.append("text").attr("class", "axl").attr("x", mid).attr("y", 9).attr("text-anchor", "middle").text(d.yLabel || "Strike");
  s.append("text").attr("class", "axl").attr("x", w - pad).attr("y", h - 4).attr("text-anchor", "end").text(d.xLabel || ("Value" + (d.unit ? ` (${d.unit})` : "")));
  // legend: one swatch over each half
  s.append("rect").attr("x", pad).attr("y", 14).attr("width", 10).attr("height", 8).attr("rx", 2).attr("fill", cL);
  s.append("text").attr("class", "lbl").attr("x", pad + 15).attr("y", 22).text(d.left || "Left");
  s.append("rect").attr("x", w - pad - 10).attr("y", 14).attr("width", 10).attr("height", 8).attr("rx", 2).attr("fill", cR);
  s.append("text").attr("class", "lbl").attr("x", w - pad - 15).attr("y", 22).attr("text-anchor", "end").text(d.right || "Right");
  const bh = Math.max(3, Math.min(14, rh - 3)), step = Math.max(1, Math.ceil(11 / rh));
  const g = s.selectAll("g.k").data(rows).join("g").attr("class", "k").attr("transform", (r, i) => `translate(0,${top + i * rh})`);
  const tipOf = r => `<b>${kf(r.strike)}</b> · ${d.left || "Left"} ${full(r.left || 0)} · ${d.right || "Right"} ${full(r.right || 0)}`;
  g.append("text").attr("class", "val").style("font-size", "10px").attr("x", mid).attr("y", rh / 2 + 3.5).attr("text-anchor", "middle").text((r, i) => i % step === 0 ? kf(r.strike) : "");
  g.append("rect").attr("rx", 2).attr("y", (rh - bh) / 2).attr("height", bh).attr("fill", cL).attr("x", mid - gap).attr("width", 0)
    .transition().duration(T).delay((r, i) => i * 18).attr("x", r => xl(r.left || 0)).attr("width", r => Math.max(0, mid - gap - xl(r.left || 0)));
  g.append("rect").attr("rx", 2).attr("y", (rh - bh) / 2).attr("height", bh).attr("fill", cR).attr("x", mid + gap).attr("width", 0)
    .transition().duration(T).delay((r, i) => i * 18).attr("width", r => Math.max(0, xr(r.right || 0) - mid - gap));
  // a full-width tap target per row, so thin bars can still be read
  g.append("rect").attr("x", 0).attr("y", 0).attr("width", w).attr("height", rh).attr("fill", "transparent")
    .on("click", (e, r) => showTip(tipOf(r), Math.max(4, Math.min(e.pageX - 110, w - 256)), e.pageY));
  // spot marker: a dashed line placed between the strikes it falls between
  if (isFinite(d.spot) && d.spot > 0) {
    const ks = rows.map(r => r.strike), c = i => top + i * rh + rh / 2; let y;
    if (d.spot >= ks[0]) y = top + 1; else if (d.spot <= ks[n - 1]) y = h - bot - 1;
    else { const i = ks.findIndex(k => k <= d.spot); y = c(i - 1) + (ks[i - 1] - d.spot) / (ks[i - 1] - ks[i]) * rh; }
    [[pad, mid - gap + 2], [mid + gap - 2, w - pad]].forEach(([a, b]) => s.append("line").attr("x1", a).attr("x2", b).attr("y1", y).attr("y2", y)   // broken in the middle so it never strikes through a strike label
      .attr("stroke", C.text).attr("stroke-width", 1).attr("stroke-dasharray", "3 3").style("pointer-events", "none").attr("opacity", 0).transition().duration(400).delay(T).attr("opacity", .9));
    const lab = "Spot " + (+d.spot).toLocaleString("en-IN", { maximumFractionDigits: 2 }), ty = y < top + 14 ? y + 11 : y - 4;
    const t = s.append("text").attr("class", "tag").attr("fill", C.text).attr("x", w - pad).attr("y", ty).attr("text-anchor", "end").text(lab);
    const b = t.node().getBBox(); s.insert("rect", () => t.node()).attr("x", b.x - 3).attr("y", b.y - 1).attr("width", b.width + 6).attr("height", b.height + 2).attr("rx", 3).attr("fill", "#1E2A38").attr("opacity", .85).style("pointer-events", "none");
  }
};
