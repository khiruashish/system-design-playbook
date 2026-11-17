"""Render Mermaid diagrams to PNG with headless Chromium (Playwright) and a local mermaid.min.js."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path

THEME = {
    "background": "#ffffff", "fontFamily": "Inter, sans-serif", "fontSize": "18px",
    "primaryColor": "#eef2ff", "primaryTextColor": "#1e1b4b", "primaryBorderColor": "#6366f1",
    "secondaryColor": "#fdf4ff", "secondaryTextColor": "#1e1b4b", "secondaryBorderColor": "#c026d3",
    "tertiaryColor": "#ecfeff", "tertiaryTextColor": "#164e63", "tertiaryBorderColor": "#0891b2",
    "lineColor": "#6366f1", "textColor": "#1e293b",
    "actorBkg": "#eef2ff", "actorBorder": "#6366f1", "noteBkgColor": "#fef9c3", "noteBorderColor": "#ca8a04",
}


class MermaidRenderer:
    def __init__(self, mermaid_js: Path, cache: Path):
        self.js = mermaid_js
        self.cache = cache
        self.cache.mkdir(parents=True, exist_ok=True)
        self._pw = self._browser = self._page = None

    def _ensure(self):
        if self._page:
            return
        from playwright.sync_api import sync_playwright
        self._pw = sync_playwright().start()
        self._browser = self._pw.chromium.launch(executable_path=os.environ.get("CHROMIUM", "/opt/pw-browsers/chromium") if os.path.exists(os.environ.get("CHROMIUM", "/opt/pw-browsers/chromium")) else None)
        self._page = self._browser.new_page(device_scale_factor=2, viewport={"width": 1800, "height": 1000})
        self._page.set_content("<html><body style='margin:0;background:#fff'><div id=c style='display:inline-block;"
                               "padding:12px'></div></body></html>")
        self._page.add_script_tag(path=str(self.js))
        self._page.evaluate("t => mermaid.initialize({startOnLoad:false, theme:'base', themeVariables:t, "
                            "securityLevel:'loose', flowchart:{htmlLabels:true}})", THEME)

    def render(self, src: str) -> Path | None:
        key = hashlib.sha1(("v1" + src).encode()).hexdigest()
        out = self.cache / f"{key}.png"
        bad = self.cache / f"{key}.err"
        if out.exists():
            return out
        if bad.exists():
            return None
        self._ensure()
        try:
            self._page.evaluate("""async src => {
                const {svg} = await mermaid.render('d' + Math.floor(Math.random()*1e9), src);
                const c = document.getElementById('c'); c.innerHTML = svg;
                const s = c.querySelector('svg'); s.style.maxWidth = 'none';
            }""", src)
            self._page.locator("#c").screenshot(path=str(out))
            return out
        except Exception as e:  # invalid diagram: remember and skip
            bad.write_text(json.dumps(str(e))[:2000])
            return None

    def close(self):
        if self._browser:
            self._browser.close()
            self._pw.stop()
            self._page = None
