// ThreadsL1.java — run with: java ThreadsL1.java
// A. How many concurrent blocking requests can one JVM hold: platform threads vs virtual threads (real threads).
// B. Pool sizing: CPU-bound vs I/O-heavy work, measured throughput for different pool sizes.
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ThreadsL1 {

    static long rssMb() throws Exception {
        for (String l : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/self/status")))
            if (l.startsWith("VmRSS:")) return Long.parseLong(l.replaceAll("\\D+", "")) / 1024;
        return -1;
    }

    static String hold(boolean virtual, int n) throws Exception {
        CountDownLatch started = new CountDownLatch(n), release = new CountDownLatch(1);
        long before = rssMb(), t0 = System.nanoTime();
        List<Thread> ts = new ArrayList<>(n);
        try {
            for (int i = 0; i < n; i++) {
                Runnable r = () -> { started.countDown(); try { release.await(); } catch (InterruptedException ignored) { } };  // "waiting on I/O"
                ts.add(virtual ? Thread.ofVirtual().start(r) : Thread.ofPlatform().start(r));
            }
            started.await();
        } catch (Throwable e) {
            release.countDown();
            return String.format("FAILED after %,d threads: %s", ts.size(), e);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000, after = rssMb();
        release.countDown(); for (Thread t : ts) t.join();
        return String.format("%,7d waiting requests in %,5d ms, +%,d MB RSS", n, ms, after - before);
    }

    static double throughput(int poolSize, long cpuMicros, long waitMicros, int tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        long t0 = System.nanoTime();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < tasks; i++) fs.add(pool.submit(() -> {
            long end = System.nanoTime() + cpuMicros * 1000; double x = 0;
            while (System.nanoTime() < end) x += Math.sqrt(x + 1);               // CPU work
            if (waitMicros > 0) Thread.sleep(waitMicros / 1000, (int) (waitMicros % 1000) * 1000);   // I/O wait
            return x;
        }));
        for (var f : fs) f.get();
        pool.shutdown();
        return tasks / ((System.nanoTime() - t0) / 1e9);
    }

    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        System.out.println("A. Concurrent requests blocked on I/O (" + cores + " CPU cores)");
        System.out.println("   platform threads: " + hold(false, 5_000));
        System.out.println("   virtual threads : " + hold(true, 5_000));
        System.out.println("   virtual threads : " + hold(true, 100_000));

        System.out.println("B. Throughput (tasks/s) by pool size");
        System.out.printf("   %-10s %18s %30s%n", "pool size", "CPU-bound (2 ms)", "I/O-heavy (0.5 ms CPU + 10 ms wait)");
        for (int size : new int[]{1, cores, cores * 2, cores * 8, cores * 21, cores * 64}) {
            System.out.printf("   %-10d %18.0f %30.0f%n", size, throughput(size, 2_000, 0, 800), throughput(size, 500, 10_000, 3_000));
        }
        System.out.printf("   formula for the I/O-heavy case: threads ≈ cores × (1 + wait/compute) = %d × (1 + 10/0.5) = %d%n", cores, cores * 21);
    }
}
