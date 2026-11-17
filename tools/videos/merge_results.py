"""Merge per-shard results (downloaded artifacts) into docs/videos and a checkout of the `videos` branch.

    python tools/videos/merge_results.py <artifacts dir> <videos-branch checkout>
Each artifact holds docs-videos/ (captions, posters, manifest.json) and mp4/ (the MP4s).
"""
import json
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import make_videos as M  # noqa: E402

arts, videos_repo = Path(sys.argv[1]), Path(sys.argv[2])
man = M.OUT / "manifest.json"
entries = {m["page"]: m for m in json.loads(man.read_text())} if man.exists() else {}
added = 0
for art in sorted(p for p in arts.iterdir() if p.is_dir()):
    dv, mp4 = art / "docs-videos", art / "mp4"
    if (dv / "manifest.json").exists():
        for m in json.loads((dv / "manifest.json").read_text()):
            if not (mp4 / m["video"].rsplit("/videos/", 1)[-1]).exists():
                continue  # only chapters whose MP4 was produced in this run
            stem = m["video"].rsplit("/videos/", 1)[-1][:-4]
            for ext in (".vtt", ".jpg"):
                src = dv / (stem + ext)
                (M.OUT / src.relative_to(dv)).parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, M.OUT / src.relative_to(dv))
            dst = videos_repo / (stem + ".mp4")
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(mp4 / (stem + ".mp4"), dst)
            entries[m["page"]] = m
            added += 1
order = [c["path"] for c in M.chapters()]
final = [entries[p] for p in order if p in entries]
man.write_text(json.dumps(final, indent=2, ensure_ascii=False))
M.write_index(final)
print(f"merged {added} new videos; {len(final)} in manifest")
