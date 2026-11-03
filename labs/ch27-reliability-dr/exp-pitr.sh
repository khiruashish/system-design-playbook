#!/bin/bash
# exp-pitr.sh — REAL PostgreSQL 16 point-in-time recovery. Set up WAL archiving + a base backup, run a day of orders,
# then a human runs a catastrophic `DELETE FROM orders` (no WHERE). We recover to the instant BEFORE the mistake and
# measure RPO (rows lost) and RTO (restore wall-clock time).
set -e
B=/usr/lib/postgresql/16/bin
D=/tmp/drlab/primary; ARCHIVE=/tmp/drlab/wal_archive; RESTORE=/tmp/drlab/restored
PSQL="$B/psql -h localhost -p 5551 -U postgres -qAt -v ON_ERROR_STOP=1"
rm -rf "$D" "$ARCHIVE" "$RESTORE"; mkdir -p "$ARCHIVE"

echo "== 1. Init primary with WAL archiving on (the prerequisite for PITR) =="
$B/initdb -D "$D" -U postgres -A trust >/dev/null
cat >> "$D/postgresql.conf" <<CONF
port = 5551
listen_addresses = 'localhost'
unix_socket_directories = '/tmp/drlab'
wal_level = replica
archive_mode = on
archive_command = 'test ! -f $ARCHIVE/%f && cp %p $ARCHIVE/%f'
CONF
$B/pg_ctl -D "$D" -l /tmp/drlab/primary.log start >/dev/null; sleep 1
$PSQL -c "create table orders(id bigserial primary key, customer int, total_paise bigint, created_at timestamptz default clock_timestamp())" >/dev/null

echo "== 2. Base backup (the daily full backup) =="
t0=$(date +%s%N)
$B/pg_basebackup -h localhost -p 5551 -U postgres -D "$RESTORE" -Fp -Xs -c fast >/dev/null 2>&1
echo "   base backup taken: $(du -sm "$RESTORE" | cut -f1) MB"

echo "== 3. Ongoing traffic AFTER the backup (these rows exist only in the WAL, not the base backup) =="
$PSQL -c "insert into orders(customer, total_paise) select (random()*10000)::int, (random()*500000)::bigint from generate_series(1,50000)" >/dev/null
$PSQL -c "select '   50,000 orders written after the backup, last at ' || max(created_at) from orders"
$PSQL -c "insert into orders(customer, total_paise) select (random()*10000)::int, (random()*500000)::bigint from generate_series(1,7000)" >/dev/null
GOOD=$($PSQL -c "select count(*) from orders")
# mark a named restore point at the safe moment (what a deploy/ops tool does before a risky operation), then seal the WAL
$PSQL -c "select pg_create_restore_point('before_mistake')" >/dev/null
$PSQL -c "select pg_switch_wal()" >/dev/null; sleep 1
echo "   restore point 'before_mistake' created (orders now: $GOOD)"

echo "== 4. THE MISTAKE: a human runs DELETE with no WHERE at 14:03 =="
$PSQL -c "delete from orders" >/dev/null
$PSQL -c "insert into orders(customer, total_paise) values (1, 999)" >/dev/null   # and some later writes happen too
AFTER=$($PSQL -c "select count(*) from orders")
echo "   orders after the mistake: $AFTER  (catastrophe: $GOOD rows gone)"
# force the current WAL to be archived so recovery can reach the safe point
$PSQL -c "select pg_switch_wal()" >/dev/null; sleep 2

echo "== 5. RECOVER to the instant before the mistake (PITR) =="
$B/pg_ctl -D "$D" stop -m fast >/dev/null
rm -rf "$RESTORE_LIVE"; RESTORE_LIVE=/tmp/drlab/recovered; rm -rf "$RESTORE_LIVE"; cp -r "$RESTORE" "$RESTORE_LIVE"
# point recovery at the archive and the target time
cat >> "$RESTORE_LIVE/postgresql.conf" <<CONF
port = 5552
restore_command = 'cp $ARCHIVE/%f %p'
recovery_target_name = 'before_mistake'
recovery_target_action = 'promote'
CONF
touch "$RESTORE_LIVE/recovery.signal"
chmod 700 "$RESTORE_LIVE"
r0=$(date +%s%N)
$B/pg_ctl -D "$RESTORE_LIVE" -l /tmp/drlab/recover.log start >/dev/null
# wait until recovery finishes and the server accepts connections
for i in $(seq 1 60); do $B/psql -h localhost -p 5552 -U postgres -qAt -c "select 1" >/dev/null 2>&1 && break; sleep 0.5; done
RTO=$(( ($(date +%s%N) - r0)/1000000 ))
RECOVERED=$($B/psql -h localhost -p 5552 -U postgres -qAt -c "select count(*) from orders")
echo "   recovered database has $RECOVERED orders (target: $GOOD before the mistake)"
echo ""
echo "== RESULT =="
echo "   rows at safe point : $GOOD"
echo "   rows after mistake : $AFTER   (data destroyed by the DELETE)"
echo "   rows after PITR    : $RECOVERED"
echo "   RPO (data lost vs safe point) : $(( GOOD - RECOVERED )) rows"
echo "   RTO (restore wall-clock)      : ${RTO} ms for this small DB"
echo "   → PITR replays the base backup + the archived WAL up to a named restore point, undoing the mistake without losing the"
echo "     50,000+ orders that were written after the backup. Without WAL archiving you could only restore the base backup"
echo "     (losing everything since it); without a recorded safe timestamp you'd guess where to stop."
$B/psql -h localhost -p 5552 -U postgres -qAt -c "select pg_is_in_recovery()" >/dev/null 2>&1
$B/pg_ctl -D "$RESTORE_LIVE" stop -m fast >/dev/null 2>&1 || true
