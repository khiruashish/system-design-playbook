#!/bin/bash
# Experiment 3: synchronous replication (quorum), its availability cost, and per-transaction durability levels
B=/usr/lib/postgresql/16/bin; cd /tmp/pglab
P="psql -h localhost -p 5501 -U postgres -qAt"
$P -c "alter system set synchronous_standby_names = 'ANY 1 (r1, r2)'" -c "select pg_reload_conf()" >/dev/null; sleep 1
echo "== quorum sync: $($P -c "select string_agg(application_name||'='||sync_state, ', ' order by application_name) from pg_stat_replication")"
$P -c "drop table if exists payments" 2>/dev/null; $P -c "create table payments(id text primary key, amount_paise bigint)"
t0=$(date +%s%N); $P -c "insert into payments values ('pay_1', 45000)"; t1=$(date +%s%N)
echo "== commit with both standbys up: $(( (t1-t0)/1000000 )) ms (waits for 1 of 2 standbys to flush)"
$B/pg_ctl -D r1 stop -m fast >/dev/null
t0=$(date +%s%N); $P -c "insert into payments values ('pay_2', 12000)"; t1=$(date +%s%N)
echo "== r1 down, r2 up: commit $(( (t1-t0)/1000000 )) ms (ANY 1 still satisfied)"
$B/pg_ctl -D r2 stop -m fast >/dev/null
echo "== both standbys down: insert pay_3 ..."
psql -h localhost -p 5501 -U postgres -qAt -c "insert into payments values ('pay_3', 9900)" > pay3.out 2>&1 &
sleep 2
echo "   after 2 s the client is still waiting: $($P -c "select count(*) from pg_stat_activity where wait_event = 'SyncRep'") backend(s) in wait_event=SyncRep"
echo "   pay_3 visible to other sessions? $($P -c "select count(*) from payments where id='pay_3'") (not until the wait ends)"
echo "== operator cancels the stuck wait (pg_cancel_backend):"
$P -c "select pg_cancel_backend(pid) from pg_stat_activity where wait_event = 'SyncRep'" >/dev/null; wait
sed 's/^/   client saw: /' pay3.out
echo "   pay_3 visible on primary now? $($P -c "select count(*) from payments where id='pay_3'")  <- committed locally, on NO standby: a failover now would lose it"
echo "== low-value write opting out per transaction:"
t0=$(date +%s%N); $P -c "begin; set local synchronous_commit = local; insert into payments values ('clickstream_1', 0); commit;"; t1=$(date +%s%N)
echo "   synchronous_commit=local commit took $(( (t1-t0)/1000000 )) ms with both standbys down"
$B/pg_ctl -D r1 -l r1.log start >/dev/null; $B/pg_ctl -D r2 -l r2.log start >/dev/null; sleep 3
echo "== standbys back: pay_3 on r2? $(psql -h localhost -p 5503 -U postgres -qAt -c "select count(*) from payments where id='pay_3'")"
$P -c "alter system reset synchronous_standby_names" -c "select pg_reload_conf()" >/dev/null
