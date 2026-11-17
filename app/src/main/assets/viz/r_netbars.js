// bars above / below zero with value labels (net flows, % changes)
// data: { unit: "₹ Cr" | "%" | "", yLabel, xLabel, horizontal?: true, color?: "blue" (one colour instead of green / red), signed?: true,
//         items: [{ label, value, note? }] }
R.netbars = function (d, h) {
  const items = (d.items || []).filter(i => i && isFinite(i.value)), s = svg(h);
  if (!items.length) { s.append("text").attr("class", "lbl").attr("x", W() / 2).attr("y", h / 2).attr("text-anchor", "middle").text("No data"); return; }
  const unit = d.unit || "", money = unit.indexOf("₹") >= 0, pc = unit === "%", suffix = unit.replace("₹", "").trim();
  const num = a => a >= 1000 ? Math.round(a).toLocaleString("en-IN") : pc ? a.toFixed(a < 10 ? 2 : 1) : a.toFixed(a < 10 ? 2 : 1).replace(/\.?0+$/, "");
  const tick = v => (v < 0 ? "−" : "") + (money ? "₹" : "") + (pc ? String(+Math.abs(v).toFixed(1)) : num(Math.abs(v))) + (pc ? "%" : "");
  const full = v => (v < 0 ? "−" : d.signed && v > 0 ? "+" : "") + (money ? "₹" : "") + num(Math.abs(v)) + (pc ? "%" : suffix ? " " + suffix : "");
  const fill = i => d.color ? (C[d.color] || C.blue) : i.value >= 0 ? C.green : C.red;
  const tap = (e, i) => showTip(`${i.label} <b>${full(i.value)}</b>${i.note ? "<br>" + i.note : ""}`, e.pageX, e.pageY);
  const lo = Math.min(0, d3.min(items, i => i.value)), hi = Math.max(0, d3.max(items, i => i.value)), span = (hi - lo) || 1;

  if (d.horizontal) {
    const top = 16, bot = 36, Rg = 66, L = Math.min(118, 14 + 6.3 * d3.max(items, i => String(i.label).length)), bh = (h - top - bot) / items.length;
    const x = d3.scaleLinear().domain([lo, hi || 1]).nice().range([L, W() - Rg]);
    const gx = s.append("g").attr("transform", `translate(0,${h - bot})`).call(d3.axisBottom(x).ticks(W() < 420 ? 3 : 4).tickFormat(tick).tickSize(-(h - top - bot)));
    gx.select(".domain").remove(); gx.selectAll("line").attr("stroke", C.line).attr("stroke-opacity", .55).attr("stroke-dasharray", "2 3"); gx.selectAll("text").attr("class", "lbl").attr("dy", 10);
    s.append("line").attr("x1", x(0)).attr("x2", x(0)).attr("y1", top).attr("y2", h - bot).attr("stroke", C.muted).attr("stroke-opacity", .7);
    s.append("text").attr("class", "axl").attr("x", 2).attr("y", 9).text(d.yLabel || "Name");
    s.append("text").attr("class", "axl").attr("x", W() - 2).attr("y", h - 4).attr("text-anchor", "end").text(d.xLabel || (unit ? `Value (${unit})` : "Value"));
    const g = s.selectAll("g.r").data(items).join("g").attr("class", "r").attr("transform", (i, k) => `translate(0,${top + k * bh})`);
    g.append("rect").attr("width", W()).attr("height", bh).attr("fill", "transparent").on("click", tap);
    g.append("text").attr("class", "lbl").attr("y", bh / 2 + 4).style("pointer-events", "none").text(i => i.label);
    const bw = Math.min(14, Math.max(4, bh - 6));
    g.append("rect").attr("rx", 4).attr("y", (bh - bw) / 2).attr("height", bw).attr("x", x(0)).attr("width", 0).attr("fill", fill).on("click", tap)
      .transition().duration(T).delay((i, k) => k * 45).attr("x", i => x(Math.min(0, i.value))).attr("width", i => Math.max(2, Math.abs(x(i.value) - x(0))));
    g.append("text").attr("class", "val").attr("x", W() - 2).attr("text-anchor", "end").attr("y", bh / 2 + 4).style("pointer-events", "none").text(i => full(i.value));
    return;
  }

  const x = d3.scaleBand().domain(items.map(i => i.label)).range([M.l, W() - M.r]).padding(items.length > 6 ? 0.25 : 0.38);
  const y = d3.scaleLinear().domain([lo - (lo < 0 ? span * 0.16 : 0), hi + (hi > 0 ? span * 0.16 : 0)]).nice().range([h - M.b, M.t + 6]);
  axes(s, x, y, h, { yFmt: tick, yLabel: d.yLabel || (unit ? `Net (${unit})` : "Net"), xLabel: d.xLabel || "" });
  s.append("line").attr("x1", M.l).attr("x2", W() - M.r).attr("y1", y(0)).attr("y2", y(0)).attr("stroke", C.muted).attr("stroke-opacity", .8);
  const g = s.selectAll("g.i").data(items).join("g").attr("class", "i").attr("transform", i => `translate(${x(i.label)},0)`);
  g.append("rect").attr("rx", 4).attr("width", x.bandwidth()).attr("y", y(0)).attr("height", 0).attr("fill", fill).on("click", tap)
    .transition().duration(T).delay((i, k) => k * 60).attr("y", i => y(Math.max(0, i.value))).attr("height", i => Math.max(1, Math.abs(y(i.value) - y(0))));
  const small = x.bandwidth() < 46;
  g.append("text").attr("class", "val").attr("text-anchor", "middle").attr("x", x.bandwidth() / 2).style("font-size", small ? "9.5px" : "11px").style("pointer-events", "none")
    .attr("y", i => i.value >= 0 ? y(i.value) - 5 : y(i.value) + 13).text(i => small ? tick(i.value) : full(i.value))
    .style("opacity", 0).transition().duration(300).delay(T).style("opacity", 1);
};
