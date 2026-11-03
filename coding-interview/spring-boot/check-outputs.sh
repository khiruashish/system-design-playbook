#!/usr/bin/env bash
# Runs the Spring Boot 4 integration tests against a real Redis and compares their transcripts with outputs/.
#   ./check-outputs.sh          run tests, write outputs/<Name>.txt (the transcripts shown in the docs)
#   ./check-outputs.sh --check  run tests, fail if any test fails or a transcript differs from outputs/ (CI)
# Needs Redis on localhost:6379 (or REDIS_HOST / REDIS_PORT), e.g. `docker run -d -p 6379:6379 redis:7`.
set -euo pipefail
cd "$(dirname "$0")"
mode="${1:-write}"
rm -rf target/transcripts
mvn -B -q test
mkdir -p outputs
fail=0
for f in target/transcripts/*.txt; do
  name=$(basename "$f")
  if [ "$(tail -n 1 "$f")" != "PASS" ]; then echo "FAIL  $name (last line is not PASS)"; fail=1; continue; fi
  if [ "$mode" = "--check" ]; then
    if ! diff -u "outputs/$name" "$f"; then echo "DIFF  $name (run ./check-outputs.sh and commit outputs/)"; fail=1; continue; fi
  else
    cp "$f" "outputs/$name"
  fi
  echo "PASS  ${name%.txt}"
done
count=$(ls target/transcripts/*.txt | wc -l)
[ "$count" -eq 12 ] || { echo "expected 12 transcripts, got $count"; fail=1; }
echo "$count Spring Boot solutions, $([ $fail = 0 ] && echo 'all passed' || echo 'FAILURES')"
exit $fail
