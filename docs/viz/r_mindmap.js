// mind map — a collapsible left-to-right tree: centre → topics → figures. Tap a topic to fold it, a figure for its detail.
// data: { name, sub?, children: [{ name, color?, children: [{ name, value, tone? ("up" | "down" | "warn"), detail? }] }] }
R.mindmap = function (d, h) {
  const s = svg(h), BG = "#1E2A38", D = 350, pal = [C.blue, C.green, C.violet, C.saffron, C.pink], tones = { up: C.green, down: C.red, warn: C.saffron };
  if (!d || !(d.children || []).length) { s.append("text").attr("class", "lbl").attr("x", W() / 2).attr("y", h / 2).attr("text-anchor", "middle").text("Nothing to map"); return; }
  s.append("text").attr("class", "axl").attr("x", 2).attr("y", 9).text("Company → topic → figure");
  s.append("text").attr("class", "axl").attr("x", W() - 2).attr("y", 9).attr("text-anchor", "end").text("Tap a topic to fold");
  const root = d3.hierarchy(d), X = [10, Math.round(W() * 0.3), Math.round(W() * 0.37)]; let uid = 0;
  root.each(n => { n.id = ++uid; n.kids = n.children; });
  (root.children || []).forEach((b, i) => { b.col = C[b.data.color] || pal[i % pal.length]; (b.kids || []).forEach(l => { l.col = b.col; }); });
  const diag = (a, b) => `M${a[0]},${a[1]}C${(a[0] + b[0]) / 2},${a[1]} ${(a[0] + b[0]) / 2},${b[1]} ${b[0]},${b[1]}`;
  const wrap = (t, n) => { const out = []; let line = ""; String(t).split(" ").forEach(w => { if ((line + " " + w).trim().length > n && line) { out.push(line); line = w; } else line = (line + " " + w).trim(); }); if (line) out.push(line); return out.join("<br>"); };
  const gl = s.append("g"), gn = s.append("g");
  function draw(src) {
    d3.tree().size([h - 40, 1]).separation((a, b) => a.parent === b.parent ? 1 : 1.3)(root);
    root.each(n => { n.px = X[Math.min(2, n.depth)]; n.py = n.x + 24; });
    const from = [src.px0 == null ? src.px : src.px0, src.py0 == null ? src.py : src.py0], to = [src.px, src.py];
    gl.selectAll("path").data(root.links(), l => l.target.id).join(
      en => en.append("path").attr("fill", "none").attr("stroke", l => l.target.col).attr("stroke-opacity", .5).attr("stroke-width", l => l.target.depth === 1 ? 2 : 1.2).attr("d", diag(from, from)),
      up => up, ex => ex.transition().duration(D).attr("d", diag(to, to)).remove())
      .transition().duration(D).attr("d", l => diag([l.source.px, l.source.py], [l.target.px, l.target.py]));
    const node = gn.selectAll("g.n").data(root.descendants(), n => n.id).join(
      en => { const g = en.append("g").attr("class", "n").attr("transform", `translate(${from})`).attr("opacity", 0);
        g.each(function (n) { const e = d3.select(this);
          if (n.depth === 0) {
            e.append("circle").attr("r", 6).attr("fill", C.text);
            e.append("text").attr("class", "val").style("font-size", "12.5px").attr("x", -6).attr("y", -13).text(n.data.name);
          } else if (n.depth === 1) {
            e.append("circle").attr("class", "dot").attr("r", 5.5).attr("stroke", n.col).attr("stroke-width", 2);
            e.append("text").attr("class", "tag").attr("x", -10).attr("y", 4).attr("text-anchor", "end").attr("fill", n.col).attr("stroke", BG).attr("stroke-width", 4).style("paint-order", "stroke").text(n.data.name);
            e.append("text").attr("class", "lbl cnt").attr("x", 11).attr("y", 4);
            e.append("rect").attr("x", -X[1] + 22).attr("y", -11).attr("width", X[1] + 10).attr("height", 22).attr("fill", "transparent")
              .on("click", () => { hideTip(); n.children = n.children ? null : n.kids; draw(n); });
          } else {
            e.append("circle").attr("r", 2.5).attr("fill", n.col);
            const room = W() - X[2] - 12, t = e.append("text").attr("class", "lbl").attr("x", 7).attr("y", 4).text(n.data.name + "  ");
            let val = String(n.data.value == null ? "" : n.data.value); const v = t.append("tspan").attr("class", "val").style("fill", tones[n.data.tone] || C.text).text(val);
            while (t.node().getComputedTextLength() > room && val.length > 4) { val = val.slice(0, -2); v.text(val.trimEnd() + "…"); }
            e.append("rect").attr("x", -4).attr("y", -9).attr("width", room + 12).attr("height", 18).attr("fill", "transparent")
              .on("click", ev => { const html = `<b>${n.data.name}</b> ${wrap(n.data.value == null ? "" : n.data.value, 40)}${n.data.detail ? "<br>" + wrap(n.data.detail, 44) : ""}`;
                showTip(html, 8, n.py + 8 - 16 * html.split("<br>").length); });       // sits above the tapped row, however many lines it has
          } });
        return g; },
      up => up, ex => ex.transition().duration(D).attr("transform", `translate(${to})`).attr("opacity", 0).remove());
    node.transition().duration(D).attr("transform", n => `translate(${n.px},${n.py})`).attr("opacity", 1);
    node.select("circle.dot").attr("fill", n => n.children ? BG : n.col);
    node.select("text.cnt").text(n => n.depth === 1 && !n.children && n.kids ? n.kids.length + " folded" : "");
    root.each(n => { n.px0 = n.px; n.py0 = n.py; }); (src.kids || []).forEach(k => { k.px0 = src.px; k.py0 = src.py; });
  }
  draw(root);
};
