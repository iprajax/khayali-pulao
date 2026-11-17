// vertical bars by year with value labels and both axes — data: { unit: "%", yLabel, xLabel?, avg?, avgLabel?, seriesLabel?, items: [{ label: "2025", value }] }
// The latest bar is highlighted; an optional dashed line marks an average.
R.yearbars = function (d, h) {
  const items = (d.items || []).filter(i => i.value != null && !isNaN(+i.value)).map(i => ({ label: String(i.label), value: +i.value })), s = svg(h), unit = d.unit || "";
  if (!items.length) { s.append("text").attr("class", "lbl").attr("x", W() / 2).attr("y", h / 2).attr("text-anchor", "middle").text("No yearly data to show"); return; }
  const vals = items.map(i => i.value).concat(d.avg == null ? [] : [d.avg]), lo = Math.min(0, d3.min(vals)), hi = Math.max(0, d3.max(vals));
  const x = d3.scaleBand().domain(items.map(i => i.label)).range([M.l, W() - M.r]).padding(0.22);
  const y = d3.scaleLinear().domain([lo, hi || 1]).nice().range([h - M.b - (lo < 0 ? 12 : 0), M.t + 30]);
  const every = Math.max(1, Math.ceil(30 / x.step())), n = items.length, tight = x.step() < 34;
  axes(s, x, y, h, { yFmt: v => v + unit, yLabel: d.yLabel || "Value", xLabel: d.xLabel || "Year",
    xValues: items.map(i => i.label).filter((l, k) => (n - 1 - k) % every === 0), xFmt: l => tight && l.length === 4 ? "’" + l.slice(2) : l });
  const one = v => (v < 0 ? "−" : "") + Math.abs(v).toFixed(1), last = items[n - 1];
  const fill = (i, k) => i.value < 0 ? C.red : k === n - 1 ? C.saffron : C.blue;
  const key = [[`Latest (${last.label})`, C.saffron, false]];
  if (d.avg != null) { s.append("line").attr("x1", M.l).attr("x2", W() - M.r).attr("y1", y(d.avg)).attr("y2", y(d.avg)).attr("stroke", C.violet).attr("stroke-width", 1.5).attr("stroke-dasharray", "4 3");
    key.push([d.avgLabel || "Average", C.violet, true]); }
  if (lo < 0) s.append("line").attr("x1", M.l).attr("x2", W() - M.r).attr("y1", y(0)).attr("y2", y(0)).attr("stroke", C.muted);
  s.selectAll("rect.b").data(items).join("rect").attr("class", "b").attr("x", i => x(i.label)).attr("width", x.bandwidth()).attr("rx", Math.min(3, x.bandwidth() / 3))
    .attr("fill", fill).attr("y", y(0)).attr("height", 0)
    .on("click", (e, i) => showTip(`${i.label} · <b>${one(i.value)}${unit}</b>`, e.pageX - 40, e.pageY))
    .transition().duration(T).delay((i, k) => k * 25).attr("y", i => y(Math.max(0, i.value))).attr("height", i => Math.max(1, Math.abs(y(i.value) - y(0))));
  s.selectAll("text.v").data(items).join("text").attr("class", "v lbl").style("font-size", x.step() < 22 ? "8.5px" : "10px").style("pointer-events", "none").style("paint-order", "stroke").attr("stroke", "rgba(22,30,42,.9)").attr("stroke-width", 2)
    .attr("fill", (i, k) => k === n - 1 ? C.text : C.muted).style("fill", (i, k) => k === n - 1 ? C.text : null)
    .attr("x", i => x(i.label) + x.bandwidth() / 2).attr("y", i => i.value >= 0 ? y(i.value) - 4 : y(i.value) + 10).attr("text-anchor", "middle").text(i => one(i.value))
    .attr("opacity", 0).transition().duration(300).delay(T).attr("opacity", 1);
  legend(s, key, M.t + 8);
};
