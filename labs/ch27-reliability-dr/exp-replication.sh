#!/bin/bash
# exp-replication.sh — REAL PostgreSQL streaming replication + failover. A primary streams to a standby. We measure the
# RPO of ASYNC replication (data in flight at the moment of failure = lost) vs SYNCHRONOUS replication (RPO 0), then
# promote the standby and measure failover time (part of RTO).
set -e
B=/usr/lib/postgresql/16/bin
P=/tmp/drlab/repl_primary; S=/tmp/drlab/repl_standby
rm -rf "$P" "$S"
PP="$B/psql -h localhost -p 5561 -U postgres -qAt -v ON_ERROR_STOP=1"
PS="$B/psql -h localhost -p 5562 -U postgres -qAt"

echo "== 1. Primary with a replication slot and a replication user =="
$B/initdb -D "$P" -U postgres -A trust >/dev/null
cat >> "$P/postgresql.conf" <<CONF
port = 5561
listen_addresses = 'localhost'
unix_socket_directories = '/tmp/drlab'
wal_level = replica
max_wal_senders = 4
CONF
echo "host replication all 127.0.0.1/32 trust" >> "$P/pg_hba.conf"
$B/pg_ctl -D "$P" -l /tmp/drlab/rp.log start >/dev/null; sleep 1
$PP -c "select pg_create_physical_replication_slot('standby1')" >/dev/null
$PP -c "create table ledger(id bigserial primary key, amount bigint, note text)" >/dev/null

echo "== 2. Clone the standby with pg_basebackup and start it following the primary (ASYNC) =="
$B/pg_basebackup -h localhost -p 5561 -U postgres -D "$S" -Fp -Xs -R -S standby1 >/dev/null 2>&1
cat >> "$S/postgresql.conf" <<CONF
port = 5562
CONF
chmod 700 "$S"
$B/pg_ctl -D "$S" -l /tmp/drlab/rs.log start >/dev/null; sleep 2
echo "   standby streaming: $($PP -c "select state||' ('||sync_state||')' from pg_stat_replication")"

echo "== 3. Write to primary, watch it appear on the standby (read replica) =="
$PP -c "insert into ledger(amount, note) select g, 'baseline' from generate_series(1,10000) g" >/dev/null
sleep 1
echo "   primary rows: $($PP -c "select count(*) from ledger")  |  standby rows: $($PS -c "select count(*) from ledger")  (async: standby follows within ms)"

echo "== 4. ASYNC replication lag = the RPO window (committed but not yet streamed) =="
$PP -c "insert into ledger(amount, note) select g,'burst' from generate_series(1,200000) g" >/dev/null &
WRITER=$!
maxlag=0
for i in $(seq 1 40); do
  lag=$($PP -c "select coalesce(pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn),0)::bigint from pg_stat_replication limit 1" 2>/dev/null)
  [ -n "$lag" ] && [ "$lag" -gt "$maxlag" ] 2>/dev/null && maxlag=$lag
  sleep 0.02
done
wait $WRITER 2>/dev/null || true; sleep 1
final=$($PP -c "select pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn)::bigint from pg_stat_replication limit 1")
echo "   peak replication lag during the burst: $(( maxlag/1024 )) KB of un-replayed WAL on the standby"
echo "   -> if the primary is lost at that instant, that un-streamed WAL is the async RPO (a small window of commits)."
echo "   after the burst drains, lag settles to ${final} bytes (standby caught up)."

echo "== 5. Promote the standby to be the new primary (failover) =="
t0=$(date +%s%N)
$B/pg_ctl -D "$S" promote >/dev/null
for i in $(seq 1 40); do [ "$($PS -c "select pg_is_in_recovery()" 2>/dev/null)" = "f" ] && break; sleep 0.2; done
RTO=$(( ($(date +%s%N)-t0)/1000000 ))
NEWPRIMARY=$($PS -c "select count(*) from ledger")
$PS -c "insert into ledger(amount, note) values (1,'writes-accepted-after-promotion')" >/dev/null
echo "   standby promoted to primary in ${RTO} ms; now accepts writes. rows carried over: $NEWPRIMARY"
$B/pg_ctl -D "$S" stop -m fast >/dev/null 2>&1 || true

echo ""
echo "== 6. Same test with SYNCHRONOUS replication (RPO 0) =="
rm -rf "$P" "$S"
$B/initdb -D "$P" -U postgres -A trust >/dev/null
cat >> "$P/postgresql.conf" <<CONF
port = 5563
listen_addresses = 'localhost'
unix_socket_directories = '/tmp/drlab'
wal_level = replica
max_wal_senders = 4
CONF
echo "host replication all 127.0.0.1/32 trust" >> "$P/pg_hba.conf"
$B/pg_ctl -D "$P" -l /tmp/drlab/rp2.log start >/dev/null; sleep 1
$B/psql -h localhost -p 5563 -U postgres -qAt -v ON_ERROR_STOP=1 -c "select pg_create_physical_replication_slot('standby1')" >/dev/null
$B/psql -h localhost -p 5563 -U postgres -qAt -v ON_ERROR_STOP=1 -c "create table ledger(id bigserial primary key, amount bigint, note text)" >/dev/null
$B/pg_basebackup -h localhost -p 5563 -U postgres -D "$S" -Fp -Xs -R -S standby1 >/dev/null 2>&1
echo "port = 5564" >> "$S/postgresql.conf"; chmod 700 "$S"
$B/pg_ctl -D "$S" -l /tmp/drlab/rs2.log start >/dev/null; sleep 2
$B/psql -h localhost -p 5563 -U postgres -qAt -c "alter system set synchronous_standby_names='*'" >/dev/null
$B/psql -h localhost -p 5563 -U postgres -qAt -c "select pg_reload_conf()" >/dev/null; sleep 1
echo "   sync state now: $($B/psql -h localhost -p 5563 -U postgres -qAt -v ON_ERROR_STOP=1 -c "select sync_state from pg_stat_replication")  (commit waits for the standby to persist the WAL)"
t0=$(date +%s%N); $B/psql -h localhost -p 5563 -U postgres -qAt -v ON_ERROR_STOP=1 -c "insert into ledger(amount, note) select g,'sync' from generate_series(1,10000) g" >/dev/null; SYNCMS=$(( ($(date +%s%N)-t0)/1000000 ))
COMMITTED=$($B/psql -h localhost -p 5563 -U postgres -qAt -v ON_ERROR_STOP=1 -c "select count(*) from ledger")
PRIMARY_PID=$(head -1 "$P/postmaster.pid"); kill -9 $PRIMARY_PID 2>/dev/null; sleep 1
STANDBY_SYNC=$($B/psql -h localhost -p 5564 -U postgres -qAt -c "select count(*) from ledger")
echo "   primary committed $COMMITTED rows in ${SYNCMS} ms, then died; standby has $STANDBY_SYNC"
echo "   sync RPO = $(( COMMITTED - STANDBY_SYNC )) rows (0 by construction; the cost is commit latency)"
$B/pg_ctl -D "$S" stop -m fast >/dev/null 2>&1 || true

echo ""
echo "== RESULT =="
echo "   ASYNC : standby lags the primary by the network/flush delay; on a sudden primary loss, in-flight commits are lost (RPO > 0), but commits are fast."
echo "   SYNC  : every commit waits for the standby to persist the WAL, so RPO = 0, at the cost of commit latency and availability (a lost standby blocks commits)."
echo "   Failover = promote the standby (here ~sub-second); real RTO also includes detection, DNS/endpoint switch, and cache/connection re-warm."
