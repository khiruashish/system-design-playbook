#!/bin/bash
# Experiment 4: failover done wrong (split brain), then fencing + pg_rewind of every diverged node
B=/usr/lib/postgresql/16/bin; cd /tmp/pglab
P="psql -h localhost -p 5501 -U postgres -qAt"; R1="psql -h localhost -p 5502 -U postgres -qAt"; R2="psql -h localhost -p 5503 -U postgres -qAt"
$P -c "drop table if exists wallet" 2>/dev/null; $P -c "create table wallet(customer text primary key, balance_paise bigint)"
$P -c "insert into wallet values ('cust-42', 50000)"; sleep 1
tl() { $1 -c "select timeline_id from pg_control_checkpoint()"; }
rejoin() {   # $1 = data dir, $2 = its port, $3 = application name: rewind onto the new primary and restart as its standby
  $B/pg_ctl -D $1 stop -m fast >/dev/null 2>&1
  $B/pg_rewind -D $1 --source-server="host=localhost port=5502 user=postgres" 2>&1 | tail -1 | sed 's/^/   /'
  sed -i "s/^port = .*/port = $2/" $1/postgresql.conf                     # pg_rewind copied the SOURCE's config files
  echo "primary_conninfo = 'application_name=$3 host=localhost port=5502 user=postgres'" > $1/postgresql.auto.conf
  touch $1/standby.signal
  $B/pg_ctl -D $1 -l $1.log start >/dev/null
}
echo "== before: primary timeline $(tl "$P"); r1 in recovery? $($R1 -c "select pg_is_in_recovery()")"
echo "== network partition: the failover tool can't reach the primary and PROMOTES r1 (the primary is actually alive)"
$R1 -c "select pg_promote()" >/dev/null; sleep 2; $R1 -c "checkpoint" >/dev/null
echo "   r1 in recovery? $($R1 -c "select pg_is_in_recovery()")  r1 timeline $(tl "$R1")   old primary in recovery? $($P -c "select pg_is_in_recovery()")"
echo "== SPLIT BRAIN: pods with the old endpoint debit on the old primary, new pods debit on r1"
$P -c "update wallet set balance_paise = balance_paise - 20000 where customer='cust-42'"
$R1 -c "update wallet set balance_paise = balance_paise - 45000 where customer='cust-42'"
sleep 1
echo "   old primary: $($P -c "select balance_paise from wallet")   new primary r1: $($R1 -c "select balance_paise from wallet")   r2 (still following the old primary): $($R2 -c "select balance_paise from wallet")"
echo "   20000 + 45000 = 65000 debited from a 50000 balance"
echo "== fencing: stop the old primary and rewind it onto r1's timeline"
rejoin p 5501 p
echo "== r2 also replayed WAL written after the fork, so it must be rewound too"
rejoin r2 5503 r2
sleep 3
echo "== result: r1 standbys = $($R1 -c "select string_agg(application_name||'='||state, ', ' order by 1) from pg_stat_replication")"
rtl() { $1 -c "select received_tli from pg_stat_wal_receiver"; }
echo "   timelines: r1 (primary)=$(tl "$R1")  p streams timeline $(rtl "$P")  r2 streams timeline $(rtl "$R2")   balances: p=$($P -c "select balance_paise from wallet") r1=$($R1 -c "select balance_paise from wallet") r2=$($R2 -c "select balance_paise from wallet")"
echo "   the old side's -20000 debit is GONE: pg_rewind discarded every write made after the fork"
