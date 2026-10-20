// PinningL2.java — run with BOTH: java PinningL2.java   (JDK 21)   and   /opt/jdk25/bin/java PinningL2.java   (JDK 25)
// 200 virtual threads each hold a lock while blocking for 50 ms (e.g. a legacy client doing I/O inside synchronized).
// On JDK 21 a virtual thread blocking inside `synchronized` PINS its carrier thread; JDK 24+ (JEP 491) fixes that.
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

public class PinningL2 {

    static long run(boolean useSynchronized) throws Exception {
        int n = 200;
        Object[] monitors = new Object[n]; ReentrantLock[] locks = new ReentrantLock[n];   // one lock per task: no contention
        for (int i = 0; i < n; i++) { monitors[i] = new Object(); locks[i] = new ReentrantLock(); }
        long t0 = System.nanoTime();
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                int ii = i;
                exec.submit(() -> {
                    if (useSynchronized) { synchronized (monitors[ii]) { sleep(50); } }
                    else { locks[ii].lock(); try { sleep(50); } finally { locks[ii].unlock(); } }
                });
            }
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    public static void main(String[] args) throws Exception {
        run(true); run(false);                                                             // warm up
        System.out.printf("JDK %s, %d carrier threads: 200 virtual threads × 50 ms blocking while holding a lock%n",
                Runtime.version().feature(), Runtime.getRuntime().availableProcessors());
        System.out.printf("   synchronized  : %,6d ms%n", run(true));
        System.out.printf("   ReentrantLock : %,6d ms%n", run(false));
    }
}
