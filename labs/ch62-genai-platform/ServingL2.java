import java.util.*;

/**
 * L2 — Serving an LLM on GPUs: how batching policy decides latency, throughput and the size of the GPU fleet.
 * Discrete-event SIMULATION with a stated cost model (illustrative, in the range published for an 8B-parameter model on one
 * 80 GB GPU). The schedulers are real algorithms; the GPU is a formula. Fixed seed: output is deterministic.
 */
public class ServingL2 {
    // ---- cost model (ILLUSTRATIVE) ----
    static final double DECODE_STEP_MS = 22;            // one decode iteration, memory-bandwidth bound...
    static final double DECODE_PER_SEQ_MS = 0.35;       // ...plus a little per sequence in the batch
    static final double PREFILL_MS_PER_1K = 9;          // prompt processing is compute bound: ~110k tokens/s
    static final long KV_BYTES_PER_TOKEN = 131_072;     // 32 layers x 8 KV heads x 128 dims x (K,V) x 2 bytes (GQA, fp16)
    static final long KV_BUDGET = 56L << 30;            // 80 GB - 16 GB weights - 8 GB activations/overhead
    static final int SYSTEM_PROMPT = 1_200, MAX_NEW = 512; // shared system prompt + tool schemas; generation cap
    static final int MAX_SEQ = 16_384;                  // the model's context window: what pre-paging engines reserved per request
    static final double GPU_HOUR_USD = 2.50;

    record Req(int id, double arrival, int prompt, int output) {}
    static final class Live { Req r; int done; double firstToken = -1; boolean prefilled; long reserved; Live(Req r) { this.r = r; } }

    public static void main(String[] args) {
        System.out.println("One GPU serving the assistant (8B model, illustrative cost model). Prompt = 1,200-token shared system prompt");
        System.out.println("+ ~1,600 tokens of retrieved context + question; answers ~220 tokens (capped at 512). SLO: TTFT p99 <= 2 s.\n");
        String[] policies = {"one at a time", "static batch of 16", "continuous batching, reserve 16k KV", "continuous batching, paged KV",
            "paged KV + prefix cache"};
        System.out.println("A. Load sweep (requests/s offered to one GPU): time to first token (TTFT) and goodput");
        System.out.printf("   %-40s %6s %10s %10s %12s %13s%n", "policy", "req/s", "TTFT p50", "TTFT p99", "tokens/s", "avg batch");
        Map<String, Double> maxRate = new LinkedHashMap<>();
        for (String p : policies) {
            double best = 0;
            for (double rate : new double[]{0.1, 0.2, 0.5, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12}) {
                Result r = simulate(p, rate, 600, 62, 1_600);
                boolean ok = r.ttftP99 <= 2_000 && r.completed >= 0.98 * r.offered;
                if (ok) best = rate;
                if (rate == 0.1 || rate == 4 || rate == 9)
                    System.out.printf("   %-40s %6.1f %9.0fms %9s %12.0f %13.1f%n", p, rate, r.ttftP50,
                        r.ttftP99 > 60_000 ? ">60 s" : String.format("%.0fms", r.ttftP99), r.tokensPerSec, r.avgBatch);
            }
            maxRate.put(p, best);
        }

        System.out.println("\nB. Capacity at the SLO, and the fleet for the dinner-time peak (180 requests/s)");
        System.out.printf("   %-40s %14s %10s %18s%n", "policy", "max req/s/GPU", "GPUs", "cost per 1k answers");
        for (var e : maxRate.entrySet()) {
            double r = e.getValue();
            String gpus = r == 0 ? "n/a" : String.valueOf((int) Math.ceil(180 / r));
            String cost = r == 0 ? "n/a" : String.format("$%.3f", GPU_HOUR_USD / 3600 / r * 1000);
            System.out.printf("   %-40s %14.1f %10s %18s%n", e.getKey(), r, gpus, cost);
        }
        long kvTokens = KV_BUDGET / KV_BYTES_PER_TOKEN;
        System.out.printf("%n   KV cache budget: %,d tokens (%.0f GB at %d KB/token). Reserving the %,d-token context window per request fits %d%n",
            kvTokens, KV_BUDGET / Math.pow(2, 30), KV_BYTES_PER_TOKEN / 1024, MAX_SEQ, kvTokens / MAX_SEQ);
        System.out.printf("   requests at once; paging in 16-token blocks allocates only what is used (~%d typical requests).%n",
            kvTokens / (2_800 + 220));

        System.out.println("\nC. Long-context requests (order history + documents, ~9,000-token prompts): memory per request is 3x larger");
        System.out.printf("   %-40s %14s %16s%n", "policy", "max req/s/GPU", "max concurrent");
        for (String p : new String[]{"continuous batching, reserve 16k KV", "continuous batching, paged KV", "paged KV + prefix cache"}) {
            double best = 0;
            for (double rate = 0.25; rate <= 4; rate += 0.25) {
                Result r = simulate(p, rate, 600, 62, 7_800);
                if (r.ttftP99 <= 2_000 && r.completed >= 0.98 * r.offered) best = rate;
            }
            Result at = simulate(p, 3, 600, 62, 7_800);
            System.out.printf("   %-40s %14.2f %16d%n", p, best, at.maxConcurrent);
        }
        System.out.println("\n   -> the model is the same in every row: scheduling alone changes how many GPUs you buy.");
        System.out.println("     Static batches wait for their slowest member; continuous batching admits work every iteration.");
        System.out.println("     Paged KV memory raises concurrency; prefix caching skips re-reading the 1,200-token system prompt.");
    }

