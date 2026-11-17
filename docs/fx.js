/* Motion for the landing page — small, dependency-free versions of effects in the spirit of React Bits
   (Aurora, SplitText, RotatingText, ShinyText, CountUp, LogoLoop, Magnet, SpotlightCard, TiltedCard,
   DotGrid, ScrollReveal). All of it switches off for people who ask their system for reduced motion. */
(function () {
  "use strict";
  document.documentElement.classList.add("js");      // content is only hidden for the reveal when this script runs
  var still = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  var $ = function (s, r) { return (r || document).querySelector(s); }, $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };

  // Aurora: soft colour fields drifting behind the hero.
  var au = $("#aurora");
  if (au && au.getContext) {
    var cx = au.getContext("2d"), t0 = 0, blobs = [["82,130,235", .55, .25, .42], ["142,124,240", .25, .55, .36], ["103,173,91", .78, .7, .3], ["255,111,165", .4, .9, .26]];
    var size = function () { au.width = au.offsetWidth; au.height = au.offsetHeight; }; size(); window.addEventListener("resize", size);
    var draw = function (t) {
      var w = au.width, h = au.height; cx.clearRect(0, 0, w, h); cx.globalCompositeOperation = "lighter";
      blobs.forEach(function (b, i) {
        var x = w * (b[1] + Math.sin(t / 9000 + i * 1.7) * .12), y = h * (b[2] + Math.cos(t / 11000 + i) * .1), r = Math.max(w, h) * b[3];
        var g = cx.createRadialGradient(x, y, 0, x, y, r); g.addColorStop(0, "rgba(" + b[0] + ",.22)"); g.addColorStop(1, "rgba(" + b[0] + ",0)");
        cx.fillStyle = g; cx.fillRect(0, 0, w, h);
      });
      if (!still) requestAnimationFrame(draw);
    };
    requestAnimationFrame(draw);
  }

  // SplitText: the headline's words rise in one after another.
  $$("[data-split]").forEach(function (el) {
    var words = el.textContent.trim().split(/\s+/); el.textContent = "";
    words.forEach(function (w, i) { var s = document.createElement("span"); s.className = "split-w"; s.style.animationDelay = (i * 0.08) + "s"; s.textContent = w; el.appendChild(s); el.appendChild(document.createTextNode(" ")); });
  });

  // RotatingText: cycles through example questions, typing each one.
  $$("[data-rotate]").forEach(function (el) {
    var items = JSON.parse(el.getAttribute("data-rotate")), k = 0;
    if (still) { el.textContent = items[0]; return; }
    var type = function () {
      var s = items[k % items.length], i = 0; el.textContent = "";
      var step = function () { el.textContent = s.slice(0, ++i); if (i < s.length) setTimeout(step, 28); else setTimeout(function () { k++; type(); }, 1900); };
      step();
    };
    type();
  });

  // CountUp: numbers count up the first time they are seen.
  var counted = new IntersectionObserver(function (es) { es.forEach(function (e) {
    if (!e.isIntersecting) return; counted.unobserve(e.target);
    var el = e.target, to = +el.getAttribute("data-count"), d = 1400, s = performance.now();
    if (still) { el.textContent = to.toLocaleString("en-IN"); return; }
    var tick = function (n) { var p = Math.min(1, (n - s) / d), v = Math.round(to * (1 - Math.pow(1 - p, 3))); el.textContent = v.toLocaleString("en-IN"); if (p < 1) requestAnimationFrame(tick); };
    requestAnimationFrame(tick);
  }); }, { threshold: .4 });
  $$("[data-count]").forEach(function (el) { counted.observe(el); });

  // LogoLoop: endless marquees (content duplicated once so the loop is seamless).
  $$(".marquee").forEach(function (m) { var tr = $(".marquee-track", m); if (tr) tr.innerHTML += tr.innerHTML; });

  // ScrollReveal: sections and tiles fade up as they enter.
  var rev = new IntersectionObserver(function (es) { es.forEach(function (e) { if (e.isIntersecting) { e.target.classList.add("in"); rev.unobserve(e.target); } }); }, { threshold: .08 });
  var arm = function () { $$(".reveal:not(.in)").forEach(function (el) { rev.observe(el); }); };
  arm(); setTimeout(arm, 600);

  if (still) return;

  // SpotlightCard: a soft light follows the pointer across any card.
  document.addEventListener("pointermove", function (e) {
    var c = e.target.closest && e.target.closest(".card, .wall-tile, .bento > div");
    if (!c) return; var r = c.getBoundingClientRect();
    c.style.setProperty("--mx", (e.clientX - r.left) + "px"); c.style.setProperty("--my", (e.clientY - r.top) + "px");
  });

  // Magnet: call-to-action buttons lean toward the pointer.
  $$(".magnet").forEach(function (b) {
    b.addEventListener("pointermove", function (e) { var r = b.getBoundingClientRect(); b.style.transform = "translate(" + ((e.clientX - r.left - r.width / 2) * .25) + "px," + ((e.clientY - r.top - r.height / 2) * .35) + "px)"; });
    b.addEventListener("pointerleave", function () { b.style.transform = ""; });
  });

  // TiltedCard: the phone tilts with the pointer.
  $$(".tilt").forEach(function (el) {
    el.addEventListener("pointermove", function (e) { var r = el.getBoundingClientRect(), x = (e.clientX - r.left) / r.width - .5, y = (e.clientY - r.top) / r.height - .5;
      el.style.transform = "perspective(900px) rotateY(" + (x * 10) + "deg) rotateX(" + (-y * 10) + "deg)"; });
    el.addEventListener("pointerleave", function () { el.style.transform = ""; });
  });

  // DotGrid: a field of dots that brighten near the pointer.
  $$("canvas.dots").forEach(function (cv) {
    var c = cv.getContext("2d"), mx = -999, my = -999;
    var size = function () { cv.width = cv.offsetWidth; cv.height = cv.offsetHeight; }; size(); window.addEventListener("resize", size);
    cv.parentElement.addEventListener("pointermove", function (e) { var r = cv.getBoundingClientRect(); mx = e.clientX - r.left; my = e.clientY - r.top; });
    cv.parentElement.addEventListener("pointerleave", function () { mx = my = -999; });
    var loop = function () {
      c.clearRect(0, 0, cv.width, cv.height);
      for (var y = 12; y < cv.height; y += 22) for (var x = 12; x < cv.width; x += 22) {
        var d = Math.hypot(x - mx, y - my), a = d < 140 ? .5 - d / 280 : .08; c.fillStyle = "rgba(82,130,235," + Math.max(.08, a) + ")"; c.beginPath(); c.arc(x, y, d < 140 ? 1.9 : 1.2, 0, 7); c.fill();
      }
      requestAnimationFrame(loop);
    };
    loop();
  });
})();
