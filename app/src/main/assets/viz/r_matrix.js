// heat matrix: rows × columns of values, each cell coloured by its value (red below zero, green above) — data:
// { cols: ["Jan", …], rows: [{ label: "2024", values: [.. or null] }], footer: { label: "Avg", values: [..] },
//   unit: "%", xLabel: "Month", yLabel: "Year", partial: { row: "2026", col: 10 } }   (partial = a cell still in progress)
(function () {
  R.matrix = function (d, h) {
    const s = svg(h), cols = d.cols || [], rows = (d.rows || []).slice(), foot = d.footer, unit = d.unit || "";
    const vals = rows.flatMap(r => r.values || []).filter(v => v != null);
    if (!cols.length || !vals.length) { s.append("text").attr("class", "lbl").attr("x", W() / 2).attr("y", h / 2).attr("text-anchor", "middle").text("Not enough history"); return; }
    const L = 36, top = 32, legH = 30, gap = foot ? 7 : 0, n = rows.length + (foot ? 1 : 0);
    const cw = (W() - L - 1) / cols.length, ch = Math.min(26, (h - top - legH - gap) / n);
    const lim = Math.max(3, Math.ceil(d3.quantile(vals.map(Math.abs).sort(d3.ascending), 0.9) || 3));
    const colour = d3.scaleLinear().domain([-lim, 0, lim]).range([C.red, "#3a4656", C.green]).clamp(true);
    const sign = v => v > 0 ? "+" : v < 0 ? "−" : "", f = v => sign(v) + Math.abs(v).toFixed(1) + unit;
    s.append("text").attr("class", "axl").attr("x", 2).attr("y", 9).text((d.yLabel || "Row") + " ↓");
    s.append("text").attr("class", "axl").attr("x", W() - 1).attr("y", 9).attr("text-anchor", "end").text((d.xLabel || "Column") + " →");
    cols.forEach((c, j) => s.append("text").attr("class", "lbl").attr("x", L + (j + .5) * cw).attr("y", top - 7).attr("text-anchor", "middle").style("font-size", cw < 26 ? "9.5px" : null).text(cw < 22 ? c.slice(0, 1) : c));
    const cells = [];
    rows.forEach((r, i) => (r.values || []).forEach((v, j) => cells.push({ v, i, j, y: top + i * ch, row: r.label, part: !!d.partial && d.partial.row === r.label && d.partial.col === j })));
    if (foot) (foot.values || []).forEach((v, j) => cells.push({ v, i: rows.length, j, y: top + rows.length * ch + gap, row: foot.label, foot: true }));
    rows.forEach((r, i) => s.append("text").attr("class", "lbl").attr("x", L - 6).attr("y", top + i * ch + ch / 2 + 3.5).attr("text-anchor", "end").text(r.label));
    if (foot) s.append("text").attr("class", "val").attr("x", L - 6).attr("y", top + rows.length * ch + gap + ch / 2 + 3.5).attr("text-anchor", "end").text(foot.label);
    const g = s.selectAll("g.c").data(cells).join("g").attr("class", "c").attr("transform", c => `translate(${L + c.j * cw},${c.y})`);
    g.append("rect").attr("width", cw - 2).attr("height", ch - 2).attr("rx", 3)
      .attr("fill", c => c.v == null ? "transparent" : colour(c.v)).attr("stroke", c => c.part ? C.text : c.v == null ? C.line : "none").attr("stroke-dasharray", c => c.part ? "2 2" : null).attr("stroke-opacity", c => c.v == null ? .5 : 1)
      .attr("opacity", 0)
      .on("click", (e, c) => { if (c.v == null) { hideTip(); return; }
        showTip(`${cols[c.j]} ${c.foot ? "average" : c.row}${c.part ? " (so far)" : ""} · <b>${f(c.v)}</b>`, e.pageX, e.pageY); })
      .transition().duration(350).delay(c => c.i * 35 + c.j * 12).attr("opacity", 1);
    if (cw >= 22 && ch >= 14) g.append("text").attr("x", (cw - 2) / 2).attr("y", (ch - 2) / 2 + 3.5).attr("text-anchor", "middle").style("font-size", "9.5px").style("fill", "#fff").style("pointer-events", "none")
      .style("font-weight", c => c.foot ? 600 : 400).text(c => c.v == null ? "" : sign(Math.round(c.v)) + Math.abs(Math.round(c.v)));
    // colour scale with its values
    const lw = Math.min(170, W() - 150), lx = (W() - lw) / 2, ly = h - 8;
    const grad = s.append("defs").append("linearGradient").attr("id", "mxg");
    [[0, C.red], [0.5, "#3a4656"], [1, C.green]].forEach(([o, c]) => grad.append("stop").attr("offset", o).attr("stop-color", c));
    s.append("rect").attr("x", lx).attr("y", ly - 7).attr("width", lw).attr("height", 7).attr("rx", 3).attr("fill", "url(#mxg)");
    s.append("text").attr("class", "lbl").attr("x", lx - 6).attr("y", ly).attr("text-anchor", "end").text("−" + lim + unit + " or less");
    s.append("text").attr("class", "lbl").attr("x", lx + lw + 6).attr("y", ly).text("+" + lim + unit + " or more");
  };
})();
