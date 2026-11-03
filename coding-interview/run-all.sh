#!/usr/bin/env bash
# Runs every coding-interview solution with the JDK single-file launcher (JDK 21+).
#   ./run-all.sh          run all, write outputs/<Name>.txt (the output shown in the docs)
#   ./run-all.sh --check  run all, fail if any solution fails or its output differs from outputs/
set -uo pipefail
cd "$(dirname "$0")"
mode="${1:-write}"; fail=0; count=0
mkdir -p outputs
for f in src/*.java; do
  name=$(basename "$f" .java); count=$((count + 1))
  if ! out=$(java "$f" 2>/tmp/ci-err-$$); then
    echo "FAIL  $name"; cat /tmp/ci-err-$$; echo "$out"; fail=1; continue
  fi
  if [ "$(printf '%s\n' "$out" | tail -n 1)" != "PASS" ]; then echo "FAIL  $name (last line is not PASS)"; fail=1; continue; fi
  if [ "$mode" = "--check" ]; then
    if ! diff -u "outputs/$name.txt" <(printf '%s\n' "$out") >/dev/null; then echo "DIFF  $name (run ./run-all.sh and commit outputs/)"; fail=1; continue; fi
  else
    printf '%s\n' "$out" > "outputs/$name.txt"
  fi
  echo "PASS  $name"
done
rm -f /tmp/ci-err-$$
echo "$count solutions, $([ $fail = 0 ] && echo 'all passed' || echo 'FAILURES')"
exit $fail
