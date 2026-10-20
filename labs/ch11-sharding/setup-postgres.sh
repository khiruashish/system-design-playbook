#!/bin/bash
# Three PostgreSQL 16 instances: 5511 = single-node partitioning demo, 5521 = shard A, 5522 = shard B
set -e
B=/usr/lib/postgresql/16/bin; cd /tmp/shardlab
for d in single shardA shardB; do [ -d $d ] && $B/pg_ctl -D $d stop -m immediate >/dev/null 2>&1 || true; done
rm -rf single shardA shardB *.log
for x in single:5511 shardA:5521 shardB:5522; do
  d=${x%%:*}; p=${x##*:}
  $B/initdb -D $d -U postgres -A trust >/dev/null
  printf "port = $p\nlisten_addresses = 'localhost'\nunix_socket_directories = '/tmp/shardlab'\n" >> $d/postgresql.conf
  $B/pg_ctl -D $d -l $d.log start >/dev/null
done
sleep 1; echo "up: 5511 5521 5522"
