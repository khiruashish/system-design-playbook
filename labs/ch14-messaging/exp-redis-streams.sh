#!/bin/bash
# Redis 7 Streams: consumer groups, pending entries, crash takeover, dead-lettering, fan-out, retention.
# Starts its own redis-server on :6390.
redis-cli -p 6390 shutdown nosave >/dev/null 2>&1; sleep 0.5
redis-server --port 6390 --save "" --appendonly no --daemonize yes >/dev/null; sleep 0.5
R="redis-cli -p 6390"

echo "== 1. append 10 order events (the log); ids are <ms>-<seq>"
for i in $(seq 1 10); do $R xadd orders '*' order ord-$i sku ATTA-5KG >/dev/null; done
echo "   length $($R xlen orders), first id $($R xrange orders - + count 1 | head -1)"

echo "== 2. a consumer group with two workers: each entry goes to ONE worker"
$R xgroup create orders inventory 0 >/dev/null
w1=$($R xreadgroup group inventory w1 count 4 streams orders '>' | grep -c '^ord-')
w2=$($R xreadgroup group inventory w2 count 4 streams orders '>' | grep -c '^ord-')
echo "   w1 got $w1, w2 got $w2; nobody has acked yet → pending (PEL): $($R xpending orders inventory | head -1)"
for id in $($R xpending orders inventory - + 10 w2 | awk 'NR%4==1'); do $R xack orders inventory $id >/dev/null; done
echo "   w2 processes and ACKs its 4; w1 crashes holding 4; pending now: $($R xpending orders inventory | head -1)"

echo "== 3. crash takeover: w2 claims entries idle > 1 s (XAUTOCLAIM)"
sleep 1.2
claimed=$($R xautoclaim orders inventory w2 1000 0-0 count 10 | grep -c '^ord-')
echo "   w2 claimed $claimed; delivery counts now: $($R xpending orders inventory - + 10 | awk 'NR%4==0' | tr '\n' ' ')"

echo "== 4. poison message: at 3 deliveries, move it to a dead-letter stream"
POISON=$($R xpending orders inventory - + 1 | head -1)
for attempt in 3; do sleep 1.1; $R xclaim orders inventory w2 1000 $POISON >/dev/null; done
d=$($R xpending orders inventory $POISON $POISON 1 | sed -n 4p)
if [ "$d" -ge 3 ]; then
  fields=$($R xrange orders $POISON $POISON | tail -4 | tr '\n' ' ')
  $R xadd orders.dlq '*' original_id $POISON deliveries $d $fields >/dev/null; $R xack orders inventory $POISON >/dev/null
fi
echo "   $POISON delivered $d times → orders.dlq has $($R xlen orders.dlq) entry: $($R xrange orders.dlq - + | tail -8 | paste -sd' ')"

echo "== 5. fan-out and replay: a second group starts from the beginning and gets everything"
$R xgroup create orders analytics 0 >/dev/null
echo "   analytics read $($R xreadgroup group analytics a1 count 100 streams orders '>' | grep -c '^ord-') entries (inventory's progress is independent)"

echo "== 6. retention: cap the stream at 1,000 entries while a group lags behind"
$R xgroup create orders search-indexer 0 >/dev/null
for i in $(seq 11 3000); do echo "XADD orders MAXLEN = 1000 * order ord-$i sku ATTA-5KG"; done | $R >/dev/null
echo "   length $($R xlen orders) (oldest trimmed); first id now $($R xrange orders - + count 1 | head -1)"
$R --json xinfo groups orders | python3 -c "
import sys, json
for g in json.load(sys.stdin):
    print('   group %-15s entries-read %-5s lag %s' % (g['name'], g['entries-read'], g['lag']))"
FIRST=$($R xrange orders - + count 1 | sed -n 3p)
got=$($R xreadgroup group search-indexer s1 count 5000 streams orders '>' | grep -c '^ord-')
echo "   search-indexer (created at id 0 before the burst) now reads $got entries, starting at $FIRST: ord-1…ord-2000 were trimmed before it read them"
echo "   analytics and inventory report lag 2990+ but only 1,000 entries exist: ~2,000 events will never reach them"
redis-cli -p 6390 shutdown nosave >/dev/null 2>&1
