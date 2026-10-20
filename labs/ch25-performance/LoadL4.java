// LoadL4.java — run with: java LoadL4.java      (JDK 21, no dependencies)
// Load testing, capacity planning and the Universal Scalability Law, measured against a REAL contended service function
// (a real ReentrantLock critical section + a real Thread.sleep for downstream I/O), driven by N real worker threads.
// A. closed-model load: throughput and latency at each concurrency N — find where throughput plateaus/retrogrades and
//    where p99 crosses an SLO. B. fit the USL to the measured curve and predict the peak. C. Little's law for pool sizing.
// D. open vs closed model. (We drive the service function directly to avoid HTTP-client connection-pool artifacts; the
//    contention being measured — the lock — is exactly what an HTTP handler would hit.)
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

public class LoadL4 {
    static final ReentrantLock serial = new ReentrantLock();
    static final AtomicLong blackhole = new AtomicLong();

    /** One "request": a small serialised critical section (contended), then downstream I/O wait (parallelisable). */
    static void handle() {
        serial.lock();
        try { long e = System.nanoTime() + 80_000; long a = 1; while (System.nanoTime() < e) a = a * 31 + 7; blackhole.addAndGet(a); }  // ~0.08 ms serial
        finally { serial.unlock(); }
        try { Thread.sleep(1); } catch (InterruptedException ignored) {}   // ~1 ms downstream I/O (does not hold the lock)
    }

    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < 5000; i++) handle();   // warm up the JIT

        System.out.printf("Service: ~0.08 ms serial (locked) + ~1 ms downstream I/O per request, on %d cores.%n", cores);
        System.out.println("\nA/B. Closed model: N concurrent clients, each calling in a loop for 1.5 s. Throughput and latency vs N.");
        System.out.printf("   %-6s %-14s %-10s %-10s %-12s%n", "N", "throughput/s", "p50 ms", "p99 ms", "in-flight L");
        int[] Ns = {1, 2, 4, 8, 16, 32, 64, 96, 128, 192, 256};
        double[] xN = new double[Ns.length], xThru = new double[Ns.length];
        for (int idx = 0; idx < Ns.length; idx++) {
            int N = Ns[idx];
            long[][] perThread = new long[N][];
            AtomicLong done = new AtomicLong();
            long t0 = System.nanoTime(); long dur = 1_500_000_000L;
            Thread[] ts = new Thread[N];
            for (int c = 0; c < N; c++) {
                final int cc = c;
                ts[c] = new Thread(() -> {
                    long[] buf = new long[200_000]; int n = 0;
                    while (System.nanoTime() - t0 < dur) { long s0 = System.nanoTime(); handle(); if (n < buf.length) buf[n] = System.nanoTime() - s0; n++; done.incrementAndGet(); }
                    perThread[cc] = Arrays.copyOf(buf, Math.min(n, buf.length));
                });
                ts[c].start();
            }
            for (Thread t : ts) t.join();
            double secs = (System.nanoTime() - t0) / 1e9, thru = done.get() / secs;
            long total = 0; for (long[] b : perThread) total += b.length;
            long[] all = new long[(int) Math.min(total, Integer.MAX_VALUE)]; int p = 0;
            for (long[] b : perThread) for (long v : b) if (p < all.length) all[p++] = v;
            Arrays.sort(all);
            double p50 = all[all.length/2]/1e6, p99 = all[(int)(all.length*0.99)]/1e6;
            xN[idx] = N; xThru[idx] = thru;
            System.out.printf("   %-6d %-14.0f %-10.2f %-10.2f %-12.1f%n", N, thru, p50, p99, thru * (p50/1000.0));
        }

        // C. Universal Scalability Law fit: X(N) = N·X1 / (1 + α(N-1) + βN(N-1))
        double bestErr = Double.MAX_VALUE, bestA = 0, bestB = 0, X1 = xThru[0];
        for (double al = 0; al <= 0.3; al += 0.002) for (double be = 0; be <= 0.005; be += 0.00002) {
            double err = 0; for (int i = 0; i < xN.length; i++) { double pred = X1 * xN[i] / (1 + al*(xN[i]-1) + be*xN[i]*(xN[i]-1)); err += Math.pow((pred - xThru[i]) / xThru[i], 2); }
            if (err < bestErr) { bestErr = err; bestA = al; bestB = be; }
        }
        System.out.println("\nC. Universal Scalability Law fit  X(N) = N·X1 / (1 + α(N-1) + βN(N-1))");
        System.out.printf("   X1 (one client) = %,.0f req/s%n", X1);
        System.out.printf("   contention α = %.3f  (the serialised critical section — Amdahl's serial fraction)%n", bestA);
        System.out.printf("   coherency  β = %.5f  (crosstalk between workers)%n", bestB);
        if (bestB > 0) {
            double nMax = Math.sqrt((1 - bestA) / bestB);
            double xMax = X1 * nMax / (1 + bestA*(nMax-1) + bestB*nMax*(nMax-1));
            System.out.printf("   predicted peak: N* ≈ %.0f concurrent → X_max ≈ %,.0f req/s; beyond N* throughput RETROGRADES%n", nMax, xMax);
        } else System.out.printf("   β ≈ 0: throughput plateaus at ~%,.0f req/s (Amdahl ceiling 1/α)%n", X1 / Math.max(bestA, 1e-9));
        System.out.println("   → the model, fit from a few load points, predicts the peak so you don't have to test to destruction.");

        // D. Little's law
        System.out.println("\nD. Little's law for capacity planning (Ch 9, Ch 19)");
        double targetRps = 2000, serviceSec = 1.1e-3;
        double L = targetRps * serviceSec;
        System.out.printf("   to serve %,.0f req/s at ~%.1f ms each: in-flight L = λ·W = %.0f → pool/DB-connections ≈ %.0f with headroom%n",
                targetRps, serviceSec*1000, L, Math.ceil(L*1.5));
        System.out.println("   Plan to run at ~60–70% of X_max, not at the peak: a spike or a lost node then can't push you past the knee (L1 C).");
        System.out.println("\n   Closed vs open model: this closed test caps in-flight at N, so it can't overload the service — throughput just plateaus.");
        System.out.println("   An open model (fixed arrival rate, measured from the scheduled time) is what reveals the latency cliff and coordinated omission (L1 D).");
    }
}
