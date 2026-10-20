// L3 — Evidence for an impact claim. Your story says "I cut the order-history p99 by X%". Measure it honestly on
// real PostgreSQL: before/after latency samples, p50/p99 with bootstrap 95% confidence intervals, and the claims
// that would be misleading (best run, mean instead of p99, a single measurement).
import java.sql.*;
import java.util.*;

public class ImpactL3 {
    static final String URL = "jdbc:postgresql://localhost:5581/shop?user=claude";

    public static void main(String[] a) throws Exception {
        Connection c = DriverManager.getConnection(URL);
        try {
            Statement s = c.createStatement();
            s.execute("DROP TABLE IF EXISTS beh_orders");
            s.execute("CREATE TABLE beh_orders (id bigserial PRIMARY KEY, customer_id int NOT NULL, created_at timestamptz NOT NULL, total_paise bigint NOT NULL, status text NOT NULL)");
            s.execute("INSERT INTO beh_orders (customer_id, created_at, total_paise, status) " +
                      "SELECT (random()*200000)::int, now() - random() * interval '365 days', (random()*500000)::bigint, " +
                      "(ARRAY['DELIVERED','CANCELLED','DELIVERED','DELIVERED'])[1 + (random()*3)::int] FROM generate_series(1, 1500000)");
            s.execute("VACUUM ANALYZE beh_orders");
            String q = "SELECT id, created_at, total_paise, status FROM beh_orders WHERE customer_id = ? AND created_at > now() - interval '90 days' ORDER BY created_at DESC LIMIT 20";
            System.out.println("1.5M orders, 200k customers; query: a customer's orders in the last 90 days, newest first (the order-history page)");
            System.out.println("before: index on (customer_id) — after: covering index on (customer_id, created_at DESC) INCLUDE (total_paise, status)\n");

            // three rounds: switch the index back and forth, warm up, then 300 timed queries per state
            System.out.printf("%-6s %-7s %8s %10s %10s %10s %10s%n", "round", "state", "samples", "best ms", "mean ms", "p50 ms", "p99 ms");
            Random r = new Random(61); double[] b1 = null, a1 = null; List<String> red = new ArrayList<>();
            for (int round = 1; round <= 3; round++) {
                setIndex(s, false); double[] before = measure(c, q, 300, round * 10L);
                setIndex(s, true);  double[] after = measure(c, q, 300, round * 10L + 1);
                row(round, "before", before); row(round, "after", after);
                double[] p50 = ciRatio(before, after, 0.50, r), p99 = ciRatio(before, after, 0.99, r);
                red.add(String.format("round %d   p50 %5.1f%% (95%% CI %5.1f … %5.1f%%)   p99 %6.1f%% (95%% CI %6.1f … %5.1f%%)", round, p50[0], p50[1], p50[2], p99[0], p99[1], p99[2]));
                if (round == 1) { b1 = before; a1 = after; }
            }
            System.out.println("\nreduction after the change (positive = faster):"); red.forEach(x -> System.out.println("  " + x));
            System.out.printf("%nmisleading versions of round 1:%n");
            System.out.printf("  worst-before vs best-after:   \"%.0f%% faster\"  (cherry-picked pair)%n", 100 * (1 - min(a1) / max(b1)));
            System.out.printf("  one sample each:              \"%.1f%% faster\"  (a single pair of queries is mostly noise)%n", 100 * (1 - a1[0] / b1[0]));
            System.out.printf("  mean before vs mean after:    \"%.1f%% faster\"  (says nothing about the tail)%n", 100 * (1 - avg(a1) / avg(b1)));
            ResultSet plan = s.executeQuery("EXPLAIN SELECT id, created_at, total_paise, status FROM beh_orders WHERE customer_id = 42 AND created_at > now() - interval '90 days' ORDER BY created_at DESC LIMIT 20");
            System.out.println("\nplan after:"); while (plan.next()) System.out.println("  " + plan.getString(1));
            s.execute("DROP TABLE beh_orders");
        } finally { c.close(); }
    }

    // before = index on (customer_id), what production had; after = the change the story is about: a composite,
    // covering index that returns the page's rows already in order
    static void setIndex(Statement s, boolean after) throws SQLException {
        s.execute("DROP INDEX IF EXISTS beh_orders_cust"); s.execute("DROP INDEX IF EXISTS beh_orders_cust_created");
        s.execute(after ? "CREATE INDEX beh_orders_cust_created ON beh_orders (customer_id, created_at DESC) INCLUDE (total_paise, status)"
                        : "CREATE INDEX beh_orders_cust ON beh_orders (customer_id)");
        s.execute("VACUUM ANALYZE beh_orders");
    }
    static double avg(double[] x) { return Arrays.stream(x).average().orElse(0); }
    static double[] measure(Connection c, String q, int n, long seed) throws SQLException {
        PreparedStatement ps = c.prepareStatement(q); Random r = new Random(seed); double[] out = new double[n];
        for (int i = 0; i < 50; i++) { ps.setInt(1, r.nextInt(200_000)); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) { } } }   // warm-up
        for (int i = 0; i < n; i++) {
            ps.setInt(1, r.nextInt(200_000)); long t0 = System.nanoTime();
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) { } }
            out[i] = (System.nanoTime() - t0) / 1e6;
        }
        return out;
    }
    static void row(int round, String name, double[] x) { System.out.printf("%-6d %-7s %8d %10.2f %10.2f %10.2f %10.2f%n", round, name, x.length, min(x), avg(x), pct(x, .5), pct(x, .99)); }
    static double pct(double[] x, double p) { double[] s = x.clone(); Arrays.sort(s); return s[Math.min(s.length - 1, (int) Math.floor(p * s.length))]; }
    static double min(double[] x) { return Arrays.stream(x).min().orElse(0); }
    static double max(double[] x) { return Arrays.stream(x).max().orElse(0); }
    // bootstrap: resample both sets, compute % reduction of the percentile
    static double[] ciRatio(double[] b, double[] a, double p, Random r) {
        double point = 100 * (1 - pct(a, p) / pct(b, p)); double[] boots = new double[2000];
        for (int k = 0; k < boots.length; k++) boots[k] = 100 * (1 - pct(resample(a, r), p) / pct(resample(b, r), p));
        Arrays.sort(boots); return new double[]{point, boots[50], boots[1949]};
    }
    static double[] resample(double[] x, Random r) { double[] y = new double[x.length]; for (int i = 0; i < y.length; i++) y[i] = x[r.nextInt(x.length)]; return y; }
}
