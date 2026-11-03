#!/bin/bash
# Redis Cluster lab: hash slots, MOVED, CROSSSLOT, hash tags, live slot migration (ASK), adding a node
C="redis-cli -p 7001"; CC="redis-cli -c -p 7001"
owners() { redis-cli --cluster check 127.0.0.1:7001 2>/dev/null | grep -E '^M:|slots:' | paste - - | \
           sed -E 's/^M: [0-9a-f]+ 127.0.0.1:([0-9]+).*slots:(.*) \(([0-9]+) slots\).*/   port \1 owns \3 slots \2/' | sort; }
echo "== slot ownership (key → CRC16(key) mod 16384 → owning node)"; owners
echo "== where keys live"
for k in 'order:ord_1001' 'order:ord_1002' '{cust-42}:orders' '{cust-42}:cart'; do echo "   $k -> slot $($C cluster keyslot "$k")"; done
echo "== a client that ignores cluster topology:"
for p in 7001 7002 7003; do echo "   SET order:ord_1002 via port $p: $(redis-cli -p $p set order:ord_1002 PLACED 2>&1)"; done
echo "== multi-key command across slots vs with a hash tag:"
$CC set cust:42:name Asha >/dev/null; $CC set cust:77:name Ravi >/dev/null
echo "   MGET cust:42:name cust:77:name       -> $($CC mget cust:42:name cust:77:name 2>&1 | head -1)"
$CC set '{cust-42}:orders' 3 >/dev/null; $CC set '{cust-42}:cart' 2 >/dev/null
echo "   MGET {cust-42}:orders {cust-42}:cart -> $($CC mget '{cust-42}:orders' '{cust-42}:cart' | tr '\n' ' ')"
echo "== load 30,000 order keys"
for i in $(seq 1 30000); do echo "SET order:ord_$i PLACED"; done | redis-cli -c -p 7001 >/dev/null 2>&1
for p in 7001 7002 7003; do echo "   port $p holds $(redis-cli -p $p dbsize) keys"; done
echo "== live migration of ONE slot, step by step (what resharding does under the hood)"
S=$($C cluster keyslot order:ord_1002)                     # slot 4133, owned by 7001
SRC=$(redis-cli -p 7001 cluster myid); DST=$(redis-cli -p 7002 cluster myid)
KEYS=$(redis-cli -p 7001 cluster getkeysinslot $S 100 | tr '\n' ' '); set -- $KEYS
echo "   slot $S has $(redis-cli -p 7001 cluster countkeysinslot $S) keys on port 7001: $KEYS"
redis-cli -p 7002 cluster setslot $S importing $SRC >/dev/null
redis-cli -p 7001 cluster setslot $S migrating $DST >/dev/null
redis-cli -p 7001 migrate 127.0.0.1 7002 "" 0 5000 KEYS $1 >/dev/null              # move only the first key
echo "   GET $1 on the source (already moved)  -> $(redis-cli -p 7001 get $1 2>&1)"
echo "   GET $2 on the source (not moved yet)  -> $(redis-cli -p 7001 get $2 2>&1)"
echo "   GET $1 on the target without ASKING   -> $(redis-cli -p 7002 get $1 2>&1)"
echo "   ASKING + GET $1 on the target         -> $(printf 'ASKING\nGET %s\n' $1 | redis-cli -p 7002 | tail -1)"
shift; [ $# -gt 0 ] && redis-cli -p 7001 migrate 127.0.0.1 7002 "" 0 5000 KEYS "$@" >/dev/null
for p in 7001 7002 7003; do redis-cli -p $p cluster setslot $S node $DST >/dev/null; done
sleep 1
echo "   after SETSLOT NODE everywhere: GET $1 on port 7001 -> $(redis-cli -p 7001 get $1 2>&1)"
echo "== add a 4th node and rebalance"
redis-server /tmp/rclab/7004/redis.conf; sleep 1
redis-cli --cluster add-node 127.0.0.1:7004 127.0.0.1:7001 >/dev/null 2>&1
# wait until every node knows 7004 and agrees on the slot map (gossip takes a few seconds)
for i in $(seq 1 30); do
  known=$(for p in 7001 7002 7003; do redis-cli -p $p cluster nodes | grep -c ':7004@'; done | paste -sd+ | bc)
  [ "$known" = "3" ] && redis-cli --cluster check 127.0.0.1:7001 2>&1 | grep -q 'All nodes agree' && break; sleep 1
done; sleep 2
before=$(for p in 7001 7002 7003; do redis-cli -p $p --scan --pattern 'order:ord_*' | sed "s/$/ $p/"; done | sort)
redis-cli --cluster rebalance 127.0.0.1:7001 --cluster-use-empty-masters >/dev/null 2>&1 || \
  { redis-cli --cluster fix 127.0.0.1:7001 --cluster-yes >/dev/null 2>&1; redis-cli --cluster rebalance 127.0.0.1:7001 --cluster-use-empty-masters >/dev/null 2>&1; }
sleep 2
after=$(for p in 7001 7002 7003 7004; do redis-cli -p $p --scan --pattern 'order:ord_*' | sed "s/$/ $p/"; done | sort)
moved=$(comm -13 <(echo "$before") <(echo "$after") | wc -l)
echo "   keys that changed node: $moved of 30000 ($((moved * 100 / 30000))%)"; owners
for p in 7001 7002 7003 7004; do echo "   port $p holds $(redis-cli -p $p dbsize) keys"; done
