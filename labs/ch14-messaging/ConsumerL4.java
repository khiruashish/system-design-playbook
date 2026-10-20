// ConsumerL4.java — run with: java ConsumerL4.java
// A. Where you commit the offset decides at-most-once vs at-least-once vs effectively-once.
// B. A poison message: blocking retries stall the partition; a retry topic keeps it flowing but breaks per-key order.
import java.util.*;

public class ConsumerL4 {

    enum Commit { BEFORE_PROCESSING, AFTER_PROCESSING, OFFSET_IN_DB_TXN }

    public static void main(String[] args) {
        System.out.println("A. 1,000,000 records, batches of 500, the consumer crashes in 2 % of batches at a random record");
        System.out.printf("   %-20s %12s %18s%n", "commit strategy", "never done", "done twice or more");
        for (Commit mode : Commit.values()) {
            Random rnd = new Random(9);
            int n = 1_000_000; int[] applied = new int[n];
            long committed = 0;                  // the group's committed offset (Kafka) — or the DB-stored offset
            long dbOffset = 0;                   // OFFSET_IN_DB_TXN: offset written in the same DB transaction as the effect
            long pos = 0;
            while (pos < n) {
                long end = Math.min(n, pos + 500);
                boolean crash = rnd.nextDouble() < 0.02; long crashAt = pos + rnd.nextInt((int) (end - pos));
                if (mode == Commit.BEFORE_PROCESSING) committed = end;          // enable.auto.commit on poll / commit first
                boolean crashed = false;
                for (long o = pos; o < end; o++) {
                    if (crash && o == crashAt) { crashed = true; break; }
                    if (mode == Commit.OFFSET_IN_DB_TXN) {
                        if (o >= dbOffset) { applied[(int) o]++; dbOffset = o + 1; }   // effect + offset: one DB transaction
                    } else applied[(int) o]++;
                }
                if (crashed) {                                                   // restart from the last committed position
                    pos = mode == Commit.OFFSET_IN_DB_TXN ? dbOffset : committed;
                    continue;
                }
                if (mode == Commit.AFTER_PROCESSING) committed = end;           // commitSync after the batch
                pos = end;
            }
            long never = Arrays.stream(applied).filter(a -> a == 0).count(), twice = Arrays.stream(applied).filter(a -> a > 1).count();
            System.out.printf("   %-20s %,12d %,18d%n", mode, never, twice);
        }

        System.out.println("B. 10,000 order events on one partition (100 orders x 100 status events), 2 ms each; event #500 fails 5 times (1 s backoff)");
        for (String strategy : List.of("blocking retries", "retry topic")) {
            long clock = 0; long maxLagMs = 0; int outOfOrder = 0;
            Map<Integer, Integer> lastSeq = new HashMap<>();
            Deque<int[]> retryTopic = new ArrayDeque<>();        // {orderId, seq, attemptsLeft, dueAt}
            int failuresLeft = 5;
            for (int i = 0; i < 10_000; i++) {
                int orderId = i % 100, seq = i / 100;
                long arrival = i * 2L;                            // events arrive every 2 ms
                clock = Math.max(clock, arrival);
                while (!retryTopic.isEmpty() && retryTopic.peek()[3] <= clock) {       // retry consumer runs in parallel
                    int[] r = retryTopic.poll();
                    if (lastSeq.getOrDefault(r[0], -1) > r[1]) outOfOrder++;
                    lastSeq.merge(r[0], r[1], Math::max);
                }
                if (i == 500) {
                    if (strategy.equals("blocking retries")) { clock += 5 * 1_000; failuresLeft = 0; }   // partition stalls
                    else { retryTopic.add(new int[]{orderId, seq, 0, (int) (clock + 5 * 1_000)}); clock += 2; continue; }
                }
                clock += 2;
                maxLagMs = Math.max(maxLagMs, clock - arrival);
                if (lastSeq.getOrDefault(orderId, -1) > seq) outOfOrder++;
                lastSeq.merge(orderId, seq, Math::max);
            }
            while (!retryTopic.isEmpty()) { int[] r = retryTopic.poll(); if (lastSeq.getOrDefault(r[0], -1) > r[1]) outOfOrder++; }
            System.out.printf("   %-17s max consumer lag %,6d ms   events applied out of order for their order: %d%n", strategy, maxLagMs, outOfOrder);
        }
    }
}
