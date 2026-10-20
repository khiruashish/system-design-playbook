#!/bin/bash
# Change data capture from the WAL with logical decoding (the mechanism Debezium's PostgreSQL connector uses).
P="psql -h localhost -p 5541 -U postgres -qAtX -d orders"
$P -c "select pg_drop_replication_slot(slot_name) from pg_replication_slots where slot_name = 'outbox_cdc'" >/dev/null
$P -c "drop table if exists outbox, orders; create table orders (id text primary key, customer_id text, status text, amount_paise bigint);
       create table outbox (id bigserial primary key, aggregate_id text not null, type text not null, payload jsonb not null, created_at timestamptz default now())"
$P -c "select 'slot created at ' || lsn from pg_create_logical_replication_slot('outbox_cdc', 'test_decoding')"
echo "== 1. three transactions: commit, ROLLBACK, commit"
$P -c "begin; insert into orders values ('ord-1', 'cust-42', 'PLACED', 32000);
       insert into outbox(aggregate_id, type, payload) values ('ord-1', 'order.order.placed.v1', '{\"amount\": 32000}'); commit"
$P -c "begin; insert into orders values ('ord-2', 'cust-77', 'PLACED', 5000);
       insert into outbox(aggregate_id, type, payload) values ('ord-2', 'order.order.placed.v1', '{\"amount\": 5000}'); rollback"
$P -c "begin; update orders set status = 'CONFIRMED' where id = 'ord-1';
       insert into outbox(aggregate_id, type, payload) values ('ord-1', 'order.order.confirmed.v1', '{}'); commit"
echo "== 2. what the CDC consumer receives (outbox table only, in commit order):"
$P -c "select data from pg_logical_slot_peek_changes('outbox_cdc', null, null, 'include-xids', '0')
       where data like 'BEGIN%' or data like 'COMMIT%' or data like 'table public.outbox%'" | sed -E 's/payload\[jsonb\]:/payload:/; s/created_at\[timestamp with time zone\]:[^ ]+ [^ ]+//; s/^/   /'
echo "   (ord-2 was rolled back: it never appears)"
echo "== 3. the consumer stalls; the slot keeps WAL on the primary's disk"
$P -c "insert into orders select 'bulk-' || g, 'cust-' || g, 'PLACED', 1000 from generate_series(1, 200000) g" 
echo "   retained WAL: $($P -c "select pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) from pg_replication_slots where slot_name = 'outbox_cdc'")"
N=$($P -c "select count(*) from pg_logical_slot_get_changes('outbox_cdc', null, null)")
# restart_lsn moves at the next decoding round after a checkpoint (a running-xacts record); emit one tiny message and read again
$P -c "checkpoint"; $P -c "select pg_logical_emit_message(false, 'heartbeat', 'x')" >/dev/null; sleep 1
$P -c "select count(*) from pg_logical_slot_get_changes('outbox_cdc', null, null)" >/dev/null
echo "   consumer catches up ($N change rows read and acknowledged); retained WAL: $($P -c "select pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) from pg_replication_slots where slot_name = 'outbox_cdc'")"
$P -c "select pg_drop_replication_slot('outbox_cdc')" >/dev/null
