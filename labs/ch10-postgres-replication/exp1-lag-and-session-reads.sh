#!/bin/bash
# Experiment 1-2: async lag, stale reads, read-your-writes via LSN, monotonic reads
P="psql -h localhost -p 5501 -U postgres -qAt"; R1="psql -h localhost -p 5502 -U postgres -qAt"; R2="psql -h localhost -p 5503 -U postgres -qAt"
$P -c "drop table if exists orders" 2>/dev/null; $P -c "create table orders(id text primary key, status text)"
$P -c "insert into orders values ('ord_1','PLACED')"; sleep 1
echo "== replicas caught up: r1=$($R1 -c "select status from orders where id='ord_1'") r2=$($R2 -c "select status from orders where id='ord_1'")"
$R1 -c "select pg_wal_replay_pause()" >/dev/null          # simulate a lagging replica (long query / slow disk)
$P -c "update orders set status='CANCELLED' where id='ord_1'"
TOKEN=$($P -c "select pg_current_wal_lsn()")
sleep 1
echo "== customer cancelled; commit LSN token = $TOKEN"
echo "   primary : $($P -c "select status from orders where id='ord_1'")"
echo "   r2      : $($R2 -c "select status from orders where id='ord_1'")   replayed up to $($R2 -c "select pg_last_wal_replay_lsn()")"
echo "   r1      : $($R1 -c "select status from orders where id='ord_1'")      replayed up to $($R1 -c "select pg_last_wal_replay_lsn()")  (paused)"
echo "== read-your-writes check: is r1 >= token? $($R1 -c "select pg_last_wal_replay_lsn() >= '$TOKEN'::pg_lsn")   is r2 >= token? $($R2 -c "select pg_last_wal_replay_lsn() >= '$TOKEN'::pg_lsn")"
echo "== monotonic reads violation without a token: read r2 then r1 -> $($R2 -c "select status from orders where id='ord_1'") then $($R1 -c "select status from orders where id='ord_1'")"
echo "== lag as the primary sees it:"
$P -c "select application_name, pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn) as replay_lag_bytes from pg_stat_replication order by 1"
$R1 -c "select pg_wal_replay_resume()" >/dev/null; sleep 1
echo "== after resume: r1=$($R1 -c "select status from orders where id='ord_1'")  r1>=token? $($R1 -c "select pg_last_wal_replay_lsn() >= '$TOKEN'::pg_lsn")"
