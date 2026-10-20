#!/bin/bash
# Two-phase commit with PostgreSQL's PREPARE TRANSACTION across two databases (inventory, payments).
P="psql -h localhost -p 5541 -U postgres -qAtX"
$P -d inventory -c "drop table if exists stock; create table stock (sku text primary key, qty int not null check (qty >= 0));
                    insert into stock values ('ATTA-5KG', 10), ('GHEE-1L', 0)"
$P -d payments  -c "drop table if exists wallet; create table wallet (customer text primary key, balance_paise bigint not null check (balance_paise >= 0));
                    insert into wallet values ('cust-42', 100000)"
for g in $($P -d inventory -c "select gid from pg_prepared_xacts"); do $P -d inventory -c "rollback prepared '$g'"; done
for g in $($P -d payments -c "select gid from pg_prepared_xacts"); do $P -d payments -c "rollback prepared '$g'"; done

echo "== 1. happy path: both participants prepare (vote yes), coordinator commits both"
$P -d inventory -c "begin; update stock set qty = qty - 1 where sku = 'ATTA-5KG'; prepare transaction 'ord-1001:inv'"
$P -d payments  -c "begin; update wallet set balance_paise = balance_paise - 32000 where customer = 'cust-42'; prepare transaction 'ord-1001:pay'"
$P -d inventory -c "commit prepared 'ord-1001:inv'"; $P -d payments -c "commit prepared 'ord-1001:pay'"
echo "   stock ATTA-5KG = $($P -d inventory -c "select qty from stock where sku='ATTA-5KG'"), wallet = $($P -d payments -c "select balance_paise from wallet")"

echo "== 2. a participant votes NO: prepare fails, coordinator rolls back the other"
$P -d inventory -c "begin; update stock set qty = qty - 1 where sku = 'GHEE-1L'; prepare transaction 'ord-1002:inv'" 2>&1 | grep -E 'ERROR' | sed 's/^/   inventory: /'
$P -d payments  -c "begin; update wallet set balance_paise = balance_paise - 65000 where customer = 'cust-42'; prepare transaction 'ord-1002:pay'"
echo "   prepared now: $($P -d payments -c "select string_agg(gid, ', ') from pg_prepared_xacts where database = current_database()")"
$P -d payments -c "rollback prepared 'ord-1002:pay'"
echo "   after ROLLBACK PREPARED: wallet = $($P -d payments -c "select balance_paise from wallet")"

echo "== 3. the coordinator crashes after both prepared, before deciding"
$P -d inventory -c "begin; update stock set qty = qty - 2 where sku = 'ATTA-5KG'; prepare transaction 'ord-1003:inv'"
$P -d payments  -c "begin; update wallet set balance_paise = balance_paise - 50000 where customer = 'cust-42'; prepare transaction 'ord-1003:pay'"
echo "   in-doubt transactions: inventory [$($P -d inventory -c "select gid || ' since ' || to_char(prepared, 'HH24:MI:SS') from pg_prepared_xacts where database = current_database()")]  payments [$($P -d payments -c "select gid from pg_prepared_xacts where database = current_database()")]"
echo "   a reader is fine (MVCC):           stock ATTA-5KG = $($P -d inventory -c "select qty from stock where sku='ATTA-5KG'")"
echo "   another order for ATTA-5KG waits:  $($P -d inventory -c "set lock_timeout = '2s'; update stock set qty = qty - 1 where sku = 'ATTA-5KG'" 2>&1 | grep ERROR)"
echo "   restart the inventory database server…"
/usr/lib/postgresql/16/bin/pg_ctl -D /tmp/txlab/db restart -m fast -l /tmp/txlab/db.log >/dev/null; sleep 1
echo "   after restart, still in doubt:     [$($P -d inventory -c "select gid from pg_prepared_xacts where database = current_database()")] and the row is still locked: $($P -d inventory -c "set lock_timeout = '1s'; update stock set qty = qty - 1 where sku = 'ATTA-5KG'" 2>&1 | grep -c ERROR) error"
echo "   the coordinator recovers, reads its log (decision: COMMIT) and finishes both"
$P -d inventory -c "commit prepared 'ord-1003:inv'"; $P -d payments -c "commit prepared 'ord-1003:pay'"
echo "   stock ATTA-5KG = $($P -d inventory -c "select qty from stock where sku='ATTA-5KG'"), wallet = $($P -d payments -c "select balance_paise from wallet"); the waiting order can proceed: $($P -d inventory -c "update stock set qty = qty - 1 where sku = 'ATTA-5KG' returning qty")"
