/* Interactive demos for the landing page. Each mirrors logic the Android app ships and unit-tests
   (ChatFlow.next, WebData.sanitize, Ace.curate). No network, no storage, no trackers. */
(function () {
  "use strict";
  var $ = function (id) { return document.getElementById(id); };

  // ---------------------------------------------------------------- chat state machine
  var BUSY = { FETCHING: 1, WARMING: 1, THINKING: 1, TOOL: 1, STREAMING: 1 };
  function next(p, e) {
    switch (e) {
      case "Reset": case "Finished": case "Failed": case "Stopped": return p === "NEEDS_MODEL" ? p : "IDLE";
      case "ModelMissing": return p === "IDLE" ? "NEEDS_MODEL" : p;
      case "ModelReady": return p === "NEEDS_MODEL" ? "IDLE" : p;
      case "StartApp": return BUSY[p] ? p : "FETCHING";
      case "StartModelCold": return p === "IDLE" ? "WARMING" : p;
      case "StartModelWarm": return p === "IDLE" ? "THINKING" : p;
      case "EngineReady": return p === "WARMING" ? "THINKING" : p;
      case "ToolCall": return (p === "THINKING" || p === "STREAMING" || p === "WARMING") ? "TOOL" : p;
      case "ToolResult": return p === "TOOL" ? "THINKING" : p;
      case "Token": return (p === "THINKING" || p === "TOOL" || p === "WARMING") ? "STREAMING" : p;
    }
    return p;
  }
  var NAMES = { NEEDS_MODEL: "No model", IDLE: "Idle", FETCHING: "App work", WARMING: "Warming up", THINKING: "Thinking", TOOL: "Tool", STREAMING: "Answering" };
  var flow = { phase: "IDLE", engineWarm: false, kite: "connected", pending: null };
  var ACTIONS = [
    ["Ask a question", function () { if (flow.phase === "NEEDS_MODEL") return say("Free-form questions need the model. Commands still work — try <b>/holdings</b>.");
        fire(flow.engineWarm ? "StartModelWarm" : "StartModelCold", flow.engineWarm ? "Model already loaded: straight to thinking." : "Model was unloaded to save memory, so it warms up first. Your thread is re-seeded into the new conversation."); },
      function () { return !BUSY[flow.phase]; }],
    ["Model ready", function () { flow.engineWarm = true; fire("EngineReady", "Engine loaded on the GPU (falls back to CPU if the GPU can't run it)."); }, function () { return flow.phase === "WARMING"; }],
    ["Calls a tool", function () { fire("ToolCall", "The model asks for a calculator, Kite or the web. The card appears from the tool's exact output."); }, function () { return flow.phase === "THINKING" || flow.phase === "STREAMING"; }],
    ["Tool returns", function () { fire("ToolResult", "Result goes back to the model in a compact form — charts and links stay with the card."); }, function () { return flow.phase === "TOOL"; }],
    ["First word", function () { fire("Token", "Text streams in. Figures are checked against tool output as they arrive."); }, function () { return flow.phase === "THINKING" || flow.phase === "TOOL"; }],
    ["Answer done", function () { fire("Finished", "Final text is composed from tool summaries, follow-up pills are curated, and the turn is saved."); }, function () { return flow.phase === "STREAMING"; }],
    ["Run /holdings", function () {
        if (flow.kite !== "connected") { flow.pending = "/holdings"; return say("Kite login expired, so the app shows <b>Reconnect Kite</b> and keeps <b>/holdings</b> waiting. No model needed for this."); }
        fire("StartApp", "A /command is app work: read Kite, draw the card. No model involved."); },
      function () { return !BUSY[flow.phase]; }],
    ["Kite replies", function () { fire("Finished", "Holdings card shown from Kite's data. Back to idle."); }, function () { return flow.phase === "FETCHING"; }],
    ["Stop", function () { fire("Stopped", "Stopped. Whatever had streamed stays; nothing is left spinning."); }, function () { return !!BUSY[flow.phase]; }],
    ["Phone sleeps overnight", function () { flow.engineWarm = false; flow.kite = "expired"; flow.phase = next(flow.phase, "Reset");
        say("Next morning: Android unloaded the model and Zerodha's daily login lapsed. The chat notices both on resume — the Kite pill turns amber, and the thread is still on screen."); },
      function () { return !BUSY[flow.phase]; }],
    ["Reconnect Kite", function () { flow.kite = "connected"; if (flow.pending) { var p = flow.pending; flow.pending = null; flow.phase = next(flow.phase, "StartApp"); return say("Signed in on Zerodha's page. <b>" + p + "</b> runs automatically."); }
        say("Kite connected again (read-only)."); }, function () { return flow.kite === "expired" && !BUSY[flow.phase]; }],
  ];
  function say(html) { $("flow-log").innerHTML = html; paint(); }
  function fire(ev, why) { var before = flow.phase; flow.phase = next(flow.phase, ev); say((before !== flow.phase ? "<b>" + NAMES[before] + " → " + NAMES[flow.phase] + ".</b> " : "") + why); }
  function paint() {
    document.querySelectorAll(".flow .node").forEach(function (n) { n.classList.toggle("on", n.getAttribute("data-n") === flow.phase); });
    $("flow-title").textContent = NAMES[flow.phase] + (flow.kite === "expired" ? " · Kite login expired" : "") + (flow.engineWarm ? " · model loaded" : " · model unloaded");
    var box = $("flow-btns"); box.innerHTML = "";
    ACTIONS.forEach(function (a) { var b = document.createElement("button"); b.textContent = a[0]; b.disabled = !a[2](); b.onclick = a[1]; box.appendChild(b); });
  }
  if ($("flow-btns")) paint();

  // ---------------------------------------------------------------- query sanitizer (WebData.sanitize)
  function sanitize(q) {
    return q.replace(/[\w.+-]+@[\w-]+\.[\w.]+/g, " ")
      .replace(/(₹|\brs\.?|\binr)\s*[\d,]+(\.\d+)?\s*(lakhs?|lacs?|crores?|cr|k)?\b/gi, " ")
      .replace(/\b[\d,]*\d{5,}[\d,]*\b/g, " ")
      .replace(/\b\d+(\.\d+)?\s*(lakhs?|lacs?|crores?)\b/gi, " ")
      .replace(/["<>]/g, " ").replace(/\s+/g, " ").trim().slice(0, 120);
  }
  if ($("q-in")) { var upd = function () { $("q-out").textContent = sanitize($("q-in").value) || "(nothing to send)"; }; $("q-in").addEventListener("input", upd); upd(); }
  document.querySelectorAll(".cite").forEach(function (c) {
    var on = function () { document.querySelectorAll(".src").forEach(function (s) { s.classList.toggle("hot", s.getAttribute("data-s") === c.getAttribute("data-c")); }); };
    c.addEventListener("click", on); c.addEventListener("mouseenter", on);
  });

  // ---------------------------------------------------------------- command search
  if ($("term-q")) $("term-q").addEventListener("input", function () {
    var q = this.value.trim().toLowerCase();
    document.querySelectorAll(".term-group").forEach(function (g) {
      var shown = 0;
      g.querySelectorAll("li").forEach(function (li) { var hit = !q || li.getAttribute("data-q").indexOf(q) >= 0; li.classList.toggle("hide", !hit); if (hit) shown++; });
      g.style.display = shown ? "" : "none";
    });
  });

  // ---------------------------------------------------------------- memory playbook (Ace.reflectRules + Ace.curate)
  var STOP = "the a an and or of to in on for is are my i me it at with that this be as if what how you your user when always into".split(" ");
  function words(t) { return (t.toLowerCase().match(/[a-z0-9%₹]+/g) || []).map(function (w) { return w.replace(/s$/, ""); }).filter(function (w) { return w.length > 1 && STOP.indexOf(w) < 0; }); }
  function sim(a, b) { var x = words(a), y = words(b), n = x.filter(function (w) { return y.indexOf(w) >= 0; }).length; return x.length && y.length ? n / (x.length + y.length - n) : 0; }
  function acceptable(t) { return t.length >= 8 && !/\b(password|otp|pin|cvv|aadhaar|pan (number|no|card)|account number)\b/i.test(t) && !/\b[A-Z]{5}\d{4}[A-Z]\b|\b\d{9,}\b/.test(t) && !/\b(should|must) (buy|sell|hold)\b/i.test(t); }
  var pb = [];
  var SAY = [
    ["My goal is ₹1 crore by 2035", [["FACT", "Goal: ₹1 crore by 2035."]], "Reflect: a durable fact about you. Curate: new note added."],
    ["I prefer short answers", [["PREF", "Prefers short answers."]], "Reflect: a preference. Curate: new note added."],
    ["Remember my goal is 1 crore by 2035", [["FACT", "Goal: 1 crore by 2035."]], "Curate: this restates an existing note, so its count goes up instead of adding a duplicate."],
    ["What if INFRA falls 20%? (model said −2%)", [["LESSON", "Pass the percentage exactly as the user typed it, with its sign."]], "Reflect: the app corrected the model's −2% to your −20%. That mistake becomes a lesson the model is shown next time."],
    ["My PAN is ABCDE1234F", [["FACT", "PAN is ABCDE1234F."]], ""],
    ["↻ Regenerate the last answer", null, "Feedback: a redo counts against the notes that were used. Notes that keep leading to redos are retired."],
  ];
  function drawPb() {
    var ul = $("mem-pb"); ul.innerHTML = "";
    if (!pb.length) { ul.innerHTML = '<li style="color:var(--dim)">Nothing remembered yet.</li>'; return; }
    pb.forEach(function (b) { var li = document.createElement("li");
      li.innerHTML = '<span class="tag' + (b.s === "LESSON" ? " l" : "") + '">' + b.s + "</span><span></span><span class=\"cnt\">helped " + b.h + "×" + (b.x ? " · redo " + b.x + "×" : "") + "</span>";
      li.children[1].textContent = b.t; ul.appendChild(li); });
  }
  if ($("mem-btns")) SAY.forEach(function (m) {
    var b = document.createElement("button"); b.textContent = m[0];
    b.onclick = function () {
      var log = $("mem-log");
      if (!m[1]) { if (!pb.length) { log.textContent = "Nothing to give feedback on yet."; return; }
        pb.forEach(function (x) { x.x++; }); pb = pb.filter(function (x) { return x.x < x.h + 3; }); log.textContent = m[2]; return drawPb(); }
      var out = m[2];
      m[1].forEach(function (d) {
        if (!acceptable(d[1])) { out = ""; log.innerHTML = '<span class="refused">Refused: that looks like an identity number. Secrets, IDs and buy/sell instructions are never stored.</span>'; return; }
        var hit = pb.filter(function (x) { return x.s === d[0] && sim(x.t, d[1]) >= 0.6; })[0];
        if (hit) hit.h++; else pb.push({ s: d[0], t: d[1], h: 1, x: 0 });
      });
      if (out) log.textContent = out; drawPb();
    };
    $("mem-btns").appendChild(b);
  });

  // ---------------------------------------------------------------- auto-play (pauses while you use a demo)
  var reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  function autoplay(host, every, step) {
    if (!host || reduce) return;
    var paused = 0, seen = false;
    host.addEventListener("pointerdown", function () { paused = Date.now() + 15000; });
    host.addEventListener("keydown", function () { paused = Date.now() + 15000; });
    new IntersectionObserver(function (es) { seen = es[0].isIntersecting; }, { threshold: .2 }).observe(host);
    setInterval(function () { if (seen && Date.now() > paused) step(); }, every);
  }
  // chat state machine: a scripted day in the life, looping
  var SCRIPT = ["Ask a question", "Model ready", "Calls a tool", "Tool returns", "First word", "Answer done", "Run /holdings", "Kite replies", "Phone sleeps overnight", "Run /holdings", "Reconnect Kite", "Kite replies", "Ask a question", "Calls a tool", "Tool returns", "First word", "Answer done"];
  var si = 0;
  autoplay(document.getElementById("flow"), 1700, function () {
    var box = $("flow-btns"); if (!box) return;
    for (var tries = 0; tries < SCRIPT.length; tries++) {
      var want = SCRIPT[si++ % SCRIPT.length];
      var b = Array.prototype.find.call(box.querySelectorAll("button"), function (x) { return x.textContent === want && !x.disabled; });
      if (b) { b.click(); b.classList.add("pulse"); setTimeout(function () { b.classList.remove("pulse"); }, 600); return; }
    }
  });
  // privacy filter: types example queries
  var QUERIES = ["should I sell my 250 shares of INFRA worth ₹3,62,500?", "INFRA Q2 results date", "is my ₹12 lakh in CHAICO too much? call 9876543210", "mail me@example.com the BALLIA dividend record date", "Nifty IT outlook this week"];
  var qi = 0, qin = $("q-in");
  autoplay(document.getElementById("web"), 4200, function () {
    if (!qin || document.activeElement === qin) return;
    var q = QUERIES[qi++ % QUERIES.length], k = 0;
    var t = setInterval(function () { qin.value = q.slice(0, ++k); qin.dispatchEvent(new Event("input")); if (k >= q.length) clearInterval(t); }, 22);
  });
  // citations: light up each source in turn
  var ci = 0;
  autoplay(document.getElementById("web"), 1500, function () {
    var cs = document.querySelectorAll(".cite"); if (!cs.length) return;
    cs[ci++ % cs.length].dispatchEvent(new Event("mouseenter"));
  });
  // memory: says each thing in turn, then starts again
  var mi = 0;
  autoplay(document.getElementById("memory"), 2600, function () {
    var bs = document.querySelectorAll("#mem-btns button"); if (!bs.length) return;
    if (mi > 0 && mi % bs.length === 0) { pb = []; drawPb(); $("mem-log").textContent = "Starting again: each message is reflected on, then curated into the playbook."; mi++; return; }
    bs[mi++ % bs.length].click();
  });
})();
