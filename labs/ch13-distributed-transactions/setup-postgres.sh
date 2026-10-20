#!/bin/bash
# One PostgreSQL 16 instance (:5541) with three databases, one per service: orders, inventory, payments.
# wal_level=logical for the CDC lab; max_prepared_transactions for the 2PC lab.
set -e
B=/usr/lib/postgresql/16/bin; cd /tmp/txlab
[ -d db ] && $B/pg_ctl -D db stop -m immediate >/dev/null 2>&1 || true
rm -rf db db.log
$B/initdb -D /tmp/txlab/db -U postgres -A trust >/dev/null
cat >> db/postgresql.conf <<CONF
port = 5541
listen_addresses = 'localhost'
unix_socket_directories = '/tmp/txlab'
wal_level = logical
max_prepared_transactions = 20
max_replication_slots = 4
max_connections = 200
CONF
$B/pg_ctl -D /tmp/txlab/db -l /tmp/txlab/db.log start >/dev/null; sleep 1
for d in orders inventory payments; do psql -h localhost -p 5541 -U postgres -qc "create database $d"; done
echo "up: 5541 (orders, inventory, payments)"
