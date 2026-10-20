// IdsL3.java — run with: java -Duser.timezone=UTC -cp /tmp/pgjdbc.jar IdsL3.java
// Part A: 2,000,000 order rows into real PostgreSQL 16 (:5542) with four primary-key schemes; measures insert time,
//         primary-key index size and leaf density (pgstattuple), and WAL written.
// Part B: properties of the generators: Snowflake uniqueness across threads, clock moving backwards, time ordering,
//         and how soon short random "order codes" collide.
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;

public class IdsL3 {

    // ---------- generators ----------
    static final SecureRandom SR = new SecureRandom();

    /** UUIDv7 (RFC 9562): 48-bit Unix ms timestamp, version 7, 12-bit counter (monotonic within a ms, method 1), variant, 62 random bits. */
    static long v7LastMs = -1, v7Counter;
    static synchronized UUID uuidV7() {
        long ms = System.currentTimeMillis();
        if (ms > v7LastMs) { v7LastMs = ms; v7Counter = SR.nextInt(1 << 11); }       // random start, headroom to count up
        else if (++v7Counter > 0xFFF) { v7LastMs++; v7Counter = 0; }                  // counter full: borrow the next ms
        long hi = (v7LastMs << 16) | 0x7000L | v7Counter;
        long lo = (SR.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(hi, lo);
    }

    /** Snowflake: 41 bits ms since a custom epoch | 10 bits worker id | 12 bits sequence. */
    static final class Snowflake {
        static final long EPOCH = 1_735_689_600_000L;                 // 2025-01-01T00:00:00Z
        final long worker; final LongSupplier clock; final boolean guardClock;
        long lastMs = -1, seq;
        Snowflake(long worker, LongSupplier clock, boolean guardClock) { this.worker = worker; this.clock = clock; this.guardClock = guardClock; }
        synchronized long next() {
            long ms = clock.getAsLong();
            if (ms < lastMs) {
                if (guardClock) { while ((ms = clock.getAsLong()) < lastMs) Thread.onSpinWait(); }   // wait out the rollback (or throw if large)
            }
            if (ms == lastMs) {
                seq = (seq + 1) & 0xFFF;
                if (seq == 0) { while ((ms = clock.getAsLong()) <= lastMs) Thread.onSpinWait(); }     // 4,096 ids used up in this ms
            } else seq = 0;
            lastMs = ms;
            return ((ms - EPOCH) << 22) | (worker << 12) | seq;
        }
    }

    // ---------- Part A ----------
    static void partA() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5542/shop?user=postgres&reWriteBatchedInserts=true")) {
            try (var st = c.createStatement()) { st.execute("create extension if not exists pgstattuple"); }
            int rows = Integer.getInteger("rows", 2_000_000), W = Integer.getInteger("writers", 8);
            System.out.printf("A. %,d orders inserted in batches of 1,000 (writers: %d), PostgreSQL 16%n", rows, W);
            System.out.printf("   %-22s %9s %11s %13s %14s%n", "primary key", "insert", "pk index", "leaf density", "WAL written");
            Snowflake sf = new Snowflake(1, System::currentTimeMillis, true);
            for (String scheme : List.of("bigint sequence", "Snowflake bigint", "UUIDv7", "UUIDv4 (random)")) {
                String type = scheme.startsWith("UUID") ? "uuid" : "bigint";
                try (var st = c.createStatement()) {
                    st.execute("drop table if exists orders_ids");
                    st.execute("create table orders_ids(id " + type + (scheme.equals("bigint sequence") ? " generated always as identity" : "")
                            + " primary key, customer_id bigint not null, total_paise bigint not null, created_at timestamptz not null default now())");
                    st.execute("checkpoint");
                }
                long walStart = lsn(c);
                long t0 = System.nanoTime();
                try (var exec = Executors.newFixedThreadPool(W)) {
                    List<Future<?>> fs = new ArrayList<>();
                    for (int t = 0; t < W; t++) fs.add(exec.submit(() -> {
                        try (Connection w = DriverManager.getConnection("jdbc:postgresql://localhost:5542/shop?user=postgres&reWriteBatchedInserts=true")) {
                            w.setAutoCommit(false);
                            String sql = scheme.equals("bigint sequence")
                                    ? "insert into orders_ids(customer_id, total_paise) values (?, ?)"
                                    : "insert into orders_ids(id, customer_id, total_paise) values (?, ?, ?)";
                            try (var ps = w.prepareStatement(sql)) {
                                for (int i = 0; i < rows / W; i++) {
                                    int p = 1;
                                    switch (scheme) {
                                        case "Snowflake bigint" -> ps.setLong(p++, sf.next());
                                        case "UUIDv7" -> ps.setObject(p++, uuidV7());
                                        case "UUIDv4 (random)" -> ps.setObject(p++, UUID.randomUUID());
                                        default -> { }
                                    }
                                    ps.setLong(p++, ThreadLocalRandom.current().nextLong(1_000_000));
                                    ps.setLong(p, 10_000 + ThreadLocalRandom.current().nextLong(500_000));
                                    ps.addBatch();
                                    if (i % 1_000 == 999) { ps.executeBatch(); w.commit(); }
                                }
                                ps.executeBatch(); w.commit();
                            }
                        }
                        return null;
                    }));
                    for (var f : fs) f.get();
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                long wal = lsn(c) - walStart;
                try (var st = c.createStatement();
                     var rs = st.executeQuery("select pg_relation_size('orders_ids_pkey'), (pgstatindex('orders_ids_pkey')).avg_leaf_density")) {
                    rs.next();
                    System.out.printf("   %-22s %,7d ms %,8d MB %12.0f %% %,11d MB%n", scheme, ms, rs.getLong(1) >> 20, rs.getDouble(2), wal >> 20);
                }
            }
        }
    }

