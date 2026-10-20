// HotspotL3.java — run with: java -XX:+FlightRecorder -XX:StartFlightRecording=duration=... HotspotL3.java
// A "pricing" workload with a deliberately planted performance bug: it builds a promo key by STRING CONCATENATION in a
// loop (O(n^2), heavy char[] allocation) and recomputes an unindexed lookup every call. JFR records CPU samples and
// allocation. We then run `jfr print` to show the hot method and the top allocation site — how you find a hotspot in prod
// without guessing. A "fixed" variant (StringBuilder + a precomputed map) shows the speedup the profile predicted.
import java.util.*;

public class HotspotL3 {
    static final int SKUS = 5000;
    static double[] basePrice = new double[SKUS];
    static Map<String, Double> promoByKey = new HashMap<>();
    static { Random r = new Random(3); for (int i = 0; i < SKUS; i++) basePrice[i] = 20 + r.nextInt(500); }

    // ❌ the slow path: quadratic key building + a linear scan of a list that should be a map
    static List<String[]> promoList = new ArrayList<>();   // (key, discount) pairs, scanned linearly
    static { for (int i = 0; i < 2000; i++) promoList.add(new String[]{"PROMO-REGION-" + (i % 20) + "-TIER-" + (i % 5) + "-" + i, String.valueOf(i % 30)}); }

    static double priceSlow(int sku, int region, int tier) {
        // build a key by repeated concatenation inside a loop (allocates a new String each time)
        String key = "";
        for (String part : new String[]{"PROMO", "REGION", String.valueOf(region), "TIER", String.valueOf(tier), String.valueOf(sku % 2000)})
            key = key + "-" + part;                                    // O(n^2) allocation, the planted hotspot
        double discount = 0;
        for (String[] p : promoList) if (p[0].endsWith("-" + (sku % 2000))) { discount = Double.parseDouble(p[1]); break; }  // linear scan
        return basePrice[sku] * (1 - discount / 100.0);
    }

    // ✅ the fixed path: StringBuilder + a precomputed index
    static Map<Integer, Double> promoIndex = new HashMap<>();
    static { for (String[] p : promoList) { int suf = Integer.parseInt(p[0].substring(p[0].lastIndexOf('-') + 1)); promoIndex.putIfAbsent(suf, Double.parseDouble(p[1])); } }
    static double priceFast(int sku, int region, int tier) {
        StringBuilder key = new StringBuilder(32).append("PROMO-REGION-").append(region).append("-TIER-").append(tier).append('-').append(sku % 2000);
        double discount = promoIndex.getOrDefault(sku % 2000, 0.0);
        return basePrice[sku] * (1 - discount / 100.0) + key.length() * 0;   // use key so JIT can't delete it
    }

    public static void main(String[] args) {
        boolean fast = args.length > 0 && args[0].equals("fast");
        Random r = new Random(9);
        long iters = Long.getLong("iters", 3_000_000);
        // warm up
        for (int i = 0; i < 100_000; i++) { if (fast) priceFast(i % SKUS, i % 20, i % 5); else priceSlow(i % SKUS, i % 20, i % 5); }
        long t0 = System.nanoTime(); double sink = 0;
        for (long i = 0; i < iters; i++) { int sku = (int) (i % SKUS); sink += fast ? priceFast(sku, (int)(i%20), (int)(i%5)) : priceSlow(sku, (int)(i%20), (int)(i%5)); }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("%s: %,d price computations in %,d ms = %,.0f/s (checksum %.0f)%n", fast ? "FAST" : "SLOW", iters, ms, iters * 1000.0 / ms, sink % 1000);
    }
}
