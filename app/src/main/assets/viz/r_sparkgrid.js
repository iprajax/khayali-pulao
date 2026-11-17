// grid of labelled sparklines — data: { cols?, yLabel?, xLabel?, items: [{ label, t: ["2026-10-17", …], v: [n, …],
//   digits?, prefix?, suffix?, change? (number: green/red tag), changeText? (overrides "+1.2%"), changeLabel?, color? (name in C: a neutral line and tag,
//   for series where up is not "good", such as a yield or an exchange rate) }] }
// Each panel prints its last value, its change, and its own low and high on the value axis; the date range is shared and printed once.
R.sparkgrid = function (d, h) {
  const items = (d.items || []).map(i => ({ ...i, pts: (i.v || []).map((v, k) => ({ t: new Date((i.t || [])[k]), v: +v })).filter(p => !isNaN(p.v) && !isNaN(p.t)) })).filter(i => i.pts.length > 1);
  const s = svg(h);
  if (!items.length) { s.append("text").attr("class", "lbl").attr("x", W() / 2).attr("y", h / 2).attr("text-anchor", "middle").text("No price history to show"); return; }
  const cols = Math.max(1, Math.min(d.cols || 2, items.length)), rows = Math.ceil(items.length / cols);
  const top = 18, foot = 20, gap = 12, pw = (W() - gap * (cols - 1)) / cols, ph = (h - top - foot) / rows, gut = 44;
  const full = (i, v) => (i.prefix || "") + v.toLocaleString(i.prefix === "₹" ? "en-IN" : "en-US", { minimumFractionDigits: i.digits == null ? 2 : i.digits, maximumFractionDigits: i.digits == null ? 2 : i.digits }) + (i.suffix || "");
  const tick = (i, v) => { const a = Math.abs(v), r = i.prefix === "₹", f = (n, u) => (i.prefix || "") + n.toFixed(n < 10 ? 2 : n < 100 ? 1 : 0) + u, dg = a >= 1000 ? 0 : Math.min(2, i.digits == null ? 2 : i.digits);
    return a >= 1e7 && r ? f(v / 1e7, "Cr") : a >= 1e5 && r ? f(v / 1e5, "L") : a >= 1e6 ? f(v / 1e6, "M") : a >= 1e4 ? f(v / 1e3, "K")
      : (i.prefix || "") + v.toLocaleString("en-US", { minimumFractionDigits: dg, maximumFractionDigits: dg }) + (i.suffix || ""); };
  const all = items.flatMap(i => i.pts.map(p => p.t)), t0 = d3.min(all), t1 = d3.max(all), df = d3.timeFormat((t1 - t0) / 864e5 > 200 ? "%d %b %Y" : "%d %b");
  s.append("text").attr("class", "axl").attr("x", 2).attr("y", 9).text(d.yLabel || "Value · low and high per panel");
  s.append("text").attr("class", "axl").attr("x", W()).attr("y", h - 4).attr("text-anchor", "end").text(`${d.xLabel || "Date"} · ${df(t0)} → ${df(t1)}`);
  items.forEach((i, k) => {
    const g = s.append("g").attr("transform", `translate(${(k % cols) * (pw + gap)},${top + Math.floor(k / cols) * ph})`);
    const pts = i.pts, first = pts[0], last = pts[pts.length - 1], col = C[i.color] || (last.v >= first.v ? C.green : C.red);
    const y0 = 36, y1 = ph - 12, x = d3.scaleTime().domain([first.t, last.t]).range([gut, pw - 5]);
    const lo = d3.min(pts, p => p.v), hi = d3.max(pts, p => p.v), y = d3.scaleLinear().domain(lo === hi ? [lo - 1, hi + 1] : [lo, hi]).range([y1, y0]);
    g.append("text").attr("class", "val").attr("x", 1).attr("y", 11).text(i.label);
    if (i.change != null || i.changeText) g.append("text").attr("class", "tag").attr("x", pw - 2).attr("y", 11).attr("text-anchor", "end")
      .attr("fill", i.color ? C.muted : i.change > 0 ? C.green : i.change < 0 ? C.red : C.muted).text((i.changeText || pct(i.change)) + (i.changeLabel ? " " + i.changeLabel : ""));
    g.append("text").attr("class", "val").style("font-size", "13px").attr("x", 1).attr("y", 27).text(full(i, last.v));
    [[hi, y0], [lo, y1]].forEach(([v, yy]) => {
      g.append("line").attr("x1", gut).attr("x2", pw).attr("y1", yy).attr("y2", yy).attr("stroke", C.line).attr("stroke-opacity", .55).attr("stroke-dasharray", "2 3");
      g.append("text").attr("class", "lbl").style("font-size", "9.5px").attr("x", gut - 5).attr("y", yy + 3).attr("text-anchor", "end").text(tick(i, v)); });
    const id = "sg" + k, grad = g.append("defs").append("linearGradient").attr("id", id).attr("x1", 0).attr("x2", 0).attr("y1", 0).attr("y2", 1);
    grad.append("stop").attr("offset", 0).attr("stop-color", col).attr("stop-opacity", .28); grad.append("stop").attr("offset", 1).attr("stop-color", col).attr("stop-opacity", 0);
    g.append("path").datum(pts).attr("fill", `url(#${id})`).attr("d", d3.area().x(p => x(p.t)).y0(y1).y1(p => y(p.v)).curve(d3.curveMonotoneX)).attr("opacity", 0).transition().duration(T).attr("opacity", 1);
    const line = g.append("path").datum(pts).attr("fill", "none").attr("stroke", col).attr("stroke-width", 1.6).attr("d", d3.line().x(p => x(p.t)).y(p => y(p.v)).curve(d3.curveMonotoneX));
    const L = line.node().getTotalLength(); line.attr("stroke-dasharray", L).attr("stroke-dashoffset", L).transition().duration(T).delay(k * 40).attr("stroke-dashoffset", 0);
    g.append("circle").attr("cx", x(last.t)).attr("cy", y(last.v)).attr("r", 2.5).attr("fill", col);
    const dot = g.append("circle").attr("r", 3.5).attr("fill", col).attr("stroke", "#fff").attr("stroke-width", 1).style("opacity", 0);
    g.append("rect").attr("width", pw).attr("height", ph - 4).attr("fill", "transparent").on("pointermove pointerdown", e => {
      const p = pts[d3.bisector(q => q.t).center(pts, x.invert(d3.pointer(e)[0]))];
      dot.attr("cx", x(p.t)).attr("cy", y(p.v)).style("opacity", 1);
      showTip(`${i.label} · ${d3.timeFormat("%d %b %Y")(p.t)} · <b>${full(i, p.v)}</b>`, e.pageX - 60, e.pageY);
    }).on("pointerleave", () => { dot.style("opacity", 0); hideTip(); });
  });
};
