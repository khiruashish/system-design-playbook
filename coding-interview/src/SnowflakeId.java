import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Problem: generate unique, roughly time-ordered 64-bit ids on many machines without coordination. */
public class SnowflakeId {

    // | 1 bit sign | 41 bits ms since epoch (~69 years) | 10 bits machine id (1024) | 12 bits sequence (4096/ms) |
    static final long EPOCH = 1_700_000_000_000L;                 // custom epoch: 2023-11-14
    static final int MACHINE_BITS = 10, SEQUENCE_BITS = 12;
    static final long MAX_MACHINE = (1L << MACHINE_BITS) - 1, MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    static final class Generator {
        private final long machineId;
        private final LongSupplier clock;
        private long lastMs = -1, sequence;

        Generator(long machineId, LongSupplier clock) {
            if (machineId < 0 || machineId > MAX_MACHINE) throw new IllegalArgumentException("machine id 0.." + MAX_MACHINE);
            this.machineId = machineId; this.clock = clock;
        }

        synchronized long next() {
            long now = clock.getAsLong();
            if (now < lastMs) throw new IllegalStateException("clock moved backwards by " + (lastMs - now) + " ms; refusing to generate");
            if (now == lastMs) {
                sequence = (sequence + 1) & MAX_SEQUENCE;
                if (sequence == 0) { while ((now = clock.getAsLong()) <= lastMs) Thread.onSpinWait(); }   // 4096 used: wait for next ms
            } else {
                sequence = 0;
            }
            lastMs = now;
            return ((now - EPOCH) << (MACHINE_BITS + SEQUENCE_BITS)) | (machineId << SEQUENCE_BITS) | sequence;
        }
    }

    static String explain(long id) {
        long seq = id & MAX_SEQUENCE, machine = (id >>> SEQUENCE_BITS) & MAX_MACHINE, ms = (id >>> (MACHINE_BITS + SEQUENCE_BITS)) + EPOCH;
        return "id=" + id + " -> time=" + java.time.Instant.ofEpochMilli(ms) + " machine=" + machine + " seq=" + seq;
    }

    public static void main(String[] args) throws Exception {
        long[] fakeNow = {1_735_689_600_000L};                    // 2025-01-01T00:00:00Z, controllable
        Generator g = new Generator(7, () -> fakeNow[0]);
        long id1 = g.next(), id2 = g.next();
        fakeNow[0] += 1;
        long id3 = g.next();
        System.out.println(explain(id1));
        System.out.println(explain(id2));
        System.out.println(explain(id3));
        check(id1 < id2 && id2 < id3, "monotonic on one machine");

        fakeNow[0] -= 5;
        try { g.next(); check(false, "backwards clock"); } catch (IllegalStateException e) { System.out.println("NTP step back: " + e.getMessage()); }

        // Real clock, 8 threads x 2 machines, 100k ids: all unique
        Generator m1 = new Generator(1, System::currentTimeMillis), m2 = new Generator(2, System::currentTimeMillis);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int t = 0; t < 8; t++) { Generator gen = t % 2 == 0 ? m1 : m2; pool.submit(() -> { for (int i = 0; i < 12_500; i++) ids.add(gen.next()); }); }
        }
        System.out.println("100000 ids from 2 machines x 8 threads -> unique: " + (ids.size() == 100_000));
        check(ids.size() == 100_000, "uniqueness");
        System.out.println("PASS");
    }

    static void check(boolean ok, String what) { if (!ok) { System.out.println("FAILED: " + what); System.exit(1); } }
}
