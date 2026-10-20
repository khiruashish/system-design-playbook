// GcWorkload.java — a fixed allocation-heavy workload used to compare GCs. Run under different -XX:+Use...GC flags with
// -Xlog:gc so the pauses are recorded. It keeps a churning LRU-ish set of live objects (steady live heap) and allocates
// short-lived garbage at a high rate — the classic pattern that stresses a collector — while recording its own request
// latencies so we can see GC pauses as latency, not just as GC-log lines.
import java.util.*;
import java.util.concurrent.*;

public class GcWorkload {
    public static void main(String[] args) throws Exception {
        int seconds = Integer.getInteger("seconds", 15);
        int liveMB = Integer.getInteger("liveMB", 300);
        // live set: a ring of byte arrays we keep replacing (steady ~liveMB of live data → forces real GC work, not just young)
        int slots = liveMB * 1024 / 16;                     // 16 KB objects
        byte[][] live = new byte[slots][];
        for (int i = 0; i < slots; i++) live[i] = new byte[16 * 1024];
        Random r = new Random(7);

        // one "request" thread: does a little work, allocates garbage, records latency; GC pauses show up as spikes here
        List<Long> latenciesNs = new ArrayList<>(2_000_000);
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        long allocated = 0;
        while (System.nanoTime() < end) {
            long t0 = System.nanoTime();
            // allocate ~1 MB of short-lived garbage per request and touch it
            int junkSlots = 64; byte[][] junk = new byte[junkSlots][];
            long acc = 0;
            for (int j = 0; j < junkSlots; j++) { junk[j] = new byte[16 * 1024]; junk[j][0] = (byte) j; acc += junk[j].length; }
            allocated += acc;
            // replace some live objects (keeps the old generation churning → mixed/major GC work)
            for (int k = 0; k < 8; k++) live[r.nextInt(slots)] = new byte[16 * 1024];
            latenciesNs.add(System.nanoTime() - t0);
        }
        long[] a = latenciesNs.stream().mapToLong(Long::longValue).sorted().toArray();
        java.util.function.DoubleFunction<Double> p = q -> a[Math.min(a.length - 1, (int) Math.ceil(q * a.length) - 1)] / 1e6;
        System.out.printf("REQS=%d ALLOC_GB=%.1f P50_MS=%.3f P99_MS=%.3f P999_MS=%.3f MAX_MS=%.1f%n",
                a.length, allocated / 1e9, p.apply(.5), p.apply(.99), p.apply(.999), a[a.length - 1] / 1e6);
    }
}
