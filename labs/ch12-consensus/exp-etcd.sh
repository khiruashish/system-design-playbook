#!/bin/bash
# Real etcd 3.5, 3 members. Needs: setup.sh running; the fencing DB from the Ch 12 PostgreSQL lab on :5531 (optional part 2).
E=${ETCD_BIN:-/opt/etcd}; ALL=127.0.0.1:2379,127.0.0.1:22379,127.0.0.1:32379
C() { $E/etcdctl --endpoints=${EP:-$ALL} --command-timeout=${TO:-5s} "$@"; }
js() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }
port() { case $1 in e1) echo 2379;; e2) echo 22379;; e3) echo 32379;; esac; }
status() {  # prints: name leader? term for each live member
  for n in e1 e2 e3; do
    s=$(EP=127.0.0.1:$(port $n) TO=1s C endpoint status -w json 2>/dev/null) || { echo "   $n: down/unreachable"; continue; }
    echo "$s" | js "'   $n: raft term %d, %s' % (d[0]['Status']['raftTerm'], 'LEADER' if d[0]['Status']['leader']==d[0]['Status']['header']['member_id'] else 'follower')"
  done; }
leader_name() { for n in e1 e2 e3; do s=$(EP=127.0.0.1:$(port $n) TO=1s C endpoint status -w json 2>/dev/null) || continue
  [ "$(echo "$s" | js "d[0]['Status']['leader']==d[0]['Status']['header']['member_id']")" = True ] && echo $n; done; }
C del --prefix /elections/ >/dev/null; C del --prefix /shardmap/ >/dev/null

echo "== 1. cluster status"; status

echo "== 2. leader election with a lease; the leader key's create_revision is the fencing token"
campaign() {  # $1 pod $2 lease → prints token or 'lost'
  r=$(printf 'create("/elections/settlement") = "0"\n\nput /elections/settlement %s --lease=%s\n\n\n' $1 $2 | C txn -w json | js "d.get('succeeded', False)")
  if [ "$r" = True ]; then C get /elections/settlement -w json | js "d['kvs'][0]['create_revision']"; else echo lost; fi; }
LA=$(C lease grant 3 | awk '{print $2}')
$E/etcdctl --endpoints=$ALL lease keep-alive $LA >/dev/null 2>&1 & KA=$!
TA=$(campaign pod-A $LA); echo "   pod-A campaigns (lease TTL 3 s, keep-alive running) → token $TA"
LB=$(C lease grant 3 | awk '{print $2}')
C lease keep-alive $LB >/dev/null 2>&1 & KB=$!
echo "   pod-B campaigns → $(campaign pod-B $LB)   (and keeps its own lease alive, retrying)"
kill -STOP $KA; t0=$(date +%s%N)
echo "   pod-A freezes (SIGSTOP on its keep-alive process)"
while [ -n "$(C get /elections/settlement --print-value-only)" ]; do sleep 0.1; done
echo "   leader key gone after $(( ($(date +%s%N) - t0) / 1000000 )) ms (lease expired on the etcd leader's clock)"
TB=$(campaign pod-B $LB); echo "   pod-B campaigns again → token $TB"
kill -CONT $KA; sleep 1
echo "   pod-A wakes; its keep-alive now reports: $(C lease timetolive $LA)"
if psql -h localhost -p 5531 -U postgres -qAtX -c 'select 1' >/dev/null 2>&1; then
  S="psql -h localhost -p 5531 -U postgres -qAtX"
  $S -c "drop table if exists settlement; create table settlement (store_id text primary key, payout_paise bigint, written_by text, fence bigint not null default 0); insert into settlement(store_id) values ('store-7')"
  w() { $S -c "with u as (update settlement set payout_paise=$2, written_by='$1', fence=$3 where store_id='store-7' and fence <= $3 returning 1) select count(*) from u"; }
  echo "   PostgreSQL: pod-B writes with token $TB → $(w pod-B 3998000 $TB) row; pod-A writes with token $TA → $(w pod-A 4125000 $TA) rows"
  echo "   final: $($S -c "select written_by || ' fence ' || fence from settlement")"
fi
kill $KA $KB 2>/dev/null; wait $KA $KB 2>/dev/null

echo "== 3. kill the Raft leader, measure failover, keep writing"
L=$(leader_name); C put /probe before >/dev/null
T0=$(EP=127.0.0.1:$(port $L) C endpoint status -w json | js "d[0]['Status']['raftTerm']")
pkill -KILL -f -- "^$E/etcd --name $L " ; t0=$(date +%s%N)
SURV3=$(for n in e1 e2 e3; do [ "$n" != "$L" ] && echo 127.0.0.1:$(port $n); done | paste -sd,)
until EP=$SURV3 TO=1s C endpoint status -w json 2>/dev/null | js "any(x['Status']['leader'] != 0 and x['Status']['raftTerm'] > $T0 for x in d)" | grep -q True; do sleep 0.02; done
echo "   killed $L; new leader $(leader_name) after $(( ($(date +%s%N) - t0) / 1000000 )) ms"; status
echo "   put /probe after-failover → $(C put /probe after-failover)"

