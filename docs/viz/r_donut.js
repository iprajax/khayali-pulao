// donut with legend and centre figure — data: { centre, centreLabel, nameLabel, valueLabel, unit: "" | "₹ Cr",
//   (the unit is printed once, in the legend header)
//   items: [{ label, value, color: "green" | "red" | "blue" | "saffron" | "violet" | "pink" | "muted" }] }
// The legend is the chart's two "axes": what each slice is (nameLabel) and how big it is (valueLabel + share of the whole).
R.donut = function (d, h) {
  const items = (d.items || []).filter(i => i.value > 0), s = svg(h), w = W(), total = d3.sum(items, i => i.value);
  if (!items.length) { s.append("text").attr("class", "lbl").attr("x", w / 2).attr("y", h / 2).attr("text-anchor", "middle").text("Nothing to show"); return; }
  const order = [C.blue, C.saffron, C.violet, C.green, C.pink, C.muted], col = (i, k) => C[i.color] || order[k % order.length];
  const money = (d.unit || "").indexOf("₹") >= 0, suffix = (d.unit || "").replace("₹", "").trim();
  const bare = v => (money ? "₹" : "") + Math.round(v).toLocaleString("en-IN"), fmt = v => bare(v) + (suffix ? " " + suffix : "");
  const share = v => { const p = v / total * 100; return (p >= 10 || p === 0 ? p.toFixed(0) : p.toFixed(1)) + "%"; };
  const r = Math.min(h - 8, w * 0.37) / 2, cx = r + 2, cy = h / 2;
  const arc = d3.arc().innerRadius(r * 0.66).outerRadius(r).cornerRadius(4).padAngle(items.length > 1 ? 0.025 : 0);
  const g = s.append("g").attr("transform", `translate(${cx},${cy})`);
  g.selectAll("path").data(d3.pie().value(i => i.value).sort(null)(items)).join("path").attr("fill", (a, k) => col(a.data, k))
    .on("click", (e, a) => { e.stopPropagation(); showTip(`<b>${a.data.label}</b> ${fmt(a.data.value)} · ${share(a.data.value)}`, e.pageX, e.pageY); })
    .transition().duration(T).attrTween("d", a => { const i = d3.interpolate({ startAngle: a.startAngle, endAngle: a.startAngle }, a); return t => arc(i(t)); });
  if (d.centre != null) { const c = String(d.centre), fs = Math.max(11, Math.min(20, (r * 1.15) / Math.max(1, c.length) * 1.8));
    g.append("text").attr("class", "big").attr("text-anchor", "middle").attr("y", d.centreLabel ? 2 : 6).style("font-size", fs + "px").text(c); }
  if (d.centreLabel) g.append("text").attr("class", "lbl").attr("text-anchor", "middle").attr("y", 18).style("font-size", "10px").text(d.centreLabel);
  // legend: name · value · share, with a header naming the columns
  const lx = cx + r + 16, rowH = Math.min(26, (h - 26) / items.length), y0 = Math.max(24, (h - rowH * items.length) / 2 + 12), pw = 36;
  s.append("text").attr("class", "axl").attr("x", lx).attr("y", y0 - 14).text(d.nameLabel || "Part");
  s.append("text").attr("class", "axl").attr("x", w).attr("y", y0 - 14).attr("text-anchor", "end").text((d.valueLabel || "Value") + (d.unit ? ", " + d.unit : "") + " · share");
  const row = s.selectAll("g.lg").data(items).join("g").attr("class", "lg").attr("transform", (i, k) => `translate(0,${y0 + k * rowH})`).attr("opacity", 0);
  row.append("rect").attr("x", lx).attr("y", -1).attr("width", 9).attr("height", 9).attr("rx", 2).attr("fill", col)
    .on("click", (e, i) => showTip(`<b>${i.label}</b> ${fmt(i.value)} · ${share(i.value)}`, e.pageX, e.pageY));
  row.append("text").attr("class", "lbl").attr("x", w).attr("y", 8).attr("text-anchor", "end").text(i => share(i.value));
  const val = row.append("text").attr("class", "val").attr("x", w - pw).attr("y", 8).attr("text-anchor", "end").text(i => bare(i.value));
  row.append("text").attr("class", "lbl").attr("x", lx + 14).attr("y", 8).text(i => i.label).each(function (i, k) {
    const room = w - pw - val.nodes()[k].getComputedTextLength() - 8 - (lx + 14); let t = String(i.label), n = t.length;
    while (n > 3 && this.getComputedTextLength() > room) this.textContent = t.slice(0, --n) + "…"; });
  row.transition().duration(400).delay((i, k) => 200 + k * 70).attr("opacity", 1);
};
