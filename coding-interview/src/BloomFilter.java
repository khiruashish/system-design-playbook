import java.nio.charset.StandardCharsets;
import java.util.BitSet;

/** Problem: "has this username / URL been seen?" for a billion items in little memory, accepting rare false positives. */
public class BloomFilter {

    private final BitSet bits;
    private final int m;            // number of bits
    private final int k;            // number of hash functions

    /** Optimal sizing: m = -n ln p / (ln 2)^2,  k = (m/n) ln 2. */
    BloomFilter(long expectedItems, double falsePositiveRate) {
        this.m = (int) Math.ceil(-expectedItems * Math.log(falsePositiveRate) / (Math.log(2) * Math.log(2)));
        this.k = Math.max(1, (int) Math.round((double) m / expectedItems * Math.log(2)));
        this.bits = new BitSet(m);
    }

    void add(String item) { long[] h = hashes(item); for (int i = 0; i < k; i++) bits.set(index(h, i)); }

    /** false = definitely absent. true = probably present. */
    boolean mightContain(String item) {
        long[] h = hashes(item);
        for (int i = 0; i < k; i++) if (!bits.get(index(h, i))) return false;
        return true;
    }

    /** Kirsch–Mitzenmacher: two hashes simulate k: h_i = h1 + i*h2. */
    private int index(long[] h, int i) { return (int) Math.floorMod(h[0] + i * h[1], (long) m); }

    private static long[] hashes(String s) {
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        long h1 = 0xcbf29ce484222325L, h2 = 0x9E3779B97F4A7C15L;
        for (byte b : data) { h1 ^= b; h1 *= 0x100000001b3L; h2 = (h2 ^ b) * 0xff51afd7ed558ccdL; h2 ^= h2 >>> 33; }  // FNV-1a + a mix
        return new long[]{h1, h2 | 1};
    }

    int bitCount() { return m; }
    int hashCount() { return k; }

    public static void main(String[] args) {
        int n = 100_000;
        BloomFilter bf = new BloomFilter(n, 0.01);
        for (int i = 0; i < n; i++) bf.add("user-" + i);
        System.out.printf("n=%d, target p=1%% -> m=%d bits (%.1f KB, %.1f bits/item), k=%d hashes%n",
                n, bf.bitCount(), bf.bitCount() / 8192.0, (double) bf.bitCount() / n, bf.hashCount());

        boolean noFalseNegatives = true;
        for (int i = 0; i < n; i++) noFalseNegatives &= bf.mightContain("user-" + i);
        int falsePositives = 0, trials = 100_000;
        for (int i = 0; i < trials; i++) if (bf.mightContain("other-" + i)) falsePositives++;
        double fpr = 100.0 * falsePositives / trials;
        System.out.printf("false negatives: %s; measured false-positive rate on 100k unseen items: %.2f%%%n", noFalseNegatives ? "none" : "SOME", fpr);
        System.out.printf("a HashSet<String> of the same items would need roughly %d KB%n", n * 60 / 1024);
        check(noFalseNegatives, "never a false negative");
        check(fpr > 0.5 && fpr < 1.6, "FPR close to the 1% target");
        System.out.println("PASS");
    }

    static void check(boolean ok, String what) { if (!ok) { System.out.println("FAILED: " + what); System.exit(1); } }
}
