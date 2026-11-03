#!/bin/bash
# PostgreSQL 16 on :5543 (database "orders") for the Ch 21 OLTP-vs-OLAP lab.
set -e
B=/usr/lib/postgresql/16/bin; D=/tmp/datalab/pg/db
[ -d $D ] && $B/pg_ctl -D $D stop -m immediate >/dev/null 2>&1 || true
rm -rf $D; $B/initdb -D $D -U postgres -A trust >/dev/null
cat >> $D/postgresql.conf <<CONF
port = 5543
listen_addresses = 'localhost'
unix_socket_directories = '/tmp/datalab/pg'
shared_buffers = 1GB
work_mem = 64MB
max_parallel_workers_per_gather = 2
CONF
$B/pg_ctl -D $D -l /tmp/datalab/pg/db.log start >/dev/null; sleep 1
psql -h localhost -p 5543 -U postgres -qc "create database orders"
echo "postgres up: 5543 (orders)"
