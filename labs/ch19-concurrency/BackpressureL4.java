// BackpressureL4.java — run with: java -Xmx512m BackpressureL4.java
// Rider GPS pings arrive at ~50,000/s; the enrichment consumer handles ~10,000/s. For 4 s, what happens to memory and
// latency with an unbounded queue, a bounded blocking queue, and a reactive-streams publisher (JDK Flow) with demand?
import java.lang.management.ManagementFactory;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class BackpressureL4 {

    record Ping(long riderId, double lat, double lon, long createdNanos, byte[] payload) { }

    static long heapMb() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / (1024 * 1024); }
    static Ping ping(long i) { return new Ping(i % 50_000, 12.97, 77.59, System.nanoTime(), new byte[200]); }
    static void consumeOne(Ping p, AtomicLong maxLagMs) {
        long end = System.nanoTime() + 100_000; while (System.nanoTime() < end) Thread.onSpinWait();   // ~10,000/s
        maxLagMs.accumulateAndGet((System.nanoTime() - p.createdNanos()) / 1_000_000, Math::max);
    }

    static void queueRun(String label, BlockingQueue<Ping> q) throws Exception {
        AtomicLong produced = new AtomicLong(), consumed = new AtomicLong(), maxLag = new AtomicLong();
        AtomicBoolean stop = new AtomicBoolean();
        Thread consumer = Thread.ofPlatform().start(() -> { while (!stop.get()) { Ping p = q.poll(); if (p != null) { consumeOne(p, maxLag); consumed.incrementAndGet(); } } });
        long t0 = System.nanoTime(); long peakHeap = 0;
        Thread producer = Thread.ofPlatform().start(() -> {
            long i = 0;
            while (!stop.get()) {
                long due = t0 + i * 20_000;                                   // 50,000 per second
                while (System.nanoTime() < due) Thread.onSpinWait();
                try { q.put(ping(i++)); produced.incrementAndGet(); } catch (InterruptedException e) { return; }   // put() blocks when bounded
            }
        });
        for (int s = 0; s < 40; s++) { Thread.sleep(100); peakHeap = Math.max(peakHeap, heapMb()); }
        stop.set(true); producer.interrupt(); producer.join(); consumer.join();
        System.out.printf("   %-34s produced %,7d  consumed %,6d  queued %,7d  peak heap %,4d MB  max lag %,5d ms%n",
                label, produced.get(), consumed.get(), q.size(), peakHeap, maxLag.get());
    }

    public static void main(String[] args) throws Exception {
        System.out.println("GPS pings offered at 50,000/s, consumer handles ~10,000/s, 4 seconds");
        queueRun("unbounded LinkedBlockingQueue", new LinkedBlockingQueue<>());
        System.gc(); Thread.sleep(500);
        queueRun("bounded ArrayBlockingQueue(1,000)", new ArrayBlockingQueue<>(1_000));
        System.gc(); Thread.sleep(500);

        // Reactive Streams (java.util.concurrent.Flow): the subscriber requests N; the publisher drops when the buffer is full
        AtomicLong consumed = new AtomicLong(), dropped = new AtomicLong(), maxLag = new AtomicLong(), offered = new AtomicLong();
        try (SubmissionPublisher<Ping> pub = new SubmissionPublisher<>(ForkJoinPool.commonPool(), 1_024)) {
            pub.subscribe(new Flow.Subscriber<>() {
                Flow.Subscription sub;
                public void onSubscribe(Flow.Subscription s) { sub = s; s.request(64); }
                int n = 0;
                public void onNext(Ping p) { consumeOne(p, maxLag); consumed.incrementAndGet(); if (++n % 64 == 0) sub.request(64); }   // demand-driven
                public void onError(Throwable t) { }
                public void onComplete() { }
            });
            long t0 = System.nanoTime(), peakHeap = 0;
            for (long i = 0; System.nanoTime() - t0 < 4_000_000_000L; i++) {
                long due = t0 + i * 20_000; while (System.nanoTime() < due) Thread.onSpinWait();
                offered.incrementAndGet();
                // latest positions matter more than old ones: drop the ping instead of blocking the network reader
                pub.offer(ping(i), (s, p) -> { dropped.incrementAndGet(); return false; });
                if (i % 50_000 == 0) peakHeap = Math.max(peakHeap, heapMb());
            }
            System.out.printf("   %-34s offered  %,7d  consumed %,6d  dropped %,7d  peak heap %,4d MB  max lag %,5d ms%n",
                    "Flow publisher, demand + drop", offered.get(), consumed.get(), dropped.get(), peakHeap, maxLag.get());
        }
        System.exit(0);
    }
}
