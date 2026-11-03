#!/bin/bash
# PostgreSQL 16 on :5542 for the Ch 20 labs (database "shop"), and Redis on :6390.
set -e
B=/usr/lib/postgresql/16/bin; cd /tmp/idlab
[ -d db ] && $B/pg_ctl -D db stop -m immediate >/dev/null 2>&1 || true
rm -rf db db.log
$B/initdb -D /tmp/idlab/db -U postgres -A trust >/dev/null
cat >> db/postgresql.conf <<CONF
port = 5542
listen_addresses = 'localhost'
unix_socket_directories = '/tmp/idlab'
max_connections = 200
shared_buffers = 32MB
max_wal_size = 256MB
CONF
$B/pg_ctl -D /tmp/idlab/db -l /tmp/idlab/db.log start >/dev/null; sleep 1
psql -h localhost -p 5542 -U postgres -qc "create database shop"
echo "postgres up: 5542 (shop)"
