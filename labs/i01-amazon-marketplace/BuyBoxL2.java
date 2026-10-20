// L2 — The buy box: many sellers offer the same product; the product page shows one winning offer.
// Real PostgreSQL 16: 50,000 products, ~250,000 offers (seeded). Compare computing the winner on every page read (A) with
// maintaining a buy_box row in the same transaction as each offer change (B). Measures read latency, repricing
// throughput, and checks that B's table agrees with a from-scratch computation after heavy churn.
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class BuyBoxL2 {
    static final String URL = "jdbc:postgresql://localhost:5581/shop?user=claude";
    // lower is better: landed price, penalised for slow promise and low seller rating (illustrative weights)
    static final String SCORE = "(price_paise + ship_paise) * (1 + 0.02 * greatest(promise_days - 2, 0)) * (1 + 0.1 * (4.5 - least(rating, 4.5)))";
    static final String WINNER = "SELECT product_id, seller_id, price_paise + ship_paise AS landed FROM offers WHERE product_id = ? AND in_stock ORDER BY " + SCORE + ", seller_id LIMIT 1";
    static final String UPSERT = "INSERT INTO buy_box (product_id, seller_id, landed) " + WINNER.replace("?", "?") +
            " ON CONFLICT (product_id) DO UPDATE SET seller_id = EXCLUDED.seller_id, landed = EXCLUDED.landed";

    public static void main(String[] a) throws Exception {
        Connection c = DriverManager.getConnection(URL);
        try {
            Statement s = c.createStatement();
            s.execute("DROP TABLE IF EXISTS offers, buy_box");
            s.execute("CREATE TABLE offers (product_id int, seller_id int, price_paise bigint, ship_paise bigint, rating numeric(2,1), promise_days int, in_stock boolean, PRIMARY KEY (product_id, seller_id))");
            s.execute("SELECT setseed(0.42)");
            s.execute("INSERT INTO offers SELECT p, sl, 10000 + (random()*90000)::int, (ARRAY[0,0,4000,9900])[1 + (random()*3)::int], 3.0 + round((random()*2)::numeric, 1), 1 + (random()*6)::int, random() > 0.08 " +
                      "FROM generate_series(1, 50000) p, LATERAL (SELECT DISTINCT (random()*2000)::int + p*0 AS sl FROM generate_series(1, 1 + (random()*7)::int)) x");
            s.execute("CREATE TABLE buy_box (product_id int PRIMARY KEY, seller_id int, landed bigint)");
            s.execute("INSERT INTO buy_box SELECT DISTINCT ON (product_id) product_id, seller_id, price_paise + ship_paise FROM offers WHERE in_stock ORDER BY product_id, " + SCORE + ", seller_id");
            s.execute("VACUUM ANALYZE offers"); s.execute("VACUUM ANALYZE buy_box");
            ResultSet rs = s.executeQuery("SELECT count(*), count(DISTINCT product_id) FROM offers"); rs.next();
            System.out.printf("%,d offers across %,d products (1–8 sellers each, 8%% out of stock)%n%n", rs.getLong(1), rs.getLong(2));
        } finally { c.close(); }

        System.out.printf("%-44s %8s %12s %9s %9s%n", "workload (4 threads, 5 s)", "", "ops/s", "p50 ms", "p99 ms");
        run("A product page: compute winner on read", false, true);
        run("B product page: read buy_box row", true, true);
        run("A repricing: update offer only", false, false);
        rebuild(); run("B repricing: update offer + upsert buy_box", true, false); String b1 = check();
        HOT = true;
        rebuild(); run("B repricing, price war on 20 hot products", true, false); String b2 = check();
        rebuild(); LOCK = true; run("B' same price war, per-product lock first", true, false); String b3 = check();
        System.out.println("\nbuy_box vs a from-scratch computation (buy_box rebuilt before each run):");
        System.out.println("  B,  random products : " + b1);
        System.out.println("  B,  price war       : " + b2);
        System.out.println("  B', price war + lock: " + b3);
        System.out.println("\nThe race a benchmark hides — two reprices of one product, forced interleaving:");
        System.out.println("  " + race(false));
        System.out.println("  " + race(true));
        Connection v = DriverManager.getConnection(URL); try { v.createStatement().execute("DROP TABLE offers, buy_box"); } finally { v.close(); }
    }

    static volatile boolean LOCK = false, HOT = false;
    static void rebuild() throws SQLException { Connection c = DriverManager.getConnection(URL); try { Statement s = c.createStatement();
        s.execute("TRUNCATE buy_box"); s.execute("INSERT INTO buy_box SELECT DISTINCT ON (product_id) product_id, seller_id, price_paise + ship_paise FROM offers WHERE in_stock ORDER BY product_id, " + SCORE + ", seller_id"); } finally { c.close(); } }
    static String check() throws SQLException { Connection c = DriverManager.getConnection(URL); try {
        ResultSet rs = c.createStatement().executeQuery("WITH truth AS (SELECT DISTINCT ON (product_id) product_id, seller_id FROM offers WHERE in_stock ORDER BY product_id, " + SCORE + ", seller_id) " +
            "SELECT count(*) FILTER (WHERE b.seller_id IS DISTINCT FROM t.seller_id), count(*) FROM truth t FULL JOIN buy_box b USING (product_id)");
        rs.next(); return String.format("%d of %,d products show the wrong winner", rs.getLong(1), rs.getLong(2)); } finally { c.close(); } }
    // product 0: seller 1 at ₹500, seller 2 at ₹600 (same shipping, rating, promise) → seller 1 wins.
    // T1 cuts seller 2 to ₹400 and upserts (holds the buy_box row). T2 cuts seller 1 to ₹450, upserts from a snapshot
    // that cannot see T1's uncommitted change, and waits on the row. T1 commits, T2 commits. True winner: seller 2.
    static String race(boolean lock) throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("DELETE FROM offers WHERE product_id = 0"); s.execute("DELETE FROM buy_box WHERE product_id = 0");
            s.execute("INSERT INTO offers VALUES (0, 1, 50000, 0, 4.5, 2, true), (0, 2, 60000, 0, 4.5, 2, true)");
            s.execute("INSERT INTO buy_box VALUES (0, 1, 50000)");
        }
        Connection t1 = DriverManager.getConnection(URL), t2 = DriverManager.getConnection(URL);
        try {
            t1.setAutoCommit(false); t2.setAutoCommit(false);
            if (lock) t1.createStatement().executeQuery("SELECT pg_advisory_xact_lock(0)");
            t1.createStatement().executeUpdate("UPDATE offers SET price_paise = 40000 WHERE product_id = 0 AND seller_id = 2");
            PreparedStatement u1 = t1.prepareStatement(UPSERT); u1.setInt(1, 0); u1.executeUpdate();
            CompletableFuture<Void> second = CompletableFuture.runAsync(() -> { try {
                if (lock) t2.createStatement().executeQuery("SELECT pg_advisory_xact_lock(0)");      // waits for T1 here
                t2.createStatement().executeUpdate("UPDATE offers SET price_paise = 45000 WHERE product_id = 0 AND seller_id = 1");
                PreparedStatement u2 = t2.prepareStatement(UPSERT); u2.setInt(1, 0); u2.executeUpdate();    // without the lock, waits on the row
                t2.commit(); } catch (SQLException e) { throw new RuntimeException(e); } });
            Thread.sleep(500);                       // T2 is now blocked (on the advisory lock, or on the buy_box row)
            t1.commit(); second.get();
            try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
                ResultSet rs = s.executeQuery("SELECT seller_id, landed FROM buy_box WHERE product_id = 0"); rs.next();
                int shown = rs.getInt(1); long landed = rs.getLong(2);
                return String.format("%-26s buy box shows seller %d at ₹%d — %s", lock ? "with per-product lock:" : "without a lock:", shown, landed / 100,
                        shown == 2 ? "correct (seller 2 at ₹400 is cheaper)" : "WRONG: seller 2 at ₹400 is cheaper, and nothing will fix it until the next reprice");
            }
        } finally { t1.close(); t2.close(); }
    }
    static void run(String name, boolean maintained, boolean read) throws Exception {
        AtomicLong n = new AtomicLong(); ConcurrentLinkedQueue<long[]> lat = new ConcurrentLinkedQueue<>();
        long end = System.nanoTime() + 5_000_000_000L; ExecutorService ex = Executors.newFixedThreadPool(4); List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 4; t++) { int tid = t; fs.add(ex.submit(() -> {
            Connection c = DriverManager.getConnection(URL);
            try {
                PreparedStatement win = c.prepareStatement(WINNER), box = c.prepareStatement("SELECT seller_id, landed FROM buy_box WHERE product_id = ?"),
                        upd = c.prepareStatement("UPDATE offers SET price_paise = greatest(5000, price_paise + ?), in_stock = ? WHERE product_id = ? AND seller_id = (SELECT seller_id FROM offers WHERE product_id = ? ORDER BY seller_id OFFSET ? LIMIT 1)"),
                        ups = c.prepareStatement(UPSERT), del = c.prepareStatement("DELETE FROM buy_box WHERE product_id = ? AND NOT EXISTS (SELECT 1 FROM offers WHERE product_id = ? AND in_stock)");
                Random r = new Random(tid * 31L + (maintained ? 7 : 0) + (read ? 1 : 0)); long[] mine = new long[2_000_000]; int k = 0;
                if (!read) c.setAutoCommit(false);
                while (System.nanoTime() < end) {
                    int p = HOT && !read ? 1 + r.nextInt(20) : 1 + r.nextInt(50000); long t0 = System.nanoTime();
                    if (read) { PreparedStatement q = maintained ? box : win; q.setInt(1, p); try (ResultSet rs = q.executeQuery()) { rs.next(); } }
                    else {
                        if (LOCK) { try (PreparedStatement l = c.prepareStatement("SELECT pg_advisory_xact_lock(?)")) { l.setInt(1, p); l.executeQuery(); } }
                        upd.setInt(1, r.nextInt(4001) - 2000); upd.setBoolean(2, r.nextDouble() > 0.05); upd.setInt(3, p); upd.setInt(4, p); upd.setInt(5, r.nextInt(2)); upd.executeUpdate();
                        if (maintained) { ups.setInt(1, p); ups.executeUpdate(); del.setInt(1, p); del.setInt(2, p); del.executeUpdate(); }
                        c.commit();
                    }
                    if (k < mine.length) mine[k++] = System.nanoTime() - t0; n.incrementAndGet();
                }
                lat.add(Arrays.copyOf(mine, k));
            } finally { c.close(); }
            return null; })); }
        for (Future<?> f : fs) f.get(); ex.shutdown();
        long[] all = lat.stream().flatMapToLong(Arrays::stream).sorted().toArray();
        System.out.printf("%-44s %8s %,12.0f %9.2f %9.2f%n", name, "", n.get() / 5.0, all[all.length / 2] / 1e6, all[(int) (all.length * 0.99)] / 1e6);
    }
}
