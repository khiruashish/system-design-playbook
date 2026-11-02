/*
 * Diagram renderer for the playbook.
 *
 * Mermaid blocks are emitted as <div class="diagram"> (see mkdocs.yml superfences config) so that we, not the theme,
 * control rendering. That lets us:
 *   - use a custom light/dark palette with larger, readable fonts
 *   - give nodes a raised "3D" look (gradient fill + soft drop shadow)
 *   - keep diagrams at natural size (no shrinking into unreadable text) with horizontal scroll when needed
 *   - add an "Expand" button that opens a full-screen, zoomable view
 *   - re-render when the reader switches light/dark mode
 */
(function () {
  "use strict";

  var THEMES = {
    light: {
      background: "transparent",
      fontFamily: "Inter, 'Segoe UI', Roboto, sans-serif",
      fontSize: "16px",
      primaryColor: "#eef2ff",
      primaryTextColor: "#1e1b4b",
      primaryBorderColor: "#6366f1",
      secondaryColor: "#fdf4ff",
      secondaryTextColor: "#1e1b4b",
      secondaryBorderColor: "#c026d3",
      tertiaryColor: "#ecfeff",
      tertiaryTextColor: "#164e63",
      tertiaryBorderColor: "#0891b2",
      lineColor: "#6366f1",
      textColor: "#1e293b",
      mainBkg: "#eef2ff",
      nodeBorder: "#6366f1",
      clusterBkg: "#f8fafc",
      clusterBorder: "#a5b4fc",
      titleColor: "#312e81",
      edgeLabelBackground: "#ffffff",
      noteBkgColor: "#fef9c3",
      noteBorderColor: "#eab308",
      noteTextColor: "#422006",
      actorBkg: "#4f46e5",
      actorBorder: "#3730a3",
      actorTextColor: "#ffffff",
      actorLineColor: "#a5b4fc",
      signalColor: "#334155",
      signalTextColor: "#0f172a",
      labelBoxBkgColor: "#e0e7ff",
      labelBoxBorderColor: "#6366f1",
      labelTextColor: "#1e1b4b",
      loopTextColor: "#312e81",
      activationBkgColor: "#c7d2fe",
      activationBorderColor: "#6366f1",
      sequenceNumberColor: "#ffffff",
      altBackground: "#f5f3ff"
    },
    dark: {
      background: "transparent",
      fontFamily: "Inter, 'Segoe UI', Roboto, sans-serif",
      fontSize: "16px",
      darkMode: true,
      primaryColor: "#312e81",
      primaryTextColor: "#eef2ff",
      primaryBorderColor: "#818cf8",
      secondaryColor: "#4a044e",
      secondaryTextColor: "#fdf4ff",
      secondaryBorderColor: "#e879f9",
      tertiaryColor: "#164e63",
      tertiaryTextColor: "#ecfeff",
      tertiaryBorderColor: "#22d3ee",
      lineColor: "#a5b4fc",
      textColor: "#e2e8f0",
      mainBkg: "#312e81",
      nodeBorder: "#818cf8",
      clusterBkg: "#1e1b4b",
      clusterBorder: "#4f46e5",
      titleColor: "#c7d2fe",
      edgeLabelBackground: "#1e1b4b",
      noteBkgColor: "#422006",
      noteBorderColor: "#eab308",
      noteTextColor: "#fef9c3",
      actorBkg: "#6366f1",
      actorBorder: "#a5b4fc",
      actorTextColor: "#ffffff",
      actorLineColor: "#6366f1",
      signalColor: "#cbd5e1",
      signalTextColor: "#f1f5f9",
      labelBoxBkgColor: "#312e81",
      labelBoxBorderColor: "#818cf8",
      labelTextColor: "#eef2ff",
      loopTextColor: "#c7d2fe",
      activationBkgColor: "#4338ca",
      activationBorderColor: "#a5b4fc",
      sequenceNumberColor: "#1e1b4b",
      altBackground: "#1e1b4b"
    }
  };

  var counter = 0;

  function scheme() {
    return document.body.getAttribute("data-md-color-scheme") === "slate" ? "dark" : "light";
  }

  /* Shared SVG gradients referenced by every diagram (url(#id) resolves document-wide). */
  function ensureDefs() {
    if (document.getElementById("pb-diagram-defs")) return;
    var ns = "http://www.w3.org/2000/svg";
    var svg = document.createElementNS(ns, "svg");
    svg.setAttribute("id", "pb-diagram-defs");
    svg.setAttribute("aria-hidden", "true");
    svg.style.position = "absolute";
    svg.style.width = "0";
    svg.style.height = "0";
    svg.innerHTML =
      '<defs>' +
      '<linearGradient id="pb-node-light" x1="0" y1="0" x2="0" y2="1">' +
      '<stop offset="0" stop-color="#ffffff"/><stop offset="1" stop-color="#e0e7ff"/></linearGradient>' +
      '<linearGradient id="pb-node-dark" x1="0" y1="0" x2="0" y2="1">' +
      '<stop offset="0" stop-color="#4338ca"/><stop offset="1" stop-color="#1e1b4b"/></linearGradient>' +
      '<linearGradient id="pb-actor-light" x1="0" y1="0" x2="0" y2="1">' +
      '<stop offset="0" stop-color="#6366f1"/><stop offset="1" stop-color="#4338ca"/></linearGradient>' +
      '<linearGradient id="pb-actor-dark" x1="0" y1="0" x2="0" y2="1">' +
      '<stop offset="0" stop-color="#818cf8"/><stop offset="1" stop-color="#4f46e5"/></linearGradient>' +
      '</defs>';
    document.body.appendChild(svg);
  }

  /* Give plain nodes a raised gradient look. Nodes with their own style (classDef/style) keep their colours. */
  function decorate(svgEl, mode) {
    var nodeFill = "url(#pb-node-" + mode + ")";
    var actorFill = "url(#pb-actor-" + mode + ")";
    svgEl.querySelectorAll("g.node rect, g.node polygon, g.node circle, g.node path.basic").forEach(function (shape) {
      var style = shape.getAttribute("style") || "";
      if (/fill\s*:/.test(style)) return;
      shape.setAttribute("style", style + ";fill:" + nodeFill);
    });
    svgEl.querySelectorAll("rect.actor").forEach(function (shape) {
      var style = shape.getAttribute("style") || "";
      if (/fill\s*:/.test(style)) return;
      shape.setAttribute("style", style + ";fill:" + actorFill);
    });
  }

  /* Wide diagrams: shrink to fit the column, but never below MIN_SCALE (keeps text readable); beyond that, scroll. */
  var MIN_SCALE = 0.72;
  function fit(el) {
    var canvas = el.querySelector(".pb-diagram-canvas");
    var svg = el.querySelector("svg");
    if (!canvas || !svg) return;
    var vb = svg.viewBox && svg.viewBox.baseVal;
    var natural = vb && vb.width ? vb.width : svg.getBoundingClientRect().width;
    var naturalH = vb && vb.height ? vb.height : svg.getBoundingClientRect().height;
    var avail = canvas.clientWidth;
    if (!natural || !avail) return;
    var scale = natural > avail ? Math.max(avail / natural, MIN_SCALE) : 1;
    svg.setAttribute("width", Math.round(natural * scale));
    svg.setAttribute("height", Math.round(naturalH * scale));
  }

  var resizeTimer;
  window.addEventListener("resize", function () {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () {
      document.querySelectorAll("div.diagram.is-rendered").forEach(fit);
    }, 150);
  });

  function makeToolbar(container) {
    if (container.querySelector(".pb-diagram-expand")) return;
    var btn = document.createElement("button");
    btn.type = "button";
    btn.className = "pb-diagram-expand";
    btn.setAttribute("aria-label", "Expand diagram");
    btn.title = "Expand diagram";
    btn.innerHTML = "&#x2922; Expand";
    btn.addEventListener("click", function () { openLightbox(container); });
    container.appendChild(btn);
  }

  function openLightbox(container) {
    var svg = container.querySelector("svg");
    if (!svg) return;
    var overlay = document.createElement("div");
    overlay.className = "pb-lightbox";
    overlay.setAttribute("role", "dialog");
    overlay.setAttribute("aria-modal", "true");
    overlay.setAttribute("aria-label", "Diagram, full screen");

    var frame = document.createElement("div");
    frame.className = "pb-lightbox-frame";
    var clone = svg.cloneNode(true);
    clone.removeAttribute("width");
    clone.removeAttribute("height");
    clone.style.maxWidth = "none";
    clone.style.width = "100%";
    clone.style.height = "100%";
    frame.appendChild(clone);

    var close = document.createElement("button");
    close.type = "button";
    close.className = "pb-lightbox-close";
    close.setAttribute("aria-label", "Close");
    close.innerHTML = "&#x2715;";

    overlay.appendChild(frame);
    overlay.appendChild(close);
    document.body.appendChild(overlay);
    document.documentElement.classList.add("pb-no-scroll");

    function dismiss() {
      overlay.remove();
      document.documentElement.classList.remove("pb-no-scroll");
      document.removeEventListener("keydown", onKey);
    }
    function onKey(e) { if (e.key === "Escape") dismiss(); }
    close.addEventListener("click", dismiss);
    overlay.addEventListener("click", function (e) { if (e.target === overlay) dismiss(); });
    document.addEventListener("keydown", onKey);
    close.focus();
  }

  function configure(mode) {
    window.mermaid.initialize({
      startOnLoad: false,
      securityLevel: "strict",
      theme: "base",
      themeVariables: THEMES[mode],
      fontFamily: THEMES[mode].fontFamily,
      flowchart: { curve: "basis", padding: 18, nodeSpacing: 45, rankSpacing: 55, useMaxWidth: false, htmlLabels: true },
      sequence: { useMaxWidth: false, actorMargin: 70, boxMargin: 12, noteMargin: 12, messageMargin: 40,
                  mirrorActors: false, actorFontSize: 15, messageFontSize: 15, noteFontSize: 14 },
      state: { useMaxWidth: false },
      class: { useMaxWidth: false },
      timeline: { useMaxWidth: false },
      xyChart: { useMaxWidth: false, width: 760, height: 380 }
    });
  }

  async function renderAll() {
    if (!window.mermaid) return;
    var nodes = document.querySelectorAll("div.diagram");
    if (!nodes.length) return;
    ensureDefs();
    /* Measure text with the real web font, otherwise labels are sized for the fallback font and get clipped. */
    if (document.fonts && document.fonts.ready) { try { await document.fonts.ready; } catch (e) { /* ignore */ } }
    var mode = scheme();
    configure(mode);
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (!el.dataset.source) el.dataset.source = el.textContent.trim();
      if (el.dataset.rendered === mode) continue;
      try {
        var id = "pb-diagram-" + (++counter);
        var out = await window.mermaid.render(id, el.dataset.source);
        el.innerHTML = '<div class="pb-diagram-canvas">' + out.svg + "</div>";
        var svgEl = el.querySelector("svg");
        svgEl.removeAttribute("style");          /* drop max-width so text stays at natural, readable size */
        decorate(svgEl, mode);
        fit(el);
        el.dataset.rendered = mode;
        el.classList.add("is-rendered");
        makeToolbar(el);
      } catch (err) {
        el.classList.add("is-error");
        el.textContent = "Diagram failed to render: " + (err && err.message ? err.message : err);
      }
    }
  }

  function start() {
    renderAll();
    /* Re-render when the reader toggles light/dark. */
    new MutationObserver(function () { renderAll(); })
      .observe(document.body, { attributes: true, attributeFilter: ["data-md-color-scheme"] });
  }

  if (window.document$ && typeof window.document$.subscribe === "function") {
    window.document$.subscribe(function () { renderAll(); });
    new MutationObserver(function () { renderAll(); })
      .observe(document.body, { attributes: true, attributeFilter: ["data-md-color-scheme"] });
  } else if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
