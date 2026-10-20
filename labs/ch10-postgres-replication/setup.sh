#!/bin/bash
# Real PostgreSQL 16 lab: 1 primary (5501) + 2 streaming replicas (5502 r1, 5503 r2)
set -e
B=/usr/lib/postgresql/16/bin
cd /tmp/pglab
for d in p r1 r2; do [ -d $d ] && $B/pg_ctl -D $d stop -m immediate >/dev/null 2>&1 || true; done
rm -rf p r1 r2 *.log
$B/initdb -D p -U postgres -A trust >/dev/null
cat >> p/postgresql.conf <<CONF
port = 5501
listen_addresses = 'localhost'
unix_socket_directories = '/tmp/pglab'
wal_level = replica
max_wal_senders = 10
hot_standby = on
wal_log_hints = on
wal_keep_size = 512MB
CONF
echo "host replication all 127.0.0.1/32 trust" >> p/pg_hba.conf
$B/pg_ctl -D p -l p.log start >/dev/null; sleep 1
for r in r1:5502 r2:5503; do
  n=${r%%:*}; port=${r##*:}
  $B/pg_basebackup -h localhost -p 5501 -U postgres -D $n -R -X stream
  sed -i "s/^port = 5501/port = $port/" $n/postgresql.conf
  sed -i "s/primary_conninfo = '/primary_conninfo = 'application_name=$n /" $n/postgresql.auto.conf
  $B/pg_ctl -D $n -l $n.log start >/dev/null
done
sleep 2
psql -h localhost -p 5501 -U postgres -Atc "select application_name, state, sync_state from pg_stat_replication order by 1"
