#!/bin/bash
# OLTP vs OLAP on the same 10M order lines: PostgreSQL 16 (row store, B-tree) vs DuckDB 1.1 (columnar, vectorised) on a
# Parquet "lake" produced by an ELT step (extract CSV from Postgres → load + transform in DuckDB → Parquet partitioned by day).
set -e
cd /tmp/datalab
PSQL="psql -h localhost -p 5543 -U postgres -d orders -qAt -v ON_ERROR_STOP=1"
DUCK=/opt/duckdb/duckdb

if [ -z "$SKIP_LOAD" ]; then
echo "== 1. OLTP source: 10,000,000 order lines, 90 days, 200 stores, 8 cities, 20,000 SKUs, 30 categories"
$PSQL <<'SQL' >/dev/null
drop table if exists order_line;
create table order_line (
  order_id bigint not null, line_no int not null, store_id int not null, city text not null,
  sku_id int not null, category text not null, qty int not null, price_paise int not null,
  ordered_at timestamptz not null, status text not null, primary key (order_id, line_no));
insert into order_line
select g / 4, g % 4, (g / 4) % 200,
       (array['Bengaluru','Mumbai','Delhi','Hyderabad','Chennai','Pune','Kolkata','Ahmedabad'])[1 + ((g / 4) % 200) % 8],
       s.sku, 'cat-' || lpad((s.sku % 30)::text, 2, '0'), 1 + (g % 3), 2000 + (s.sku % 500) * 100,
       timestamptz '2026-06-27 00:00:00+05:30' + ((g / 4)::float8 * 90 * 86400 / 2500000) * interval '1 second',
       case when g % 50 = 0 then 'CANCELLED' else 'DELIVERED' end
from generate_series(0, 9999999) g, lateral (select (hashint4(g) & 2147483647) % 20000 as sku) s;
create index on order_line (ordered_at);
vacuum analyze order_line;
SQL
echo "   postgres table + indexes: $($PSQL -c "select pg_size_pretty(pg_total_relation_size('order_line'))")"

echo "== 2. ELT: extract → load → transform into a Parquet lake partitioned by day"
rm -rf lake extract.csv && mkdir -p lake
t0=$(date +%s%N)
$PSQL -c "\copy order_line to 'extract.csv' with (format csv, header)"
t1=$(date +%s%N)
$DUCK -c "set timezone='Asia/Kolkata';
  copy (select * exclude (ordered_at), ordered_at, cast(timezone('Asia/Kolkata', ordered_at) as date) as dt
        from read_csv('extract.csv', header = true, columns = {'order_id':'BIGINT','line_no':'INT','store_id':'INT','city':'VARCHAR','sku_id':'INT','category':'VARCHAR','qty':'INT','price_paise':'INT','ordered_at':'TIMESTAMPTZ','status':'VARCHAR'}))
  to 'lake/order_line' (format parquet, partition_by (dt), compression zstd);" >/dev/null
t2=$(date +%s%N)
echo "   extract (COPY to CSV): $(( (t1 - t0) / 1000000 )) ms, $(du -m extract.csv | cut -f1) MB   |   transform + write Parquet: $(( (t2 - t1) / 1000000 )) ms, $(du -sm lake | cut -f1) MB in $(find lake -name '*.parquet' | wc -l) files"

fi

pg_time() {   # best of 3, server-side time from \timing
  for i in 1 2 3; do printf "set timezone='Asia/Kolkata';\n\\\\timing on\n%s\n" "$1" | $PSQL 2>&1 | grep -o 'Time: [0-9.]*' | cut -d' ' -f2; done | sort -n | head -1
}
duck_time() {
  for i in 1 2 3; do printf "set timezone='Asia/Kolkata';\ncreate view order_line as select * from read_parquet('lake/order_line/*/*.parquet', hive_partitioning = true);\n.timer on\n%s;\n" "$1" \
      | $DUCK 2>&1 | grep -o 'real [0-9.]*' | tail -1 | awk '{printf "%.0f\n", $2*1000}'; done | sort -n | head -1
}

