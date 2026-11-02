import java.util.*;

/** Problem: limit each user to N requests per rolling window. Compare sliding LOG (exact) and sliding COUNTER (approximate). */
public class SlidingWindowRateLimiter {

    /** Exact: keep a timestamp per accepted request. Memory O(N) per user. (Redis: ZSET + ZREMRANGEBYSCORE + ZCARD) */
    static final class SlidingLog {
        private final int limit; private final long windowMs;
        private final Map<String, Deque<Long>> logs = new HashMap<>();
        SlidingLog(int limit, long windowMs) { this.limit = limit; this.windowMs = windowMs; }

        boolean allow(String user, long nowMs) {
            Deque<Long> log = logs.computeIfAbsent(user, u -> new ArrayDeque<>());
            while (!log.isEmpty() && log.peekFirst() <= nowMs - windowMs) log.pollFirst();   // drop expired
            if (log.size() >= limit) return false;
            log.addLast(nowMs);
            return true;
        }
    }

    /** Approximate: two fixed-window counters, weighting the previous window by its overlap. Memory O(1) per user. */
    static final class SlidingCounter {
        private final int limit; private final long windowMs;
        private final Map<String, long[]> state = new HashMap<>();   // [windowStart, currentCount, previousCount]
        SlidingCounter(int limit, long windowMs) { this.limit = limit; this.windowMs = windowMs; }

        boolean allow(String user, long nowMs) {
            long windowStart = nowMs - Math.floorMod(nowMs, windowMs);
            long[] s = state.computeIfAbsent(user, u -> new long[]{windowStart, 0, 0});
            if (s[0] != windowStart) {                                  // rolled into a new window
                s[2] = (windowStart - s[0] == windowMs) ? s[1] : 0;     // previous = last count, or 0 if we skipped windows
                s[1] = 0;
                s[0] = windowStart;
            }
            double overlap = 1.0 - (double) (nowMs - windowStart) / windowMs;
            double estimated = s[2] * overlap + s[1];
            if (estimated >= limit) return false;
            s[1]++;
            return true;
        }
    }

    /** The naive fixed window, for contrast: allows 2x the limit around a boundary. */
    static final class FixedWindow {
        private final int limit; private final long windowMs; private long windowStart = -1; private int count;
        FixedWindow(int limit, long windowMs) { this.limit = limit; this.windowMs = windowMs; }
        boolean allow(long nowMs) {
            long ws = nowMs - Math.floorMod(nowMs, windowMs);
            if (ws != windowStart) { windowStart = ws; count = 0; }
            return count < limit && ++count > 0;
        }
    }

    public static void main(String[] args) {
        int limit = 10; long window = 60_000;

        // Burst at the boundary: 10 requests at 0:59.9 and 10 at 1:00.1
        FixedWindow fixed = new FixedWindow(limit, window);
        SlidingLog log = new SlidingLog(limit, window);
        SlidingCounter counter = new SlidingCounter(limit, window);
        int fixedOk = 0, logOk = 0, counterOk = 0;
        for (int i = 0; i < 10; i++) { if (fixed.allow(59_900)) fixedOk++; if (log.allow("u", 59_900)) logOk++; if (counter.allow("u", 59_900)) counterOk++; }
        for (int i = 0; i < 10; i++) { if (fixed.allow(60_100)) fixedOk++; if (log.allow("u", 60_100)) logOk++; if (counter.allow("u", 60_100)) counterOk++; }
        System.out.println("20 requests in 200 ms across a window boundary (limit 10/min):");
        System.out.println("  fixed window   allowed " + fixedOk + "  <-- 2x burst");
        System.out.println("  sliding log    allowed " + logOk);
        System.out.println("  sliding counter allowed " + counterOk + "  (approximate: previous 10 x 99.8% overlap = 9.98 < 10 lets one more in)");
        check(fixedOk == 20 && logOk == 10 && counterOk <= limit + 1, "sliding algorithms stop the boundary burst");

        // Steady traffic: 1 request every 5 s for 3 minutes -> 12/min offered, 10/min allowed
        SlidingLog log2 = new SlidingLog(limit, window);
        SlidingCounter counter2 = new SlidingCounter(limit, window);
        int a = 0, b = 0;
        for (long t = 0; t < 180_000; t += 5_000) { if (log2.allow("u", t)) a++; if (counter2.allow("u", t)) b++; }
        System.out.println("36 requests evenly over 3 min: sliding log allowed " + a + ", sliding counter allowed " + b);
        check(a == 30 && Math.abs(a - b) <= 3, "counter approximates the log closely");
        System.out.println("PASS");
    }

    static void check(boolean ok, String what) { if (!ok) { System.out.println("FAILED: " + what); System.exit(1); } }
}
