#!/bin/bash
# The nightly store-settlement job must run on exactly one pod. Leader = holder of a 3 s lease in PostgreSQL.
# Pod A takes the lease, then stalls for 6 s (a GC pause, a frozen VM). Pod B takes over. A wakes up and writes.
S="psql -h localhost -p 5531 -U postgres -qAtX"
$S -c "drop table if exists leader_lease, settlement" >/dev/null 2>&1
$S -c "create table leader_lease (name text primary key, holder text, epoch bigint not null, expires_at timestamptz not null)"
$S -c "insert into leader_lease values ('store-settlement', null, 0, now() - interval '1 s')"
$S -c "create table settlement (store_id text primary key, payout_paise bigint, written_by text, fence bigint not null default 0)"
$S -c "insert into settlement(store_id) values ('store-7')"
# acquire: succeeds only if the lease expired (by the DATABASE's clock) or we already hold it; epoch = fencing token
acquire() { $S -c "update leader_lease set holder = '$1', epoch = epoch + 1, expires_at = now() + interval '3 s'
                   where name = 'store-settlement' and (expires_at < now() or holder = '$1') returning epoch"; }
run() {   # $1 = mode (nofence|fence)
  $S -c "update leader_lease set holder = null, epoch = 0, expires_at = now() - interval '1 s'" >/dev/null
  $S -c "update settlement set payout_paise = null, written_by = null, fence = 0" >/dev/null
  if [ "$1" = nofence ]; then
    write() { $S -c "with u as (update settlement set payout_paise = $2, written_by = '$1' where store_id = 'store-7' returning 1)
                     select count(*) || ' row updated' from u"; }
  else
    write() { $S -c "with u as (update settlement set payout_paise = $2, written_by = '$1', fence = $3
                                where store_id = 'store-7' and fence <= $3 returning 1)
                     select count(*) || case when count(*) = 1 then ' row updated' else ' rows updated' end || case when count(*) = 0 then ' (REJECTED: token too old)' else '' end from u"; }
  fi
  ta=$(acquire pod-A); echo "   t=0s  pod-A acquires lease → epoch ${ta:-none}; computes payout 41,250.00 from a snapshot taken now"
  echo "   t=0s  pod-A stalls for 6 s (lease TTL is 3 s)"
  ( sleep 6; r=$(write pod-A 4125000 $ta); echo "   t=6s  pod-A wakes, still believes it is leader, writes its stale payout with token $ta → $r" ) &
  sleep 1; tb=$(acquire pod-B); echo "   t=1s  pod-B tries → ${tb:-refused (lease still valid)}"
  sleep 3; tb=$(acquire pod-B); echo "   t=4s  pod-B tries → epoch $tb; recomputes with 3 late refunds: 39,980.00"
  r=$(write pod-B 3998000 $tb); echo "   t=4s  pod-B writes with token $tb → $r"
  wait
  echo "   final row: $($S -c "select 'payout ' || (payout_paise/100.0)::numeric(12,2) || ' written_by ' || written_by || ' fence ' || fence from settlement")"
}
echo "== without a fencing check at the resource"; run nofence
echo "== with a fencing check (update ... where fence <= my_token)"; run fence
echo "== the lease alone did its job: at most one pod held it at any DATABASE instant; the damage came from a pod that no longer held it"
