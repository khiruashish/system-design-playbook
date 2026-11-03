#!/bin/bash
# Experiment A: single-node declarative partitioning — pruning, scatter, and the uniqueness rule
S="psql -h localhost -p 5511 -U postgres -qAt"
$S -c "drop table if exists orders" 2>/dev/null
$S -c "create table orders (id text not null, customer_id text not null, store_id text not null,
        amount_paise bigint not null, created_at timestamptz not null default now()) partition by hash (customer_id)"
for i in $(seq 0 7); do $S -c "create table orders_p$i partition of orders for values with (modulus 8, remainder $i)"; done
$S -c "create index on orders (customer_id, created_at desc)" -c "create index on orders (store_id)"
$S -c "insert into orders(id, customer_id, store_id, amount_paise)
       select 'ord_'||g, 'cust-'||(g % 50000), 'store-'||(g % 500), 10000 + g % 90000 from generate_series(1, 400000) g"
$S -c "analyze orders"
echo "== query WITH the partition key (customer_id): partitions scanned"
$S -c "explain (costs off) select * from orders where customer_id = 'cust-42' order by created_at desc limit 20" | grep -oE 'orders_p[0-9]+' | sort -u | tr '\n' ' '; echo
echo "== query WITHOUT the partition key (store_id): partitions scanned"
$S -c "explain (costs off) select count(*) from orders where store_id = 'store-7'" | grep -oE 'orders_p[0-9]+' | sort -u | tr '\n' ' '; echo
echo "== timing (warm cache): by customer vs by store"
for q in "select count(*) from orders where customer_id = 'cust-42'" "select count(*) from orders where store_id = 'store-7'"; do
  $S -c "explain (analyze, costs off, timing on, summary on) $q" | grep -E "Execution Time" | sed "s/^/   ${q:34:30}  /"; done
echo "== a primary key on id alone:"
$S -c "alter table orders add primary key (id)" 2>&1 | sed 's/^/   /'
echo "== a primary key that includes the partition key:"
$S -c "alter table orders add primary key (customer_id, id)" 2>&1 && echo "   OK (uniqueness of id alone is now only per partition)"
echo "== rows per partition:"
$S -c "select tableoid::regclass, count(*) from orders group by 1 order by 1" | paste -sd' ' | sed 's/^/   /'