CLUSTER="e1=http://127.0.0.1:2380,e2=http://127.0.0.1:22380,e3=http://127.0.0.1:32380"
healthy() { for i in $(seq 1 150); do [ $(EP=$ALL TO=1s C endpoint health 2>&1 | grep -c "is healthy") = 3 ] && return; sleep 0.2; done; echo "   (cluster not healthy yet)"; }
restart() { p=$(port $1); nohup $E/etcd --name $1 --data-dir /tmp/etcdlab/$1 \
  --listen-client-urls http://127.0.0.1:$p --advertise-client-urls http://127.0.0.1:$p \
  --listen-peer-urls http://127.0.0.1:$((p+1)) --initial-advertise-peer-urls http://127.0.0.1:$((p+1)) \
  --initial-cluster $CLUSTER --initial-cluster-token lab --heartbeat-interval 100 --election-timeout 1000 >> /tmp/etcdlab/$1.log 2>&1 & disown; }
restart $L; healthy; echo "   restarted $L:"; status

echo "== 4. lose the majority: what a minority member can still answer"
LD=$(leader_name); F=$(for n in e1 e2 e3; do [ "$n" != "$LD" ] && echo $n; done | head -1)
O=$(for n in e1 e2 e3; do [ "$n" != "$LD" ] && [ "$n" != "$F" ] && echo $n; done)
FP=$(( $(port $F) + 1 )); FPID=$(pgrep -f -- "^$E/etcd --name $F ")
# cut every peer connection of $F: its listening peer port, plus the local ports of the streams it dialed to the others
DROP_PORTS="$FP $(python3 - $FPID <<'PY'
import os, sys, re
pid = sys.argv[1]
inodes = {m.group(1) for f in os.listdir(f"/proc/{pid}/fd")
          for m in [re.match(r"socket:\[(\d+)\]", os.readlink(f"/proc/{pid}/fd/{f}") if os.path.exists(f"/proc/{pid}/fd/{f}") else "")] if m}
ports = set()
for line in open("/proc/net/tcp").readlines()[1:]:
    parts = line.split()
    remote = int(parts[2].split(":")[1], 16)
    if parts[9] in inodes and parts[3] == "01" and remote in (2380, 22380, 32380):   # streams it dialed to peers
        ports.add(int(parts[1].split(":")[1], 16))
print(" ".join(map(str, sorted(ports))))
PY
)"
for p in $DROP_PORTS; do iptables -A INPUT -i lo -p tcp --dport $p -j DROP; done
echo "   partition $F (iptables drops all its peer traffic); put /probe v2 via the others → $(EP=127.0.0.1:$(port $LD) C put /probe v2)   (acknowledged by 2 of 3)"
pkill -KILL -f -- "^$E/etcd --name $LD "; pkill -KILL -f -- "^$E/etcd --name $O "; sleep 2
EP=127.0.0.1:$(port $F)
echo "   crash $LD and $O; $F is alone and never received v2:"
echo "     write                 → $(EP=$EP TO=2s C put /probe v3 2>&1 | tail -1)"
echo "     linearizable get      → $(EP=$EP TO=2s C get /probe --print-value-only 2>&1 | tail -1)"
echo "     serializable get (-s) → $(EP=$EP TO=2s C get /probe --print-value-only --consistency=s 2>&1 | tail -1)   ← STALE: v2 was acknowledged earlier"

echo "== 5. restart both; the cluster heals"
for p in $DROP_PORTS; do iptables -D INPUT -i lo -p tcp --dport $p -j DROP; done
restart $LD; restart $O; healthy; status
echo "   serializable get on $F → $(EP=$EP C get /probe --print-value-only --consistency=s)"

unset EP
echo "== 6. shard map: compare-and-swap on mod_revision"
C put /shardmap/orders/40 shard-a >/dev/null
R=$(C get /shardmap/orders/40 -w json | js "d['kvs'][0]['mod_revision']")
flip() { printf 'mod("/shardmap/orders/40") = "%s"\n\nput /shardmap/orders/40 %s\n\n\n' $1 $2 | C txn -w json | js "'SUCCEEDED' if d.get('succeeded') else 'FAILED (someone changed it)'"; }
echo "   mover-1 flips bucket 40 a→e expecting mod_revision $R → $(flip $R shard-e)"
echo "   mover-2 (read the same revision earlier) flips a→f   → $(flip $R shard-f)"
echo "   bucket 40 = $(C get /shardmap/orders/40 --print-value-only)"

echo "== 7. gap-free watch from a revision, and what compaction does to it"
S0=$(C get /shardmap/orders/40 -w json | js "d['header']['revision']")
for b in 41 42 43; do C put /shardmap/orders/$b shard-e >/dev/null; done
echo "   a pod that loaded the map at revision $S0 watches from $((S0+1)) and still gets every change:"
timeout 2 $E/etcdctl --endpoints=$ALL watch --prefix /shardmap/ --rev=$((S0+1)) 2>&1 | paste - - - | sed 's/^/     /'
NOW=$(C get /shardmap/orders/43 -w json | js "d['header']['revision']"); C compact $NOW >/dev/null
echo "   after 'compact $NOW', the same watch from $((S0+1)):"
timeout 2 $E/etcdctl --endpoints=$ALL watch --prefix /shardmap/ --rev=$((S0+1)) 2>&1 | head -1 | sed 's/^/     /'
echo "   → the client must re-read the whole map, then watch from the new revision"
