// ConcurrencyL1.java — run with: java ConcurrencyL1.java
// Little's law for synchronous calls: in-flight requests = arrival rate × latency. When a dependency slows down,
// the caller needs proportionally more concurrency — or it queues. Measured with real threads.
import java.util.*;
import java.util.concurrent.*;

public class ConcurrencyL1 {

    static double runMs(ExecutorService pool, int tasks, long blockMs) throws Exception {
        long t0 = System.nanoTime();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < tasks; i++) fs.add(pool.submit(() -> { Thread.sleep(blockMs); return null; }));   // "waiting on pricing-service"
        for (var f : fs) f.get();
        pool.shutdown();
        return (System.nanoTime() - t0) / 1e6;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("A. Little's law: threads busy waiting = requests/s × dependency latency");
        for (long latencyMs : new long[]{50, 200, 1_000, 5_000})
            System.out.printf("   400 req/s × %,5d ms → %,5d requests in flight%n", latencyMs, 400 * latencyMs / 1000);

        System.out.println("B. 4,000 calls that each wait 500 ms on a dependency (real threads)");
        System.out.printf("   fixed pool of 200 platform threads : %,7.0f ms%n", runMs(Executors.newFixedThreadPool(200), 4_000, 500));
        System.out.printf("   virtual thread per task            : %,7.0f ms%n", runMs(Executors.newVirtualThreadPerTaskExecutor(), 4_000, 500));
        System.out.println("   → virtual threads remove the thread-pool ceiling, but NOT the dependency's own limits:");
        System.out.println("     4,000 concurrent calls now reach pricing-service at once, so bound them (semaphore/bulkhead, Ch 18)");
    }
}
