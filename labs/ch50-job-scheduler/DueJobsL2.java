// DueJobsL2.java — run with (PostgreSQL on :5581 db shop):
//   java -Duser.timezone=UTC -cp /tmp/eslab/postgresql-42.7.4.jar DueJobsL2.java   (JDK 21)
// FIRING 120,000 DELAYED JOBS ON TIME. Slot cut-offs, cart nudges, payment-status checks: each job has a due time. We
// schedule 120,000 jobs over a 40-second window (fixed seed); 30% of them are due in the SAME second (an 18:00:00
// campaign). 4 worker threads fire them from a REAL PostgreSQL table (fsync on). "Firing" = claiming the job and
// recording when it started. Four designs:
//   W1 poll on every 1 s tick (all workers wake on the tick and drain together): claim due jobs (status = pending AND due_at <= now) in batches of 500 with FOR UPDATE SKIP LOCKED,
//      no index on due_at
//   W2 W1 + a partial index on due_at WHERE status = 'P'
//   W3 W2, polling every 100 ms
//   W4 W2 + lookahead: every 1 s, claim jobs due in the next 2 s (lease them) into an in-memory DelayQueue and fire
//      each at its due instant; results written back in batches
// Measured: lateness (start − due) p50 / p99 / max, claim queries run, and total time spent inside them.
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class DueJobsL2 {
    static final String URL = "jdbc:postgresql://localhost:5581/shop?user=claude&reWriteBatchedInserts=true";
    static final int JOBS = 120_000, WORKERS = 4;

    public static void main(String[] args) throws Exception {
        System.out.println("120,000 jobs due over 40 s (30% in one second); 4 workers; real PostgreSQL.\n");
        System.out.printf("   %-54s %10s %10s %10s %14s %14s%n", "design", "late p50", "late p99", "late max", "claim queries", "time in them");
        String[] names = {"W1) poll 1 s, no index", "W2) poll 1 s, partial index on due_at", "W3) poll 100 ms, partial index", "W4) index + 2 s lookahead into an in-memory timer"};
        for (int w = 0; w < 4; w++) run(w, names[w]);
        System.out.println("\n   → with pollers, the tick bounds the lateness: work that falls due while the pollers sleep waits up to a whole");
        System.out.println("     interval (the p99 and max columns), plus however long a burst takes to drain. W1's full-scan claims were so");
        System.out.println("     slow the queue was rarely empty, so it hardly slept: near-continuous polling, paid for with a table scan per");
        System.out.println("     query. Faster ticks cost more queries and more database time. The index helps less than you'd expect: each");
        System.out.println("     claim is also an UPDATE + commit, and the head of a queue index fills with dead entries for jobs just claimed");
        System.out.println("     (queue tables need vacuum tuning, time partitioning, or moving done rows out). A short lookahead decouples");
        System.out.println("     lateness from database load: lease what's due in the next seconds in big batches, fire it from an in-memory");
        System.out.println("     timer at the right instant, and let the lease expire back to the table if the node dies.");
    }

    static void run(int design, String name) throws Exception {
        long t0 = System.currentTimeMillis() + 5_000;
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS job");
            s.execute("CREATE TABLE job (id bigint PRIMARY KEY, due_at timestamptz NOT NULL, status char NOT NULL DEFAULT 'P', lease_until timestamptz, started_at timestamptz, payload text)");
            c.setAutoCommit(false);
            Random r = new Random(50);
            try (PreparedStatement p = c.prepareStatement("INSERT INTO job (id, due_at, payload) VALUES (?,?,?)")) {
                for (int i = 0; i < JOBS; i++) {
                    long due = r.nextDouble() < 0.3 ? t0 + 20_000 + r.nextInt(1000) : t0 + r.nextInt(40_000);
                    p.setLong(1, i); p.setTimestamp(2, new Timestamp(due)); p.setString(3, "reminder-" + i); p.addBatch();
                    if (i % 5000 == 4999) p.executeBatch();
                }
                p.executeBatch();
            }
            c.commit(); c.setAutoCommit(true);
            if (design >= 1) s.execute("CREATE INDEX job_due ON job (due_at) WHERE status = 'P'");
            s.execute("VACUUM ANALYZE job");
        }
        AtomicLong queries = new AtomicLong(), queryNanos = new AtomicLong();
        long now = System.currentTimeMillis(); if (t0 > now) Thread.sleep(t0 - now);
        AtomicBoolean stop = new AtomicBoolean(); ExecutorService ex = Executors.newFixedThreadPool(WORKERS + 2); List<Future<?>> fs = new ArrayList<>();
        if (design < 3) {
            long interval = design == 2 ? 100 : 1000;
            for (int w = 0; w < WORKERS; w++) fs.add(ex.submit(() -> {
                try (Connection c = DriverManager.getConnection(URL); PreparedStatement p = c.prepareStatement(
                        "UPDATE job SET status = 'D', started_at = clock_timestamp() WHERE id IN (SELECT id FROM job WHERE status = 'P' AND due_at <= now() ORDER BY due_at LIMIT 500 FOR UPDATE SKIP LOCKED)")) {
                    while (!stop.get()) { long q0 = System.nanoTime(); int n = p.executeUpdate(); queryNanos.addAndGet(System.nanoTime() - q0); queries.incrementAndGet(); if (n == 0) { long nw = System.currentTimeMillis(); Thread.sleep(interval - nw % interval); } }   // drain, then sleep to the next tick
                }
                return null; }));
        } else {
            DelayQueue<Due> q = new DelayQueue<>(); ConcurrentLinkedQueue<long[]> done = new ConcurrentLinkedQueue<>();
            fs.add(ex.submit(() -> {                                                             // lease loader
                try (Connection c = DriverManager.getConnection(URL); PreparedStatement p = c.prepareStatement(
                        "UPDATE job SET status = 'L', lease_until = now() + interval '30 seconds' WHERE id IN (SELECT id FROM job WHERE status = 'P' AND due_at <= now() + interval '2 seconds' ORDER BY due_at LIMIT 5000 FOR UPDATE SKIP LOCKED) RETURNING id, due_at")) {
                    while (!stop.get()) { int n = 0; long q0 = System.nanoTime(); try (ResultSet rs = p.executeQuery()) { while (rs.next()) { q.add(new Due(rs.getLong(1), rs.getTimestamp(2).getTime())); n++; } } queryNanos.addAndGet(System.nanoTime() - q0); queries.incrementAndGet(); if (n < 5000) Thread.sleep(1000); }
                }
                return null; }));
            for (int w = 0; w < WORKERS; w++) fs.add(ex.submit(() -> {                               // timer workers
                while (!stop.get()) { Due d = q.poll(50, TimeUnit.MILLISECONDS); if (d != null) done.add(new long[]{d.id, System.currentTimeMillis()}); }
                return null; }));
            fs.add(ex.submit(() -> {                                                             // batched write-back
                try (Connection c = DriverManager.getConnection(URL); PreparedStatement p = c.prepareStatement("UPDATE job SET status = 'D', started_at = to_timestamp(x.t / 1000.0) FROM unnest(?::bigint[], ?::bigint[]) AS x(id, t) WHERE job.id = x.id")) {
                    while (!stop.get() || !done.isEmpty()) {
                        List<Long> ids = new ArrayList<>(), ts = new ArrayList<>(); long[] e; while ((e = done.poll()) != null && ids.size() < 20_000) { ids.add(e[0]); ts.add(e[1]); }
                        if (!ids.isEmpty()) { p.setArray(1, c.createArrayOf("bigint", ids.toArray())); p.setArray(2, c.createArrayOf("bigint", ts.toArray())); p.executeUpdate(); } else Thread.sleep(100);
                    }
                }
                return null; }));
        }
        // wait until all done (or 90 s)
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            long deadline = System.currentTimeMillis() + 90_000;
            while (System.currentTimeMillis() < deadline) { try (ResultSet rs = s.executeQuery("SELECT count(*) FROM job WHERE status <> 'D'")) { rs.next(); if (rs.getLong(1) == 0) break; } Thread.sleep(500); }
            stop.set(true); for (Future<?> f : fs) f.get(); ex.shutdown();
            try (ResultSet rs = s.executeQuery("SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY ms), percentile_cont(0.99) WITHIN GROUP (ORDER BY ms), max(ms), count(*) FILTER (WHERE status <> 'D') FROM (SELECT extract(epoch FROM started_at - due_at) * 1000 AS ms, status FROM job) x")) {
                rs.next();
                System.out.printf("   %-54s %7.0f ms %7.0f ms %7.0f ms %,14d %,11.1f s%s%n", name, rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), queries.get(), queryNanos.get() / 1e9, rs.getLong(4) > 0 ? "  (" + rs.getLong(4) + " not fired)" : "");
            }
        }
    }
    record Due(long id, long at) implements Delayed {
        public long getDelay(TimeUnit u) { return u.convert(at - System.currentTimeMillis(), TimeUnit.MILLISECONDS); }
        public int compareTo(Delayed o) { return Long.compare(at, ((Due) o).at); }
    }
}
