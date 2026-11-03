#!/bin/bash
# 3-member etcd 3.5 cluster on localhost: e1 :2379/:2380, e2 :22379/:22380, e3 :32379/:32380
E=${ETCD_BIN:-/opt/etcd}; cd /tmp/etcdlab
pkill -x etcd 2>/dev/null; while pgrep -x etcd >/dev/null; do sleep 0.2; done; rm -rf e1 e2 e3 *.log
CLUSTER="e1=http://127.0.0.1:2380,e2=http://127.0.0.1:22380,e3=http://127.0.0.1:32380"
start() {  # name client peer
  nohup $E/etcd --name $1 --data-dir /tmp/etcdlab/$1 \
    --listen-client-urls http://127.0.0.1:$2 --advertise-client-urls http://127.0.0.1:$2 \
    --listen-peer-urls http://127.0.0.1:$3 --initial-advertise-peer-urls http://127.0.0.1:$3 \
    --initial-cluster $CLUSTER --initial-cluster-state ${4:-new} --initial-cluster-token lab \
    --heartbeat-interval 100 --election-timeout 1000 > /tmp/etcdlab/$1.log 2>&1 &
}
start e1 2379 2380; start e2 22379 22380; start e3 32379 32380
sleep 3
$E/etcdctl --endpoints=127.0.0.1:2379,127.0.0.1:22379,127.0.0.1:32379 endpoint health 2>&1 | sort
