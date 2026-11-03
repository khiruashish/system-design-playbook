#!/bin/bash
# Redis 7 Cluster lab: 3 masters on 7001–7003 (no replicas, to keep it small) + a spare node config on 7004
set -e
cd /tmp && mkdir -p rclab && cd rclab
for p in 7001 7002 7003 7004; do redis-cli -p $p shutdown nosave >/dev/null 2>&1 || true; done
sleep 1; rm -rf 700?
for p in 7001 7002 7003 7004; do
  mkdir -p $p
  cat > $p/redis.conf <<CONF
port $p
cluster-enabled yes
cluster-config-file nodes.conf
cluster-node-timeout 5000
appendonly no
save ""
daemonize yes
dir /tmp/rclab/$p
logfile /tmp/rclab/$p/redis.log
CONF
done
for p in 7001 7002 7003; do redis-server $p/redis.conf; done      # 7004 is started by exp.sh
sleep 1
redis-cli --cluster create 127.0.0.1:7001 127.0.0.1:7002 127.0.0.1:7003 --cluster-replicas 0 --cluster-yes >/dev/null
sleep 2; redis-cli -p 7001 cluster info | grep -E 'cluster_state|cluster_slots_assigned'
