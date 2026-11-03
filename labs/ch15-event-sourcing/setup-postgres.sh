#!/bin/bash
# One PostgreSQL 16 instance (:5551) with an "eventstore" database for the Ch 15 programs.
set -e
B=/usr/lib/postgresql/16/bin
[ -d /tmp/eslab/db ] && $B/pg_ctl -D /tmp/eslab/db stop -m immediate >/dev/null 2>&1 || true
rm -rf /tmp/eslab/db /tmp/eslab/db.log
$B/initdb -D /tmp/eslab/db -U postgres -A trust >/dev/null
printf "port = 5551\nlisten_addresses = 'localhost'\nunix_socket_directories = '/tmp/eslab'\nmax_connections = 200\n" >> /tmp/eslab/db/postgresql.conf
$B/pg_ctl -D /tmp/eslab/db -l /tmp/eslab/db.log start >/dev/null; sleep 1
psql -h localhost -p 5551 -U postgres -qc "create database eventstore"
echo "up: 5551 (eventstore)"
