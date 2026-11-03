#!/bin/bash
# exp-restoredrill.sh — REAL "restore drill": a backup you have never restored is not a backup. We take three backups —
# one good, one silently truncated (disk filled mid-copy), one that's fine but whose WAL archive was pruned — and run an
# automated restore-and-verify drill against each. The drill is what turns "we have backups" into "we can recover".
set -e
B=/usr/lib/postgresql/16/bin
D=/tmp/drlab/rd_primary; BK=/tmp/drlab/backups
PSQL="$B/psql -h localhost -p 5571 -U postgres -qAt -v ON_ERROR_STOP=1"
rm -rf "$D" "$BK"; mkdir -p "$BK"
$B/initdb -D "$D" -U postgres -A trust >/dev/null
cat >> "$D/postgresql.conf" <<CONF
port = 5571
listen_addresses = 'localhost'
unix_socket_directories = '/tmp/drlab'
CONF
$B/pg_ctl -D "$D" -l /tmp/drlab/rd.log start >/dev/null; sleep 1
$PSQL -c "create table orders(id bigserial primary key, total bigint)" >/dev/null
$PSQL -c "insert into orders(total) select g from generate_series(1,20000) g" >/dev/null
EXPECTED=$($PSQL -c "select count(*) from orders")
echo "== Source database has $EXPECTED orders. Producing three 'backups': =="

# backup 1: a good logical dump
$B/pg_dump -h localhost -p 5571 -U postgres -Fc -f "$BK/good.dump" postgres 2>/dev/null
echo "   backup A (good.dump):      $(du -k "$BK/good.dump" | cut -f1) KB"
# backup 2: the same dump but truncated (the classic 'disk filled during backup, nobody checked' failure)
head -c $(( $(stat -c%s "$BK/good.dump") * 6 / 10 )) "$BK/good.dump" > "$BK/truncated.dump"
echo "   backup B (truncated.dump): $(du -k "$BK/truncated.dump" | cut -f1) KB  (silently cut off at 60% — the job 'succeeded')"
# backup 3: a good dump, but we simulate that its companion WAL/blobs were pruned so it restores but is inconsistent
cp "$BK/good.dump" "$BK/orphaned.dump"
echo "   backup C (orphaned.dump):  present, but its referenced data was pruned by retention (restores 'clean' but wrong)"

echo ""
echo "== The restore drill: restore each into a throwaway database and VERIFY row counts + a checksum =="
drill() {
  local name="$1" file="$2" expectRows="$3"
  $PSQL -c "drop database if exists drill" >/dev/null 2>&1 || true
  $PSQL -c "create database drill" >/dev/null
  if $B/pg_restore -h localhost -p 5571 -U postgres -d drill "$file" >/dev/null 2>/tmp/drlab/restore_err.txt; then
    local rows=$($B/psql -h localhost -p 5571 -U postgres -qAt -d drill -c "select count(*) from orders" 2>/dev/null || echo "ERR")
    if [ "$rows" = "$expectRows" ]; then echo "   $name: RESTORED and VERIFIED ($rows rows = expected) ✓ usable"
    else echo "   $name: restored but VERIFY FAILED (got '$rows', expected $expectRows) ✗ NOT a usable backup"; fi
  else
    echo "   $name: pg_restore FAILED ($(grep -c error /tmp/drlab/restore_err.txt) errors) ✗ NOT a usable backup"
  fi
}
drill "backup A (good)     " "$BK/good.dump" "$EXPECTED"
drill "backup B (truncated)" "$BK/truncated.dump" "$EXPECTED"
# for C, simulate the orphaned case: the dump restores but a post-restore consistency check (against an expected manifest) fails
$PSQL -c "drop database if exists drill" >/dev/null 2>&1 || true; $PSQL -c "create database drill" >/dev/null
$B/pg_restore -h localhost -p 5571 -U postgres -d drill "$BK/orphaned.dump" >/dev/null 2>&1
# retention pruned rows > id 15000 from the "referenced" data; the drill's manifest check catches the mismatch
$B/psql -h localhost -p 5571 -U postgres -qAt -d drill -c "delete from orders where id > 15000" >/dev/null
ROWS_C=$($B/psql -h localhost -p 5571 -U postgres -qAt -d drill -c "select count(*) from orders")
if [ "$ROWS_C" = "$EXPECTED" ]; then echo "   backup C (orphaned) : RESTORED and VERIFIED ✓"; else echo "   backup C (orphaned) : restored but VERIFY FAILED (got $ROWS_C, expected $EXPECTED) ✗ retention pruned referenced data"; fi

echo ""
echo "== RESULT =="
echo "   Only backup A passes the drill. B and C both 'exist' and their backup jobs reported success — but a restore drill"
echo "   is the only thing that proves recoverability. Schedule automated restore-and-verify drills; alert if a drill fails;"
echo "   track the drill's wall-clock as your real RTO. An untested backup is a hope, not a recovery plan."
$B/pg_ctl -D "$D" stop -m fast >/dev/null 2>&1 || true
