#!/usr/bin/env bash
# Commit one MP4 to a checkout of the orphan `videos` branch and push it (retries on network errors).
set -euo pipefail
repo="$1"; src_dir="$2"; rel="$3"
mkdir -p "$repo/$(dirname "$rel")"
cp "$src_dir/$rel" "$repo/$rel"
cd "$repo"
git add "$rel"
git commit -q -m "Add video: $rel

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019tfTGN7QqwEiznF8auCEZq" || exit 0
for i in 1 2 3 4 5; do
  git -c push.negotiate=true push -q origin videos && exit 0
  sleep $((i * 10))
done
echo "push failed for $rel" >&2; exit 1
