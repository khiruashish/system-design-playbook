// BatchStreamL4.java — run with: java -Duser.timezone=UTC -cp pgjdbc.jar BatchStreamL4.java
// Part A (real PostgreSQL :5543, the 10M order lines from exp-olap.sh): a chunk-oriented, restartable batch job
//   (the Spring Batch model) computing daily revenue per store, crashing once at 60 %. Checkpoint committed separately
//   from the output vs checkpoint in the same transaction vs recompute-and-overwrite the day's partition.
// Part B (deterministic simulation of an event stream): orders per store per 1-minute window for the dark-store demand
//   board, with out-of-order arrival (phones in basements and lifts). Processing-time windows vs event-time windows with
//   different watermarks and allowed lateness, compared with the batch answer (ground truth).
import java.sql.*;
import java.util.*;

public class BatchStreamL4 {
    static final String PG = "jdbc:postgresql://localhost:5543/orders?user=postgres";

    // ======================= Part A: restartable batch =======================
    static class Crash extends RuntimeException {}

    static void batch(Connection c, String mode) throws SQLException {
        try (var st = c.createStatement()) {
            st.execute("drop table if exists daily_store_revenue, job_checkpoint");
            st.execute("create table daily_store_revenue(dt date, store_id int, revenue_paise bigint not null, lines bigint not null, primary key (dt, store_id))");
            st.execute("create table job_checkpoint(job text primary key, last_order_id bigint not null, last_line_no int not null, chunks int not null)");
            st.execute("insert into job_checkpoint values ('daily-store-revenue', -1, -1, 0)");
        }
        int chunk = 100_000, crashAtChunk = 60, chunksDone = 0, chunksRedone = 0; long rowsRead = 0;
        boolean crashed = false;
        long t0 = System.nanoTime();
        while (true) {
            try {
                if (mode.equals("overwrite")) {                                                   // idempotent by construction
                    rowsRead += overwriteRun(c, crashed ? -1 : crashAtChunk * chunk);
                    break;
                }
                long[] cp = checkpoint(c);                                                        // restart point
                long lastOrder = cp[0]; int lastLine = (int) cp[1];
                if (crashed && chunksRedone == 0) chunksRedone = chunksDone - (int) cp[2];
                chunksDone = (int) cp[2];
                while (true) {
                    Map<String, long[]> agg = new HashMap<>(); long lo = lastOrder; int ll = lastLine; int n = 0;
                    try (var ps = c.prepareStatement("select order_id, line_no, (ordered_at at time zone 'Asia/Kolkata')::date, store_id, qty * price_paise "
                            + "from order_line where (order_id, line_no) > (?, ?) order by order_id, line_no limit ?")) {
                        ps.setLong(1, lastOrder); ps.setInt(2, lastLine); ps.setInt(3, chunk);
                        var rs = ps.executeQuery();
                        while (rs.next()) {
                            lo = rs.getLong(1); ll = rs.getInt(2); n++;
                            var a = agg.computeIfAbsent(rs.getDate(3) + "|" + rs.getInt(4), k -> new long[2]);
                            a[0] += rs.getLong(5); a[1]++;
                        }
                    }
                    if (n == 0) break;
                    rowsRead += n;
                    boolean sameTx = mode.equals("checkpoint in same tx");
                    c.setAutoCommit(false);
                    try (var up = c.prepareStatement("insert into daily_store_revenue values (?::date, ?, ?, ?) on conflict (dt, store_id) do update "
                            + "set revenue_paise = daily_store_revenue.revenue_paise + excluded.revenue_paise, lines = daily_store_revenue.lines + excluded.lines")) {
                        for (var e : agg.entrySet()) {
                            String[] k = e.getKey().split("\\|");
                            up.setString(1, k[0]); up.setInt(2, Integer.parseInt(k[1])); up.setLong(3, e.getValue()[0]); up.setLong(4, e.getValue()[1]); up.addBatch();
                        }
                        up.executeBatch();
                        if (!sameTx) c.commit();                                                 // output committed on its own …
                        chunksDone++;
                        if (!crashed && chunksDone == crashAtChunk) { crashed = true; throw new Crash(); }   // … crash before the checkpoint
                        try (var cp2 = c.prepareStatement("update job_checkpoint set last_order_id = ?, last_line_no = ?, chunks = ? where job = 'daily-store-revenue'")) {
                            cp2.setLong(1, lo); cp2.setInt(2, ll); cp2.setInt(3, chunksDone); cp2.executeUpdate();
                        }
                        c.commit();
                    } catch (Crash x) { c.rollback(); throw x; }
                    finally { c.setAutoCommit(true); }
                    lastOrder = lo; lastLine = ll;
                }
                break;
            } catch (Crash x) { crashed = true; if (mode.equals("overwrite")) rowsRead += 10_000_000; }   // the pod dies; the job is restarted
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        long[] diff;
        try (var st = c.createStatement(); var rs = st.executeQuery(
                "with truth as (select (ordered_at at time zone 'Asia/Kolkata')::date dt, store_id, sum(qty * price_paise) rev from order_line group by 1, 2) "
                + "select count(*) filter (where d.revenue_paise is distinct from t.rev), coalesce(sum(abs(coalesce(d.revenue_paise, 0) - t.rev)), 0) "
                + "from truth t left join daily_store_revenue d using (dt, store_id)")) {
            rs.next(); diff = new long[]{rs.getLong(1), rs.getLong(2)};
        }
        System.out.printf("   %-26s %,11d %8s %,15d %,13d %,8d ms%n", mode, rowsRead, mode.equals("overwrite") ? "-" : String.valueOf(chunksRedone), diff[0], diff[1] / 100, ms);
    }

    static long[] checkpoint(Connection c) throws SQLException {
        try (var st = c.createStatement(); var rs = st.executeQuery("select last_order_id, last_line_no, chunks from job_checkpoint")) { rs.next(); return new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)}; }
    }

    /** Recompute everything and replace the output in one transaction: a retry simply produces the same table again. */
    static long overwriteRun(Connection c, long crashAfterRows) throws SQLException {
        c.setAutoCommit(false);
        try (var st = c.createStatement()) {
            st.execute("delete from daily_store_revenue");
            st.execute("insert into daily_store_revenue select (ordered_at at time zone 'Asia/Kolkata')::date, store_id, sum(qty * price_paise), count(*) from order_line group by 1, 2");
            if (crashAfterRows > 0) throw new Crash();                                           // crash before commit: nothing visible
            c.commit();
            return 10_000_000;
        } catch (Crash x) { c.rollback(); throw x; }
        finally { c.setAutoCommit(true); }
    }

    // ======================= Part B: stream windows =======================
    record Event(int store, long eventMs, long arrivalMs) {}

    static List<Event> events(long[] truthOut) {
        Random r = new Random(42);
        long span = 6 * 3_600_000L;                                  // 18:00–24:00 evening peak
        List<Event> es = new ArrayList<>();
        for (int i = 0; i < 1_080_000; i++) {                          // 50 orders/s across 200 stores
            long te = (long) (r.nextDouble() * span);
            double p = r.nextDouble(); long delay;
            if (p < 0.90) delay = (long) (-Math.log(1 - r.nextDouble()) * 500);            // normal: ~0.5 s
            else if (p < 0.98) delay = 5_000 + (long) (r.nextDouble() * 55_000);           // retries: 5–60 s
            else delay = 120_000 + (long) (r.nextDouble() * 1_080_000);                     // offline phone: 2–20 min
            es.add(new Event(r.nextInt(200), te, te + delay));
        }
        es.sort(Comparator.comparingLong(Event::arrivalMs));             // the order the stream processor sees them
        return es;
    }

    static final long W = 60_000;

    /** Returns {exact windows %, events dropped, p50 first-result delay ms, p99, updates per window}. */
    static double[] stream(List<Event> es, Map<Long, Integer> truth, boolean processingTime, long watermarkDelay, long allowedLateness) {
        Map<Long, Integer> state = new HashMap<>(), emitted = new HashMap<>();
        Map<Long, Long> firstEmitDelay = new HashMap<>();
        TreeMap<Long, List<Long>> byEnd = new TreeMap<>();             // window end → keys (to fire in order)
        long maxEvent = Long.MIN_VALUE, dropped = 0, updates = 0;
        for (Event e : es) {
            long t = processingTime ? e.arrivalMs() : e.eventMs();
            long start = t / W * W, key = start * 1000 + e.store();
            long now = e.arrivalMs();
            if (!processingTime) {
                maxEvent = Math.max(maxEvent, e.eventMs());
                long wm = maxEvent - watermarkDelay;
                if (start + W + allowedLateness <= wm) { dropped++; continue; }                 // too late: dropped
                state.merge(key, 1, Integer::sum);
                if (emitted.containsKey(key)) { emitted.put(key, state.get(key)); updates++; }   // late but allowed: update the result
                else byEnd.computeIfAbsent(start + W, k -> new ArrayList<>()).add(key);
                while (!byEnd.isEmpty() && byEnd.firstKey() <= wm) {                             // watermark passed: fire windows
                    var fired = byEnd.pollFirstEntry();
                    for (long k : fired.getValue()) if (!emitted.containsKey(k)) { emitted.put(k, state.get(k)); firstEmitDelay.put(k, now - fired.getKey()); }
                }
            } else {
                state.merge(key, 1, Integer::sum);                                               // bucket by arrival time
                firstEmitDelay.putIfAbsent(key, 0L);
            }
        }
        if (processingTime) emitted.putAll(state);
        else for (var k : state.keySet()) emitted.putIfAbsent(k, state.get(k));                   // end of stream flush
        long exact = truth.entrySet().stream().filter(t -> t.getValue().equals(emitted.get(t.getKey()))).count();
        long[] d = firstEmitDelay.values().stream().mapToLong(Long::longValue).sorted().toArray();
        return new double[]{100.0 * exact / truth.size(), dropped, d[d.length / 2], d[(int) (d.length * 0.99)], (double) updates / truth.size()};
    }

    public static void main(String[] args) throws Exception {
        System.out.println("A. Nightly batch over 10,000,000 order lines in chunks of 100,000 (PostgreSQL); the pod crashes once at chunk 60");
        System.out.printf("   %-26s %11s %8s %15s %13s %11s%n", "restart strategy", "rows read", "chunks", "(day, store)", "revenue off", "time");
        System.out.printf("   %-26s %11s %8s %15s %13s %11s%n", "", "", "redone", "rows wrong", "by (₹)", "");
        try (Connection c = DriverManager.getConnection(PG)) {
            for (String mode : List.of("checkpoint committed later", "checkpoint in same tx", "overwrite")) batch(c, mode);
        }

        System.out.println("B. Orders per store per 1-minute window, 1,080,000 events over 6 h; 90 % arrive in ~0.5 s, 8 % in 5–60 s, 2 % in 2–20 min");
        List<Event> es = events(null);
        Map<Long, Integer> truth = new HashMap<>();                                                   // the batch answer: by event time, all events
        for (Event e : es) truth.merge(e.eventMs() / W * W * 1000 + e.store(), 1, Integer::sum);
        System.out.printf("   %-46s %8s %9s %12s %12s %9s%n", "streaming strategy", "windows", "events", "result delay", "result delay", "updates");
        System.out.printf("   %-46s %8s %9s %12s %12s %9s%n", "", "exact", "dropped", "p50", "p99", "/window");
        Object[][] runs = {
            {"processing-time windows", true, 0L, 0L},
            {"event time, watermark 5 s, late events dropped", false, 5_000L, 0L},
            {"event time, watermark 60 s, late dropped", false, 60_000L, 0L},
            {"event time, watermark 5 s + lateness 30 min", false, 5_000L, 1_800_000L},
        };
        for (Object[] run : runs) {
            long t0 = System.nanoTime();
            double[] m = stream(es, truth, (Boolean) run[1], (Long) run[2], (Long) run[3]);
            System.out.printf("   %-46s %7.1f%% %,9.0f %9.1f s %9.1f s %9.2f%n", run[0], m[0], m[1], m[2] / 1000, m[3] / 1000, m[4]);
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < 5; i++) stream(es, truth, false, 5_000L, 1_800_000L);
        double eps = 5 * es.size() / ((System.nanoTime() - t0) / 1e9);
        System.out.printf("   Kappa-style reprocessing: replaying the 6 h log through the job runs at %,.0f events/s on one core (%.1f s for the evening)%n",
                eps, es.size() / eps);
    }
}
