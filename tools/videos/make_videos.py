"""Generate one narrated MP4 per System Design Playbook chapter.

    python tools/videos/make_videos.py                 # all chapters
    python tools/videos/make_videos.py career/resignation.md   # just one

Needs: ffmpeg, Pillow, numpy, kokoro-onnx, soundfile, PyYAML, and the Kokoro model files
(kokoro-v1.0.int8.onnx + voices-v1.0.bin; downloaded automatically into $VIDEO_WORK/models).
Output: docs/videos/<section>/<page>.mp4 (+ .vtt captions) and docs/videos/manifest.json
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

import numpy as np
import soundfile as sf
import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import parse as P  # noqa: E402
import render as R  # noqa: E402
import slides as S  # noqa: E402
from mermaid_render import MermaidRenderer  # noqa: E402
from parse import parse  # noqa: E402
from slides import build  # noqa: E402
from text import sentences  # noqa: E402

ROOT = HERE.parent.parent
DOCS = ROOT / "docs"
OUT = DOCS / "videos"            # captions, posters, manifest (small; committed to main)
WORK = Path(os.environ.get("VIDEO_WORK", "/tmp/sdp-work"))
MP4_DIR = Path(os.environ.get("VIDEO_MP4_DIR", WORK / "mp4"))   # MP4s; published on the `videos` branch
MP4_URL = os.environ.get("VIDEO_MP4_URL",
                         "https://raw.githubusercontent.com/khiruashish/system-design-playbook/videos/")
MERMAID_JS = Path(os.environ.get("MERMAID_JS", WORK / "vendor" / "mermaid.min.js"))
SKIP = {"99-appendix/qa-index.md", "07-interview/qa-bank-starred.md", "99-appendix/lab-catalogue.md"}
P.SNIPPET_ROOT = ROOT
MODELS = WORK / "models"
VOICE = os.environ.get("VIDEO_VOICE", "af_heart")
SPEED = float(os.environ.get("VIDEO_SPEED", "1.0"))
SR = 24000
MODEL_URLS = {
    "kokoro-v1.0.int8.onnx": "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.int8.onnx",
    "voices-v1.0.bin": "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin",
}

SENT_GAP = 0.28
SLIDE_PAD = {"title": 1.4, "section": 0.9, "end": 2.0, "diagram": 3.5, "code": 2.5}
DEFAULT_PAD = 1.0


# ------------------------------------------------------------------ chapters from mkdocs nav

class _Loader(yaml.SafeLoader):
    pass


_Loader.add_multi_constructor("tag:yaml.org,2002:python/", lambda l, s, n: None)
_Loader.add_multi_constructor("!", lambda l, s, n: None)


def chapters():
    cfg = yaml.load((ROOT / "mkdocs.yml").read_text(), Loader=_Loader)
    out = []

    def add(section, label, path):
        if path in SKIP or path.startswith("videos/"):
            return
        m = re.match(r"^([A-Z]?\d+[a-z]?)\.\s+(.*)$", label or "")
        num, title = (m.group(1), m.group(2)) if m else (None, label)
        out.append({"section": section, "title": title, "path": path, "number": num})

    for entry in cfg["nav"]:
        (name, val), = entry.items()
        if isinstance(val, str):
            add("", name, val)
            continue
        for sub in val:
            if isinstance(sub, str):
                add(name, f"{name}: Overview", sub)
            else:
                (t, p), = sub.items()
                add(name, t, p)
    return out


# ------------------------------------------------------------------ TTS

_kokoro = None


def tts(text: str) -> np.ndarray:
    global _kokoro
    key = hashlib.sha1(f"{VOICE}|{SPEED}|{text}".encode()).hexdigest()
    cache = WORK / "tts" / f"{key}.npy"
    if cache.exists():
        return np.load(cache)
    if _kokoro is None:
        from kokoro_onnx import Kokoro
        MODELS.mkdir(parents=True, exist_ok=True)
        for fn, url in MODEL_URLS.items():
            if not (MODELS / fn).exists():
                print("downloading", fn, flush=True)
                urllib.request.urlretrieve(url, MODELS / fn)
        _kokoro = Kokoro(str(MODELS / "kokoro-v1.0.int8.onnx"), str(MODELS / "voices-v1.0.bin"))
    samples, sr = _kokoro.create(text, voice=VOICE, speed=SPEED, lang="en-us")
    assert sr == SR
    samples = samples.astype(np.float32)
    cache.parent.mkdir(parents=True, exist_ok=True)
    np.save(cache, samples)
    return samples


def ts(t: float, vtt=True) -> str:
    h, rem = divmod(t, 3600)
    m, s = divmod(rem, 60)
    sep = "." if vtt else ","
    return f"{int(h):02d}:{int(m):02d}:{int(s):02d}{sep}{int(round((s % 1) * 1000)) % 1000:03d}"


# ------------------------------------------------------------------ one chapter

def ensure_mermaid():
    if MERMAID_JS.exists():
        return
    MERMAID_JS.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["npm", "pack", "mermaid@11.4.1", "--silent"], cwd=MERMAID_JS.parent, check=True)
    subprocess.run(["tar", "xzf", "mermaid-11.4.1.tgz", "package/dist/mermaid.min.js"], cwd=MERMAID_JS.parent, check=True)
    (MERMAID_JS.parent / "package/dist/mermaid.min.js").rename(MERMAID_JS)


def commit_main(path: str):
    """Commit this chapter's captions/poster + manifest/index on the current branch and push it."""
    branch = subprocess.run(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=ROOT, capture_output=True,
                            text=True).stdout.strip()
    subprocess.run(["git", "add", "docs/videos"], cwd=ROOT, check=True)
    msg = (f"Add chapter video captions: {path}\n\n"
           "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>\n"
           "Claude-Session: https://claude.ai/code/session_019tfTGN7QqwEiznF8auCEZq")
    if subprocess.run(["git", "commit", "-q", "-m", msg], cwd=ROOT).returncode != 0:
        return
    for i in range(5):
        if subprocess.run(["git", "-c", "push.negotiate=true", "push", "-q", "origin", branch], cwd=ROOT).returncode == 0:
            return
        import time
        time.sleep(10 * (i + 1))


