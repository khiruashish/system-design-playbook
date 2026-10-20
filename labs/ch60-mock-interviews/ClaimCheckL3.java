// L3 — Check a claim before you make it in a mock: "one conditional UPDATE on a single stock row handles a
// flash sale of 2,000 reservations/s for one SKU". Real PostgreSQL 16 (fsync=on), 2-CPU sandbox, localhost.
// Modes: A single row; B 16 bucket rows (random bucket, move on if empty); C single row, 20 reservations per commit (batched).
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ClaimCheckL3 {
    static final String URL = "jdbc:postgresql://localhost:5581/shop?user=claude";
    static final int SECONDS = 4;

    public static void main(String[] a) throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS mock_stock");
            s.execute("CREATE TABLE mock_stock (sku int, bucket int, qty int NOT NULL, PRIMARY KEY (sku, bucket))");
        }
        System.out.printf("Each run: %d s of closed-loop load, stock large enough never to run out. Target from the mock: 2,000 reservations/s.%n%n", SECONDS);
        System.out.printf("%-34s %8s %14s %10s %10s%n", "mode", "threads", "reservations/s", "p50 ms", "p99 ms");
        for (String mode : new String[]{"A single row", "B 16 bucket rows", "C single row, 20 per commit"})
            for (int threads : new int[]{1, 8, 32}) run(mode, threads);
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT count(*), min(qty) FROM mock_stock"); rs.next();
            System.out.printf("%nrows %d, lowest remaining qty %d (never ran out, so every UPDATE did real work)%n", rs.getInt(1), rs.getInt(2));
        }
    }

    static void run(String mode, int threads) throws Exception {
        int buckets = mode.startsWith("B") ? 16 : 1, batch = mode.startsWith("C") ? 20 : 1;
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("DELETE FROM mock_stock");
            for (int b = 0; b < buckets; b++) s.execute("INSERT INTO mock_stock VALUES (1, " + b + ", " + (10_000_000 / buckets) + ")");
            s.execute("VACUUM ANALYZE mock_stock");
        }
        AtomicLong done = new AtomicLong(); ConcurrentLinkedQueue<long[]> lat = new ConcurrentLinkedQueue<>();
        long end = System.nanoTime() + SECONDS * 1_000_000_000L;
        ExecutorService ex = Executors.newFixedThreadPool(threads); List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) fs.add(ex.submit(() -> {
            Connection c = DriverManager.getConnection(URL);      // one dedicated connection per worker, closed below
            try {
                PreparedStatement ps = c.prepareStatement("UPDATE mock_stock SET qty = qty - ? WHERE sku = 1 AND bucket = ? AND qty >= ?");
                ThreadLocalRandom r = ThreadLocalRandom.current(); long[] mine = new long[400_000]; int n = 0;
                while (System.nanoTime() < end) {
                    long t0 = System.nanoTime();
                    int b = r.nextInt(buckets);
                    ps.setInt(1, batch); ps.setInt(2, b); ps.setInt(3, batch);
                    if (ps.executeUpdate() == 1) done.addAndGet(batch);
                    long d = System.nanoTime() - t0;
                    for (int i = 0; i < batch && n < mine.length; i++) mine[n++] = d;   // each reservation in a batch waits for the commit
                }
                lat.add(Arrays.copyOf(mine, n));
            } finally { c.close(); }
            return null;
        }));
        for (Future<?> f : fs) f.get(); ex.shutdown();
        long[] all = lat.stream().flatMapToLong(Arrays::stream).sorted().toArray();
        System.out.printf("%-34s %8d %,14.0f %10.2f %10.2f%n", mode, threads, done.get() / (double) SECONDS,
                all[all.length / 2] / 1e6, all[(int) (all.length * 0.99)] / 1e6);
    }
}
