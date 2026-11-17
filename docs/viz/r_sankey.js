// Sankey (money flow) — data: { unit, nodes: [{ id, label, col (0..n), color }], links: [{ from, to, value }] }
// Layered layout done by hand: nodes are stacked per column, each as tall as its larger side (in or out),
// and links are ribbons between them. Every node prints its value.
R.sankey = function (d, h) {
  const s = svg(h), nodes = (d.nodes || []).map(n => ({ ...n, in: 0, out: 0, inOff: 0, outOff: 0 })), byId = {};
  nodes.forEach(n => byId[n.id] = n);
  const links = (d.links || []).filter(l => byId[l.from] && byId[l.to] && l.value > 0).map(l => ({ ...l, a: byId[l.from], b: byId[l.to] }));
  if (!links.length) { s.append("text").attr("class", "lbl").attr("x", W() / 2).attr("y", h / 2).attr("text-anchor", "middle").text("No flow to show"); return; }
  links.forEach(l => { l.a.out += l.value; l.b.in += l.value; });
  nodes.forEach(n => n.v = Math.max(n.in, n.out));
  const cols = d3.groups(nodes.filter(n => n.v > 0), n => n.col).sort((a, b) => a[0] - b[0]);
  const lastCol = cols[cols.length - 1][0];
  // Labels: every column's labels sit to the RIGHT of its nodes; the last column gets a reserved gutter on the right,
  // so labels never cover another column's nodes. Label width is estimated from its text.
  // Phones: smaller label text, and values without the unit (the caption under the chart names it).
  const compact = W() < 400, CW = compact ? 5.2 : 5.8, textW = t => t.length * CW;
  const gutter = Math.min(W() * 0.34, 8 + d3.max(cols[cols.length - 1][1], n => Math.max(textW(n.label), 64)));
  const top = 6, bottom = h - 6, gap = 10, nw = 10, padL = 2;
  const scale = d3.min(cols, ([, ns]) => (bottom - top - gap * (ns.length - 1)) / d3.sum(ns, n => n.v));
  const x = d3.scalePoint().domain(cols.map(c => c[0])).range([padL, W() - gutter - nw]);
  cols.forEach(([c, ns]) => { let y = top + (bottom - top - (d3.sum(ns, n => n.v) * scale + gap * (ns.length - 1))) / 2;
    ns.forEach(n => { n.x = x(c); n.y = y; n.h = Math.max(2, n.v * scale); y += n.h + gap; }); });
  const colour = n => C[n.color] || C.blue;
  links.sort((p, q) => p.a.y - q.a.y || p.b.y - q.b.y);
  links.forEach(l => { l.y0 = l.a.y + l.a.outOff; l.a.outOff += l.value * scale; });
  links.slice().sort((p, q) => p.b.y - q.b.y || p.a.y - q.a.y).forEach(l => { l.y1 = l.b.y + l.b.inOff; l.b.inOff += l.value * scale; });
  const ribbon = l => { const w = Math.max(1, l.value * scale), x0 = l.a.x + nw, x1 = l.b.x, mx = (x0 + x1) / 2;
    return `M${x0},${l.y0}C${mx},${l.y0} ${mx},${l.y1} ${x1},${l.y1}L${x1},${l.y1 + w}C${mx},${l.y1 + w} ${mx},${l.y0 + w} ${x0},${l.y0 + w}Z`; };
  const fmt = (v, short) => (d.unit && d.unit.indexOf("₹") >= 0 ? "₹" : "") + (v >= 1000 ? Math.round(v).toLocaleString("en-IN") : v.toFixed(v < 10 ? 1 : 0)) + (d.unit && !short ? " " + d.unit.replace("₹", "").trim() : "");
  s.selectAll("path.l").data(links).join("path").attr("class", "l").attr("d", ribbon).attr("fill", l => colour(l.b)).attr("opacity", 0)
    .on("click", (e, l) => showTip(`${l.a.label} → <b>${l.b.label}</b> · ${fmt(l.value)}`, e.pageX, e.pageY))
    .transition().duration(600).delay((l, i) => i * 60).attr("opacity", 0.38);
  const shown = nodes.filter(n => n.v > 0);
  s.selectAll("rect.n").data(shown).join("rect").attr("class", "n").attr("x", n => n.x).attr("y", n => n.y).attr("width", nw).attr("height", n => n.h).attr("rx", 3).attr("fill", colour)
    .on("click", (e, n) => showTip(`<b>${n.label}</b> ${fmt(n.v)}`, e.pageX, e.pageY));
  // Two-line labels (name, value), 26 px tall, centred on their node, then pushed apart within each column.
  const LH = 26;
  cols.forEach(([c, ns]) => {
    const ls = ns.map(n => ({ n, y: n.y + n.h / 2 - LH / 2 })).sort((a, b) => a.y - b.y);
    for (let i = 1; i < ls.length; i++) ls[i].y = Math.max(ls[i].y, ls[i - 1].y + LH);
    const over = ls.length ? ls[ls.length - 1].y + LH - h : 0;
    if (over > 0) { ls.forEach(l => l.y -= over); for (let i = ls.length - 2; i >= 0; i--) ls[i].y = Math.min(ls[i].y, ls[i + 1].y - LH); }
    const room = c === lastCol ? gutter - 8 : (x(c + 1) !== undefined ? x(cols[cols.findIndex(k => k[0] === c) + 1][0]) - x(c) - nw - 10 : 80);
    ls.forEach(({ n, y }) => {
      const lx = n.x + nw + 5, maxChars = Math.max(4, Math.floor(room / CW)), name = n.label.length > maxChars ? n.label.slice(0, maxChars - 1) + "…" : n.label;
      const g = s.append("g").style("pointer-events", "none");
      const val = fmt(n.v, compact);
      g.append("rect").attr("x", lx - 2).attr("y", y + 1).attr("width", Math.min(room, Math.max(textW(name), textW(val))) + 4).attr("height", LH - 2).attr("rx", 4).attr("fill", "#1E2A38").attr("opacity", c === lastCol ? 0 : 0.72);
      g.append("text").attr("class", "val").attr("x", lx).attr("y", y + 11).style("font-size", compact ? "10px" : null).text(name);
      g.append("text").attr("class", "lbl").attr("x", lx).attr("y", y + 23).style("font-size", compact ? "10px" : null).text(val);
      // a thin leader when the label had to move away from its node
      if (Math.abs(y + LH / 2 - (n.y + n.h / 2)) > 4) s.append("line").attr("x1", n.x + nw).attr("x2", lx - 2).attr("y1", n.y + n.h / 2).attr("y2", y + LH / 2).attr("stroke", C.dim).attr("stroke-width", 0.8);
    });
  });
};
