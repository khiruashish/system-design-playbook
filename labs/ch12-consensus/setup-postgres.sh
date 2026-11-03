#!/bin/bash
# One PostgreSQL 16 instance on 5531 used as the linearizable store for a lease + fencing tokens
set -e
B=/usr/lib/postgresql/16/bin; cd /tmp/fencelab
[ -d db ] && $B/pg_ctl -D db stop -m immediate >/dev/null 2>&1 || true
rm -rf db db.log
$B/initdb -D db -U postgres -A trust >/dev/null
printf "port = 5531\nlisten_addresses = 'localhost'\nunix_socket_directories = '/tmp/fencelab'\n" >> db/postgresql.conf
$B/pg_ctl -D db -l db.log start >/dev/null; sleep 1; echo "up: 5531"
