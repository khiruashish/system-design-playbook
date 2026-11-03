#!/bin/bash
set -e
B=/usr/lib/postgresql/16/bin; D=/tmp/evolab/db
[ -d "$D" ] && $B/pg_ctl -D "$D" stop -m immediate >/dev/null 2>&1 || true
rm -rf "$D"; $B/initdb -D "$D" -U postgres -A trust >/dev/null
cat >> "$D/postgresql.conf" <<CONF
port = 5581
listen_addresses = 'localhost'
unix_socket_directories = '/tmp/evolab'
shared_buffers = 256MB
lock_timeout = 8000
CONF
$B/pg_ctl -D "$D" -l /tmp/evolab/db.log start >/dev/null; sleep 1
$B/psql -h localhost -p 5581 -U postgres -qc "create database shop" >/dev/null
echo "postgres up :5581 (shop)"
