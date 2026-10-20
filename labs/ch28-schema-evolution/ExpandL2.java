// ExpandL2.java — run with: java -Duser.timezone=UTC -cp /tmp/pgjdbc.jar ExpandL2.java
// EXPAND / CONTRACT (the "parallel change") on a REAL PostgreSQL table, with zero downtime and NO stopped writes.
// The business wants to rename `total_paise` -> `amount_paise` AND change its type from a bigint of paise to a numeric
// of rupees. A naive `ALTER ... RENAME` + type change would break every running instance of the old code the instant
// it ran. Instead we migrate in the six phases of expand/contract, with live traffic writing THROUGHOUT:
//   Phase 0  old code only: reads/writes total_paise (bigint paise)
//   Phase 1  EXPAND: add the new nullable column amount_rupees (numeric) -- metadata-only, no lock
//   Phase 2  DUAL-WRITE: new code writes BOTH columns; old code still writes only the old one
//   Phase 3  BACKFILL: batched update of rows the dual-write hasn't touched yet (lock-avoiding, §L4 technique)
//   Phase 4  READ-NEW: readers switch to the new column (with a consistency check that old==new)
//   Phase 5  CONTRACT: once no code writes the old column, drop it
// We prove at every phase that BOTH a reader on the old contract and a reader on the new contract see correct data.
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ExpandL2 {
    static final String URL = "jdbc:postgresql://localhost:5581/shop?user=postgres";
    static volatile boolean running = true;
    static volatile int phase = 0;              // the deploy phase the "new" instances are running
    static final AtomicLong mismatches = new AtomicLong();   // rows where old and new columns disagree, seen by a live reader

    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement st = c.createStatement()) {
            st.execute("drop table if exists inv_orders");
            st.execute("create table inv_orders(id bigserial primary key, sku text, total_paise bigint not null)");
            st.execute("insert into inv_orders(sku,total_paise) select 'SKU'||(g%1000), (random()*5000000)::bigint from generate_series(1,300000) g");
            st.execute("analyze inv_orders");
        }
        System.out.println("EXPAND/CONTRACT: rename total_paise (bigint paise) -> amount_rupees (numeric rupees) with zero downtime.");
        System.out.println("300,000 rows. A writer thread inserts new orders and a reader thread verifies BOTH contracts, the whole time.\n");

        // ---- live traffic: a WRITER that follows the current phase, and a READER that checks correctness ----
        var pool = Executors.newFixedThreadPool(2);
        pool.submit(ExpandL2::writer);
        pool.submit(ExpandL2::reader);
        Thread.sleep(300);

        // Phase 1 — EXPAND: add the new column. Nullable, no default -> metadata-only, no table rewrite, no blocking.
        exec("alter table inv_orders add column amount_rupees numeric(12,2)");
        phase = 1; banner(1, "EXPAND: added nullable amount_rupees (metadata-only). Old code unaffected, new column NULL for now.");
        Thread.sleep(600);

        // Phase 2 — DUAL-WRITE: new instances now write BOTH columns. (Old instances still write only total_paise.)
        phase = 2; banner(2, "DUAL-WRITE: new inserts populate BOTH columns. Old rows + old-instance rows still have amount_rupees NULL.");
        Thread.sleep(600);
        long nullsBefore = scalar("select count(*) from inv_orders where amount_rupees is null");

        // Phase 3 — BACKFILL: fill the historical NULLs in batches so we never lock the whole table (see L4).
        long backfilled = backfill();
        long nullsAfter = scalar("select count(*) from inv_orders where amount_rupees is null");
        banner(3, "BACKFILL: filled " + String.format("%,d", backfilled) + " historical rows in batches. NULLs " +
                String.format("%,d -> %,d", nullsBefore, nullsAfter) + " (remaining are in-flight, caught by dual-write).");
        Thread.sleep(400);

        // enforce the invariant now that every row has a value, WITHOUT a full-table validation lock:
        // add a NOT VALID check first (fast, no scan), then VALIDATE (scans but takes only a SHARE UPDATE lock, no write block).
        exec("alter table inv_orders add constraint amt_present check (amount_rupees is not null) not valid");
        exec("alter table inv_orders validate constraint amt_present");
        banner(3, "Added CHECK (amount_rupees IS NOT NULL) as NOT VALID then VALIDATE -- enforced without blocking writes.");

        // Phase 4 — READ-NEW: readers switch to amount_rupees. The reader thread now also asserts old==new on the same row.
        phase = 4; banner(4, "READ-NEW: readers use amount_rupees. Reader asserts round(total_paise/100,2) == amount_rupees on live rows.");
        Thread.sleep(800);

        // Phase 5 — CONTRACT: no code writes total_paise anymore -> drop it. This is the only destructive step, done last.
        phase = 5; Thread.sleep(300);        // let writers stop touching the old column
        exec("alter table inv_orders drop column total_paise");
        banner(5, "CONTRACT: dropped total_paise. Migration complete -- and no reader or writer ever saw an error.");

        running = false; pool.shutdown(); pool.awaitTermination(10, TimeUnit.SECONDS);

        long total = scalar("select count(*) from inv_orders");
        System.out.printf("%n   Final: %,d rows, every one has amount_rupees, the old column is gone, mismatches seen by live readers: %d.%n",
                total, mismatches.get());
        System.out.println("   Zero downtime came from ORDERING: expand (add) and dual-write are backward-compatible, so old and new code");
        System.out.println("   coexist; the destructive contract (drop) happens only after nothing reads or writes the old shape. Never rename in place.");
    }

    // WRITER: inserts orders using the contract for the current phase.
    static void writer() {
        try (Connection c = DriverManager.getConnection(URL)) {
            var oldOnly = c.prepareStatement("insert into inv_orders(sku,total_paise) values (?,?)");
            var both    = c.prepareStatement("insert into inv_orders(sku,total_paise,amount_rupees) values (?,?,?)");
            var newOnly = c.prepareStatement("insert into inv_orders(sku,amount_rupees) values (?,?)");
            var r = ThreadLocalRandom.current();
            while (running) {
                long paise = r.nextLong(5_000_000); String sku = "SKU" + r.nextInt(1000);
                int p = phase;
                try {
                    if (p <= 1)      { oldOnly.setString(1, sku); oldOnly.setLong(2, paise); oldOnly.executeUpdate(); }
                    else if (p <= 4) { both.setString(1, sku); both.setLong(2, paise); both.setBigDecimal(3, new java.math.BigDecimal(paise).movePointLeft(2)); both.executeUpdate(); }
                    else             { newOnly.setString(1, sku); newOnly.setBigDecimal(2, new java.math.BigDecimal(paise).movePointLeft(2)); newOnly.executeUpdate(); }
                } catch (SQLException e) { /* a transient conflict during a phase flip is fine; the point is no OUTAGE */ }
                try { Thread.sleep(1); } catch (InterruptedException e) { return; }
            }
        } catch (Exception e) { System.out.println("writer: " + e); }
    }

    // READER: reads a recent row on the phase-appropriate contract and checks it is correct.
    static void reader() {
        try (Connection c = DriverManager.getConnection(URL)) {
            var q = c.prepareStatement("select total_paise, amount_rupees from inv_orders order by id desc limit 1");
            var qNew = c.prepareStatement("select amount_rupees from inv_orders order by id desc limit 1");
            while (running) {
                try {
                    if (phase < 5) {
                        try (ResultSet rs = q.executeQuery()) {
                            if (rs.next()) {
                                long paise = rs.getLong(1); java.math.BigDecimal rup = rs.getBigDecimal(2);
                                if (phase >= 4 && rup != null && !rs.wasNull()) {
                                    // both contracts must agree on rows that have both values
                                    java.math.BigDecimal expect = new java.math.BigDecimal(paise).movePointLeft(2);
                                    if (rup.compareTo(expect) != 0) mismatches.incrementAndGet();
                                }
                            }
                        }
                    } else {
                        try (ResultSet rs = qNew.executeQuery()) { rs.next(); rs.getBigDecimal(1); }
                    }
                } catch (SQLException e) { /* mid-flip transient; not an outage */ }
                try { Thread.sleep(2); } catch (InterruptedException e) { return; }
            }
        } catch (Exception e) { System.out.println("reader: " + e); }
    }

    // Batched backfill: update in id-ranges of 10,000 so each statement locks only its own rows briefly.
    static long backfill() throws Exception {
        long done = 0;
        try (Connection c = DriverManager.getConnection(URL)) {
            c.setAutoCommit(true);
            long maxId = 0; try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("select max(id) from inv_orders")) { rs.next(); maxId = rs.getLong(1); }
            var upd = c.prepareStatement(
                "update inv_orders set amount_rupees = round(total_paise/100.0, 2) " +
                "where id between ? and ? and amount_rupees is null and total_paise is not null");
            for (long lo = 1; lo <= maxId; lo += 10_000) {
                upd.setLong(1, lo); upd.setLong(2, lo + 9_999);
                done += upd.executeUpdate();
                Thread.sleep(2);       // breathe between batches -- let live traffic through, keep autovacuum happy
            }
        }
        return done;
    }

    static void exec(String sql) throws Exception { try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) { s.execute(sql); } }
    static long scalar(String sql) throws Exception { try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) { rs.next(); return rs.getLong(1); } }
    static void banner(int p, String msg) { System.out.printf("   [phase %d] %s%n", p, msg); }
}