Q1="select order_id, line_no, sku_id, qty, price_paise, status from order_line where order_id = 1234567"
Q2="select city, category, sum(qty * price_paise) as revenue_paise from order_line where ordered_at >= timestamptz '2026-08-26 00:00:00+05:30' and status = 'DELIVERED' group by city, category order by revenue_paise desc limit 5"
Q3="select sku_id, sum(qty) as units from order_line where city = 'Bengaluru' group by sku_id order by units desc, sku_id limit 10"
Q4="select cast(date_trunc('week', ordered_at) as date) as week, count(distinct order_id) as orders, sum(qty * price_paise) as gmv_paise from order_line group by 1 order by 1"
Q4D="select date_trunc('week', dt) as week, count(distinct order_id) as orders, sum(qty * price_paise) as gmv_paise from order_line group by 1 order by 1"
Q4A="select date_trunc('week', dt) as week, approx_count_distinct(order_id) as orders_approx, sum(qty * price_paise) as gmv_paise from order_line group by 1 order by 1"
Q5D="select sum(qty * price_paise) from order_line where dt = date '2026-09-01'"
Q5P="select sum(qty * price_paise) from order_line where ordered_at >= timestamptz '2026-09-01 00:00:00+05:30' and ordered_at < timestamptz '2026-09-02 00:00:00+05:30'"

echo "== 3. Queries (best of 3; PostgreSQL uses up to 2 parallel workers, DuckDB 2 threads; 2 vCPU)"
printf "   %-58s %12s %12s\n" "query" "PostgreSQL" "DuckDB/Parquet"
printf "   %-58s %9s ms %9s ms\n" "Q1 OLTP: one order's lines by primary key" "$(pg_time "$Q1")" "$(duck_time "$Q1")"
printf "   %-58s %9s ms %9s ms\n" "Q2 OLAP: revenue by city x category, last 30 days" "$(pg_time "$Q2")" "$(duck_time "$Q2")"
printf "   %-58s %9s ms %9s ms\n" "Q3 OLAP: top 10 SKUs in Bengaluru, whole quarter" "$(pg_time "$Q3")" "$(duck_time "$Q3")"
printf "   %-58s %9s ms %9s ms\n" "Q4 OLAP: weekly distinct orders + GMV (tz math per row)" "$(pg_time "$Q4")" "$(duck_time "$Q4")"
printf "   %-58s %12s %9s ms\n" "Q4 on the local date column dt computed once during ELT" "n/a" "$(duck_time "$Q4D")"
printf "   %-58s %12s %9s ms\n" "Q4 on dt with approx_count_distinct (HyperLogLog)" "n/a" "$(duck_time "$Q4A")"
printf "   %-58s %9s ms %9s ms\n" "Q5 one day's GMV (PG: index range; lake: partition pruning)" "$(pg_time "$Q5P")" "$(duck_time "$Q5D")"

echo "   same answers from both engines:"
for q in Q1 Q2 Q3 Q4; do
  a=$(printf "set timezone='Asia/Kolkata';\n%s;\n" "select * from (${!q}) t order by 1, 2" | $PSQL -F, | md5sum | cut -c1-8)
  b=$(printf "set timezone='Asia/Kolkata';\ncreate view order_line as select * from read_parquet('lake/order_line/*/*.parquet', hive_partitioning = true);\n.mode csv\n.headers off\n%s;\n" "select * from (${!q}) order by 1, 2" | $DUCK | tr -d "\r" | md5sum | cut -c1-8)
  printf "      %s postgres %s, duckdb %s %s\n" "$q" "$a" "$b" "$([ "$a" = "$b" ] && echo same || echo DIFFERENT)"
done
echo "== 4. Why: bytes the engines touch"
echo "   Q3 needs 3 of 11 columns. Parquet column sizes for city, sku_id, qty vs whole files:"
$DUCK -csv -c "select round(sum(total_compressed_size) filter (where path_in_schema in ('city','sku_id','qty')) / 1048576.0, 1) as needed_mb,
                 round(sum(total_compressed_size) / 1048576.0, 1) as all_columns_mb
          from parquet_metadata('lake/order_line/*/*.parquet')" | tail -1 | awk -F, '{print "   columns read: " $1 " MB of " $2 " MB (columnar)   |   PostgreSQL heap: every page, all columns"}'
echo "   Q5 with hive partitioning reads $($DUCK -csv -c "select count(*) from glob('lake/order_line/dt=2026-09-01/*.parquet')" | tail -1) file(s) of $(find lake -name '*.parquet' | wc -l)"