def make_chapter(ch: dict) -> dict:
    ensure_mermaid()
    if S.MERMAID is None:
        S.MERMAID = MermaidRenderer(MERMAID_JS, WORK / "mermaid")
    src = DOCS / ch["path"]
    slug = ch["path"][:-3]
    meta = {"section": ch["section"], "chapter": re.sub(r"[⭐\"]", "", ch["title"].replace(": Overview", "")).strip(),
            "number": ch["number"]}
    slides = build(parse(src.read_text()), meta)
    work = WORK / "chapters" / slug.replace("/", "__")
    (work / "img").mkdir(parents=True, exist_ok=True)

    audio_parts, cues, concat = [], [], []
    t = 0.0
    for i, s in enumerate(slides):
        png = work / "img" / f"{i:03d}.png"
        R.render(s, meta, i, len(slides)).save(png, optimize=False, compress_level=1)
        start = t
        sents = sentences(s["narration"])
        lead = 0.35
        audio_parts.append(np.zeros(int(lead * SR), np.float32))
        t += lead
        for k, sent in enumerate(sents):
            a = tts(sent)
            audio_parts.append(a)
            cues.append((t, t + len(a) / SR, sent))
            t += len(a) / SR
            if k < len(sents) - 1:
                audio_parts.append(np.zeros(int(SENT_GAP * SR), np.float32))
                t += SENT_GAP
        pad = SLIDE_PAD.get(s["kind"], DEFAULT_PAD)
        audio_parts.append(np.zeros(int(pad * SR), np.float32))
        t += pad
        # keep audio sample-aligned with the image timeline
        dur = t - start
        concat.append(f"file '{png}'\nduration {dur:.4f}\n")
        print(f"  [{slug}] slide {i + 1}/{len(slides)} {s['kind']:<8} {dur:5.1f}s", flush=True)
    concat.append(f"file '{work / 'img' / f'{len(slides) - 1:03d}.png'}'\n")

    wav = work / "narration.wav"
    sf.write(wav, np.concatenate(audio_parts), SR)
    (work / "slides.txt").write_text("".join(concat))

    out_dir = OUT / Path(slug).parent
    out_dir.mkdir(parents=True, exist_ok=True)
    name = Path(slug).name
    vtt = out_dir / f"{name}.vtt"
    vtt.write_text("WEBVTT\n\n" + "".join(f"{k + 1}\n{ts(a)} --> {ts(b)}\n{txt}\n\n" for k, (a, b, txt) in enumerate(cues)))
    srt = work / "captions.srt"
    srt.write_text("".join(f"{k + 1}\n{ts(a, False)} --> {ts(b, False)}\n{txt}\n\n" for k, (a, b, txt) in enumerate(cues)))

    mp4 = MP4_DIR / f"{slug}.mp4"
    mp4.parent.mkdir(parents=True, exist_ok=True)
    cmd = ["ffmpeg", "-loglevel", "error", "-y",
           "-f", "concat", "-safe", "0", "-i", str(work / "slides.txt"),
           "-i", str(wav), "-i", str(srt),
           "-map", "0:v", "-map", "1:a", "-map", "2:s",
           "-vf", "fps=10,format=yuv420p", "-g", "300",
           "-c:v", "libx264", "-preset", "medium", "-tune", "stillimage", "-crf", "26",
           "-c:a", "aac", "-b:a", "48k", "-ac", "1",
           "-c:s", "mov_text", "-metadata:s:s:0", "language=eng",
           "-metadata", f"title={meta['chapter']} — System Design Playbook",
           "-movflags", "+faststart", "-shortest", str(mp4)]
    subprocess.run(cmd, check=True)
    thumb = out_dir / f"{name}.jpg"
    from PIL import Image
    Image.open(work / "img" / "000.png").convert("RGB").resize((640, 360), Image.LANCZOS).save(thumb, quality=85)
    return {"section": ch["section"], "title": meta["chapter"], "number": ch["number"], "page": ch["path"],
            "video": MP4_URL + f"{slug}.mp4", "captions": f"{slug}.vtt", "poster": f"{slug}.jpg",
            "duration": round(t, 1), "slides": len(slides)}


