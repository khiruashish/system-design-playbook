// QuorumL4.java — run with: java QuorumL4.java   (JDK 21, no dependencies)
// TUNABLE CONSISTENCY: WHAT R AND W COST IN LATENCY AND BUY IN FRESHNESS. N = 3 replicas. One-way network+processing
// time per message: lognormal, median 1 ms; each replica independently has a 3% chance per request of a stall (GC,
// compaction, noisy neighbour) adding 20–200 ms (fixed seed). A client writes a key, gets its ack, then reads the key
// 0–20 ms later (read-your-write), 200,000 times. Writes go to all 3 replicas; the ack waits for W of them. Reads go to
// replicas and return when R have answered, choosing the newest version among them.
//   configs: R=1 read sent to one replica / to one + a hedged second after 5 ms / to all three (first answer wins);
//   then W=1/R=2, W=2/R=1, W=2/R=2 (quorum, R+W>N), W=3/R=1 (reads sent to all three, answer after R replies)
// Measured: write p50/p99, read p50/p99, and the share of reads that returned the value from BEFORE the acknowledged write.
import java.util.*;

public class QuorumL4 {
    public static void main(String[] args) {
        System.out.println("N=3, one-way latency median 1 ms, 3% stall chance per replica per request (+20–200 ms); 200,000 write→read pairs.\n");
        System.out.printf("   %-40s %9s %9s %9s %9s %14s%n", "config", "write p50", "write p99", "read p50", "read p99", "stale reads");
        Object[][] cfg = {{"W=1 R=1 (read sent to one replica)", 1, 1, 2}, {"W=1 R=1 + hedge to a 2nd after 5 ms", 1, 1, 1}, {"W=1 R=1 (read sent to all, first wins)", 1, 1, 0}, {"W=1 R=2", 1, 2, 0}, {"W=2 R=1", 2, 1, 0}, {"W=2 R=2 (quorum, R+W>N)", 2, 2, 0}, {"W=3 R=1", 3, 1, 0}};
        for (Object[] c : cfg) run((String) c[0], (int) c[1], (int) c[2], (int) c[3]);
        System.out.println("\n   → the tail comes from stalls, and how many replicas you wait for decides who pays for them. A read sent to one");
        System.out.println("     replica inherits its stalls; hedging to a second after 5 ms cuts that tail, and asking all three and taking the");
        System.out.println("     first answer cuts it further — none of these changes staleness (~3%). With R + W ≤ N a read can miss the write");
        System.out.println("     the client just made. R + W > N guarantees overlap: zero stale reads, paid for in the tail (read p99 ~26 ms at");
        System.out.println("     R=2 when two replicas are slow). W=3 puts every replica's stall into the write's p99. Choose per operation:");
        System.out.println("     carts and order status want quorum; a product-view counter is fine with ONE.");
    }
    static void run(String name, int W, int R, int mode) {                       // mode 0: to all, 1: one + hedge, 2: one
        Random r = new Random(54); int n = 200_000; double[] wl = new double[n], rl = new double[n]; int stale = 0;
        for (int i = 0; i < n; i++) {
            double[] applied = new double[3], ackBack = new double[3];
            for (int j = 0; j < 3; j++) { applied[j] = hop(r); ackBack[j] = applied[j] + hop(r); }
            double[] acks = ackBack.clone(); Arrays.sort(acks); double ackAt = acks[W - 1]; wl[i] = ackAt;
            double readAt = ackAt + r.nextDouble() * 20;
            double[] arrive = new double[3], respond = new double[3]; boolean[] fresh = new boolean[3];
            for (int j = 0; j < 3; j++) { arrive[j] = readAt + hop(r); respond[j] = arrive[j] + hop(r); fresh[j] = applied[j] <= arrive[j]; }
            double done; boolean gotFresh;
            if (mode == 2) { int a = r.nextInt(3); done = respond[a]; gotFresh = fresh[a]; }
            else if (mode == 1) { int a = r.nextInt(3), b = (a + 1 + r.nextInt(2)) % 3; double second = readAt + 5 + (respond[b] - readAt);
                if (respond[a] <= readAt + 5 || respond[a] <= second) { done = respond[a]; gotFresh = fresh[a]; } else { done = second; gotFresh = fresh[b]; } }
            else { Integer[] idx = {0, 1, 2}; final double[] rs = respond; Arrays.sort(idx, Comparator.comparingDouble(x -> rs[x])); done = respond[idx[R - 1]]; gotFresh = false; for (int k = 0; k < R; k++) gotFresh |= fresh[idx[k]]; }
            rl[i] = done - readAt; if (!gotFresh) stale++;
        }
        Arrays.sort(wl); Arrays.sort(rl);
        System.out.printf("   %-40s %6.1f ms %6.1f ms %6.1f ms %6.1f ms %13.2f%%%n", name, wl[n / 2], wl[(int) (n * 0.99)], rl[n / 2], rl[(int) (n * 0.99)], 100.0 * stale / n);
    }
    static double hop(Random r) { double t = Math.exp(0.5 * r.nextGaussian()); if (r.nextDouble() < 0.03) t += 20 + r.nextDouble() * 180; return t; }
}
