// CdnL4.java — run with: java CdnL4.java   (JDK 21, no dependencies)
// A SELLER REPLACES A PRODUCT PHOTO. How long do customers keep seeing the old one, and what does freshness cost?
// 50,000 product images, 3 days, 2,000,000 image requests a day (Zipf; fixed seed), 600 photo updates a day at random
// times. The CDN edge caches without size limits here (to isolate the effect of TTLs and invalidation). The product
// API that hands out image URLs is itself cached for 60 s.
//   C1 mutable URL (/img/{sku}/600.jpg), CDN TTL 7 days
//   C2 mutable URL, TTL 5 minutes
//   C3 mutable URL, TTL 7 days + a purge call on every update (purge takes 30 s–5 min to reach all edges; 2% of purges fail)
//   C4 immutable, versioned URL (/img/{sku}/{content-hash}/600.jpg), TTL 1 year; an update changes the URL
// Measured: requests served an outdated photo, CDN hit ratio, origin requests, and the worst staleness seen.
import java.util.*;

public class CdnL4 {
    public static void main(String[] args) {
        int imgs = 50_000, days = 3; long perDay = 2_000_000; Random r = new Random(52);
        double[] cdf = new double[imgs]; double acc = 0; for (int i = 0; i < imgs; i++) { acc += 1 / Math.pow(i + 1, 1.0); cdf[i] = acc; } for (int i = 0; i < imgs; i++) cdf[i] /= acc;
        int n = (int) (perDay * days); double[] rt = new double[n]; int[] ri = new int[n];
        for (int q = 0; q < n; q++) { rt[q] = r.nextDouble() * days * 86400; int k = Arrays.binarySearch(cdf, r.nextDouble()); ri[q] = Math.min(imgs - 1, k < 0 ? -k - 1 : k); }
        Integer[] ord = new Integer[n]; for (int q = 0; q < n; q++) ord[q] = q; Arrays.sort(ord, Comparator.comparingDouble(q -> rt[q]));
        int ups = 600 * days; double[] ut = new double[ups]; int[] ui = new int[ups]; for (int u = 0; u < ups; u++) { ut[u] = r.nextDouble() * days * 86400; ui[u] = r.nextInt(imgs); }
        Integer[] uord = new Integer[ups]; for (int u = 0; u < ups; u++) uord[u] = u; Arrays.sort(uord, Comparator.comparingDouble(u -> ut[u]));
        double[] purgeDelay = new double[ups]; boolean[] purgeFail = new boolean[ups]; for (int u = 0; u < ups; u++) { purgeDelay[u] = 30 + r.nextDouble() * 270; purgeFail[u] = r.nextDouble() < 0.02; }
        System.out.println("50,000 images, 3 days, 2M requests/day, 600 photo updates/day; product API cached 60 s.\n");
        System.out.printf("   %-48s %14s %11s %16s %16s%n", "caching", "outdated photo", "CDN hit", "origin requests", "worst staleness");
        String[] names = {"C1) mutable URL, TTL 7 days", "C2) mutable URL, TTL 5 minutes", "C3) mutable URL, TTL 7 days + purge on update", "C4) versioned (content-hash) URL, TTL 1 year"};
        for (int c = 0; c < 4; c++) {
            double ttl = c == 1 ? 300 : c == 3 ? 365 * 86400.0 : 7 * 86400.0;
            int[] version = new int[imgs]; double[] versionAt = new double[imgs];               // latest version and when it was published
            int[] cachedVer = new int[imgs]; double[] cachedAt = new double[imgs]; Arrays.fill(cachedVer, -1);
            HashSet<Long> cachedVersioned = new HashSet<>();                                    // C4: cache key includes the version
            double[] purgeAt = new double[imgs]; Arrays.fill(purgeAt, Double.MAX_VALUE);
            // for C4: the version a client asks for is what the product API (cached 60 s) told it
            long stale = 0, hits = 0, origin = 0; double worst = 0; int up = 0;
            Map<Integer, List<double[]>> hist = new HashMap<>();
            for (int qi : ord) {
                double t = rt[qi]; int img = ri[qi];
                while (up < ups && ut[uord[up]] <= t) { int u = uord[up++]; int im = ui[u]; version[im]++; versionAt[im] = ut[u]; hist.computeIfAbsent(im, k -> new ArrayList<>()).add(new double[]{ut[u], version[im]});
                    if (c == 2 && !purgeFail[u]) purgeAt[im] = Math.min(purgeAt[im], ut[u] + purgeDelay[u]); }
                int served;
                if (c == 3) {
                    int asked = version[img]; List<double[]> h = hist.get(img);                  // API cache: client may hold a URL up to 60 s old
                    if (h != null) { asked = 0; for (double[] e : h) if (e[0] <= t - 60 * r.nextDouble()) asked = (int) e[1]; }
                    long key = (long) img * 100_000 + asked;
                    if (cachedVersioned.contains(key)) hits++; else { cachedVersioned.add(key); origin++; }
                    served = asked;
                } else {
                    if (purgeAt[img] <= t) { cachedVer[img] = -1; purgeAt[img] = Double.MAX_VALUE; }
                    if (cachedVer[img] >= 0 && t - cachedAt[img] < ttl) { hits++; served = cachedVer[img]; }
                    else { origin++; cachedVer[img] = version[img]; cachedAt[img] = t; served = version[img]; }
                }
                if (served < version[img]) { stale++; worst = Math.max(worst, t - versionAt[img]); }
            }
            System.out.printf("   %-48s %,14d %10.1f%% %,16d %16s%n", names[c], stale, 100.0 * hits / n, origin, human(worst));
        }
        System.out.println("\n   → with mutable URLs you choose between long TTLs (great hit ratio, customers see an old photo for days) and");
        System.out.println("     short TTLs (fresh, but the origin serves many times more requests). Purging on update narrows the gap, but");
        System.out.println("     purges are asynchronous and sometimes fail — and a failed purge means days of staleness. Versioned URLs");
        System.out.println("     remove the dilemma: content never changes under a URL, so it can be cached for a year, and an update is a");
        System.out.println("     new URL. What remains is how stale the page that hands out the URL is (60 s here).");
    }
    static String human(double s) { return s == 0 ? "—" : s < 120 ? String.format("%.0f s", s) : s < 7200 ? String.format("%.0f min", s / 60) : s < 172800 ? String.format("%.1f h", s / 3600) : String.format("%.1f days", s / 86400); }
}
