// StorageL4.java — run with: java StorageL4.java   (JDK 21, no dependencies)
// STORAGE AND DATA-TRANSFER COSTS — the lines on the bill nobody designed. Part 1 MEASURES real encodings of 50,000 order
// events (JSON, gzip'd JSON, a compact binary encoding, gzip'd binary) with java.util.zip; Part 2 extrapolates to the
// platform's event volume and prices three years of retention under different lifecycle policies; Part 3 prices a chatty
// cross-zone call pattern. ALL PRICES ARE ILLUSTRATIVE assumptions (₹ per GB-month / per GB), not current vendor rates.
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class StorageL4 {
    static final String[] TYPES = {"OrderPlaced", "StockReserved", "PaymentCaptured", "OrderPacked", "OrderDelivered"};
    static final String[] CITIES = {"pune", "delhi", "bengaluru", "mumbai", "chennai", "hyderabad", "kolkata", "jaipur"};

    record Event(String type, String orderId, String customerId, String city, long tsMillis, long totalPaise, int items, String[] skus) {}

    static List<Event> events(int n) {
        Random r = new Random(3); List<Event> l = new ArrayList<>(); long ts = 1_780_000_000_000L;
        for (int i = 0; i < n; i++) {
            int items = 1 + r.nextInt(12); String[] skus = new String[items];
            for (int k = 0; k < items; k++) skus[k] = "SKU" + (100000 + r.nextInt(3000));
            ts += r.nextInt(400);
            l.add(new Event(TYPES[i % 5], "ORD" + (50_000_000 + i / 5), "CUST" + (1_000_000 + r.nextInt(2_000_000)),
                    CITIES[r.nextInt(CITIES.length)], ts, 5000 + r.nextInt(400_000), items, skus));
        }
        return l;
    }
    static byte[] json(List<Event> es) {
        StringBuilder sb = new StringBuilder();
        for (Event e : es) {
            sb.append("{\"eventType\":\"").append(e.type()).append("\",\"orderId\":\"").append(e.orderId())
              .append("\",\"customerId\":\"").append(e.customerId()).append("\",\"city\":\"").append(e.city())
              .append("\",\"occurredAt\":").append(e.tsMillis()).append(",\"totalPaise\":").append(e.totalPaise())
              .append(",\"itemCount\":").append(e.items()).append(",\"skus\":[");
            for (int k = 0; k < e.skus().length; k++) sb.append(k > 0 ? "," : "").append('"').append(e.skus()[k]).append('"');
            sb.append("]}\n");
        }
        return sb.toString().getBytes();
    }
    static byte[] binary(List<Event> es) throws IOException {     // schema'd binary: enums as bytes, ids/numbers as varints
        ByteArrayOutputStream bo = new ByteArrayOutputStream(); DataOutputStream o = new DataOutputStream(bo);
        for (Event e : es) {
            o.writeByte(Arrays.asList(TYPES).indexOf(e.type())); varint(o, Long.parseLong(e.orderId().substring(3)));
            varint(o, Long.parseLong(e.customerId().substring(4))); o.writeByte(Arrays.asList(CITIES).indexOf(e.city()));
            varint(o, e.tsMillis()); varint(o, e.totalPaise()); o.writeByte(e.items());
            for (String s : e.skus()) varint(o, Long.parseLong(s.substring(3)));
        }
        return bo.toByteArray();
    }
    static void varint(DataOutputStream o, long v) throws IOException { while ((v & ~0x7FL) != 0) { o.writeByte((int) ((v & 0x7F) | 0x80)); v >>>= 7; } o.writeByte((int) v); }
    static byte[] gzip(byte[] in) throws IOException { ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(bo) { { def.setLevel(6); } }) { g.write(in); } return bo.toByteArray(); }

    public static void main(String[] args) throws Exception {
        int N = 50_000; List<Event> es = events(N);
        byte[] j = json(es), jg = gzip(j), b = binary(es), bg = gzip(b);
        System.out.printf("Part 1 — measured encodings of %,d real-shaped order events (java.util.zip, level 6):%n%n", N);
        System.out.printf("   %-26s %12s %12s %10s%n", "encoding", "total", "bytes/event", "vs JSON");
        Object[][] enc = {{"JSON", j}, {"JSON + gzip", jg}, {"compact binary (varints)", b}, {"compact binary + gzip", bg}};
        for (Object[] e : enc) { byte[] x = (byte[]) e[1];
            System.out.printf("   %-26s %,10.0f KB %12.1f %9.1fx%n", e[0], x.length / 1024.0, (double) x.length / N, (double) j.length / x.length); }

        // Part 2 — three data streams, three years of retention. Order events use the measured sizes; logs and clickstream
        // use the measured JSON→gzip ratio as a proxy compression ratio (an assumption: logs typically compress 5–10x).
        double gzRatio = (double) j.length / jg.length;
        double[] rawGbPerDay = { 1_000_000 * 5.0 * j.length / N / 1e9,     // order events: ~1M orders × 5 events, measured bytes
                                 36_300 / 30.0,                              // application logs: 36,300 GB/month (L1 usage data)
                                 2_000_000.0 * 300 * 400 / 1e9 };            // clickstream: 2M DAU × 300 events × ~400 B
        String[] stream = {"order events", "application logs", "app clickstream"};
        double HOT = 8.0, STD = 1.9, IA = 1.0, ARCH = 0.08;                 // ₹/GB-month, illustrative
        int months = 36;
        System.out.printf("%n Part 2 — three data streams kept for 3 years. Illustrative ₹/GB-month: hot (DB / search cluster) %.1f,%n", HOT);
        System.out.printf("   object standard %.1f, infrequent %.1f, archive %.2f. Compression ratio applied: %.1fx (measured above).%n%n", STD, IA, ARCH, gzRatio);
        System.out.printf("   %-17s %10s %20s %22s %24s%n", "stream", "raw GB/day", "all hot, raw", "object store, gzip", "gzip + lifecycle tiers");
        double worstAll = 0, bestAll = 0;
        for (int i = 0; i < 3; i++) {
            double g = rawGbPerDay[i];
            double hotRaw = tiered(g, months, new double[]{HOT}, new int[]{9999});
            double stdGz = tiered(g / gzRatio, months, new double[]{STD}, new int[]{9999});
            double life = tiered(g / gzRatio, months, new double[]{STD, IA, ARCH}, new int[]{1, 11, 9999});
            worstAll += hotRaw; bestAll += life;
            System.out.printf("   %-17s %10.1f %18s %20s %22s%n", stream[i], g, rupees(hotRaw), rupees(stdGz), rupees(life));
        }
        System.out.printf("   %-17s %10s %18s %20s %22s%n", "all three", "", rupees(worstAll), "", rupees(bestAll));

        // Part 3 — cross-zone transfer for the same streams (3 zones; default placement vs zone-aware)
        double XAZ = 1.6;   // ₹ per GB crossing a zone boundary (both directions billed), illustrative
        System.out.printf("%n Part 3 — cross-zone transfer per month (3 zones, ₹%.1f per GB that crosses a zone, illustrative):%n", XAZ);
        System.out.printf("   %-17s %24s %28s%n", "stream", "default (2/3 cross zones)", "zone-aware (~5% spill-over)");
        double monthDefault = 0;
        for (int i = 0; i < 3; i++) {
            double gbMonth = rawGbPerDay[i] / gzRatio * 30 * 3;            // produced + replicated ×2 + consumed, compressed
            double def = gbMonth * (2.0 / 3.0) * XAZ, za = gbMonth * 0.05 * XAZ; monthDefault += def;
            System.out.printf("   %-17s %24s %28s%n", stream[i], rupees(def), rupees(za));
        }
        double bill = 22_800_000;
        System.out.printf("%n   For scale: the monthly cloud bill in L1 is %s. Worst-case storage for all three streams averages %s/month over%n",
                rupees(bill), rupees(worstAll / months));
        System.out.printf("   the 3 years; default cross-zone transfer is %s/month.%n", rupees(monthDefault));

        System.out.println("\n   → the measurement is real: gzip shrinks these JSON events ~6x and a schema'd binary encoding + gzip ~9x (Protobuf/Avro in");
        System.out.println("     practice, Ch 28). But proportionality decides where effort goes. Order events — the data engineers worry about — are");
        System.out.println("     a rounding error at any policy. Logs and clickstream are 100–1,000x bigger, and for them the retention policy, not the");
        System.out.println("     encoding, is the lever: 'keep everything hot, uncompressed' versus 'compress and tier' is the difference between a");
        System.out.println("     real line on the bill and a negligible one. Cross-zone transfer is small at this platform's scale; it becomes a");
        System.out.println("     top-five line for data-heavy systems (media, ML features, analytics replication), where zone-aware routing and");
        System.out.println("     follower-fetching consumers matter. FinOps rule: rank the streams by size first, then optimise the big ones.");
    }

    // cost of a growing dataset over `months`: each month's data ages through tiers; ageLimit[i] = months spent in tier i
    static String rupees(double v) { return v >= 1e7 ? String.format("₹%.2f Cr", v / 1e7) : v >= 1e5 ? String.format("₹%.1f L", v / 1e5) : String.format("₹%,.0f", v); }
    static double tiered(double gbPerDay, int months, double[] price, int[] ageLimit) {
        double gbPerMonth = gbPerDay * 30, cost = 0;
        for (int m = 0; m < months; m++)                   // for each billing month
            for (int born = 0; born <= m; born++) {        // each monthly cohort still stored
                int age = m - born, tier = 0, acc = 0;
                while (tier < ageLimit.length - 1 && age >= acc + ageLimit[tier]) { acc += ageLimit[tier]; tier++; }
                cost += gbPerMonth * price[tier];
            }
        return cost;
    }
}
