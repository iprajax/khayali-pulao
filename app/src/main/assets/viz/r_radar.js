// radar — several measures, each already scored on one common scale (0…max), drawn as spokes with value rings.
// data: { max (default 100), ringLabel, axes: [{ label, score, value (text shown beside the label), band (text for the tip) }] }
R.radar = function (d, h) {
  const s = svg(h), ax0 = (d.axes || []).filter(a => a && isFinite(+a.score)), n = ax0.length, max = +d.max || 100;
  if (n < 3) { s.append("text").attr("class", "lbl").attr("x", W() / 2).attr("y", h / 2).attr("text-anchor", "middle").text("Not enough measures to draw"); return; }
  const cx = W() / 2, cy = h / 2 + 8, r = Math.max(30, Math.min(W() / 2 - 96, h / 2 - 50));
  const ang = i => -Math.PI / 2 + i * 2 * Math.PI / n, pt = (i, v) => [cx + Math.cos(ang(i)) * r * v / max, cy + Math.sin(ang(i)) * r * v / max];
  const poly = vals => "M" + vals.map((v, i) => pt(i, v).map(q => q.toFixed(1)).join(",")).join("L") + "Z";
  s.append("text").attr("class", "axl").attr("x", 2).attr("y", 9).text("Rings: " + (d.ringLabel || "score"));
  s.append("text").attr("class", "axl").attr("x", W() - 2).attr("y", 9).attr("text-anchor", "end").text("Spokes: measure");
  // rings with their values, spokes
  [0.25, 0.5, 0.75, 1].forEach(k => {
    s.append("path").attr("d", poly(ax0.map(() => max * k))).attr("fill", k === 1 ? "rgba(255,255,255,.02)" : "none").attr("stroke", C.line).attr("stroke-opacity", k === 1 ? 1 : .6).attr("stroke-dasharray", k === 1 ? null : "2 3");
    s.append("text").attr("class", "lbl").style("font-size", "9px").style("fill", C.dim).attr("x", cx + 3).attr("y", cy - r * k + 10).text(Math.round(max * k)); });
  s.append("text").attr("class", "lbl").style("font-size", "9px").style("fill", C.dim).attr("x", cx + 3).attr("y", cy + 10).text("0");
  ax0.forEach((a, i) => { const p = pt(i, max); s.append("line").attr("x1", cx).attr("y1", cy).attr("x2", p[0]).attr("y2", p[1]).attr("stroke", C.line); });
  // the scored shape grows out from the centre
  const scores = ax0.map(a => Math.max(0, Math.min(max, +a.score)));
  s.append("path").attr("d", poly(scores.map(() => 0))).attr("fill", C.blue).attr("fill-opacity", .28).attr("stroke", C.blue).attr("stroke-width", 2).attr("stroke-linejoin", "round")
    .transition().duration(T).attr("d", poly(scores));
  // spoke labels: the measure, then its value and score; kept inside the card
  ax0.forEach((a, i) => { const c = Math.cos(ang(i)), sn = Math.sin(ang(i)), lx = cx + c * (r + 10), ly = cy + sn * (r + 10);
    const anchor = Math.abs(c) < 0.2 ? "middle" : c > 0 ? "start" : "end", room = anchor === "middle" ? W() - 8 : anchor === "start" ? W() - lx - 2 : lx - 2;
    const y0 = sn < -0.5 ? ly - 16 : sn > 0.5 ? ly + 10 : ly - 3;
    const t = s.append("text").attr("class", "lbl").attr("x", lx).attr("y", y0).attr("text-anchor", anchor).text(a.label);
    let lines = 1;
    if (t.node().getComputedTextLength() > room && a.label.indexOf(" ") > 0) {      // too wide for its corner: break at the space nearest the middle
      const w = a.label.split(" "); let k = 1, best = 1e9; for (let j = 1; j < w.length; j++) { const dlt = Math.abs(w.slice(0, j).join(" ").length - a.label.length / 2); if (dlt < best) { best = dlt; k = j; } }
      t.text(null); t.append("tspan").attr("x", lx).text(w.slice(0, k).join(" ")); t.append("tspan").attr("x", lx).attr("dy", 12).text(w.slice(k).join(" ")); lines = 2;
      if (sn < -0.5) t.attr("y", y0 - 12); }
    s.append("text").attr("class", "val").attr("x", lx).attr("y", (sn < -0.5 ? y0 : y0 + (lines - 1) * 12) + 13).attr("text-anchor", anchor).text((a.value != null ? a.value + " · " : "") + Math.round(a.score) + "/" + max); });
  // points: tap to read the value, the score and the band it was scored against
  const g = s.selectAll("g.p").data(ax0).join("g").attr("class", "p");
  g.append("circle").attr("r", 4).attr("fill", C.blue).attr("stroke", "#1E2A38").attr("stroke-width", 1.5).attr("cx", cx).attr("cy", cy)
    .transition().duration(T).attr("cx", (a, i) => pt(i, scores[i])[0]).attr("cy", (a, i) => pt(i, scores[i])[1]);
  g.append("circle").attr("r", 18).attr("fill", "transparent").attr("cx", (a, i) => pt(i, scores[i])[0]).attr("cy", (a, i) => pt(i, scores[i])[1])
    .on("click", (e, a) => { e.stopPropagation(); showTip(`<b>${a.label}</b> ${a.value != null ? a.value : ""} · score ${Math.round(a.score)}/${max}${a.band ? "<br>Band: " + a.band : ""}`, Math.min(e.pageX, W() - 190), e.pageY); });
};
