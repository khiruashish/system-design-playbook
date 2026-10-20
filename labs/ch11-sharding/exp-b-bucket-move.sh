#!/bin/bash
# Experiment B: application-level sharding with 64 logical buckets on 2 physical shards,
#               and an ONLINE move of buckets 32–63 from shard A to shard B with a write fence.
A="psql -h localhost -p 5521 -U postgres -qAt"; Bs="psql -h localhost -p 5522 -U postgres -qAt"
schema="
drop table if exists orders; drop table if exists bucket_fence;
create table bucket_fence (bucket int primary key, moved_to text not null);
create table orders (bucket int not null, id text not null, customer_id text not null, store_id text not null,
                     amount_paise bigint not null, primary key (bucket, id));
create index on orders (customer_id);
create or replace function reject_fenced_bucket() returns trigger language plpgsql as \$\$
begin
  if exists (select 1 from bucket_fence where bucket = new.bucket) then
    raise exception 'BUCKET_MOVED bucket=% moved_to=%', new.bucket,
      (select moved_to from bucket_fence where bucket = new.bucket) using errcode = 'P0001';
  end if;
  return new;
end \$\$;
create trigger orders_fence before insert or update on orders for each row execute function reject_fenced_bucket();"
$A -c "$schema" 2>/dev/null; $Bs -c "$schema" 2>/dev/null
# bucket = hash of customer_id mod 64 (PostgreSQL's hashtext here; the app uses the same function via SQL or a Java port)
$A -c "insert into orders select abs(hashtext('cust-'||(g % 20000))) % 64, 'ord_'||g, 'cust-'||(g % 20000), 'store-'||(g % 500), 10000 + g % 90000
       from generate_series(1, 200000) g"
echo "== before: shard A has $($A -c "select count(*) from orders") rows in $($A -c "select count(distinct bucket) from orders") buckets; shard B has $($Bs -c "select count(*) from orders")"
checksum() { $1 -c "select count(*)||':'||coalesce(sum(hashtext(id)::bigint + amount_paise),0) from orders where bucket = $2"; }
t0=$(date +%s%N)
for b in $(seq 32 63); do
  $A -c "insert into bucket_fence values ($b, 'shardB')"                                   # 1. fence: stale writers now fail
  $A -c "\copy (select * from orders where bucket = $b) to stdout" | $Bs -c "\copy orders from stdin"   # 2. copy
  src=$(checksum "$A" $b); dst=$(checksum "$Bs" $b)                                        # 3. verify
  [ "$src" = "$dst" ] || { echo "   bucket $b MISMATCH $src vs $dst"; exit 1; }
  # 4. flip the shard map for bucket b (config store) — here: the routing table below
  $A -c "delete from orders where bucket = $b"                                             # 5. clean up the source
done
t1=$(date +%s%N)
echo "== moved buckets 32–63 in $(( (t1-t0)/1000000 )) ms, every bucket verified by count + checksum"
echo "   shard A: $($A -c "select count(*) from orders") rows, buckets $($A -c "select min(bucket)||'–'||max(bucket) from orders")"
echo "   shard B: $($Bs -c "select count(*) from orders") rows, buckets $($Bs -c "select min(bucket)||'–'||max(bucket) from orders")"
echo "== a stale writer (old shard map) inserts into a moved bucket on shard A:"
$A -c "insert into orders values (40, 'ord_late', 'cust-x', 'store-1', 5000)" 2>&1 | head -1 | sed 's/^/   /'
echo "== a customer's orders are still one-shard reads:"
c='cust-4242'; b=$($A -c "select abs(hashtext('$c')) % 64")
if [ $b -lt 32 ]; then SH="$A"; NAME="shard A"; else SH="$Bs"; NAME="shard B"; fi
echo "   $c → bucket $b → $NAME: $($SH -c "select count(*) from orders where bucket = $b and customer_id = '$c'") orders (one shard, one index lookup)"
echo "== but 'orders of store-7' needs a scatter-gather over both shards:"
echo "   shard A: $($A -c "select count(*) from orders where store_id = 'store-7'")  shard B: $($Bs -c "select count(*) from orders where store_id = 'store-7'")  total: $(( $($A -c "select count(*) from orders where store_id = 'store-7'") + $($Bs -c "select count(*) from orders where store_id = 'store-7'") ))"
