// streamgraph — how a whole splits between a few parts over time. Band thickness is the value; the left axis is a ruler for it.
// data: { unit ("%"), yLabel, xLabel, series: ["Promoters", "Public", …], points: [{ t: "2026-09-30", label?, values: [a, b, …] }] }
R.stream = function (d, h) {
  const s = svg(h), series = d.series || [], unit = d.unit || "", cols = [C.blue, C.green, C.saffron, C.violet, C.pink];
  const pts = (d.points || []).map(p => ({ t: new Date(p.t), label: p.label, v: (p.values || []).map(Number) })).filter(p => !isNaN(p.t) && p.v.length >= series.length && p.v.every(isFinite)).sort((a, b) => a.t - b.t);
  if (!pts.length || !series.length) { s.append("text").attr("class", "lbl").attr("x", W() / 2).attr("y", h / 2).attr("text-anchor", "middle").text("No filings to draw"); return; }
  const one = pts.length === 1, x0 = M.l, x1 = W() - M.r, top = M.t + 20, fmtV = v => (+v).toFixed(2).replace(/\.?0+$/, "") + unit, fmtD = d3.timeFormat("%d %b %Y");
  const stack = d3.stack().keys(d3.range(series.length)).value((p, k) => Math.max(0, p.v[k])).offset(d3.stackOffsetSilhouette)(pts);
  const lo = d3.min(stack, l => d3.min(l, q => q[0])), hi = d3.max(stack, l => d3.max(l, q => q[1]));
  const x = one ? d3.scalePoint().domain([pts[0].t]).range([(x0 + x1) / 2, (x0 + x1) / 2]) : d3.scaleTime().domain(d3.extent(pts, p => p.t)).range([x0, x1]);
  const y = d3.scaleLinear().domain([0, hi - lo]).range([h - M.b, top]);          // shifted so the ruler starts at 0
  axes(s, x, y, h, { yFmt: v => v + unit, yLabel: d.yLabel || "Value", xLabel: d.xLabel || "Date", xFmt: one ? fmtD : d3.timeFormat("%b '%y"), xTicks: Math.max(2, Math.floor((W() - M.l) / 76)) });
  // bands, revealed left to right
  const clip = s.append("defs").append("clipPath").attr("id", "stc").append("rect").attr("x", x0).attr("y", 0).attr("height", h).attr("width", 0);
  clip.transition().duration(900).attr("width", x1 - x0);
  const X = (q, i) => one ? (i ? x1 : x0) : x(q.data.t);
  const area = d3.area().x(X).y0(q => y(q[0] - lo)).y1(q => y(q[1] - lo)).curve(d3.curveMonotoneX);
  s.append("g").attr("clip-path", "url(#stc)").selectAll("path").data(one ? stack.map(l => Object.assign([l[0], l[0]], { key: l.key })) : stack).join("path")
    .attr("d", area).attr("fill", l => cols[l.key % cols.length]).attr("fill-opacity", .82).attr("stroke", "#1E2A38").attr("stroke-width", 1);
  // latest value printed inside each band that is thick enough to hold it
  const lastI = pts.length - 1;
  stack.forEach(l => { const q = l[lastI], y0 = y(q[0] - lo), y1 = y(q[1] - lo);
    if (y0 - y1 >= 15) s.append("text").attr("class", "val").attr("x", x1 - 5).attr("y", (y0 + y1) / 2 + 4).attr("text-anchor", "end").style("pointer-events", "none").text(fmtV(pts[lastI].v[l.key])); });
  legend(s, series.map((k, i) => [k, cols[i % cols.length], false]), M.t + 8);
  // tap anywhere: the nearest date, with every part's value
  const guide = s.append("line").attr("y1", top).attr("y2", h - M.b).attr("stroke", C.text).attr("stroke-opacity", 0).attr("stroke-dasharray", "2 3").style("pointer-events", "none");
  s.append("rect").attr("x", x0).attr("y", top).attr("width", x1 - x0).attr("height", h - M.b - top).attr("fill", "transparent").on("click", e => {
    const mx = d3.pointer(e)[0], i = one ? 0 : d3.leastIndex(pts, p => Math.abs(x(p.t) - mx)), p = pts[i], gx = one ? mx : x(p.t);
    guide.attr("x1", gx).attr("x2", gx).attr("stroke-opacity", .6);
    showTip(`<b>${p.label || fmtD(p.t)}</b><br>` + series.map((k, j) => `<span style="color:${cols[j % cols.length]}">■</span> ${k} <b>${fmtV(p.v[j])}</b>`).join("<br>"), Math.min(gx + 8, W() - 190), e.pageY - 20 * series.length); });
};
