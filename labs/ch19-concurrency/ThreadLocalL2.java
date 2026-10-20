// ThreadLocalL2.java — run with: /opt/jdk25/bin/java --enable-preview --source 25 ThreadLocalL2.java   (ScopedValue is final in 25; StructuredTaskScope is preview)
// The "cache an expensive object per thread" idiom (ThreadLocal<byte[]>, ThreadLocal<SimpleDateFormat>, …) assumes a
// few long-lived pooled threads. With a virtual thread per request, every request gets its own copy.
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;

public class ThreadLocalL2 {

    static final ThreadLocal<byte[]> BUFFER = ThreadLocal.withInitial(() -> new byte[16 * 1024]);   // 16 KB "per-thread" scratch buffer
    static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();

    static long heapMb() { System.gc(); return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / (1024 * 1024); }

    static void simulate(ExecutorService exec, int requests, String label) throws Exception {
        long before = heapMb();
        CountDownLatch inside = new CountDownLatch(requests), release = new CountDownLatch(1);
        for (int i = 0; i < requests; i++) exec.submit(() -> {
            BUFFER.get()[0] = 1;                                  // use the per-thread buffer
            inside.countDown();
            try { release.await(); } catch (InterruptedException ignored) { }   // request still in flight (waiting on I/O)
            return null;
        });
        inside.await(requests >= 20_000 ? 60 : 5, TimeUnit.SECONDS);
        System.out.printf("   %-42s buffers alive: %,6d   heap +%,5d MB%n", label, requests - (int) inside.getCount(), heapMb() - before);
        release.countDown();
        exec.shutdown(); exec.awaitTermination(1, TimeUnit.MINUTES);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("A. ThreadLocal<byte[16 KB]> as a per-thread cache, 20,000 concurrent requests");
        simulate(Executors.newFixedThreadPool(200), 200, "pool of 200 platform threads (200 in flight)");
        simulate(Executors.newVirtualThreadPerTaskExecutor(), 20_000, "virtual thread per request (20,000 in flight)");

        System.out.println("B. Request context: ScopedValue is bound for a scope and inherited by structured subtasks");
        ScopedValue.where(REQUEST_ID, "req-7f3a").run(() -> {
            System.out.println("   handler sees      : " + REQUEST_ID.get());
            try (var scope = StructuredTaskScope.open()) {                                   // JDK 25 preview API
                var price = scope.fork(() -> "pricing sees " + REQUEST_ID.get());
                var stock = scope.fork(() -> "inventory sees " + REQUEST_ID.get());
                scope.join();
                System.out.println("   subtasks          : " + price.get() + ", " + stock.get());
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        System.out.println("   outside the scope : bound = " + REQUEST_ID.isBound() + "  (nothing to clean up, nothing leaks to the next request)");
    }
}
