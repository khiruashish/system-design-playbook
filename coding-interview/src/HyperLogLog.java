import java.util.*;

/** Problem: count unique visitors per day across billions of events in ~12 KB, mergeable across servers (Redis PFADD/PFCOUNT). */
public class HyperLogLog {

    private final int p;              // 2^p registers
    private final int m;
    private final byte[] registers;

    HyperLogLog(int p) { this.p = p; this.m = 1 << p; this.registers = new byte[m]; }

    static long hash64(String s) {    // a decent 64-bit hash (splitmix over String.hashCode is not enough: mix the chars)
        long h = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < s.length(); i++) { h ^= s.charAt(i); h *= 0xbf58476d1ce4e5b9L; h ^= h >>> 31; }
        h ^= h >>> 30; h *= 0xbf58476d1ce4e5b9L; h ^= h >>> 27; h *= 0x94d049bb133111ebL; h ^= h >>> 31;
        return h;
    }

    void add(String item) {
        long h = hash64(item);
        int idx = (int) (h >>> (64 - p));                      // first p bits pick the register
        long rest = h << p;                                    // remaining bits
        int rank = Long.numberOfLeadingZeros(rest) + 1;        // position of the first 1-bit
        if (rank > 64 - p + 1) rank = 64 - p + 1;
        if (rank > registers[idx]) registers[idx] = (byte) rank;
    }

    long estimate() {
        double alpha = 0.7213 / (1 + 1.079 / m);
        double sum = 0; int zeros = 0;
        for (byte r : registers) { sum += 1.0 / (1L << r); if (r == 0) zeros++; }
        double e = alpha * m * m / sum;
        if (e <= 2.5 * m && zeros > 0) e = m * Math.log((double) m / zeros);     // small-range correction (linear counting)
        return Math.round(e);
    }

    /** Union is a register-wise max: count uniques across servers/days without double counting. */
    void merge(HyperLogLog other) { for (int i = 0; i < m; i++) registers[i] = (byte) Math.max(registers[i], other.registers[i]); }

    public static void main(String[] args) {
        HyperLogLog server1 = new HyperLogLog(14), server2 = new HyperLogLog(14);  // 16384 registers = 16 KB (Redis packs to 12 KB)
        Random r = new Random(5);
        Set<String> exact = new HashSet<>();
        for (int i = 0; i < 1_000_000; i++) {                  // 1M events, 300k distinct users, split across two servers
            String user = "user-" + r.nextInt(300_000);
            exact.add(user);
            (i % 2 == 0 ? server1 : server2).add(user);
        }
        long s1 = server1.estimate();
        server1.merge(server2);
        long merged = server1.estimate();
        double err = 100.0 * Math.abs(merged - exact.size()) / exact.size();
        System.out.printf("exact distinct = %d, HLL estimate after merge = %d (error %.2f%%; standard error 1.04/sqrt(m) = %.2f%%)%n",
                exact.size(), merged, err, 104.0 / Math.sqrt(1 << 14));
        System.out.printf("server1 alone estimated %d; memory: 16 KB of registers vs ~%d MB for a HashSet%n", s1, exact.size() * 60 / 1_000_000);
        check(err < 2.5, "within ~3 standard errors");
        check(s1 < merged, "merge adds the other server's users");
        HyperLogLog small = new HyperLogLog(14);
        for (int i = 0; i < 1000; i++) small.add("u" + (i % 100));
        System.out.println("100 distinct among 1000 events -> estimate " + small.estimate() + " (linear-counting correction for small sets)");
        check(Math.abs(small.estimate() - 100) <= 3, "small range");
        System.out.println("PASS");
    }

    static void check(boolean ok, String what) { if (!ok) { System.out.println("FAILED: " + what); System.exit(1); } }
}
