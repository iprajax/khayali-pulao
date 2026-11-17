// horizontal diverging bars — data: { unit: "%" | "x" | "₹ Cr" | …, digits, xLabel, yLabel, legend: [upText, downText, flatText?],
//   items: [{ label, value, tone?: "up" | "down" | "flat" (colour; default = sign of value), note?: extra text for the tap tip }] }
// Bars grow left (negative) or right (positive) from a zero line. The value is printed beside every bar,
// the value axis is drawn with ticks at the bottom, and both axes are named.
R.divbars = function (d, h) {
  const items = (d.items || []).filter(i => typeof i.value === "number" && isFinite(i.value)), s = svg(h), w = W();
  if (!items.length) { s.append("text").attr("class", "lbl").attr("x", w / 2).attr("y", h / 2).attr("text-anchor", "middle").text("Nothing to show"); return; }
  const unit = d.unit || "", dg = d.digits == null ? (unit === "x" ? 1 : 2) : d.digits;
  const num = v => Math.abs(v).toLocaleString("en-IN", { minimumFractionDigits: dg, maximumFractionDigits: dg });
  const fmt = v => unit === "%" ? (v > 0 ? "+" : v < 0 ? "−" : "") + num(v) + "%" : unit === "x" ? num(v) + "×" : (v < 0 ? "−" : "") + num(v);
  const tick = v => unit === "%" ? (v > 0 ? "+" : v < 0 ? "−" : "") + Math.abs(v) + "%" : unit === "x" ? v + "×" : (v < 0 ? "−" : "") + Math.abs(v).toLocaleString("en-IN");
  const tone = i => i.tone || (i.value > 0 ? "up" : i.value < 0 ? "down" : "flat"), col = i => ({ up: C.green, down: C.red, flat: C.blue })[tone(i)] || C.blue;
  const top = 20, bottom = 36, bh = (h - top - bottom) / items.length, bar = Math.max(4, Math.min(14, bh - 4));
  // label column: as wide as the longest label needs, but never more than a third of the chart
  const probe = s.append("text").attr("class", "lbl"), maxL = Math.floor(w * 0.34);
  const cut = t => { probe.text(t); let k = t.length; while (k > 3 && probe.node().getComputedTextLength() > maxL) probe.text(t.slice(0, --k) + "…"); return probe.text(); };
  const names = items.map(i => cut(String(i.label || ""))); let L = 0;
  names.forEach(n => { probe.text(n); L = Math.max(L, probe.node().getComputedTextLength()); });
  probe.text(items.map(i => fmt(i.value)).sort((a, b) => b.length - a.length)[0]); const VW = probe.node().getComputedTextLength() + 8; probe.remove();
  const lo = Math.min(0, d3.min(items, i => i.value)), hi = Math.max(0, d3.max(items, i => i.value));
  const x = d3.scaleLinear().domain(lo === hi ? [0, 1] : [lo, hi]).nice(4).range([L + 10, w - VW - 4]);
  // value axis (bottom): grid lines, tick values, axis name
  const gx = s.append("g").attr("transform", `translate(0,${h - bottom})`).call(d3.axisBottom(x).ticks(w < 400 ? 4 : 5).tickFormat(tick).tickSize(-(h - top - bottom)));
  gx.select(".domain").remove(); gx.selectAll("line").attr("stroke", C.line).attr("stroke-opacity", .55).attr("stroke-dasharray", "2 3"); gx.selectAll("text").attr("class", "lbl").attr("dy", 10);
  s.append("line").attr("x1", x(0)).attr("x2", x(0)).attr("y1", top).attr("y2", h - bottom).attr("stroke", C.muted).attr("stroke-opacity", .7);
  s.append("text").attr("class", "axl").attr("x", w - 2).attr("y", h - 4).attr("text-anchor", "end").text(d.xLabel || (unit ? `Value (${unit})` : "Value"));
  s.append("text").attr("class", "axl").attr("x", 0).attr("y", 9).text(d.yLabel || "Name");
  // legend (top right) when more than one colour is in use
  const used = new Set(items.map(tone)), lg = d.legend || ["Up", "Down"];
  if (used.size > 1) { let rx = w - 2;
    [["flat", lg[2] || "Flat", C.blue], ["down", lg[1], C.red], ["up", lg[0], C.green]].filter(e => used.has(e[0])).forEach(([, k, c]) => { const t = s.append("text").attr("class", "lbl").style("font-size", "10px").attr("x", rx).attr("y", 9).attr("text-anchor", "end").text(k);
      rx -= t.node().getComputedTextLength() + 5; s.append("rect").attr("x", rx - 8).attr("y", 2).attr("width", 8).attr("height", 8).attr("rx", 2).attr("fill", c).style("pointer-events", "none"); rx -= 18; }); }
  const tipOf = (e, i) => showTip(`<b>${i.label}</b> ${fmt(i.value)}${i.note ? " · " + i.note : ""}`, e.pageX, e.pageY);
  const g = s.selectAll("g.r").data(items).join("g").attr("class", "r").attr("transform", (i, k) => `translate(0,${top + k * bh})`);
  g.append("rect").attr("x", 0).attr("width", w).attr("height", bh).attr("fill", "transparent").on("click", tipOf);      // whole row is tappable
  g.append("text").attr("class", "lbl").attr("y", bh / 2 + 4).style("pointer-events", "none").text((i, k) => names[k]);
  g.append("rect").attr("rx", 3).attr("y", (bh - bar) / 2).attr("height", bar).attr("x", x(0)).attr("width", 0).attr("fill", col).on("click", tipOf)
    .transition().duration(T).delay((i, k) => k * 30).attr("x", i => x(Math.min(0, i.value))).attr("width", i => Math.max(1.5, Math.abs(x(i.value) - x(0))));
  g.append("text").attr("class", "val").attr("x", w).attr("text-anchor", "end").attr("y", bh / 2 + 4).style("pointer-events", "none").style("font-size", bh < 15 ? "10px" : null).text(i => fmt(i.value));
};
