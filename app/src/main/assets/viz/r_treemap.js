// treemap heatmap — data: { items: [{ label, value (tile size), change (% → colour), price?, mine? }] }
R.treemap = function (d, h) {
  const items = (d.items || []).filter(i => i.value > 0), s = svg(h);
  if (!items.length) { s.append("text").attr("class", "lbl").attr("x", W() / 2).attr("y", h / 2).attr("text-anchor", "middle").text("No data"); return; }
  const lim = Math.max(2, d3.quantile(items.map(i => Math.abs(i.change)).sort(d3.ascending), 0.9) || 2);
  const col = d3.scaleLinear().domain([-lim, 0, lim]).range([C.red, "#3a4656", C.green]).clamp(true);
  const lh = 22, root = d3.hierarchy({ children: items }).sum(i => Math.sqrt(i.value));     // sqrt keeps small names readable
  d3.treemap().size([W(), h - lh]).paddingInner(2).round(true)(root);
  const g = s.selectAll("g.t").data(root.leaves()).join("g").attr("class", "t").attr("transform", n => `translate(${n.x0},${n.y0})`);
  g.append("rect").attr("rx", 5).attr("width", n => n.x1 - n.x0).attr("height", n => n.y1 - n.y0).attr("fill", n => col(n.data.change)).attr("opacity", 0)
    .attr("stroke", n => n.data.mine ? C.saffron : "none").attr("stroke-width", 2)
    .on("click", (e, n) => showTip(`<b>${n.data.label}</b> ${n.data.price ? "₹" + n.data.price.toLocaleString("en-IN") + " · " : ""}${pct(n.data.change)}`, e.pageX, e.pageY))
    .transition().duration(400).delay((n, i) => i * 8).attr("opacity", 1);
  g.append("text").attr("x", 5).attr("y", 14).attr("class", "val").style("font-size", "10.5px").style("pointer-events", "none")
    .text(n => { const w = n.x1 - n.x0, hh = n.y1 - n.y0, t = (n.data.mine ? "★" : "") + n.data.label; return hh > 17 && w > 30 ? t.slice(0, Math.max(2, Math.floor((w - 8) / 6.4))) : ""; });
  g.append("text").attr("x", 5).attr("y", 27).attr("class", "lbl").style("font-size", "10px").style("fill", "#dfe6ee").style("pointer-events", "none")
    .text(n => (n.x1 - n.x0) > 44 && (n.y1 - n.y0) > 32 ? pct(n.data.change) : "");
  // colour scale legend with its values
  const lw = Math.min(180, W() - 120), lx = (W() - lw) / 2, ly = h - 12;
  const grad = s.append("defs").append("linearGradient").attr("id", "tmg");
  [[0, C.red], [0.5, "#3a4656"], [1, C.green]].forEach(([o, c]) => grad.append("stop").attr("offset", o).attr("stop-color", c));
  s.append("rect").attr("x", lx).attr("y", ly - 6).attr("width", lw).attr("height", 6).attr("rx", 3).attr("fill", "url(#tmg)");
  s.append("text").attr("class", "lbl").attr("x", lx - 6).attr("y", ly).attr("text-anchor", "end").text("−" + lim.toFixed(1) + "%");
  s.append("text").attr("class", "lbl").attr("x", lx + lw + 6).attr("y", ly).text("+" + lim.toFixed(1) + "%");
};
