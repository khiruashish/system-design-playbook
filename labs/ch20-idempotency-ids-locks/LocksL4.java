// LocksL4.java — run with: java -Duser.timezone=UTC -cp /tmp/pgjdbc.jar LocksL4.java
// 8 workers each add 1 to a store's settlement counter 150 times with a read-modify-write on real PostgreSQL (:5542),
// guarded by different locks. Redis (:6390) locks have a 100 ms TTL, and 3 % of critical sections suffer a 150 ms
// "stop-the-world pause" (GC, VM steal) between the read and the write: longer than the TTL.
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class LocksL4 {
    static final String PG = "jdbc:postgresql://localhost:5542/shop?user=postgres";
    static final int WORKERS = 8, INCREMENTS = 150, TTL_MS = 100, PAUSE_MS = 150;
    static final double PAUSE_P = 0.03;

    static final AtomicInteger foreignDeletes = new AtomicInteger(), staleRejected = new AtomicInteger(), overlaps = new AtomicInteger();
    static final AtomicInteger holders = new AtomicInteger();

    // ---- minimal RESP client ----
    static class Redis implements Closeable {
        final Socket s; final OutputStream out; final BufferedReader in;
        Redis() throws IOException { s = new Socket("localhost", 6390); s.setTcpNoDelay(true); out = s.getOutputStream(); in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)); }
        String cmd(String... a) throws IOException {
            var sb = new StringBuilder("*").append(a.length).append("\r\n");
            for (String x : a) sb.append('$').append(x.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(x).append("\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8)); out.flush();
            String line = in.readLine();
            if (line.startsWith("$-1") || line.startsWith("*-1")) return null;
            if (line.startsWith("$")) return in.readLine();
            if (line.startsWith("-")) throw new IOException(line);
            return line.substring(1);
        }
        public void close() throws IOException { s.close(); }
    }

    static final String ACQUIRE_WITH_TOKEN =        // SET NX PX and, only if acquired, INCR the fencing counter, atomically
            "if redis.call('set', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then return redis.call('incr', KEYS[2]) else return 0 end";
    static final String RELEASE_IF_OWNER =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    enum Scheme {
        NO_LOCK("no lock"),
        REDIS_DEL("Redis TTL lock, DEL release"),
        REDIS_CAD("Redis TTL lock, owner-checked release"),
        FENCE_WRITE("+ fencing token checked on write"),
        FENCE_CLAIM("+ fencing token claimed at read"),
        PG_ADVISORY("PostgreSQL advisory xact lock"),
        CAS("no lock, optimistic CAS + retry");
        final String label; Scheme(String l) { label = l; }
    }

    static void pauseMaybe() throws InterruptedException { if (ThreadLocalRandom.current().nextDouble() < PAUSE_P) Thread.sleep(PAUSE_MS); }

    static long read(Connection c) throws SQLException {
        try (var st = c.createStatement(); var rs = st.executeQuery("select value from counter where id = 1")) { rs.next(); return rs.getLong(1); }
    }
    static void write(Connection c, long v) throws SQLException {
        try (var ps = c.prepareStatement("update counter set value = ? where id = 1")) { ps.setLong(1, v); ps.executeUpdate(); }
    }

    /** One increment under the given scheme; returns when it has been applied (or believed applied). */
    static void increment(Scheme scheme, Connection c, Redis r, String me) throws Exception {
        switch (scheme) {
            case NO_LOCK -> { long v = read(c); pauseMaybe(); write(c, v + 1); }

            case REDIS_DEL, REDIS_CAD -> {
                while (r.cmd("SET", "lock:store-17", me, "NX", "PX", String.valueOf(TTL_MS)) == null) Thread.sleep(1);
                if (holders.incrementAndGet() > 1) overlaps.incrementAndGet();
                try { long v = read(c); pauseMaybe(); write(c, v + 1); }
                finally {
                    holders.decrementAndGet();
                    if (scheme == Scheme.REDIS_DEL) {
                        String owner = r.cmd("GET", "lock:store-17");                 // (only to count the damage)
                        if (owner != null && !owner.equals(me)) foreignDeletes.incrementAndGet();
                        r.cmd("DEL", "lock:store-17");
                    } else r.cmd("EVAL", RELEASE_IF_OWNER, "1", "lock:store-17", me);
                }
            }

            case FENCE_WRITE, FENCE_CLAIM -> {
                while (true) {
                    long token;
                    while ((token = Long.parseLong(r.cmd("EVAL", ACQUIRE_WITH_TOKEN, "2", "lock:store-17", "fence:store-17", me, String.valueOf(TTL_MS)))) == 0) Thread.sleep(1);
                    if (holders.incrementAndGet() > 1) overlaps.incrementAndGet();
                    try {
                        long v; int applied;
                        if (scheme == Scheme.FENCE_WRITE) {
                            v = read(c); pauseMaybe();
                            try (var ps = c.prepareStatement("update counter set value = ?, fence = ? where id = 1 and fence < ?")) {
                                ps.setLong(1, v + 1); ps.setLong(2, token); ps.setLong(3, token); applied = ps.executeUpdate();
                            }
                        } else {
                            // claim the resource with our token before reading: older holders can no longer write
                            try (var ps = c.prepareStatement("update counter set fence = ? where id = 1 and fence < ? returning value")) {
                                ps.setLong(1, token); ps.setLong(2, token); var rs = ps.executeQuery();
                                if (!rs.next()) { staleRejected.incrementAndGet(); continue; }
                                v = rs.getLong(1);
                            }
                            pauseMaybe();
                            try (var ps = c.prepareStatement("update counter set value = ? where id = 1 and fence = ?")) {
                                ps.setLong(1, v + 1); ps.setLong(2, token); applied = ps.executeUpdate();
                            }
                        }
                        if (applied == 1) return;
                        staleRejected.incrementAndGet();                                // our lease was superseded: retry
                    } finally {
                        holders.decrementAndGet();
                        r.cmd("EVAL", RELEASE_IF_OWNER, "1", "lock:store-17", me);
                    }
                }
            }

            case PG_ADVISORY -> {
                c.setAutoCommit(false);
                try (var st = c.createStatement()) {
                    st.execute("select pg_advisory_xact_lock(17)");                     // held until commit/rollback, no TTL
                    if (holders.incrementAndGet() > 1) overlaps.incrementAndGet();
                    long v = read(c); pauseMaybe(); write(c, v + 1);
                    holders.decrementAndGet();
                    c.commit();
                } catch (Exception e) { c.rollback(); throw e; }
                finally { c.setAutoCommit(true); }
            }

            case CAS -> {
                while (true) {
                    long v = read(c); pauseMaybe();
                    try (var ps = c.prepareStatement("update counter set value = ? where id = 1 and value = ?")) {
                        ps.setLong(1, v + 1); ps.setLong(2, v);
                        if (ps.executeUpdate() == 1) return;
                    }
                    staleRejected.incrementAndGet();
                }
            }
        }
    }

    static int fencedWrite(Connection c, long v, long token, String cond) throws SQLException {
        try (var ps = c.prepareStatement("update counter set value = ?, fence = greatest(fence, ?) where id = 1 and " + cond)) {
            ps.setLong(1, v); ps.setLong(2, token); ps.setLong(3, token); return ps.executeUpdate();
        }
    }
    static long claim(Connection c, long token) throws SQLException {
        try (var ps = c.prepareStatement("update counter set fence = ? where id = 1 and fence < ? returning value")) {
            ps.setLong(1, token); ps.setLong(2, token); var rs = ps.executeQuery(); rs.next(); return rs.getLong(1);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.printf("%d workers × %d increments (expected %,d); lock TTL %d ms; %.0f %% of critical sections pause %d ms%n",
                WORKERS, INCREMENTS, WORKERS * INCREMENTS, TTL_MS, PAUSE_P * 100, PAUSE_MS);
        System.out.printf("   %-38s %7s %6s %9s %8s %9s %8s%n", "scheme", "final", "lost", "2 holders", "foreign", "rejected", "time");
        System.out.printf("   %-38s %7s %6s %9s %8s %9s %8s%n", "", "value", "", "at once", "unlocks", "& retried", "");
        for (Scheme scheme : Scheme.values()) {
            try (Connection admin = DriverManager.getConnection(PG); var st = admin.createStatement(); Redis r = new Redis()) {
                st.execute("drop table if exists counter");
                st.execute("create table counter(id int primary key, value bigint not null, fence bigint not null default 0)");
                st.execute("insert into counter values (1, 0, 0)");
                r.cmd("DEL", "lock:store-17", "fence:store-17");
            }
            foreignDeletes.set(0); staleRejected.set(0); overlaps.set(0); holders.set(0);
            long t0 = System.nanoTime();
            try (var exec = Executors.newFixedThreadPool(WORKERS)) {
                for (int w = 0; w < WORKERS; w++) {
                    String me = "worker-" + w + "-" + UUID.randomUUID();
                    exec.submit(() -> {
                        try (Connection c = DriverManager.getConnection(PG); Redis r = new Redis()) {
                            for (int i = 0; i < INCREMENTS; i++) increment(scheme, c, r, me);
                        }
                        return null;
                    });
                }
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            long v; try (Connection c = DriverManager.getConnection(PG)) { v = read(c); }
            System.out.printf("   %-38s %,7d %,6d %9d %8d %9d %,5d ms%n", scheme.label, v, WORKERS * INCREMENTS - v,
                    overlaps.get(), foreignDeletes.get(), staleRejected.get(), ms);
        }

        // Scripted interleaving: A (token 5) pauses after reading; its lease expires; B (token 6) reads;
        // A wakes and writes; then B writes. Random pauses rarely hit this window, so it is forced here.
        for (boolean claimAtRead : List.of(false, true)) {
            try (Connection a = DriverManager.getConnection(PG); Connection b = DriverManager.getConnection(PG); var st = a.createStatement()) {
                st.execute("update counter set value = 0, fence = 4 where id = 1");
                long va, vb; int aApplied, bApplied; boolean retried = false;
                if (!claimAtRead) {
                    va = read(a);                                                             // A reads 0 ... pause, lease expires
                    vb = read(b);                                                             // B (token 6) reads 0
                    aApplied = fencedWrite(a, va + 1, 5, "fence < ?");                        // A wakes: 4 < 5, accepted
                    bApplied = fencedWrite(b, vb + 1, 6, "fence < ?");                        // B: 5 < 6, accepted
                } else {
                    va = claim(a, 5);                                                         // A claims with 5, reads 0 ... pause
                    vb = claim(b, 6);                                                         // B claims with 6, reads 0
                    aApplied = fencedWrite(a, va + 1, 5, "fence = ?");                        // A wakes: fence is 6, rejected
                    bApplied = fencedWrite(b, vb + 1, 6, "fence = ?");                        // B: accepted
                    if (aApplied == 0) { long v = claim(a, 7); retried = fencedWrite(a, v + 1, 7, "fence = ?") == 1; }   // A retries with a new lease
                }
                System.out.printf("scripted pause, fencing token %-17s: A's stale write %s, B's write %s%s → value %d after 2 increments%n",
                        claimAtRead ? "claimed at read" : "checked on write", aApplied == 1 ? "accepted" : "rejected",
                        bApplied == 1 ? "accepted" : "rejected", retried ? ", A retried with token 7" : "", read(a));
            }
        }

        // A lock without a TTL and a holder that crashes
        try (Redis a = new Redis(); Redis b = new Redis()) {
            a.cmd("DEL", "lock:settlement");
            a.cmd("SET", "lock:settlement", "worker-a", "NX");                       // no TTL
            a.close();                                                                 // worker-a crashes while holding it
            long t0 = System.nanoTime(); boolean got = false;
            while (System.nanoTime() - t0 < 3_000_000_000L) { if (b.cmd("SET", "lock:settlement", "worker-b", "NX") != null) { got = true; break; } Thread.sleep(10); }
            System.out.printf("SET NX without TTL, holder crashed: worker-b acquired within 3 s: %s; key TTL: %s (held forever)%n", got, b.cmd("PTTL", "lock:settlement"));
        }
    }
}
