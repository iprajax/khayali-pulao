// range gauge — where a value sits between a low and a high, on a labelled value axis.
// data: { title, axis, unit ("₹" default, "" for plain numbers), min, max, value, minLabel, maxLabel, valueLabel, marks: [{ label, value }] }
R.gauge = function (d, h) {
  const s = svg(h), lo = +d.min, hi = +d.max, v = +d.value, marks = (d.marks || []).filter(m => m && isFinite(+m.value)).slice(0, 4);
  if (d.min == null || d.max == null || d.value == null || !isFinite(lo) || !isFinite(hi) || !isFinite(v) || hi <= lo) {
    s.append("text").attr("class", "lbl").attr("x", W() / 2).attr("y", h / 2).attr("text-anchor", "middle").text("No range to show"); return; }
  const unit = d.unit == null ? "₹" : d.unit, f = n => unit + (+n).toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const all = [lo, hi, v].concat(marks.map(m => +m.value)), a = d3.min(all), b = d3.max(all), pad = (b - a) * 0.03;
  const L = 16, Rg = 16, x = d3.scaleLinear().domain([a - pad, b + pad]).range([L, W() - Rg]), ty = 40, th = 10, axisY = h - 32;
  const where = Math.round(Math.max(0, Math.min(1, (v - lo) / (hi - lo))) * 100), mcols = [C.saffron, C.violet, C.pink, C.green];
  // value axis (x) with its name; the row itself is named by the title
  const gx = s.append("g").attr("transform", `translate(0,${axisY})`).call(d3.axisBottom(x).ticks(Math.max(2, Math.floor(W() / 80))).tickFormat(unit === "₹" ? px : n => unit + n).tickSize(4));
  gx.select(".domain").attr("stroke", C.line); gx.selectAll("line").attr("stroke", C.line); gx.selectAll("text").attr("class", "lbl");
  s.append("text").attr("class", "axl").attr("x", W() - Rg).attr("y", h - 4).attr("text-anchor", "end").text(d.axis || "Value");
  s.append("text").attr("class", "axl").attr("x", 2).attr("y", 9).text(d.title || "Range");
  // the range, and how much of it lies below the value
  const say = e => showTip(`${d.valueLabel || "Value"} <b>${f(v)}</b> · ${where}% up the range`, e.pageX, e.pageY);
  s.append("rect").attr("x", x(lo)).attr("y", ty).attr("width", x(hi) - x(lo)).attr("height", th).attr("rx", 5).attr("fill", C.line).on("click", say);
  const xv = x(Math.max(lo, Math.min(hi, v)));
  s.append("rect").attr("x", x(lo)).attr("y", ty).attr("width", 0).attr("height", th).attr("rx", 5).attr("fill", C.blue).attr("opacity", .9).on("click", say)
    .transition().duration(T).attr("width", Math.max(0, xv - x(lo)));
  [lo, hi].forEach(e => s.append("line").attr("x1", x(e)).attr("x2", x(e)).attr("y1", ty - 4).attr("y2", ty + th + 4).attr("stroke", C.muted).attr("stroke-width", 1.5));
  // marks: a coloured tick on the bar, named in a wrapping key underneath
  let kx = L, ky = 100;
  marks.forEach((m, i) => { const c = mcols[i], mx = x(+m.value);
    s.append("rect").attr("x", mx - 1.5).attr("y", ty - 5).attr("width", 3).attr("height", th + 10).attr("rx", 1.5).attr("fill", c)
      .on("click", e => showTip(`${m.label} <b>${f(m.value)}</b>`, e.pageX, e.pageY));
    const g = s.append("g"), sw = g.append("rect").attr("width", 3).attr("height", 11).attr("rx", 1.5).attr("fill", c);
    const t = g.append("text").attr("class", "lbl").text(m.label + " "); t.append("tspan").attr("class", "val").text(f(m.value));
    const w = 9 + t.node().getComputedTextLength(); if (kx > L && kx + w > W() - Rg) { kx = L; ky += 15; }
    sw.attr("x", kx).attr("y", ky - 10); t.attr("x", kx + 8).attr("y", ky); kx += w + 14; });
  // the value: a marker that slides in from the low end, labelled above
  s.append("circle").attr("cx", x(lo)).attr("cy", ty + th / 2).attr("r", 7).attr("fill", C.text).attr("stroke", "#1E2A38").attr("stroke-width", 2).style("pointer-events", "none")
    .transition().duration(T).attr("cx", x(v));
  const vt = s.append("text").attr("class", "tag").attr("fill", C.text).attr("y", ty - 12).text((d.valueLabel || "Value") + " " + f(v) + " · " + where + "% up the range");
  const half = vt.node().getComputedTextLength() / 2; vt.attr("text-anchor", "middle").attr("x", Math.max(half + 2, Math.min(W() - half - 2, x(v))));
  // both ends, named and valued
  [[lo, d.minLabel || "Low", "start"], [hi, d.maxLabel || "High", "end"]].forEach(([e, name, anchor]) => {
    s.append("text").attr("class", "lbl").attr("x", x(e)).attr("y", ty + th + 18).attr("text-anchor", anchor).text(name);
    s.append("text").attr("class", "val").attr("x", x(e)).attr("y", ty + th + 31).attr("text-anchor", anchor).text(f(e)); });
};
