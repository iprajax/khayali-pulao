/*
 * The hero: a scripted, live-rendered replay of the app's chat — the same layout, cards, colours and
 * interactions as on the phone — playing like a video. Fictional data only (stocks like BALLIA,
 * CHAICO, INFRA; made-up news outlets on .example domains). Scenes are listed beside the phone and
 * light up as they play; tapping one jumps to it. Pauses when off-screen; reduced-motion users get
 * the finished frames without animation.
 */
(function () {
  "use strict";
  var phone = document.getElementById("chatdemo");
  if (!phone) return;
  var body = phone.querySelector(".cd-body"), pills = phone.querySelector(".cd-pills"), typed = phone.querySelector(".cd-typed"),
      suggest = phone.querySelector(".cd-suggest"), finger = phone.querySelector(".cd-finger"), sheet = phone.querySelector(".cd-sheet"), wheel = phone.querySelector(".cd-wheel"),
      sendBtn = phone.querySelector(".cd-send"), scenesEl = document.getElementById("scenes"), toggle = document.getElementById("cd-toggle");
  var reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  var STOP = {}, runId = 0, paused = false, fast = false, waiting = [];

  // ---------------------------------------------------------------- timing
  function sleep(ms) {
    return new Promise(function (res, rej) {
      var my = runId, left = fast || reduce ? 0 : ms;
      (function tick() {
        if (my !== runId) return rej(STOP);
        if (paused) { waiting.push(tick); return; }
        if (left <= 0) return res();
        var step = Math.min(left, 80); left -= step; setTimeout(tick, step);
      })();
    });
  }
  function setPaused(p) {
    paused = p; if (toggle) toggle.textContent = p ? "Play" : "Pause";
    if (!p) { var w = waiting; waiting = []; w.forEach(function (f) { f(); }); }
  }

  // ---------------------------------------------------------------- building blocks
  function el(tag, cls, html) { var e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; }
  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function scrollEnd() { body.scrollTo({ top: body.scrollHeight, behavior: fast || reduce ? "auto" : "smooth" }); }
  function add(node) { body.appendChild(node); if (!fast && !reduce) { node.classList.add("cd-in"); } scrollEnd(); return node; }
  function pct(v) { return (v >= 0 ? "+" : "−") + Math.abs(v).toFixed(2) + "%"; }
  function pill(v) { return '<span class="cd-pill ' + (v >= 0 ? "up" : "down") + '">' + pct(v) + "</span>"; }
  function spark(pts, up) {
    var mn = Math.min.apply(null, pts), mx = Math.max.apply(null, pts), w = 54, h = 20;
    var d = pts.map(function (p, i) { return (i * w / (pts.length - 1)).toFixed(1) + "," + (h - (p - mn) / (mx - mn || 1) * h).toFixed(1); }).join(" ");
    return '<svg class="cd-spark" viewBox="0 0 ' + w + " " + h + '"><polyline points="' + d + '" fill="none" stroke="' + (up ? "#67AD5B" : "#CF5B52") + '" stroke-width="1.6"/></svg>';
  }
  function tick(sym) { return '<a class="cd-tick" data-sym="' + sym + '">' + sym + "</a>"; }
  function act(label, cmd) { return '<a class="cd-act" data-cmd="' + esc(cmd) + '">▸ ' + label + "</a>"; }
  function user(text) { return add(el("div", "cd-user", esc(text))); }
  function card(inner, cls) { return add(el("div", "cd-card " + (cls || ""), inner)); }
  function setPills(list) {
    pills.innerHTML = "";
    list.forEach(function (p, i) { var b = el("span", "cd-chip", esc(p)); b.style.animationDelay = (i * 60) + "ms"; pills.appendChild(b); });
  }
  function chip(label) { return Array.prototype.find.call(pills.children, function (c) { return c.textContent === label; }); }

  // the finger: glides to an element, presses, and the element lights up
  function tap(target) {
    if (!target) return sleep(0);
    var pr = phone.getBoundingClientRect(), r = target.getBoundingClientRect();
    finger.style.left = (r.left - pr.left + Math.min(r.width / 2, 60)) + "px";
    finger.style.top = (r.top - pr.top + r.height / 2) + "px";
    finger.classList.add("on");
    return sleep(520).then(function () {
      finger.classList.add("press"); target.classList.add("cd-pressed");
      return sleep(260);
    }).then(function () { finger.classList.remove("press"); target.classList.remove("cd-pressed"); return sleep(120); });
  }
  function hideFinger() { finger.classList.remove("on"); }

  function type(text, cps) {
    typed.textContent = ""; phone.classList.add("typing");
    var i = 0;
    function next() { if (i >= text.length) return sleep(250); typed.textContent += text[i++]; return sleep(1000 / (cps || 22)).then(next); }
    return next();
  }
  function send() { return tap(sendBtn).then(function () { var t = typed.textContent; typed.textContent = ""; phone.classList.remove("typing"); suggest.classList.remove("on"); setPills([]); return t; }); }

  // ---------------------------------------------------------------- cards (fictional data)
  function planCard(steps) {
    return card('<div class="cd-kick">PLAN</div><div class="cd-h">Your plan for today</div>' +
      steps.map(function (s, i) { return '<div class="cd-step"><span class="cd-mark" data-i="' + i + '">○</span>' + (i + 1) + ". " + s + "</div>"; }).join(""));
  }
  function mark(c, i, state) {
    var m = c.querySelector('.cd-mark[data-i="' + i + '"]');
    m.textContent = { run: "◔", ok: "✓" }[state]; m.className = "cd-mark " + state;
  }
  function briefCard() {
    return card('<div class="cd-kick">ACTION BRIEF · YOUR OWN NSE DATA</div>' +
      '<div class="cd-big up">Recovering</div><div class="cd-sub">58% of 1,079 liquid stocks above their 50-day average · market +1.84% this week</div>' +
      '<div class="cd-kpis"><div><b>TODAY</b><i class="up">+0.62%</i></div><div><b>THIS WEEK</b><i class="up">+1.84%</i></div><div><b>HIGHS / LOWS</b><i>41 / 12</i></div><div><b>VOLATILITY</b><i>14%</i></div></div>' +
      '<div class="cd-breadth"><span style="width:58%"></span></div><div class="cd-bl"><span class="up">▲ 612 advancing</span><span class="down">431 declining ▼</span></div>' +
      '<div class="cd-sec">HIDDEN PATTERNS TODAY</div>' +
      mover("BALLIA", "Volume spike · 6.2× its usual volume", 7.41, [3, 3.2, 3.1, 3.3, 3.2, 3.6, 4.4]) +
      mover("CHAICO", "52-week breakout on 2.8× volume", 4.12, [5, 5.1, 5.3, 5.2, 5.5, 5.6, 5.9]) +
      mover("JHUMRI", "Losing streak · 8 sessions in a row", -2.34, [9, 8.8, 8.7, 8.5, 8.4, 8.2, 8.0]) +
      '<div class="cd-take"><b>TAKEAWAY</b>A recovering market: breadth is improving and new highs outnumber lows. Most unusual today: ' + tick("BALLIA") + ' on <span class="up">6.2×</span> its usual volume.</div>', "wide");
  }
  function mover(sym, sub, ch, sp) {
    return '<div class="cd-mv"><div><div class="cd-sym">' + tick(sym) + '</div><div class="cd-mvsub">' + sub + "</div></div>" + spark(sp, ch >= 0) + pill(ch) + "</div>";
  }
  function newsCard() {
    return card('<div class="cd-kick">FROM THE WEB · BALLIA</div>' +
      src(1, "Ballia Agro wins a ₹420 crore state irrigation order", "dailyledger.example") +
      src(2, "BALLIA volumes jump as order book swells", "marketwire.example") +
      src(3, "Ballia Agro: Q2 results date set for 21 Nov", "exchangenotes.example") +
      '<div class="cd-note">Web results with sources · fictional demo outlets</div>');
  }
  function src(n, t, d) { return '<div class="cd-src"><span>' + n + "</span><div><div>" + t + '</div><a>' + d + " ↗</a></div></div>"; }
  function calCard() {
    return card('<div class="cd-kick">MARKET CALENDAR · THIS WEEK</div><div class="cd-h2">18 dividends · 9 results · 2 IPOs open · 1 holiday</div>' +
      '<div class="cd-row"><span>Wed 18</span><b>' + tick("CHAICO") + ' results</b></div><div class="cd-row"><span>Thu 19</span><b>' + tick("INFRA") + ' dividend ₹4</b></div><div class="cd-row"><span>Fri 21</span><b>' + tick("BALLIA") + " results</b></div>");
  }
  function picksCard() {
    return card('<div class="cd-kick">STEADY COMPOUNDERS · SCREEN</div><div class="cd-h2">The rule that tested best on your data: <span class="up">+14.9%</span> a year vs <span class="up">+11.3%</span> for the market</div>' +
      mover("INFRA", "1 month +6.1% · 1 year +38%", 1.21, [1, 1.1, 1.15, 1.2, 1.3, 1.32, 1.4]) +
      mover("CHAICO", "1 month +4.4% · 1 year +29%", 4.12, [2, 2.1, 2.05, 2.2, 2.3, 2.35, 2.5]) +
      mover("SAMOSA", "1 month +3.0% · 1 year +24%", 0.82, [4, 4.1, 4.2, 4.15, 4.3, 4.35, 4.4]));
  }
  function wrapUp() {
    return add(el("div", "cd-bot", '<b>Your plan for today</b> — 4 of 4 steps done.<div class="cd-mh">What it shows</div><ul>' +
      '<li><b>Market:</b> recovering — 58% of liquid stocks above their 50-day average.</li>' +
      '<li><b>Why ' + tick("BALLIA") + ' moved:</b> a ₹420 crore order; results on 21 Nov.</li>' +
      '<li><b>Best-tested rule:</b> steady compounders, <span class="up">+14.9%</span> a year.</li></ul>' +
      '<div class="cd-mh">What to do next</div><ul class="cd-acts"><li>' + act("🩺 Check BALLIA's trend", "/health BALLIA") + "</li><li>" +
      act("📘 Learn steady compounders", "/learn sharpe") + "</li><li>" + act("📅 BALLIA's events", "/events BALLIA") + "</li></ul>"));
  }
  function healthCard() {
    return card('<div class="cd-kick">BALLIA · HEALTH · YOUR OWN NSE DATA</div><div class="cd-big">Nothing flagged</div><div class="cd-sub">Above its averages and near its high</div>' +
      '<div class="cd-kpis"><div><b>1 MONTH</b><i class="up">+12.4%</i></div><div><b>1 YEAR</b><i class="up">+41%</i></div><div><b>VS 200-DAY</b><i class="up">+18%</i></div><div><b>FROM HIGH</b><i class="down">−3.2%</i></div></div>');
  }
  function chartCard() {
    var pts = [], v = 100; for (var i = 0; i < 48; i++) { v *= 1 + Math.sin(i / 5) * 0.012 + 0.006; pts.push(v); }
    var mn = Math.min.apply(null, pts), mx = Math.max.apply(null, pts);
    var d = pts.map(function (p, i) { return (i * 300 / 47).toFixed(1) + "," + (90 - (p - mn) / (mx - mn) * 80).toFixed(1); }).join(" ");
    return card('<div class="cd-kick">BALLIA · 1 YEAR</div><div class="cd-big up">+41.2%</div>' +
      '<svg class="cd-chart" viewBox="0 0 300 100"><polygon points="0,100 ' + d + ' 300,100" fill="rgba(103,173,91,.18)"/><polyline points="' + d + '" fill="none" stroke="#67AD5B" stroke-width="2"/></svg>' +
      '<div class="cd-axis"><span>Nov</span><span>Feb</span><span>May</span><span>Aug</span><span>Nov</span></div>');
  }
  function sipCard() {
    var bars = [], inv = 0, val = 0; for (var y = 1; y <= 15; y++) { for (var m = 0; m < 12; m++) { val = (val + 10000) * 1.01; inv += 10000; } bars.push([inv, val]); }
    var max = bars[14][1];
    return card('<div class="cd-kick">SIP · ₹10,000 A MONTH · 12% · 15 YEARS</div><div class="cd-big up">₹50.5 lakh</div><div class="cd-sub">You put in ₹18 lakh; growth adds ₹32.5 lakh (hypothetical, before tax)</div>' +
      '<div class="cd-bars">' + bars.map(function (b) { return '<span><i style="height:' + (b[1] / max * 100).toFixed(0) + '%"></i><em style="height:' + (b[0] / max * 100).toFixed(0) + '%"></em></span>'; }).join("") + "</div>" +
      '<div class="cd-axis"><span>Year 1</span><span>Year 15</span></div>');
  }
  function needCard() {
    return card('<div class="cd-h">This needs the strategy knowledge bank</div><div class="cd-sub">/screens searches 500 community stock screens with their rules — about 289 KB, checked by checksum.</div><div class="cd-btn">Download (289 KB)</div>');
  }
  function progressCard() {
    return card('<div class="cd-h cd-ptitle">Downloading knowledge bank… 0%</div><div class="cd-prog"><span></span></div><div class="cd-sub cd-pst">0 of 289 KB</div>');
  }
  function screensCard() {
    return card('<div class="cd-kick">COMMUNITY SCREENS · “QUALITY LOW DEBT”</div><div class="cd-h2">8 closest screens · by keywords, then by meaning</div>' +
      '<div class="cd-rule"><b>Quality compounders</b><span>Low debt · Quality</span><code>ROCE &gt; 20 AND Debt to equity &lt; 0.5 AND Sales growth 5Years &gt; 12</code></div>' +
      '<div class="cd-rule"><b>≈ Debt-free leaders</b><span>Low debt · Value</span><code>Debt to equity &lt; 0.1 AND Return on equity &gt; 18 AND PE &lt; 25</code></div>');
  }

  // ---------------------------------------------------------------- scenes
  function greet() {
    body.innerHTML = ""; setPills([]); sheet.classList.remove("on"); wheel.classList.remove("on"); phone.querySelector(".cd-live").classList.remove("on"); hideFinger();
    var g = el("div", "cd-greet", '<div class="cd-logo">🍲</div><div class="cd-hi">What shall we cook today?</div><div class="cd-sub2">Ask anything — or start with a task</div>');
    var tasks = [["🧭", "Plan my day — what should I do today?"], ["📰", "What's happening with INFRA — trend, news and results"], ["💼", "Review my portfolio — trend, concentration, risk"],
      ["🎯", "Find a strategy that's working right now"], ["📅", "What's coming up this week — results, dividends, IPOs"], ["🔎", "Find quality companies with low debt"]];
    tasks.forEach(function (t) { g.appendChild(el("div", "cd-task", "<span>" + t[0] + "</span>" + t[1])); });
    g.appendChild(el("div", "cd-fine", "🔒 Gemma 4 E2B · runs on your phone"));
    body.appendChild(g); body.scrollTop = 0;
  }

  function setupCard() {
    var steps = [["🧠", "On-device AI (Gemma 4 E2B)", "2.6 GB · waits for Wi-Fi"], ["📈", "Market history · 3 years", "about 75 MB, straight from NSE"],
      ["📚", "Strategy knowledge bank", "289 KB"], ["🌐", "Web lookups", "topic words only"], ["🔌", "Connect Kite (read-only)", "your Zerodha login"]];
    return card('<div class="cd-kick">SETUP</div><div class="cd-h cd-sh">Set up everything in one tap</div><div class="cd-sub cd-ss">Pick what you want; it all starts at once.</div>' +
      steps.map(function (st, i) { return '<div class="cd-srow"><span class="cd-chk">' + (i < 4 ? "☑" : "☐") + '</span><span>' + st[0] + '</span><div><b>' + st[1] + '</b><em>' + st[2] + '</em><i class="cd-sbar"><u></u></i></div><span class="cd-sst"></span></div>'; }).join("") +
      '<div class="cd-btn">⚡ Set up everything</div>');
  }

  // live voice: the orb, the user's words as live captions, then the answer lit word by word
  function liveScene() {
    var lv = phone.querySelector(".cd-live");
    var you = lv.querySelector(".cd-you"), ans = lv.querySelector(".cd-ans"), orb = lv.querySelector(".cd-orb"), hint = lv.querySelector(".cd-lhint");
    lv.classList.add("on"); orb.className = "cd-orb listen"; you.textContent = ""; ans.innerHTML = ""; hint.textContent = "Listening…";
    var q = "What's the market mood today?".split(" "), i = 0;
    function word() { if (i >= q.length) return sleep(500); you.textContent = q.slice(0, ++i).join(" "); return sleep(230).then(word); }
    var a = "Market mood is 42 out of 100 — fear. Breadth is weak: only 40 percent of liquid stocks are above their 50-day average, and volatility is higher than most of the past year.".split(" ");
    return sleep(500).then(word).then(function () { orb.className = "cd-orb think"; hint.textContent = "Working it out…"; you.classList.add("dim"); return sleep(1100); })
      .then(function () {
        orb.className = "cd-orb speak"; hint.textContent = "Tap the orb to interrupt";
        ans.innerHTML = a.map(function (w) { return "<span>" + w + "</span>"; }).join(" ");
        var spans = ans.querySelectorAll("span"), k = 0;
        function lit() { if (k >= spans.length) return sleep(900); if (k > 0) spans[k - 1].className = "said"; spans[k].className = "now"; k++; return sleep(190).then(lit); }
        return lit();
      }).then(function () { lv.classList.remove("on"); you.classList.remove("dim"); return sleep(400); });
  }

  var SCENES = [
    { title: "Sets itself up in one tap", sub: "AI model, your own market data, knowledge bank and web — all at once, with live status", run: function () {
      body.innerHTML = ""; hideFinger(); user("/setup");
      var c; return sleep(500).then(function () { c = setupCard(); return sleep(1300); })
        .then(function () { return tap(c.querySelector(".cd-btn")); })
        .then(function () {
          hideFinger(); c.querySelector(".cd-btn").style.display = "none";
          c.querySelector(".cd-sh").textContent = "Setting up… 0 of 4 ready"; c.querySelector(".cd-ss").textContent = "You can keep chatting while it works.";
          var rows = c.querySelectorAll(".cd-srow"); rows[4].querySelector(".cd-sst").textContent = "skipped";
          Array.prototype.forEach.call(rows, function (r) { r.querySelector(".cd-chk").style.visibility = "hidden"; });
          var speed = [4, 11, 50, 100], pct = [0, 0, 0, 0], k = 0;
          function step() {
            var done = 0;
            for (var i = 0; i < 4; i++) {
              pct[i] = Math.min(100, pct[i] + speed[i]); var st = rows[i].querySelector(".cd-sst"), bar = rows[i].querySelector(".cd-sbar");
              if (pct[i] >= 100) { st.textContent = "✓ Ready"; st.className = "cd-sst up"; bar.style.display = "none"; done++; }
              else { st.textContent = pct[i] + "%"; st.className = "cd-sst blue"; bar.style.display = "block"; bar.firstChild.style.width = pct[i] + "%"; }
            }
            c.querySelector(".cd-sh").textContent = done === 4 ? "✓ You're all set" : "Setting up… " + done + " of 4 ready";
            if (done === 4) { c.querySelector(".cd-ss").textContent = "Everything you picked is ready on this phone."; return sleep(1400); }
            k++; if (k === 9) speed[0] = 30;
            return sleep(260).then(step);
          }
          return step();
        });
    } },
    { title: "Starts with tasks, not a blank box", sub: "Personalised starters: plan my day, a stock you own, your portfolio", run: function () {
      greet();
      return sleep(1600).then(function () { return tap(body.querySelector(".cd-task")); });
    } },
    { title: "Talk to it — live captions, on the phone", sub: "Speak; see your words as you say them; hear the answer, each word lit as it's spoken", run: function () {
      return liveScene();
    } },
    { title: "Makes a plan — and runs every step", sub: "A broad question becomes 4 steps; each lands as its own card", run: function () {
      body.innerHTML = ""; hideFinger();
      user("Plan my day — what should I do today?");
      var p;
      return sleep(600).then(function () { p = planCard(["Read the market and your holdings", "Find out why the most unusual stock moved", "See what's coming up this week", "See today's picks of the best-tested strategy"]); return sleep(700); })
        .then(function () { mark(p, 0, "run"); return sleep(900); }).then(function () { mark(p, 0, "ok"); briefCard(); return sleep(2600); })
        .then(function () { mark(p, 1, "run"); return sleep(800); }).then(function () { mark(p, 1, "ok"); newsCard(); return sleep(2000); })
        .then(function () { mark(p, 2, "run"); return sleep(700); }).then(function () { mark(p, 2, "ok"); calCard(); return sleep(1600); })
        .then(function () { mark(p, 3, "run"); return sleep(700); }).then(function () { mark(p, 3, "ok"); picksCard(); return sleep(1800); });
    } },
    { title: "Ends with next steps you can tap", sub: "What it all shows, and actions that run in one tap", run: function () {
      wrapUp(); setPills(["🩺 Check BALLIA's trend", "🎯 Today's picks", "📊 Compare strategies"]);
      return sleep(1800).then(function () { return tap(body.querySelector('.cd-act[data-cmd="/health BALLIA"]')); })
        .then(function () { hideFinger(); setPills([]); user("/health BALLIA"); return sleep(700); }).then(function () { healthCard(); return sleep(2000); });
    } },
    { title: "Every ticker is a wheel", sub: "Tap or long-press any stock: its actions fan out in a ring around your finger", run: function () {
      var t = Array.prototype.slice.call(body.querySelectorAll(".cd-bot .cd-tick")).pop() || body.querySelector(".cd-tick");
      // scroll only the chat, never the page
      if (t) body.scrollTo({ top: t.getBoundingClientRect().top - body.getBoundingClientRect().top + body.scrollTop - body.clientHeight / 2, behavior: fast || reduce ? "auto" : "smooth" });
      return sleep(700).then(function () { return tap(t); }).then(function () {
        hideFinger();
        // the circular menu, as in the app: actions fan out on a ring around the finger, the symbol in the centre
        // thin line icons, as in the app
        var I = {
          Trend: '<path d="M2 15l6-6 4 3.5 8-8M15 4.5h5v5"/>', Chart: '<path d="M4 20v-8M11 20V4M18 20v-12"/>',
          News: '<rect x="3" y="3" width="17" height="17" rx="2.5"/><path d="M7.5 8.5h8M7.5 12h8M7.5 15.5h5"/>',
          Events: '<rect x="3" y="5" width="17" height="15" rx="2.5"/><path d="M3 9.5h17M8 2.5v4M15 2.5v4"/>',
          Quote: '<path d="M7 5h10M7 9h10M12 9c0 3-2 5-5 5h-.5l7 6"/>', "Deep dive": '<circle cx="10" cy="10" r="6"/><path d="M14.5 14.5l5.5 5.5"/>',
          Ask: '<path d="M4 4h15a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-8l-5 4v-4H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z"/>' };
        var acts = ["Trend", "Chart", "News", "Events", "Quote", "Deep dive", "Ask"].map(function (n) { return ['<svg viewBox="0 0 23 23">' + I[n] + "</svg>", n]; });
        var pr = phone.getBoundingClientRect(), tr = t.getBoundingClientRect(), R = 76;
        var cx = Math.min(Math.max(tr.left - pr.left + 30, R + 44), pr.width - R - 44), cy = Math.min(Math.max(tr.top - pr.top, R + 70), pr.height - R - 90);
        wheel.innerHTML = '<div class="cd-disc" style="left:' + cx + 'px;top:' + cy + 'px"></div><div class="cd-hub" style="left:' + cx + 'px;top:' + cy + 'px">BALLIA</div>' +
          acts.map(function (a, i) { var ang = (-90 + i * 360 / acts.length) * Math.PI / 180;
            return '<div class="cd-spoke" style="left:' + (cx + R * Math.cos(ang)) + 'px;top:' + (cy + R * Math.sin(ang)) + 'px;--dx:' + (-R * Math.cos(ang) * 0.28) + 'px;--dy:' + (-R * Math.sin(ang) * 0.28) + 'px;animation-delay:' + (70 + i * 22) + 'ms"><b>' + a[0] + "</b><span>" + a[1] + "</span></div>"; }).join("");
        wheel.classList.add("on"); return sleep(1700);
      }).then(function () { return tap(wheel.querySelectorAll(".cd-spoke")[1]); })
        .then(function () { wheel.classList.remove("on"); hideFinger(); user("/chart BALLIA 1y"); return sleep(700); }).then(function () { chartCard(); return sleep(2000); });
    } },
    { title: "Commands ask for what they need", sub: "Tap a command; it asks each parameter with choices — then runs", run: function () {
      return type("/sip ", 14).then(function () {
        suggest.innerHTML = '<b>/sip 10000 12% 15y</b><span>SIP growth: monthly amount, return, years</span><em>Next: How much a month? ↓</em>'; suggest.classList.add("on");
        setPills(["5000", "10000", "25000", "50000"]); return sleep(1200);
      }).then(function () { return tap(chip("10000")); })
        .then(function () { typed.textContent = "/sip 10000 "; suggest.querySelector("em").textContent = "Next: Expected yearly return? ↓"; setPills(["10%", "12%", "15%"]); return sleep(900); })
        .then(function () { return tap(chip("12%")); })
        .then(function () { typed.textContent = "/sip 10000 12% "; suggest.querySelector("em").textContent = "Next: For how long? ↓"; setPills(["5y", "10y", "15y", "20y"]); return sleep(900); })
        .then(function () { return tap(chip("15y")); })
        .then(function () { hideFinger(); typed.textContent = ""; phone.classList.remove("typing"); suggest.classList.remove("on"); setPills([]); user("/sip 10000 12% 15y"); return sleep(700); })
        .then(function () { sipCard(); return sleep(2200); });
    } },
    { title: "Downloads only when needed", sub: "Market history and the knowledge bank arrive on demand, then it runs", run: function () {
      return type("/screens quality low debt", 20).then(send).then(function (t) { hideFinger(); user(t); return sleep(600); })
        .then(function () { needCard(); return sleep(1300); })
        .then(function () { return tap(body.lastElementChild.querySelector(".cd-btn")); })
        .then(function () {
          hideFinger(); var c = progressCard(), bar = c.querySelector(".cd-prog span"), h = c.querySelector(".cd-ptitle"), st = c.querySelector(".cd-pst"), k = 0;
          function step() {
            if (k > 10) { h.textContent = "✓ Knowledge bank ready"; st.textContent = "Running what you asked…"; return sleep(700); }
            var p = k * 10; bar.style.width = p + "%"; h.textContent = "Downloading knowledge bank… " + p + "%"; st.textContent = Math.round(289 * p / 100) + " of 289 KB"; k++;
            return sleep(160).then(step);
          }
          return step();
        }).then(function () { screensCard(); return sleep(2400); });
    } },
    { title: "Every answer explains itself", sub: "Why you're seeing it, how it was made — and that nothing left the phone", run: function () {
      var foot = add(el("div", "cd-foot", "⧉ &nbsp; ⤴ &nbsp; ⏱ 0.4 s · app · <u>how this was made ▾</u>"));
      return sleep(900).then(function () { return tap(foot); }).then(function () {
        hideFinger(); foot.innerHTML = "⧉ &nbsp; ⤴ &nbsp; ⏱ 0.4 s · app · <u>how this was made ▴</u>";
        card('<div class="cd-kick">HOW THIS WAS MADE</div>' +
          '<div class="cd-why"><b>WHY YOU\'RE SEEING THIS</b>You typed /screens quality low debt. It needed the knowledge bank first, so it downloaded that, then ran.</div>' +
          '<div class="cd-why"><b>HOW · /SCREENS</b>Data: rules of 500 public screens, downloaded once and checked by checksum.<br>Method: keyword search picks the best matches; EmbeddingGemma 2 vectors add the closest in meaning (≈).<br>Limits: rules are each author\'s; they need company financials.</div>' +
          '<div class="cd-why"><b>PRIVACY</b><span class="up">Worked out entirely on this phone. Nothing was sent anywhere.</span></div>' +
          '<div class="cd-why"><b>TIMING</b>Total 0.4 s · App only (no AI needed)</div>', "cd-how");
        return sleep(3200);
      });
    } },
  ];

  // ---------------------------------------------------------------- player
  if (scenesEl) {
    scenesEl.innerHTML = SCENES.map(function (s, i) { return '<li data-i="' + i + '"><b>' + s.title + "</b><span>" + s.sub + "</span></li>"; }).join("");
    scenesEl.addEventListener("click", function (e) { var li = e.target.closest("li"); if (li) jump(+li.getAttribute("data-i")); });
  }
  function light(i) { if (!scenesEl) return; Array.prototype.forEach.call(scenesEl.children, function (li, j) { li.classList.toggle("on", j === i); li.classList.toggle("done", j < i); }); }

  function play(from) {
    var my = ++runId;
    var chain = Promise.resolve();
    // catch up instantly to the scene asked for
    fast = true; greet();
    for (var j = 0; j < from; j++) (function (s) { chain = chain.then(function () { return s.run(); }); })(SCENES[j]);
    chain = chain.then(function () { fast = false; hideFinger(); });
    var i = from;
    function loop() {
      light(i);
      return SCENES[i].run().then(function () { return sleep(1300); }).then(function () {
        i = (i + 1) % SCENES.length;
        if (reduce && i === 0) return;                    // reduced motion: one pass, finished frames
        if (i === 0) return sleep(600).then(function () { greet(); return loop(); });
        return loop();
      });
    }
    chain.then(loop).catch(function (e) { if (e !== STOP && window.console) console.error(e); });
    return my;
  }
  function jump(i) { userPaused = false; sync(); play(i); }

  // plays only while on screen and the tab is visible, unless the visitor paused it
  var userPaused = false, onScreen = true;
  function sync() { setPaused(userPaused || !onScreen || document.hidden); }
  if (toggle) toggle.addEventListener("click", function () { userPaused = !userPaused; sync(); });
  if ("IntersectionObserver" in window) new IntersectionObserver(function (es) { es.forEach(function (e) { onScreen = e.isIntersecting; }); sync(); }, { threshold: 0.2 }).observe(phone);
  document.addEventListener("visibilitychange", sync);

  play(0);
})();
