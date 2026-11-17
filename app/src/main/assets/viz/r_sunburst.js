// sunburst (two rings) — data: { centre: "Portfolio value", unit: "₹" | "", inner: "group", outer: "stock", measure: "share of portfolio value",
//   root: { name, children: [{ name, color ("green" | "red" | …), children: [{ name, value, note? }] }] } }
// The centre prints the total; tap a segment to read its value and share there (and in a tip); tap the centre to go back.
R.sunburst = function (d, h) {
  const s = svg(h), money = (d.unit || "").indexOf("₹") >= 0, fmt = v => money ? short(v) : Math.round(v).toLocaleString("en-IN") + (d.unit ? " " + d.unit : "");
  const groups = ((d.root || {}).children || []).map(g => ({ ...g, children: (g.children || []).filter(c => c.value > 0) })).filter(g => g.children.length);
  if (!groups.length) { s.append("text").attr("class", "lbl").attr("x", W() / 2).attr("y", h / 2).attr("text-anchor", "middle").text("Nothing to show"); return; }
  const root = d3.hierarchy({ name: (d.root || {}).name || "", children: groups }).sum(n => n.children ? 0 : n.value);
  root.each(n => { if (n.children) n.children.sort((a, b) => b.value - a.value); });
  d3.partition().size([2 * Math.PI, 1])(root);
  const total = root.value, footH = 46, r = Math.max(40, Math.min(W() - 8, h - footH - 4) / 2), cx = W() / 2, cy = r + 2;
  const r0 = r * 0.36, r1 = r * 0.56, rad = n => n.depth === 1 ? [r0 + 2, r1] : [r1 + 2, r];
  const arc = d3.arc().startAngle(n => n.x0).endAngle(n => n.x1).innerRadius(n => rad(n)[0]).outerRadius(n => rad(n)[1]).padAngle(0.008).cornerRadius(3);
  const base = n => C[(n.depth === 1 ? n : n.parent).data.color] || C.blue;
  const fill = n => { if (n.depth === 1) return base(n); const k = n.parent.children.indexOf(n), m = n.parent.children.length;
    return d3.interpolateRgb(base(n), "#1E2A38")(0.12 + 0.5 * (m > 1 ? k / (m - 1) : 0)); };
  const share = n => (n.value / total * 100).toFixed(1) + "%";
  const g = s.append("g").attr("transform", `translate(${cx},${cy})`), nodes = root.descendants().filter(n => n.depth > 0);
  // centre: total by default, the tapped segment after a tap
  const c1 = g.append("text").attr("class", "lbl").attr("text-anchor", "middle").attr("y", -14), c2 = g.append("text").attr("class", "big").attr("text-anchor", "middle").attr("y", 8).style("font-size", r0 < 44 ? "14px" : "17px"),
    c3 = g.append("text").attr("class", "lbl").attr("text-anchor", "middle").attr("y", 24);
  const clip = (t, n) => t.length > n ? t.slice(0, n - 1) + "…" : t, cn = Math.max(6, Math.floor(r0 * 2 / 6.4));
  const centre = n => { c1.text(clip(n ? n.data.name : d.centre || "Total", cn)); c2.text(fmt(n ? n.value : total)); c3.text(n ? share(n) + " of total" : (k => k + " " + (d.outer || "item") + (k === 1 ? "" : "s"))(nodes.filter(k => k.depth === 2).length)); };
  centre(null);
  const paths = g.selectAll("path").data(nodes).join("path").attr("fill", fill).attr("stroke", "none")
    .on("click", function (e, n) { e.stopPropagation(); paths.attr("opacity", k => k === n || k.parent === n || n.parent === k ? 1 : 0.35); centre(n);
      showTip(`<b>${n.data.name}</b><br>${fmt(n.value)} · ${share(n)}${n.data.note ? "<br>" + n.data.note : ""}`, Math.min(e.pageX, W() - 190), e.pageY - 30); });
  paths.transition().duration(T).attrTween("d", n => { const i = d3.interpolate({ x0: 0, x1: 0 }, { x0: n.x0, x1: n.x1 }); return t => { const p = i(t); return arc({ x0: p.x0, x1: p.x1, depth: n.depth }); }; });
  g.append("circle").attr("r", r0 - 2).attr("fill", "transparent").on("click", e => { e.stopPropagation(); paths.attr("opacity", 1); centre(null); hideTip(); });
  // labels along the radius on the outer ring, where the segment is wide enough to hold a line of text
  const mid = (r1 + r) / 2, room = Math.max(3, Math.floor((r - r1 - 8) / 5.9));
  g.selectAll("text.sl").data(nodes.filter(n => n.depth === 2 && (n.x1 - n.x0) * mid > 13)).join("text").attr("class", "val sl").style("font-size", "10px").style("pointer-events", "none")
    .attr("text-anchor", "middle").attr("dy", "0.35em")
    .attr("transform", n => { const a = (n.x0 + n.x1) / 2 * 180 / Math.PI; return `rotate(${a - 90}) translate(${mid},0) rotate(${a < 180 ? 0 : 180})`; })
    .text(n => { const wide = (n.x1 - n.x0) * mid > 26, t = clip(n.data.name, room); return wide && t.length + 5 <= room ? t + " " + Math.round(n.value / total * 100) + "%" : t; })
    .attr("opacity", 0).transition().delay(T * 0.6).duration(300).attr("opacity", 1);
  // legend: each inner-ring group with its share, then what the rings and the arc length mean
  let lx = 0; const ly = cy + r + 16, lg = s.append("g");
  root.children.forEach(n => { lg.append("circle").attr("cx", lx + 5).attr("cy", ly - 4).attr("r", 5).attr("fill", base(n));
    const t = lg.append("text").attr("class", "lbl").attr("x", lx + 14).attr("y", ly).text(`${n.data.name} ${share(n)}`); lx += 28 + t.node().getComputedTextLength(); });
  lg.attr("transform", `translate(${Math.max(2, (W() - lx + 14) / 2)},0)`);
  s.append("text").attr("class", "axl").attr("x", W() / 2).attr("y", ly + 15).attr("text-anchor", "middle").text(`Inner ring: ${d.inner || "group"} · outer ring: ${d.outer || "item"}`);
  s.append("text").attr("class", "axl").attr("x", W() / 2).attr("y", ly + 27).attr("text-anchor", "middle").text(`Arc length = ${d.measure || "share of the total"}`);
};
