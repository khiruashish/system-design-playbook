"""MkDocs hook: embed each chapter's narrated video under the page's H1.

Reads docs/videos/manifest.json (written by tools/videos/make_videos.py). Pages without a video are left
untouched, so the site still builds before any video has been generated.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

_manifest: dict[str, dict] = {}


def on_config(config, **kwargs):
    global _manifest
    path = Path(config["docs_dir"]) / "videos" / "manifest.json"
    _manifest = {m["page"]: m for m in json.loads(path.read_text())} if path.exists() else {}
    return config


def _fmt(sec: float) -> str:
    m, s = divmod(int(round(sec)), 60)
    return f"{m}:{s:02d}"


def player(m: dict, prefix: str) -> str:
    src = m["video"] if m["video"].startswith("http") else prefix + m["video"]
    return (
        f'<figure class="ssp-video">\n'
        f'<video controls preload="none" playsinline poster="{prefix}{m["poster"]}">\n'
        f'<source src="{src}" type="video/mp4">\n'
        f'<track kind="captions" srclang="en" label="English" src="{prefix}{m["captions"]}" default>\n'
        f"</video>\n"
        f'<figcaption>▶ Narrated walkthrough of this chapter · {_fmt(m["duration"])} · concepts, diagrams, code and interview Q&A'
        f"</figcaption>\n</figure>\n"
    )


def on_page_markdown(markdown, page, config, files, **kwargs):
    m = _manifest.get(page.file.src_uri)
    if not m or page.file.src_uri == "videos/index.md":
        return markdown
    # page URL depth decides how many ../ to reach the site root where /videos lives
    depth = page.url.rstrip("/").count("/") + (1 if page.url.strip("/") else 0)
    if not config.get("use_directory_urls", True):
        depth = page.file.src_uri.count("/")
    prefix = "../" * depth + "videos/"
    html = player(m, prefix)
    new, n = re.subn(r"^(# .+\n)", lambda mm: mm.group(1) + "\n" + html + "\n", markdown, count=1, flags=re.M)
    if n:
        return new
    return markdown.replace("</div>\n</div>\n", "</div>\n</div>\n\n" + html + "\n", 1) if "ssp-hero" in markdown \
        else html + "\n" + markdown
