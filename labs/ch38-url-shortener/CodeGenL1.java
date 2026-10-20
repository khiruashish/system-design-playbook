// CodeGenL1.java — run with: java CodeGenL1.java   (JDK 21, no dependencies)
// FOUR WAYS TO MINT A SHORT CODE. The grocery platform sends ~2 million SMS a day with a link (order tracking, slot reminders,
// "your refund is processed"). Each link needs a short, unique code: gro.cr/aZ3kP9q. We mint 2,000,000 codes four ways
// and measure what matters: collisions, extra database round-trips, length, and whether an attacker can ENUMERATE other
// customers' links by guessing neighbouring codes (tracking links carry an order id and an address on the page behind them).
//   A) hash the long URL (MD5) and keep the first 6 base62 chars     — no coordination, but collisions
//   B) random 7-char base62, check-and-retry on collision            — needs a uniqueness check per insert
//   C) global counter → base62                                       — unique and short, but sequential (guessable)
//   D) counter → Feistel permutation over 62^7 → base62              — unique, no check needed, not sequential
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.*;

public class CodeGenL1 {
    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    static final long SPACE7 = pow(62, 7);                       // 3,521,614,606,208

    static long pow(long b, int e) { long r = 1; for (int i = 0; i < e; i++) r *= b; return r; }
    static String base62(long v, int width) {
        char[] c = new char[width];
        for (int i = width - 1; i >= 0; i--) { c[i] = ALPHABET.charAt((int) (v % 62)); v /= 62; }
        return v == 0 ? new String(c) : null;
    }
    static String base62(long v) { StringBuilder sb = new StringBuilder(); do { sb.append(ALPHABET.charAt((int) (v % 62))); v /= 62; } while (v > 0); return sb.reverse().toString(); }

    // --- D: a 4-round Feistel network on two halves, with cycle-walking so the result stays inside [0, 62^7) ---
    static final long HALF = 1L << 21;                           // 2^42 > 62^7, so split 42 bits into 2 × 21
    static final long[] KEYS = {0x5DEECE66DL, 0x2545F491L, 0x9E3779B9L, 0x7F4A7C15L};  // secret round keys
    static long round(long x, long k) { long h = (x * 0x9E3779B97F4A7C15L) ^ k; h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32; return h & (HALF - 1); }
    static long feistel(long v) {
        long l = v >>> 21, r = v & (HALF - 1);
        for (long k : KEYS) { long t = l ^ round(r, k); l = r; r = t; }
        return (l << 21) | r;
    }
    static long permute(long v) { long x = feistel(v); while (x >= SPACE7) x = feistel(x); return x; }   // cycle-walk

    public static void main(String[] args) throws Exception {
        int n = 2_000_000; Random rnd = new Random(38);
        System.out.printf("Minting %,d short codes. 62^6 = %,d   62^7 = %,d%n%n", n, pow(62, 6), SPACE7);
        System.out.printf("   %-44s %6s %12s %14s %18s%n", "strategy", "length", "collisions", "extra lookups", "neighbour guess hits");

        // A) hash-truncate
        MessageDigest md5 = MessageDigest.getInstance("MD5"); Set<String> a = new HashSet<>(); int aColl = 0;
        for (int i = 0; i < n; i++) {
            String url = "https://grocery.in/orders/ORD-" + (10_000_000 + i) + "/track?t=" + Long.toHexString(rnd.nextLong());
            byte[] h = md5.digest(url.getBytes());
            String code = base62(new BigInteger(1, h).mod(BigInteger.valueOf(pow(62, 6))).longValue(), 6);
            if (!a.add(code)) aColl++;
        }
        System.out.printf("   %-44s %6d %,12d %,14d %18s%n", "A) MD5(url) truncated to 6 base62 chars", 6, aColl, 0, "~0% (random)");

        // B) random + retry
        Set<String> b = new HashSet<>(); int bRetry = 0;
        for (int i = 0; i < n; i++) { String c; do { c = base62(Math.floorMod(rnd.nextLong(), SPACE7), 7); if (b.contains(c)) bRetry++; } while (!b.add(c)); }
        System.out.printf("   %-44s %6d %,12d %,14d %18s%n", "B) random 7 chars, check-and-retry", 7, 0, n + bRetry, "~0% (random)");

        // C) counter
        long start = 100_000_000L; Set<String> c = new HashSet<>(); int cHits = 0;
        for (long i = 0; i < n; i++) c.add(base62(start + i));
        List<String> cList = new ArrayList<>(c);
        for (int i = 0; i < 10_000; i++) { String s = cList.get(rnd.nextInt(cList.size())); if (c.contains(base62(decode(s) + 1))) cHits++; }
        System.out.printf("   %-44s %6d %,12d %,14d %17.1f%%%n", "C) counter → base62", base62(start + n).length(), 0, 0, 100.0 * cHits / 10_000);

        // D) counter → permutation
        Set<Long> dSet = new HashSet<>(); List<String> dList = new ArrayList<>(); Set<String> dCodes = new HashSet<>();
        for (long i = 0; i < n; i++) { long p = permute(start + i); dSet.add(p); String s = base62(p, 7); dList.add(s); dCodes.add(s); }
        int dHits = 0;
        for (int i = 0; i < 10_000; i++) { String s = dList.get(rnd.nextInt(dList.size())); String nb = base62(decode(s) + 1, 7); if (nb != null && dCodes.contains(nb)) dHits++; }
        System.out.printf("   %-44s %6d %,12d %,14d %17.2f%%%n", "D) counter → Feistel permutation → base62", 7, n - dSet.size(), 0, 100.0 * dHits / 10_000);

        System.out.println("\n   First five codes minted by C and D (consecutive counter values):");
        StringBuilder sc = new StringBuilder(), sd = new StringBuilder();
        for (int i = 0; i < 5; i++) { sc.append(base62(start + i)).append(' '); sd.append(dList.get(i)).append(' '); }
        System.out.println("        C: " + sc.toString().trim()); System.out.println("        D: " + sd.toString().trim());

        // birthday check on A
        double expectedA = (double) n * (n - 1) / 2 / pow(62, 6);
        System.out.printf("%n   Birthday estimate for A: n²/2N = %,.0f expected colliding pairs (measured %,d).%n", expectedA, aColl);
        System.out.printf("   Random 7-char space is %.5f%% full after %,d codes, so B needed %,d retries — but still one lookup per insert.%n", 100.0 * n / SPACE7, n, bRetry);
        System.out.printf("   At 2M links/day, 62^7 lasts %,.0f years for C/D; 62^6 would last %,.0f years.%n", SPACE7 / 2e6 / 365, pow(62, 6) / 2e6 / 365);

        System.out.println("\n   → hashing needs no coordination but collides (and two customers' different URLs can't share a code), so it");
        System.out.println("     still needs a check-and-retry — at which point it is B with extra steps. B is simple and unguessable but");
        System.out.println("     costs a uniqueness check on every insert (a unique index and a retry loop). C is perfect for the database and");
        System.out.println("     terrible for privacy: every tracking link's neighbour is another customer's order. D keeps C's properties —");
        System.out.println("     unique by construction, no lookup, one short range per node — and a keyed permutation makes neighbours useless.");
        System.out.println("     A permutation hides ORDER, not existence: codes are still 7 chars from a 3.5-trillion space, so pair it with");
        System.out.println("     rate limiting (Ch 39) and never put secrets behind a short link alone.");
    }
    static long decode(String s) { long v = 0; for (char ch : s.toCharArray()) v = v * 62 + ALPHABET.indexOf(ch); return v; }
}
