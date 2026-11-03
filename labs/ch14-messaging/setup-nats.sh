#!/bin/bash
# 3-node NATS JetStream cluster: clients 4222/4223/4224, cluster routes 6222/6223/6224
N=${NATS_BIN:-/opt/nats}; cd /tmp/natslab
pkill -x nats-server 2>/dev/null; while pgrep -x nats-server >/dev/null; do sleep 0.2; done
rm -rf n1 n2 n3 *.log *.conf
for i in 1 2 3; do
cat > n$i.conf <<CONF
server_name: n$i
port: $((4221 + i))
jetstream { store_dir: /tmp/natslab/n$i }
cluster {
  name: grocery
  port: $((6221 + i))
  routes: [ nats://127.0.0.1:6222, nats://127.0.0.1:6223, nats://127.0.0.1:6224 ]
}
CONF
setsid nohup $N/nats-server -c n$i.conf > n$i.log 2>&1 &
done
sleep 4
$N/nats --server nats://127.0.0.1:4222 server check jetstream 2>&1 | head -2
