// CallChainL1.java — run with: java CallChainL1.java
// What splitting a request across services does to availability and tail latency.
import java.util.*;

public class CallChainL1 {

    /** One service call: 99.9 % available; latency lognormal-ish with median ~20 ms and a slow tail. */
    static double callMs(Random r) { return 20 * Math.exp(0.5 * r.nextGaussian()); }

    public static void main(String[] args) {
        Random r = new Random(7);
        int requests = 200_000;
        System.out.println("A. Synchronous dependencies in SERIES, each 99.9 % available");
        for (int n : new int[]{1, 3, 5, 8, 12}) {
            double avail = Math.pow(0.999, n);
            System.out.printf("   %2d services on the request path → %.2f %% available (%4.0f min of downtime a month)%n",
                    n, avail * 100, (1 - avail) * 30 * 24 * 60);
        }

        System.out.println("B. Latency of one page that calls N services (each median ~20 ms)");
        for (int n : new int[]{1, 4, 8}) {
            double[] serial = new double[requests], parallel = new double[requests];
            for (int i = 0; i < requests; i++) {
                double sum = 0, max = 0;
                for (int k = 0; k < n; k++) { double t = callMs(r); sum += t; max = Math.max(max, t); }
                serial[i] = sum; parallel[i] = max;
            }
            Arrays.sort(serial); Arrays.sort(parallel);
            System.out.printf("   N=%d  sequential p50 %5.0f ms  p99 %5.0f ms   |   parallel p50 %4.0f ms  p99 %4.0f ms%n",
                    n, serial[requests / 2], serial[requests * 99 / 100], parallel[requests / 2], parallel[requests * 99 / 100]);
        }

        System.out.println("C. Fan-out: chance that at least one of N parallel calls hits its own p99 (tail at scale)");
        for (int n : new int[]{1, 5, 10, 26, 100}) System.out.printf("   N=%3d → %4.1f %% of page loads see a p99-slow dependency%n", n, (1 - Math.pow(0.99, n)) * 100);
    }
}
