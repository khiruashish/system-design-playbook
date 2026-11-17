"""Print a GitHub Actions matrix that splits the chapters without a video into balanced shards.

    python tools/videos/plan_shards.py 20   ->   {"include": [{"shard": 1, "pages": "a.md b.md"}, ...]}
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import make_videos as M  # noqa: E402

n = int(sys.argv[1]) if len(sys.argv) > 1 else 20
man = M.OUT / "manifest.json"
done = {m["page"] for m in json.loads(man.read_text())} if man.exists() else set()
todo = [c["path"] for c in M.chapters() if c["path"] not in done]
# longest first, each into the currently lightest shard (file size ~ narration length)
todo.sort(key=lambda p: (M.DOCS / p).stat().st_size, reverse=True)
shards = [[0, []] for _ in range(min(n, len(todo)) or 1)]
for p in todo:
    s = min(shards, key=lambda x: x[0])
    s[0] += (M.DOCS / p).stat().st_size
    s[1].append(p)
print(json.dumps({"include": [{"shard": i + 1, "pages": " ".join(s[1])} for i, s in enumerate(shards) if s[1]]}))
