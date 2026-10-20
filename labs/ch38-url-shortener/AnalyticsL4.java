// AnalyticsL4.java — run with (PostgreSQL 16 on :5581, db "shop"):
//   java -cp postgresql-42.7.4.jar AnalyticsL4.java
// COUNTING CLICKS WITHOUT HURTING REDIRECTS. Marketing wants clicks and UNIQUE visitors per link, per campaign, in near
// real time. Part 1 measures three ways to count 40,000 clicks against real PostgreSQL with 32 concurrent writers:
//   A) UPDATE the link's counter on every click — clicks spread over 1,000 links
//   B) the same, but every click is on ONE campaign link (a single hot row)
//   C) append clicks to an in-memory buffer; a flusher aggregates per code and upserts once a second (micro-batching)
// Part 2 counts unique visitors: exact (a HashSet of device ids) vs a HyperLogLog sketch implemented below (p = 14,
// 16,384 registers), including MERGING the sketches of 20 redirect nodes — something exact sets can't do cheaply.
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

public class AnalyticsL4 {
    static final String DB = "jdbc:postgresql://127.0.0.1:5581/shop?user=claude";

    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection(DB); Statement s = c.createStatement()) {
            s.execute("drop table if exists link_stats"); s.execute("create table link_stats(code text primary key, clicks bigint not null)");
            s.execute("insert into link_stats select 'L' || g, 0 from generate_series(0, 999) g");
        }
        int clicks = 40_000, writers = 32;
        System.out.printf("Part 1 — %,d clicks, %d concurrent writers, PostgreSQL 16 with durable commits (fsync on)%n%n", clicks, writers);
        System.out.printf("   %-52s %10s %22s %14s %10s%n", "strategy", "clicks/s", "p99 cost on redirect", "DB statements", "counted");
        run("A) UPDATE per click, spread over 1,000 links", clicks, writers, false);
        run("B) UPDATE per click, ONE hot campaign link", clicks, writers, true);
        batched(clicks, writers);

        // ---------------- Part 2: unique visitors ----------------
        int nodes = 20, events = 3_000_000; Random rnd = new Random(38);
        long[] devicePool = new long[900_000]; for (int i = 0; i < devicePool.length; i++) devicePool[i] = rnd.nextLong();
        Set<Long> exact = new HashSet<>(); HLL[] perNode = new HLL[nodes]; for (int i = 0; i < nodes; i++) perNode[i] = new HLL(14);
        for (int i = 0; i < events; i++) {
            long dev = devicePool[(int) Math.min(devicePool.length - 1, Math.abs(rnd.nextGaussian()) * 300_000)];   // repeat visitors
            exact.add(dev); perNode[rnd.nextInt(nodes)].add(dev);                                                   // any node may serve a click
        }
        HLL merged = new HLL(14); for (HLL h : perNode) merged.merge(h);
        long sumOfNodes = 0; for (HLL h : perNode) sumOfNodes += Math.round(h.estimate());
        long est = Math.round(merged.estimate());
        System.out.printf("%nPart 2 — %,d clicks from repeat visitors across %d redirect nodes%n%n", events, nodes);
        System.out.printf("   %-46s %,14d %20s%n", "exact unique devices (HashSet)", exact.size(), "~" + (exact.size() * 56L / 1_000_000) + " MB of set entries");
        System.out.printf("   %-46s %,14d %20s   error %+.2f%%%n", "HyperLogLog, 20 node sketches merged", est, "16 KB per sketch", 100.0 * (est - exact.size()) / exact.size());
        System.out.printf("   %-46s %,14d %20s   error %+.0f%%%n", "adding the 20 per-node unique counts instead", sumOfNodes, "", 100.0 * (sumOfNodes - exact.size()) / exact.size());
        System.out.printf("   HLL standard error at p=14: 1.04/√16384 = %.2f%%%n", 104.0 / Math.sqrt(16384));

        System.out.println("\n   → counting with a synchronous UPDATE puts a row lock on the redirect's critical path; it is tolerable when");
        System.out.println("     clicks spread out and collapses onto one lock exactly when a campaign makes one link hot (several times slower,");
        System.out.println("     with a far worse p99). Micro-batching makes the redirect's cost a memory increment and turns 40,000 increments into one upsert");
        System.out.println("     per link per second (20x fewer statements) — take clicks off the redirect path (in production: a");
        System.out.println("     Kafka topic + stream aggregation, Ch 14), accepting counts that are a second or so behind. Unique visitors");
        System.out.println("     don't add up across nodes or days; HyperLogLog sketches do merge, in kilobytes, within about 1%.");
        System.exit(0);
    }

    static void run(String label, int clicks, int writers, boolean hot) throws Exception {
        try (Connection c = DriverManager.getConnection(DB)) { c.createStatement().execute("update link_stats set clicks = 0"); }
        AtomicInteger next = new AtomicInteger(); AtomicLong stmts = new AtomicLong(); long[] lat = new long[clicks];
        long t0 = System.nanoTime();
        try (var exec = Executors.newFixedThreadPool(writers)) {
            for (int w = 0; w < writers; w++) exec.submit(() -> {
                try (Connection c = DriverManager.getConnection(DB); PreparedStatement ps = c.prepareStatement("update link_stats set clicks = clicks + 1 where code = ?")) {
                    int i;
                    while ((i = next.getAndIncrement()) < clicks) { long s0 = System.nanoTime(); ps.setString(1, hot ? "L7" : "L" + (i * 7919 % 1000)); ps.executeUpdate(); lat[i] = System.nanoTime() - s0; stmts.incrementAndGet(); }
                } catch (SQLException e) { throw new RuntimeException(e); }
                return null;
            });
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        Arrays.sort(lat);
        System.out.printf("   %-52s %,10.0f %19.2f ms %,14d %,10d%n", label, clicks / secs, lat[(int) (clicks * 0.99)] / 1e6, stmts.get(), total());
    }

    static void batched(int clicks, int writers) throws Exception {
        try (Connection c = DriverManager.getConnection(DB)) { c.createStatement().execute("update link_stats set clicks = 0"); }
        ConcurrentHashMap<String, LongAdder> buffer = new ConcurrentHashMap<>(); AtomicLong stmts = new AtomicLong();
        Connection fc = DriverManager.getConnection(DB);
        Runnable flush = () -> {
            try {
                Map<String, Long> snap = new TreeMap<>();
                for (String k : buffer.keySet()) { long v = buffer.get(k).sumThenReset(); if (v > 0) snap.put(k, v); }
                if (snap.isEmpty()) return;
                try (PreparedStatement ps = fc.prepareStatement("insert into link_stats values (?, ?) on conflict (code) do update set clicks = link_stats.clicks + excluded.clicks")) {
                    for (var e : snap.entrySet()) { ps.setString(1, e.getKey()); ps.setLong(2, e.getValue()); ps.addBatch(); }
                    ps.executeBatch(); stmts.addAndGet(snap.size());
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
        };
        ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor();
        flusher.scheduleAtFixedRate(flush, 1, 1, TimeUnit.SECONDS);
        // clicks arrive at a steady 10,000/s for 4 s (as they would from redirect traffic), so the flusher runs several times
        AtomicInteger next = new AtomicInteger(); long[] lat = new long[clicks]; long t0 = System.nanoTime(); double pace = 10_000;
        try (var exec = Executors.newFixedThreadPool(writers)) {
            for (int w = 0; w < writers; w++) exec.submit(() -> {
                int i;
                while ((i = next.getAndIncrement()) < clicks) {
                    long due = t0 + (long) (i / pace * 1e9); long wait = due - System.nanoTime();
                    if (wait > 0) LockSupport.parkNanos(wait);
                    long s0 = System.nanoTime();
                    String code = i % 2 == 0 ? "L7" : "L" + (i * 7919 % 1000);           // half the clicks on the hot link
                    buffer.computeIfAbsent(code, k -> new LongAdder()).increment();
                    lat[i] = System.nanoTime() - s0;
                }
            });
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        flusher.shutdown(); flusher.awaitTermination(5, TimeUnit.SECONDS); flush.run();   // final flush on shutdown
        Arrays.sort(lat);
        System.out.printf("   %-52s %,10.0f %19.4f ms %,14d %,10d%n", "C) buffer + 1 s micro-batch upsert (half on hot link)", clicks / secs, lat[(int) (clicks * 0.99)] / 1e6, stmts.get(), total());
        System.out.println("      (C is paced at 10,000 clicks/s — its limit is the redirect traffic, not the counter)");
        fc.close();
    }

    static long total() throws SQLException {
        try (Connection c = DriverManager.getConnection(DB); ResultSet rs = c.createStatement().executeQuery("select sum(clicks) from link_stats")) { rs.next(); return rs.getLong(1); }
    }

    // ---------------- HyperLogLog ----------------
    static final class HLL {
        final int p, m; final byte[] reg;
        HLL(int p) { this.p = p; this.m = 1 << p; this.reg = new byte[m]; }
        static long mix(long z) { z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL; z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L; return z ^ (z >>> 33); }
        void add(long item) {
            long h = mix(item); int idx = (int) (h >>> (64 - p)); long w = h << p;
            byte rho = (byte) (w == 0 ? 64 - p + 1 : Long.numberOfLeadingZeros(w) + 1);
            if (rho > reg[idx]) reg[idx] = rho;
        }
        void merge(HLL o) { for (int i = 0; i < m; i++) if (o.reg[i] > reg[i]) reg[i] = o.reg[i]; }
        double estimate() {
            double sum = 0; int zeros = 0;
            for (byte r : reg) { sum += Math.pow(2, -r); if (r == 0) zeros++; }
            double alpha = 0.7213 / (1 + 1.079 / m), e = alpha * m * m / sum;
            return (e <= 2.5 * m && zeros > 0) ? m * Math.log((double) m / zeros) : e;   // small-range correction
        }
    }
}
