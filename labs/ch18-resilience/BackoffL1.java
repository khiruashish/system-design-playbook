// BackoffL1.java — run with: java BackoffL1.java
// 1,000 app instances all fail at the same moment (pricing restarted) and retry. The server has 100 units of work
// per 10 ms tick: serving a call costs 1 unit, REJECTING one still costs 0.25 (accept, parse, reply 503).
// So an overloaded server serves fewer calls, not more — the classic congestion collapse.
import java.util.*;
import java.util.function.*;

public class BackoffL1 {

    record Result(int peakCallsPerTick, int ticksToFinish, long totalCalls) { }

    static Result run(BiFunction<Integer, Random, Integer> delayTicks, long seed) {
        Random r = new Random(seed);
        int clients = 1_000, capacity = 100;
        Map<Integer, List<Integer>> schedule = new TreeMap<>();   // tick → clients calling
        int[] attempt = new int[clients];
        for (int c = 0; c < clients; c++) schedule.computeIfAbsent(0, k -> new ArrayList<>()).add(c);
        int done = 0, peak = 0, tick = 0; long calls = 0;
        while (done < clients && tick < 10_000) {
            List<Integer> now = schedule.getOrDefault(tick, List.of());
            schedule.remove(tick);
            peak = Math.max(peak, now.size()); calls += now.size();
            Collections.shuffle(now, r);
            int servable = (int) Math.max(0, Math.min(Math.min(now.size(), capacity), (capacity - 0.25 * now.size()) / 0.75));
            for (int i = 0; i < now.size(); i++) {
                int c = now.get(i);
                if (i < servable) { done++; continue; }                  // served
                attempt[c]++;                                            // rejected → schedule a retry
                int next = tick + Math.max(1, delayTicks.apply(attempt[c], r));
                schedule.computeIfAbsent(next, k -> new ArrayList<>()).add(c);
            }
            tick++;
        }
        return new Result(peak, done < clients ? -1 : tick, calls);
    }

    public static void main(String[] args) {
        Map<String, BiFunction<Integer, Random, Integer>> strategies = new LinkedHashMap<>();
        strategies.put("immediate retry (every tick)", (a, r) -> 1);
        strategies.put("fixed 100 ms", (a, r) -> 10);
        strategies.put("exponential (10ms·2^n, cap 2 s)", (a, r) -> Math.min(200, 1 << Math.min(a, 20)));
        strategies.put("exponential + full jitter", (a, r) -> r.nextInt(Math.min(200, 1 << Math.min(a, 20)) + 1));
        strategies.put("exponential + equal jitter", (a, r) -> { int b = Math.min(200, 1 << Math.min(a, 20)); return b / 2 + r.nextInt(b / 2 + 1); });
        System.out.println("1,000 clients fail together; server: 100 units per 10 ms tick, serve = 1 unit, reject = 0.25 unit");
        System.out.printf("   %-34s %14s %16s %14s%n", "retry schedule", "peak calls/tick", "all served after", "total calls");
        strategies.forEach((name, f) -> {
            Result res = run(f, 42);
            System.out.printf("   %-34s %14d %16s %,14d%n", name, res.peakCallsPerTick(),
                    res.ticksToFinish() < 0 ? "never (100 s)" : String.format("%,d ms", res.ticksToFinish() * 10), res.totalCalls());
        });
    }
}
