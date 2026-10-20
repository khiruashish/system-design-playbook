// LeaderLockL1.java — run with (Redis on :6390, PostgreSQL on :5581 db shop):
//   java -Duser.timezone=UTC -cp /tmp/eslab/postgresql-42.7.4.jar LeaderLockL1.java   (JDK 21)
// "RUN THIS JOB ONCE EVERY TICK, ON ONE OF FIVE NODES." The job is an incremental batch: read a cursor from PostgreSQL,
// process the next 100 events (insert them into `processed`), advance the cursor. Every event must be processed exactly
// once. Five node threads tick every 300 ms (clocks skewed 0–60 ms); the job takes 60–120 ms; with 8% probability a node
// freezes for 400–900 ms mid-job (a GC pause, a VM steal, a slow disk) — longer than the lock's lifetime. REAL Redis locks,
// REAL PostgreSQL writes. Four strategies:
//   S1 no coordination: every node runs the job every tick (the "@Scheduled on every pod" bug)
//   S2 Redis SET lock NX PX 500; release with DEL when done (no owner check)
//   S3 S2 with an owner token: release only if the lock is still ours (Lua compare-and-delete)
//   S4 S3 + a fencing token: INCR on acquire; the job first CLAIMS the cursor row with its token (UPDATE ... SET fence =
//      token WHERE fence < token RETURNING pos) and later writes only if the fence is still its own; otherwise the
//      batch rolls back. (A first version checked the token only on the write — see the chapter for why that failed.)
// Measured over 100 ticks: job runs, runs that overlapped another run, events processed more than once, cursor moved
// backwards, and batches rejected by the fence.
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class LeaderLockL1 {
    static final String URL = "jdbc:postgresql://localhost:5581/shop?user=claude";
    static final int NODES = 5, TICKS = 100, TICK_MS = 300;
    static final String RELEASE = "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end";

    public static void main(String[] args) throws Exception {
        System.out.println("5 nodes, 100 ticks of 300 ms, job 60–120 ms, 8% of runs freeze 400–900 ms; lock TTL 500 ms. Real Redis + PostgreSQL.\n");
        System.out.printf("   %-58s %6s %11s %14s %12s %11s%n", "strategy", "runs", "overlapping", "events done", "cursor went", "fenced off");
        System.out.printf("   %-58s %6s %11s %14s %12s %11s%n", "", "", "runs", "more than once", "backwards", "");
        String[] names = {"S1) every node runs every tick", "S2) Redis lock, release with plain DEL", "S3) Redis lock with owner token (compare-and-delete)", "S4) S3 + fencing token claimed on read, checked on write"};
        for (int s = 0; s < 4; s++) run(s, names[s]);
        System.out.println("\n   → a lock with a timeout is a lease, and a paused process doesn't know its lease ran out. When it wakes it");
        System.out.println("     finishes a batch built on a cursor it read long ago, and the events are processed twice. A plain DEL can also");
        System.out.println("     delete the NEXT holder's lock; an owner token stops that, but not the stale write. What closes the gap is a check");
        System.out.println("     at the resource: a monotonically increasing fencing token the database compares — on the READ as well as the");
        System.out.println("     write, or a new holder can read before the old one's late write lands. (Or make the work idempotent, e.g. a");
        System.out.println("     unique key per event, so duplicates bounce.)");
    }

    static void run(int strategy, String name) throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS processed, job_cursor");
            st.execute("CREATE TABLE processed (event_id bigint NOT NULL, node int, ts timestamptz DEFAULT clock_timestamp())");
            st.execute("CREATE TABLE job_cursor (id int PRIMARY KEY, pos bigint NOT NULL, fence bigint NOT NULL)");
            st.execute("INSERT INTO job_cursor VALUES (1, 0, 0)");
        }
        try (Redis r = new Redis()) { r.cmd("DEL", "lock:batch", "fence:batch"); }
        AtomicInteger runs = new AtomicInteger(), overlapping = new AtomicInteger(), regress = new AtomicInteger(), fenced = new AtomicInteger(), active = new AtomicInteger();
        long start = System.currentTimeMillis() + 200;
        ExecutorService ex = Executors.newFixedThreadPool(NODES); List<Future<?>> fs = new ArrayList<>();
        for (int n = 0; n < NODES; n++) { final int node = n; fs.add(ex.submit(() -> {
            Random rnd = new Random(50 + node * 7919L); long skew = rnd.nextInt(61);
            try (Connection c = DriverManager.getConnection(URL); Redis r = new Redis()) {
                for (int t = 0; t < TICKS; t++) {
                    long at = start + (long) t * TICK_MS + skew; long now = System.currentTimeMillis(); if (at > now) Thread.sleep(at - now);
                    String owner = "n" + node + "-" + t; long token = 0;
                    if (strategy >= 1) {
                        Object ok = r.cmd("SET", "lock:batch", owner, "NX", "PX", "500");
                        if (ok == null) continue;
                        if (strategy == 3) token = (Long) r.cmd("INCR", "fence:batch");
                    }
                    runs.incrementAndGet(); if (active.incrementAndGet() > 1) overlapping.incrementAndGet();
                    try {
                        long pos;
                        if (strategy == 3) {                                                           // fence the READ: claim the cursor with our token first
                            try (PreparedStatement p = c.prepareStatement("UPDATE job_cursor SET fence = ? WHERE id = 1 AND fence < ? RETURNING pos")) {
                                p.setLong(1, token); p.setLong(2, token); try (ResultSet rs = p.executeQuery()) { if (!rs.next()) { fenced.incrementAndGet(); continue; } pos = rs.getLong(1); }
                            }
                        } else try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT pos FROM job_cursor WHERE id = 1")) { rs.next(); pos = rs.getLong(1); }
                        Thread.sleep(60 + rnd.nextInt(61));
                        if (rnd.nextDouble() < 0.08) Thread.sleep(400 + rnd.nextInt(501));          // the freeze
                        c.setAutoCommit(false);
                        try (PreparedStatement p = c.prepareStatement("INSERT INTO processed (event_id, node) SELECT g, ? FROM generate_series(?::bigint, ?::bigint) g")) {
                            p.setInt(1, node); p.setLong(2, pos + 1); p.setLong(3, pos + 100); p.executeUpdate();
                        }
                        int updated;
                        try (PreparedStatement p = c.prepareStatement(strategy == 3 ? "UPDATE job_cursor SET pos = ? WHERE id = 1 AND fence = ? AND ? > 0" : "UPDATE job_cursor SET pos = ? WHERE id = 1 RETURNING (SELECT pos FROM job_cursor WHERE id = 1)")) {
                            p.setLong(1, pos + 100); if (strategy == 3) { p.setLong(2, token); p.setLong(3, token); updated = p.executeUpdate(); }
                            else { try (ResultSet rs = p.executeQuery()) { rs.next(); if (rs.getLong(1) > pos) regress.incrementAndGet(); updated = 1; } }
                        }
                        if (updated == 0) { c.rollback(); fenced.incrementAndGet(); } else c.commit();
                        c.setAutoCommit(true);
                    } finally {
                        active.decrementAndGet();
                        if (strategy == 1) r.cmd("DEL", "lock:batch");
                        else if (strategy >= 2) r.cmd("EVAL", RELEASE, "1", "lock:batch", owner);
                    }
                }
            }
            return null; })); }
        for (Future<?> f : fs) f.get(); ex.shutdown();
        long dup;
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*) - count(DISTINCT event_id) FROM processed")) { rs.next(); dup = rs.getLong(1); }
        System.out.printf("   %-58s %6d %11d %,14d %12d %11d%n", name, runs.get(), overlapping.get(), dup, regress.get(), fenced.get());
    }

    static final class Redis implements Closeable {
        final Socket s; final OutputStream out; final InputStream in;
        Redis() throws IOException { s = new Socket("127.0.0.1", 6390); s.setTcpNoDelay(true); out = new BufferedOutputStream(s.getOutputStream()); in = new BufferedInputStream(s.getInputStream()); }
        Object cmd(String... parts) throws IOException {
            StringBuilder sb = new StringBuilder("*").append(parts.length).append("\r\n");
            for (String p : parts) { byte[] b = p.getBytes(StandardCharsets.UTF_8); sb.append('$').append(b.length).append("\r\n").append(p).append("\r\n"); }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8)); out.flush(); return read();
        }
        Object read() throws IOException {
            int type = in.read(); String line = line();
            return switch (type) {
                case '+' -> line; case ':' -> Long.parseLong(line);
                case '$' -> { int n = Integer.parseInt(line); if (n < 0) yield null; byte[] b = in.readNBytes(n); line(); yield new String(b, StandardCharsets.UTF_8); }
                case '*' -> { int n = Integer.parseInt(line); List<Object> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(read()); yield l; }
                case '-' -> throw new IOException(line);
                default -> throw new IOException("unexpected " + (char) type);
            };
        }
        String line() throws IOException { StringBuilder sb = new StringBuilder(); int c; while ((c = in.read()) != '\r') sb.append((char) c); in.read(); return sb.toString(); }
        public void close() throws IOException { s.close(); }
    }
}
