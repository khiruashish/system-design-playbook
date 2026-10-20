// BackfillL4.java — run with: java -Duser.timezone=UTC -cp /tmp/pgjdbc.jar BackfillL4.java
// The recurring "backfill a column across a huge table" problem on REAL PostgreSQL. We compare two ways to set
// amount_rupees on 3,000,000 rows while a live writer keeps updating random rows:
//   A) ONE big UPDATE  -- simplest to write, but it locks every row it touches for the whole statement and produces one
//                         giant transaction (lots of WAL, dead tuples, and it blocks/serializes concurrent writers).
//   B) BATCHED UPDATE  -- update in id-ranges, committing each batch, so each lock is tiny and brief and autovacuum can
//                         reclaim dead tuples as you go. This is how you backfill in production without an outage.
// We measure: total time, how long a concurrent writer's UPDATE on an affected row was BLOCKED, and dead-tuple bloat.
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class BackfillL4 {
    static final String URL = "jdbc:postgresql://localhost:5581/shop?user=postgres";
    static volatile boolean running = true;

    public static void main(String[] args) throws Exception {
        seed();
        System.out.println("3,000,000 rows. A live writer updates random rows the whole time. We backfill amount_rupees two ways.\n");

        // ---- Approach A: one big UPDATE ----
        resetColumn();
        var maxBlockA = new AtomicLong();
        var stopA = liveWriter(maxBlockA);
        long t0 = System.nanoTime();
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("update inv_big set amount_rupees = round(total_paise/100.0,2)");   // touches all 3M rows in ONE txn
        }
        long msA = (System.nanoTime() - t0) / 1_000_000;
        stopA.run();
        long deadA = deadTuples();
        System.out.printf("   A) ONE big UPDATE   : %,6d ms total   concurrent writer blocked up to %,5d ms   dead tuples after: %,d%n",
                msA, maxBlockA.get(), deadA);
        vacuum();

        // ---- Approach B: batched UPDATE, 25k rows per committed batch ----
        resetColumn();
        var maxBlockB = new AtomicLong();
        var stopB = liveWriter(maxBlockB);
        t0 = System.nanoTime();
        long batches = 0, maxId = scalar("select max(id) from inv_big");
        try (Connection c = DriverManager.getConnection(URL)) {
            c.setAutoCommit(true);   // each batch is its own transaction -> short locks, WAL flushed, dead tuples reclaimable
            var upd = c.prepareStatement("update inv_big set amount_rupees = round(total_paise/100.0,2) where id between ? and ? and amount_rupees is null");
            for (long lo = 1; lo <= maxId; lo += 25_000) { upd.setLong(1, lo); upd.setLong(2, lo + 24_999); upd.executeUpdate(); batches++; }
        }
        long msB = (System.nanoTime() - t0) / 1_000_000;
        stopB.run();
        long deadB = deadTuples();
        System.out.printf("   B) BATCHED (25k/txn): %,6d ms total   concurrent writer blocked up to %,5d ms   dead tuples after: %,d   (%d batches)%n",
                msB, maxBlockB.get(), deadB, batches);

        running = false;
        System.out.println("\n   → the single UPDATE locks every row it rewrites for the entire statement, so a concurrent writer touching one of");
        System.out.println("     those rows waits behind the whole backfill -- here it blocked until lock_timeout (8 s) fired. Both forms create");
        System.out.println("     ~3M dead tuples, but the single UPDATE holds them in ONE uncommitted transaction that autovacuum cannot touch");
        System.out.println("     until it ends; the batched form commits every 25k rows, so each lock lasts milliseconds, writers slip between");
        System.out.println("     batches, and autovacuum can reclaim as it goes. Same result, but only the batched form is safe on a live table.");
        System.out.println("     Add a short sleep between batches under load, and drive batches off an indexed key range (not OFFSET, which re-scans).");
    }

    // a writer that repeatedly UPDATEs a row in the low id-range (which the backfill also touches) and records its worst wait
    static Runnable liveWriter(AtomicLong maxBlock) {
        var pool = Executors.newSingleThreadExecutor();
        running = true;
        pool.submit(() -> {
            try (Connection c = DriverManager.getConnection(URL)) {
                var upd = c.prepareStatement("update inv_big set total_paise = total_paise + 1 where id = ?");
                var r = ThreadLocalRandom.current();
                while (running) {
                    long id = 1 + r.nextLong(3_000_000); long t0 = System.nanoTime();
                    try { upd.setLong(1, id); upd.executeUpdate(); } catch (SQLException e) {}
                    maxBlock.accumulateAndGet((System.nanoTime() - t0) / 1_000_000, Math::max);
                    try { Thread.sleep(1); } catch (InterruptedException e) { return; }
                }
            } catch (Exception e) {}
        });
        return () -> { running = false; pool.shutdown(); try { pool.awaitTermination(10, TimeUnit.SECONDS); } catch (Exception e) {} };
    }

    static void seed() throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement st = c.createStatement()) {
            st.execute("drop table if exists inv_big");
            st.execute("create table inv_big(id bigserial primary key, total_paise bigint not null, amount_rupees numeric(12,2))");
            st.execute("insert into inv_big(total_paise) select (random()*5000000)::bigint from generate_series(1,3000000)");
            st.execute("analyze inv_big");
        }
    }
    static void resetColumn() throws Exception { try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) { s.execute("update inv_big set amount_rupees = null"); s.execute("vacuum inv_big"); } }
    static void vacuum() throws Exception { try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) { s.execute("vacuum inv_big"); } }
    static long deadTuples() throws Exception { return scalar("select coalesce(n_dead_tup,0) from pg_stat_user_tables where relname='inv_big'"); }
    static long scalar(String sql) throws Exception { try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) { rs.next(); return rs.getLong(1); } }
}
