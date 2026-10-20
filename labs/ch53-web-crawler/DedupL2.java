// DedupL2.java — run with: java -Xmx3g DedupL2.java   (JDK 21, no dependencies)
// "HAVE WE ALREADY FETCHED THIS URL?" 2,000,000 real pages across 5,000 competitor/catalogue sites, discovered through
// 12,000,000 links (fixed seed). Links to the same page come in many spellings: http vs https, upper-case host,
// default port, trailing slash, #fragments, utm_* tracking parameters, session ids, and query parameters in any order.
//   D1 exact string set of URLs as written
//   D2 canonicalise (lower-case scheme/host, drop default port, fragment, tracking and session params, sort query,
//      strip trailing slash), then an exact string set
//   D3 canonicalise, then a set of 64-bit hashes (open addressing)
//   D4 canonicalise, then a Bloom filter sized for 1% false positives
// Measured: fetches, duplicate fetches of the same page, real pages never fetched (skipped by a false positive), and
// the memory of the "seen" structure.
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public class DedupL2 {
    public static void main(String[] args) throws Exception {
        int pages = 2_000_000, links = 12_000_000; Random r = new Random(53);
        String[] canon = new String[pages]; for (int p = 0; p < pages; p++) { int site = r.nextInt(5000);
            canon[p] = "https://shop" + site + ".example.in/c/" + (p % 97) + "/product/" + p + (r.nextBoolean() ? "?size=" + (1 + r.nextInt(5)) + "&variant=" + r.nextInt(4) : ""); }
        String[] linkUrl = new String[links]; int[] linkPage = new int[links];
        for (int i = 0; i < links; i++) { int p = i < pages ? i : r.nextInt(pages); linkPage[i] = p; linkUrl[i] = mangle(canon[p], r); }
        System.out.printf("%,d pages, %,d links; e.g. %s%n                              → %s%n%n", pages, links, linkUrl[pages + 7], canonicalise(linkUrl[pages + 7]));
        System.out.printf("   %-52s %12s %16s %16s %12s%n", "seen-set", "fetches", "duplicate", "pages never", "memory");
        System.out.printf("   %-52s %12s %16s %16s %12s%n", "", "", "fetches", "fetched", "");
        String[] names = {"D1) exact URL strings as written", "D2) canonicalised URLs, exact string set", "D3) canonicalised, 64-bit hash set", "D4) canonicalised, Bloom filter (1% FP)"};
        for (int d = 0; d < 4; d++) {
            long fetches = 0; boolean[] fetched = new boolean[pages]; long dup = 0, mem;
            if (d <= 1) { HashSet<String> seen = new HashSet<>(); long chars = 0;
                for (int i = 0; i < links; i++) { String u = d == 0 ? linkUrl[i] : canonicalise(linkUrl[i]); if (seen.add(u)) { chars += u.length(); fetches++; if (fetched[linkPage[i]]) dup++; fetched[linkPage[i]] = true; } }
                mem = seen.size() * (48L + 32) + chars; }                                          // ~String + node overhead + Latin-1 bytes
            else if (d == 2) { LongSet seen = new LongSet(1 << 23);
                for (int i = 0; i < links; i++) { if (seen.add(hash64(canonicalise(linkUrl[i])))) { fetches++; if (fetched[linkPage[i]]) dup++; fetched[linkPage[i]] = true; } }
                mem = seen.bytes(); }
            else { Bloom b = new Bloom(pages, 0.01);
                for (int i = 0; i < links; i++) { if (b.addIfAbsent(canonicalise(linkUrl[i]))) { fetches++; if (fetched[linkPage[i]]) dup++; fetched[linkPage[i]] = true; } }
                mem = b.bytes(); }
            int never = 0; for (boolean f : fetched) if (!f) never++;
            System.out.printf("   %-52s %,12d %,16d %,16d %9.1f MB%n", names[d], fetches, dup, never, mem / 1048576.0);
        }
        System.out.println("\n   → without canonicalisation the crawler fetches the same page under every spelling it finds — here more");
        System.out.println("     fetches go to duplicates than to new pages, and every one costs the site a request. Canonicalising first makes");
        System.out.println("     dedup exact; storing 8-byte hashes instead of strings cuts the seen-set several-fold with no practical collision");
        System.out.println("     risk at this scale. A Bloom filter is smaller still, but its false positives are pages we silently NEVER");
        System.out.println("     fetch — acceptable for a web-scale crawl, not for a price monitor that must see every product.");
    }

    static final String[] TRACK = {"utm_source=app", "utm_medium=cpc", "utm_campaign=diwali", "gclid=Cj0K9x", "fbclid=IwAR2", "sid=8f3a9c", "ref=home"};
    static String mangle(String u, Random r) {
        String s = u;
        if (r.nextDouble() < 0.3) s = s.replace("https://", "http://");
        if (r.nextDouble() < 0.2) s = s.replace(".example.in", ".EXAMPLE.in").replace("https://shop", "https://SHOP");
        if (r.nextDouble() < 0.1) s = s.replace(".in/", ".in:443/");
        String path = s, query = ""; int q = s.indexOf('?'); if (q >= 0) { path = s.substring(0, q); query = s.substring(q + 1); }
        if (r.nextDouble() < 0.3) path = path + "/";
        List<String> params = new ArrayList<>(query.isEmpty() ? List.of() : Arrays.asList(query.split("&")));
        if (r.nextDouble() < 0.4) params.add(TRACK[r.nextInt(TRACK.length)]);
        if (r.nextDouble() < 0.5) Collections.shuffle(params, r);
        String out = path + (params.isEmpty() ? "" : "?" + String.join("&", params));
        if (r.nextDouble() < 0.15) out += "#reviews";
        return out;
    }
    static String canonicalise(String u) {
        int h = u.indexOf('#'); if (h >= 0) u = u.substring(0, h);
        int se = u.indexOf("://"); String scheme = u.substring(0, se).toLowerCase(Locale.ROOT); String rest = u.substring(se + 3);
        int slash = rest.indexOf('/'); String host = (slash < 0 ? rest : rest.substring(0, slash)).toLowerCase(Locale.ROOT); String pathq = slash < 0 ? "/" : rest.substring(slash);
        if (host.endsWith(":443") || host.endsWith(":80")) host = host.substring(0, host.lastIndexOf(':'));
        scheme = "https";                                                                         // site serves both; https is canonical
        String path = pathq, query = ""; int q = pathq.indexOf('?'); if (q >= 0) { path = pathq.substring(0, q); query = pathq.substring(q + 1); }
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        List<String> keep = new ArrayList<>(); if (!query.isEmpty()) for (String p : query.split("&")) { String k = p.split("=")[0];
            if (k.startsWith("utm_") || k.equals("gclid") || k.equals("fbclid") || k.equals("sid") || k.equals("ref")) continue; keep.add(p); }
        Collections.sort(keep);
        return scheme + "://" + host + path + (keep.isEmpty() ? "" : "?" + String.join("&", keep));
    }
    static long hash64(String s) { byte[] b = s.getBytes(StandardCharsets.UTF_8); long h = 0xcbf29ce484222325L; for (byte x : b) { h ^= x; h *= 0x100000001b3L; } h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; return h == 0 ? 1 : h; }
    static final class LongSet { long[] t; int n; LongSet(int cap) { t = new long[cap]; }
        boolean add(long k) { if (n * 2 > t.length) grow(); int m = t.length - 1, i = (int) (k ^ (k >>> 32)) & m; while (t[i] != 0) { if (t[i] == k) return false; i = (i + 1) & m; } t[i] = k; n++; return true; }
        void grow() { long[] old = t; t = new long[old.length * 2]; n = 0; for (long k : old) if (k != 0) add(k); }
        long bytes() { return t.length * 8L; } }
    static final class Bloom { final long[] bits; final int k; final long m;
        Bloom(int n, double fp) { m = (long) Math.ceil(-n * Math.log(fp) / (Math.log(2) * Math.log(2))); k = (int) Math.round(m / (double) n * Math.log(2)); bits = new long[(int) ((m + 63) / 64)]; }
        boolean addIfAbsent(String s) { long h1 = hash64(s), h2 = hash64(s + "#") | 1; boolean all = true;
            for (int i = 0; i < k; i++) { long idx = Math.floorMod(h1 + i * h2, m); int w = (int) (idx >>> 6); long bit = 1L << (idx & 63); if ((bits[w] & bit) == 0) { all = false; bits[w] |= bit; } }
            return !all; }
        long bytes() { return bits.length * 8L; } }
}