def write_index(entries: list[dict]):
    """docs/videos/index.md: every chapter video, grouped by section."""
    total = sum(e["duration"] for e in entries)
    out = ["# Video Course", "",
           '!!! abstract "Every chapter as a narrated video"',
           f"    {len(entries)} videos, about {round(total / 60)} minutes in all. Each walks through its chapter: the",
           "    concepts, tables, diagrams, code and the interview Q&A (question + 30-second answer), with English",
           "    captions. Each chapter page also shows its video under the title. The MP4 files are served from the",
           "    repository's `videos` branch.", ""]
    sections: dict[str, list[dict]] = {}
    for e in entries:
        sections.setdefault(e["section"] or "Start here", []).append(e)
    for sec, items in sections.items():
        mins = round(sum(e["duration"] for e in items) / 60)
        out += [f"## {sec}", "", f"{len(items)} videos · about {mins} min", "", '<div class="ssp-video-grid">', ""]
        for e in items:
            m, s_ = divmod(int(round(e["duration"])), 60)
            label = (f"{e['number']}. " if e.get("number") else "") + e["title"]
            out += ['<figure class="ssp-video">',
                    f'<video controls preload="none" playsinline poster="{e["poster"]}">',
                    f'<source src="{e["video"]}" type="video/mp4">',
                    f'<track kind="captions" srclang="en" label="English" src="{e["captions"]}" default>',
                    "</video>",
                    f'<figcaption><strong>{label}</strong> · {m}:{s_:02d} · '
                    f'<a href="{e["video"]}">open MP4</a></figcaption>',
                    "</figure>", ""]
        out += ["</div>", ""]
    out += ["!!! tip \"How these were made\"",
            "    Generated from the markdown pages by `tools/videos/make_videos.py` (slides rendered with Pillow,",
            "    narration by the open-source Kokoro text-to-speech model, assembled with ffmpeg). Edit a page,",
            "    re-run the script for that page, and its video updates.", ""]
    (OUT / "index.md").write_text("\n".join(out))


def main():
    only = set(sys.argv[1:])
    force = "--force" in only
    only.discard("--force")
    publish = os.environ.get("VIDEO_PUBLISH")  # path to a checkout of the `videos` branch
    man_path = OUT / "manifest.json"
    manifest = {m["page"]: m for m in json.loads(man_path.read_text())} if man_path.exists() else {}
    chs = [c for c in chapters() if (not only or c["path"] in only) and (force or only or c["path"] not in manifest)]
    for k, ch in enumerate(chs):
        print(f"[{k + 1}/{len(chs)}] {ch['path']}", flush=True)
        manifest[ch["path"]] = make_chapter(ch)
        if publish:
            subprocess.run([str(HERE / "publish.sh"), publish, str(MP4_DIR), ch["path"][:-3] + ".mp4"], check=True)
        order = [c["path"] for c in chapters()]
        OUT.mkdir(parents=True, exist_ok=True)
        man_path.write_text(json.dumps([manifest[p] for p in order if p in manifest], indent=2, ensure_ascii=False))
        write_index([manifest[p] for p in order if p in manifest])
        if os.environ.get("VIDEO_COMMIT"):
            commit_main(ch["path"])
    order = [c["path"] for c in chapters()]
    write_index([manifest[p] for p in order if p in manifest])
    total = sum(m["duration"] for m in manifest.values())
    print(f"done: {len(manifest)} videos, {total / 60:.1f} minutes total")


if __name__ == "__main__":
    main()