    static long lsn(Connection c) throws SQLException {
        try (var st = c.createStatement(); var rs = st.executeQuery("select pg_current_wal_lsn() - '0/0'::pg_lsn")) { rs.next(); return rs.getLong(1); }
    }

    // ---------- Part B ----------
    static void partB() throws Exception {
        System.out.println("B. Generator properties");
        // B1: Snowflake from 8 threads, one generator per worker id vs a shared worker id on two "pods"
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        AtomicLong dups = new AtomicLong();
        Snowflake[] pods = { new Snowflake(1, System::currentTimeMillis, true), new Snowflake(2, System::currentTimeMillis, true) };
        long t0 = System.nanoTime();
        try (var exec = Executors.newFixedThreadPool(8)) {
            for (int t = 0; t < 8; t++) { int tt = t; exec.submit(() -> { for (int i = 0; i < 250_000; i++) if (!ids.add(pods[tt % 2].next())) dups.incrementAndGet(); }); }
        }
        double perSec = 2_000_000 / ((System.nanoTime() - t0) / 1e9);
        System.out.printf("   Snowflake, 2 pods with distinct worker ids, 8 threads: %,d ids, %d duplicates, %,.0f ids/s%n", ids.size(), dups.get(), perSec);

        ids.clear(); dups.set(0);
        Snowflake[] sameId = { new Snowflake(7, System::currentTimeMillis, true), new Snowflake(7, System::currentTimeMillis, true) };
        try (var exec = Executors.newFixedThreadPool(2)) {
            for (int t = 0; t < 2; t++) { int tt = t; exec.submit(() -> { for (int i = 0; i < 500_000; i++) if (!ids.add(sameId[tt].next())) dups.incrementAndGet(); }); }
        }
        System.out.printf("   Snowflake, 2 pods given the SAME worker id:          %,d ids, %,d duplicates%n", ids.size() + dups.get(), dups.get());

        // B2: clock moves back 5 ms (NTP step) while generating
        for (boolean guard : List.of(false, true)) {
            AtomicLong fakeNow = new AtomicLong(Snowflake.EPOCH + 1_000_000);
            Snowflake g = new Snowflake(3, fakeNow::get, guard);
            Set<Long> seen = new HashSet<>(); int dup = 0;
            for (int i = 0; i < 40_000; i++) {
                if (i % 2_000 == 1_999) fakeNow.addAndGet(1);                             // time advances 1 ms per 2,000 ids
                if (i == 20_000) {                                                        // NTP steps the clock back 5 ms
                    fakeNow.addAndGet(-5);
                    if (guard) { long target = fakeNow.get() + 5; new Thread(() -> { sleep(20); fakeNow.set(target); }).start(); }
                }
                if (!seen.add(g.next())) dup++;
            }
            System.out.printf("   Snowflake, clock stepped back 5 ms, %s: %,d duplicates%n", guard ? "guarded (wait until last ms)" : "unguarded                   ", dup);
        }

        // B3: ordering: fraction of consecutive ids (generated 1 µs apart) that sort in creation order
        List<UUID> v4 = new ArrayList<>(), v7 = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) { v4.add(UUID.randomUUID()); v7.add(uuidV7()); if (i % 100 == 0) sleep(0); }
        System.out.printf("   sorted in creation order (consecutive pairs): UUIDv4 %.0f %%, UUIDv7 (monotonic counter) %.0f %%%n",
                orderedPct(v4), orderedPct(v7));

        // B4: short random public order codes: when does the first collision happen?
        for (int bits : List.of(30, 40)) {
            int trials = 20; long sum = 0;
            for (int t = 0; t < trials; t++) {
                Set<Long> s = new HashSet<>(); long n = 0;
                while (s.add(SR.nextLong() & ((1L << bits) - 1))) n++;
                sum += n;
            }
            System.out.printf("   random %d-bit order code (%d base32 chars): first collision after %,d codes on average (√(π/2·2^%d) ≈ %,.0f)%n",
                    bits, bits / 5, sum / trials, bits, Math.sqrt(Math.PI / 2 * Math.pow(2, bits)));
        }
    }

    static double orderedPct(List<UUID> l) {
        int ok = 0;
        for (int i = 1; i < l.size(); i++) if (compareUnsigned(l.get(i - 1), l.get(i)) < 0) ok++;
        return 100.0 * ok / (l.size() - 1);
    }
    static int compareUnsigned(UUID a, UUID b) {         // byte order, as PostgreSQL compares uuid
        int c = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return c != 0 ? c : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { throw new RuntimeException(e); } }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("A")) partA();
        if (args.length == 0 || args[0].equals("B")) partB();
    }
}