    record Result(double ttftP50, double ttftP99, double tokensPerSec, double avgBatch, int completed, int offered, int maxConcurrent) {}

    static Result simulate(String policy, double rate, double seconds, long seed, int contextMean) {
        Random rnd = new Random(seed);
        List<Req> reqs = new ArrayList<>();
        double t = 0; int id = 0;
        while ((t += -Math.log(1 - rnd.nextDouble()) / rate * 1000) < seconds * 1000) {
            int ctx = (int) (rnd.nextGaussian() * contextMean * 0.2 + contextMean);   // retrieved chunks (+ history) + question
            int out = (int) Math.min(MAX_NEW, Math.max(8, Math.exp(rnd.nextGaussian() * 0.6 + Math.log(185))));
            reqs.add(new Req(id++, t, SYSTEM_PROMPT + Math.max(200, ctx), out));
        }
        boolean continuous = policy.startsWith("continuous") || policy.startsWith("paged");
        boolean paged = policy.contains("paged"), prefix = policy.contains("prefix");
        int staticBatch = policy.startsWith("static") ? 16 : policy.startsWith("one") ? 1 : Integer.MAX_VALUE;
        long kvCap = KV_BUDGET / KV_BYTES_PER_TOKEN;

        ArrayDeque<Req> queue = new ArrayDeque<>();
        List<Live> running = new ArrayList<>();
        List<Double> ttft = new ArrayList<>();
        double now = 0, batchSum = 0; long tokens = 0, steps = 0; int next = 0, completed = 0, maxConc = 0; long kvUsed = 0;
        double horizon = (seconds + 120) * 1000;   // drain for 2 minutes after arrivals stop
        while (now < horizon && (next < reqs.size() || !queue.isEmpty() || !running.isEmpty())) {
            while (next < reqs.size() && reqs.get(next).arrival() <= now) queue.add(reqs.get(next++));
            // admission
            if (continuous) {
                while (!queue.isEmpty()) {
                    Req r = queue.peek();
                    // contiguous KV: reserve the whole context window up front; paged: only the prompt now, more pages as it grows
                    long need = paged ? (r.prompt() - (prefix ? SYSTEM_PROMPT : 0) + 16) : MAX_SEQ;
                    if (kvUsed + need > kvCap) break;
                    Live l = new Live(queue.poll()); l.reserved = need; kvUsed += need; running.add(l);
                }
            } else if (running.isEmpty() && !queue.isEmpty()) {
                // static: start a batch when full, or when the oldest request has waited 50 ms
                if (queue.size() >= staticBatch || now - queue.peek().arrival() >= 50 || staticBatch == 1)
                    for (int i = 0; i < staticBatch && !queue.isEmpty(); i++) running.add(new Live(queue.poll()));
            }
            if (running.isEmpty()) {
                // idle: jump to the next event, which is either an arrival or the end of a static batch's 50 ms window
                double nextEvent = next < reqs.size() ? reqs.get(next).arrival() : horizon;
                if (!continuous && !queue.isEmpty()) nextEvent = Math.min(nextEvent, queue.peek().arrival() + 50);
                now = Math.max(now, nextEvent);
                continue;
            }
            // one engine iteration: prefill newly admitted requests, then one decode step for everyone
            double stepMs = 0; int prefillTokens = 0;
            for (Live l : running) if (!l.prefilled) { prefillTokens += l.r.prompt() - (prefix ? SYSTEM_PROMPT : 0); l.prefilled = true; }
            stepMs += prefillTokens / 1000.0 * PREFILL_MS_PER_1K;
            stepMs += DECODE_STEP_MS + DECODE_PER_SEQ_MS * running.size();
            now += stepMs; steps++; batchSum += running.size(); maxConc = Math.max(maxConc, running.size());
            for (Iterator<Live> it = running.iterator(); it.hasNext(); ) {
                Live l = it.next();
                if (l.firstToken < 0) { l.firstToken = now; ttft.add(now - l.r.arrival()); }
                l.done++; tokens++;
                if (paged && l.done % 16 == 0) kvUsed += 16;            // pages grow with the answer
                if (l.done >= l.r.output() && continuous) { kvUsed -= l.reserved + (paged ? l.done / 16 * 16 : 0); it.remove(); completed++; }
            }
            if (!continuous && running.stream().allMatch(l -> l.done >= l.r.output())) { completed += running.size(); running.clear(); }
        }
        Collections.sort(ttft);
        double p50 = ttft.isEmpty() ? 0 : ttft.get(ttft.size() / 2), p99 = ttft.isEmpty() ? 0 : ttft.get((int) (ttft.size() * 0.99));
        // requests never started count as missing the SLO
        if (ttft.size() < reqs.size()) p99 = Math.max(p99, 1e9);
        return new Result(p50, p99, tokens / (now / 1000), steps == 0 ? 0 : batchSum / steps, completed, reqs.size(), maxConc);
    }
}
