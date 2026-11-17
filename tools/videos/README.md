# Chapter videos

`make_videos.py` turns every chapter in `mkdocs.yml`'s nav into a narrated 1080p MP4:

- **Slides** (Pillow): title, section dividers, paragraphs and lists, tables, call-outs, Mermaid diagrams
  (rendered with headless Chromium), code (first screenful; `package`/`import` lines omitted), interview
  questions with their 30-second answers, and a closing slide.
- **Narration** (Kokoro TTS, offline): all prose, list items, table rows, call-outs and diagram steps; each
  interview Q&A as question + 30-second answer; code is introduced briefly, not read line by line.
- **Captions**: WebVTT next to the poster in `docs/videos/`, and embedded in the MP4.

The generated Q&A index, drill bank and lab catalogue are skipped (their content is in the chapter videos).

MP4s are published to the orphan `videos` branch (keeps `main` and the Pages site small) and streamed from
`raw.githubusercontent.com`; `docs/videos/manifest.json`, captions and posters stay on the docs branch.
`hooks/videos.py` embeds each chapter's video under its title; `docs/videos/index.md` lists them all.

```bash
pip install -r tools/videos/requirements.txt playwright      # plus ffmpeg and Node/npm (for mermaid.min.js)
git clone --single-branch --branch videos https://github.com/khiruashish/system-design-playbook /tmp/sdp-work/videos-repo
VIDEO_PUBLISH=/tmp/sdp-work/videos-repo VIDEO_COMMIT=1 python tools/videos/make_videos.py   # resumes: skips chapters in the manifest
python tools/videos/make_videos.py 01-foundations/07-caching.md                             # (re)make one chapter
```

Narration audio and rendered diagrams are cached in `$VIDEO_WORK` (default `/tmp/sdp-work`).

## Running on GitHub Actions

`.github/workflows/videos.yml` does the same thing in parallel: `plan_shards.py` splits the chapters still missing
from `manifest.json` into balanced shards (20 by default), each shard runs `make_videos.py` on its chapters, and
a final job (`merge_results.py`) pushes the MP4s to `videos` and commits captions, posters, the manifest and the
Videos page to the branch the workflow ran on. It runs on pushes that touch `tools/videos/**` or the workflow,
or manually (Actions → Chapter videos → Run workflow). Re-running only builds chapters that are still missing.
