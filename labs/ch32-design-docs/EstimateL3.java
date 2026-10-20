// EstimateL3.java — run with: java EstimateL3.java   (JDK 21, no dependencies)
// INTERNAL CONSISTENCY OF A DESIGN DOC'S NUMBERS. Design docs state assumptions (DAU, orders per user, payload size,
// retention) in one section and derived figures (peak QPS, storage, bandwidth) in another — often written on different
// days, sometimes by different people. Reviewers rarely recompute them. This check derives every figure from the stated
// assumptions (the back-of-envelope method of Ch 02) and compares it with what the doc claims, flagging anything off by
// more than 2x. An order-of-magnitude error in a capacity claim becomes a wrong instance count, a wrong DB, a wrong budget.
import java.util.*;

public class EstimateL3 {
    record Check(String metric, double claimed, double derived, String unit, String formula) {
        double ratio() { return claimed / derived; }
        boolean ok() { return ratio() >= 0.5 && ratio() <= 2.0; }
    }

    public static void main(String[] args) {
        // ---- assumptions as written in the doc's "Scale assumptions" section ----
        double dau = 2_000_000;             // daily active users
        double ordersPerDauPerDay = 0.5;    // half of DAU place an order each day
        double peakFactor = 5;              // festival peak vs daily average
        double orderBytes = 4 * 1024;       // stored order row + items + audit, bytes
        double retentionYears = 3;
        double readsPerOrder = 40;          // order-status polls, history views, support lookups per order lifetime
        double eventBytes = 1.5 * 1024;     // OrderPlaced event on Kafka
        double replicationFactor = 3;

        // ---- figures the doc CLAIMS in its "Capacity" section ----
        Map<String, Double> claimed = new LinkedHashMap<>();
        claimed.put("peak order writes/s", 500.0);
        claimed.put("order storage (3 yr, 1 copy)", 2.0);       // TB
        claimed.put("peak order reads/s", 2_300.0);
        claimed.put("Kafka ingest (3 replicas)/day", 4.2);      // GB/day
        claimed.put("avg order writes/s", 11.6);

        // ---- derive from assumptions ----
        double ordersPerDay = dau * ordersPerDauPerDay;
        double avgWps = ordersPerDay / 86_400;
        double peakWps = avgWps * peakFactor;
        double storageTB = ordersPerDay * 365 * retentionYears * orderBytes / 1e12;
        double peakRps = peakWps * readsPerOrder;
        double kafkaGBday = ordersPerDay * eventBytes * replicationFactor / 1e9;

        List<Check> checks = List.of(
            new Check("avg order writes/s", claimed.get("avg order writes/s"), avgWps, "/s", "2M DAU × 0.5 ÷ 86,400"),
            new Check("peak order writes/s", claimed.get("peak order writes/s"), peakWps, "/s", "avg × peak factor 5"),
            new Check("peak order reads/s", claimed.get("peak order reads/s"), peakRps, "/s", "peak writes × 40 reads/order"),
            new Check("order storage (3 yr, 1 copy)", claimed.get("order storage (3 yr, 1 copy)"), storageTB, " TB", "1M/day × 365 × 3 × 4 KiB"),
            new Check("Kafka ingest (3 replicas)/day", claimed.get("Kafka ingest (3 replicas)/day"), kafkaGBday, " GB", "1M/day × 1.5 KiB × 3")
        );

        System.out.println("Recomputing a design doc's capacity figures from its own stated assumptions (flag if off by >2x).\n");
        System.out.printf("   Assumptions: %,.0f DAU, %.1f orders/DAU/day, peak ×%.0f, %.0f KiB/order, %.0f yr retention, %.0f reads/order%n%n",
                dau, ordersPerDauPerDay, peakFactor, orderBytes / 1024, retentionYears, readsPerOrder);
        System.out.printf("   %-30s %10s %10s %7s   %s%n", "figure", "claimed", "derived", "ratio", "derivation");
        int bad = 0;
        for (Check c : checks) {
            System.out.printf("   %-30s %10s %10s %6.1fx   %-30s %s%n", c.metric(), fmt(c.claimed(), c.unit()), fmt(c.derived(), c.unit()),
                    c.ratio(), c.formula(), c.ok() ? "✓" : "✗ INCONSISTENT");
            if (!c.ok()) bad++;
        }
        System.out.printf("%n   → %d of %d figures disagree with the doc's own assumptions by more than 2x.%n", bad, checks.size());
        System.out.println("     The peak write rate is overstated ~9x — yet the peak READ figure matches the CORRECT derivation, which proves");
        System.out.println("     the author had the right number (58/s) when computing reads and the 500 is an isolated slip on one line. Storage");
        System.out.println("     is understated ~2.2x and the doc shows no working, so nobody can tell which assumption it silently changed.");
        System.out.println("     Each mistake changes a decision: 500 writes/s suggests sharding, 58 fits one PostgreSQL");
        System.out.println("     primary with room to spare. Put derivations in an estimation appendix, generated from the assumptions, so the");
        System.out.println("     numbers in the doc cannot drift apart — and so a reviewer can challenge an ASSUMPTION rather than a result.");
    }

    static String fmt(double v, String unit) {
        return (v >= 100 ? String.format("%,.0f", v) : String.format("%.1f", v)) + unit;
    }
}
