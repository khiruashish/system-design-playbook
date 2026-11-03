#!/bin/bash
# Real NATS JetStream (Raft-replicated streams), 3 nodes. Run setup.sh first.
NB=${NATS_BIN:-/opt/nats}; S=nats://127.0.0.1:4222,nats://127.0.0.1:4223,nats://127.0.0.1:4224
N() { $NB/nats -s $S "$@"; }
info() { N stream info $1 -j 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); c=d.get('cluster',{}); print('leader=%s replicas=%s messages=%d' % (c.get('leader'), [r['name']+('' if r.get('current') else '(lagging)') for r in c.get('replicas',[])], d['state']['messages']))"; }
port() { echo $((4221 + ${1#n})); }
N stream rm ORDERS -f >/dev/null 2>&1; N stream rm CARTS -f >/dev/null 2>&1

echo "== 1. a stream replicated 3 ways (Raft group per stream)"
N stream add ORDERS --subjects "orders.>" --replicas 3 --storage file --retention limits --discard old --max-msgs=-1 --max-bytes=-1 \
  --max-age=7d --max-msg-size=-1 --dupe-window 2m --no-allow-rollup --no-deny-delete --no-deny-purge --defaults >/dev/null
echo "   ORDERS: $(info ORDERS)"

echo "== 2. idempotent publishing: the same Nats-Msg-Id twice (a producer retry after a lost ack)"
for i in 1 2; do N pub orders.placed '{"order":"ord-1001","amount":32000}' -H "Nats-Msg-Id:ord-1001:placed" --jetstream 2>&1 | grep Stored | sed 's/^[0-9:]* /   /'; done

echo "== 3. at-least-once: a consumer that never acks gets the message again, until max-deliver"
N consumer add ORDERS payments-audit --pull --deliver all --ack explicit --wait 2s --max-deliver 3 --replay instant --max-pending 100 --defaults >/dev/null
( timeout 12 $NB/nats -s $S sub '$JS.EVENT.ADVISORY.CONSUMER.MAX_DELIVERIES.ORDERS.payments-audit' > /tmp/natslab/adv.out 2>&1 & )
for i in 1 2 3 4; do
  r=$(N consumer next ORDERS payments-audit --no-ack --timeout 1s 2>&1 | head -1 | sed -E 's/^\[[0-9:]+\] //')
  echo "   fetch $i (no ack): ${r:-nothing}"; sleep 2.5
done
sleep 1; echo "   advisory for the dead-letter handler: $(grep -o '"type":"[^"]*".*"deliveries":[0-9]*' /tmp/natslab/adv.out | sed 's/"timestamp":"[^"]*",//; s/"id":"[^"]*",//')"

echo "== 4. competing consumers (one durable, two workers) vs fan-out (a second durable)"
N pub orders.placed 'order {{Count}}' --count 100 --jetstream >/dev/null 2>&1
N consumer add ORDERS picking --pull --deliver new --ack explicit --wait 30s --max-deliver 5 --replay instant --max-pending 1000 --defaults >/dev/null
N consumer add ORDERS notifications --pull --deliver new --ack explicit --wait 30s --max-deliver 5 --replay instant --max-pending 1000 --defaults >/dev/null
N pub orders.placed 'order {{Count}}' --count 100 --jetstream >/dev/null 2>&1
( N consumer next ORDERS picking --count 100 --ack --timeout 3s 2>/dev/null | grep -o 'str seq: [0-9]*' > /tmp/natslab/w1.out ) &
( N consumer next ORDERS picking --count 100 --ack --timeout 3s 2>/dev/null | grep -o 'str seq: [0-9]*' > /tmp/natslab/w2.out ) &
wait
N consumer next ORDERS notifications --count 100 --ack --timeout 3s 2>/dev/null | grep -o 'str seq: [0-9]*' > /tmp/natslab/n.out
echo "   picking worker-1 got $(wc -l < /tmp/natslab/w1.out), worker-2 got $(wc -l < /tmp/natslab/w2.out), overlap $(sort /tmp/natslab/w1.out /tmp/natslab/w2.out | uniq -d | wc -l)"
echo "   notifications (its own durable) got $(wc -l < /tmp/natslab/n.out) of the same 100"

echo "== 5. kill the stream leader; the Raft group elects another and nothing acknowledged is lost"
L=$(N stream info ORDERS -j | python3 -c "import sys,json; print(json.load(sys.stdin)['cluster']['leader'])")
before=$(N stream info ORDERS -j | python3 -c "import sys,json; print(json.load(sys.stdin)['state']['messages'])")
pkill -KILL -f "nats-server -c $L.conf"; t0=$(date +%s%N)
ALIVE=$(for n in n1 n2 n3; do [ $n != $L ] && echo -n "nats://127.0.0.1:$(port $n),"; done)
until $NB/nats -s ${ALIVE%,} stream info ORDERS -j 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); sys.exit(0 if d['cluster'].get('leader') and d['cluster']['leader'] != '$L' else 1)" 2>/dev/null; do sleep 0.1; done
echo "   killed $L (had $before messages); new leader after $(( ($(date +%s%N) - t0) / 1000000 )) ms: $(info ORDERS)"
N pub orders.placed 'during outage {{Count}}' --count 500 --jetstream >/dev/null 2>&1
echo "   published 500 more with $L down: $(info ORDERS)"
(cd /tmp/natslab && setsid nohup $NB/nats-server -c $L.conf >> $L.log 2>&1 &); sleep 5
echo "   $L restarted and caught up: $(info ORDERS)"

echo "== 6. the same failure with a single replica (R1)"
N stream add CARTS --subjects "carts.>" --replicas 1 --storage file --retention limits --discard old --max-msgs=-1 --max-bytes=-1 \
  --max-age=1d --max-msg-size=-1 --dupe-window 2m --no-allow-rollup --no-deny-delete --no-deny-purge --defaults >/dev/null
N pub carts.updated 'cart {{Count}}' --count 50 --jetstream >/dev/null 2>&1
C=$(N stream info CARTS -j | python3 -c "import sys,json; print(json.load(sys.stdin)['cluster']['leader'])")
echo "   CARTS: $(info CARTS)"
pkill -KILL -f "nats-server -c $C.conf"; sleep 3
echo "   killed $C: publish → $(N pub carts.updated 'x' --jetstream --timeout 2s 2>&1 | tail -1 | sed -E 's/^[0-9:]+ //')"
(cd /tmp/natslab && setsid nohup $NB/nats-server -c $C.conf >> $C.log 2>&1 &); sleep 5
echo "   $C restarted: $(info CARTS)   (unavailable while down; lost for good if its disk had died)"

echo "== 7. compaction by key: keep only the latest status per order (max-msgs-per-subject=1)"
N stream rm STATUS -f >/dev/null 2>&1
N stream add STATUS --subjects "status.>" --replicas 3 --storage file --retention limits --discard old --max-msgs=-1 --max-bytes=-1 \
  --max-age=-1 --max-msg-size=-1 --max-msgs-per-subject=1 --dupe-window 2m --no-allow-rollup --no-deny-delete --no-deny-purge --defaults >/dev/null
for s in PLACED PACKED DELIVERED; do N pub status.ord-1 $s --jetstream >/dev/null 2>&1; done
N pub status.ord-2 PLACED --jetstream >/dev/null 2>&1
echo "   published 4 (3 for ord-1, 1 for ord-2); stream keeps: $(N stream info STATUS -j | python3 -c "import sys,json; s=json.load(sys.stdin)['state']; print('%d messages, sequences %d..%d' % (s['messages'], s['first_seq'], s['last_seq']))")"
echo "   latest for ord-1: $(N stream get STATUS --last-for status.ord-1 -j | python3 -c "import sys,json,base64; print(base64.b64decode(json.load(sys.stdin)['data']).decode())")"

echo "== 8. backpressure: max-ack-pending=5 caps unacknowledged deliveries to a slow consumer"
N consumer add ORDERS slow-exporter --pull --deliver all --ack explicit --wait 30s --max-deliver 5 --replay instant --max-pending 5 --defaults >/dev/null
echo "   asked for 20 without acking, received $(N consumer next ORDERS slow-exporter --count 20 --no-ack --timeout 2s 2>/dev/null | grep -c 'str seq'); the rest waits in the stream, not in the consumer's memory"
