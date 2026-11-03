#!/usr/bin/env bash
# Fresh 3-master Redis Cluster on 7001-7003 plus an empty node on 7004 (no replicas; lab only).
set -e
cd "$(dirname "$0")"
for p in 7001 7002 7003 7004; do redis-cli -p $p shutdown nosave >/dev/null 2>&1 || true; done
sleep 1
rm -rf cluster && mkdir -p cluster
for p in 7001 7002 7003 7004; do
  mkdir -p cluster/$p
  (cd cluster/$p && redis-server --port $p --cluster-enabled yes --cluster-config-file nodes.conf --save '' --appendonly no \
     --daemonize yes --cluster-node-timeout 2000 >/dev/null)
done
sleep 1
redis-cli --cluster create 127.0.0.1:7001 127.0.0.1:7002 127.0.0.1:7003 --cluster-yes >/dev/null
sleep 2
