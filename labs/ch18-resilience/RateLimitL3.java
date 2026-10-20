// RateLimitL3.java — run with: java RateLimitL3.java     (part C needs redis-server on :6391, started by the lab script)
// A/B. Five rate-limiting algorithms on the same traffic; limit = 100 requests per second.
// C.   Four app instances limiting locally vs one shared token bucket in Redis (Lua, atomic) — real Redis over RESP.
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class RateLimitL3 {

    interface Limiter { boolean allow(long nowMs); }

    static final class FixedWindow implements Limiter {
        long window = -1; int count; final int limit; FixedWindow(int limit) { this.limit = limit; }
        public boolean allow(long now) { long w = now / 1000; if (w != window) { window = w; count = 0; } if (count < limit) { count++; return true; } return false; }
    }
    static final class SlidingLog implements Limiter {
        final Deque<Long> log = new ArrayDeque<>(); final int limit; SlidingLog(int limit) { this.limit = limit; }
        public boolean allow(long now) { while (!log.isEmpty() && log.peekFirst() <= now - 1000) log.pollFirst(); if (log.size() < limit) { log.addLast(now); return true; } return false; }
    }
    static final class SlidingCounter implements Limiter {                     // Cloudflare-style weighted estimate
        long window = -1; int cur, prev; final int limit; SlidingCounter(int limit) { this.limit = limit; }
        public boolean allow(long now) {
            long w = now / 1000;
            if (w != window) { prev = (w == window + 1) ? cur : 0; cur = 0; window = w; }
            double est = prev * (1 - (now % 1000) / 1000.0) + cur;
            if (est < limit) { cur++; return true; } return false;
        }
    }
    static final class TokenBucket implements Limiter {
        final double rate, capacity; double tokens; long last;
        TokenBucket(double ratePerSec, double capacity) { this.rate = ratePerSec / 1000.0; this.capacity = capacity; this.tokens = capacity; }
        public boolean allow(long now) { tokens = Math.min(capacity, tokens + (now - last) * rate); last = now; if (tokens >= 1) { tokens -= 1; return true; } return false; }
    }
    /** Leaky bucket as a QUEUE (shaper): requests wait and leave at a constant rate; rejected only when the queue is full. */
    static final class LeakyBucketQueue {
        final double intervalMs; final int queueCap; double nextFree = 0; final List<Double> delays = new ArrayList<>(); int rejected;
        LeakyBucketQueue(double ratePerSec, int queueCap) { this.intervalMs = 1000.0 / ratePerSec; this.queueCap = queueCap; }
        void offer(long now) {
            double start = Math.max(now, nextFree);
            if ((start - now) / intervalMs >= queueCap) { rejected++; return; }
            delays.add(start - now); nextFree = start + intervalMs;
        }
    }

    public static void main(String[] args) throws Exception {
        // traffic: 80 req/s steady for 3 s, plus a burst of 150 requests between 990 and 1010 ms (straddling a window edge)
        List<Long> arrivals = new ArrayList<>();
        for (long t = 0; t < 3000; t += 12) arrivals.add(t);                    // ~83 req/s
        for (int i = 0; i < 150; i++) arrivals.add(990 + i * 20L / 150);
        Collections.sort(arrivals);

        System.out.printf("A. Limit 100 req/s. Traffic: ~83 req/s steady + a 150-request burst at 990–1010 ms (%d requests)%n", arrivals.size());
        System.out.printf("   %-32s %8s %9s %28s%n", "algorithm", "allowed", "rejected", "max allowed in any 1 s span");
        Map<String, Limiter> algos = new LinkedHashMap<>();
        algos.put("fixed window", new FixedWindow(100));
        algos.put("sliding window log", new SlidingLog(100));
        algos.put("sliding window counter", new SlidingCounter(100));
        algos.put("token bucket (100/s, burst 100)", new TokenBucket(100, 100));
        algos.put("token bucket (100/s, burst 20)", new TokenBucket(100, 20));
        for (var e : algos.entrySet()) {
            List<Long> ok = new ArrayList<>();
            for (long t : arrivals) if (e.getValue().allow(t)) ok.add(t);
            int maxSpan = 0;
            for (int i = 0, j = 0; i < ok.size(); i++) { while (ok.get(i) - ok.get(j) >= 1000) j++; maxSpan = Math.max(maxSpan, i - j + 1); }
            System.out.printf("   %-32s %8d %9d %28d%n", e.getKey(), ok.size(), arrivals.size() - ok.size(), maxSpan);
        }

        System.out.println("B. Leaky bucket as a queue (drains 100/s, queue of 50): shapes instead of dropping");
        LeakyBucketQueue lb = new LeakyBucketQueue(100, 50);
        for (long t : arrivals) lb.offer(t);
        double maxDelay = lb.delays.stream().mapToDouble(d -> d).max().orElse(0);
        System.out.printf("   accepted %d, rejected %d, added delay max %.0f ms (the burst waits instead of being refused)%n",
                lb.delays.size(), lb.rejected, maxDelay);

        System.out.println("C. 4 checkout pods, limit for pricing-service = 100 req/s TOTAL, each pod offered 60 req/s for 3 s");
        try (Redis redis = new Redis("127.0.0.1", 6391)) {
            String sha = redis.scriptLoad(TOKEN_BUCKET_LUA);
            redis.del("rl:pricing");
            int[] local = new int[4], global = new int[4];
            TokenBucket[] locals = {new TokenBucket(100, 100), new TokenBucket(100, 100), new TokenBucket(100, 100), new TokenBucket(100, 100)};
            long base = System.currentTimeMillis(); Random shuffle = new Random(1);
            for (int step = 0; step < 180; step++) {                             // 3 s, each pod sends one request every 16.7 ms
                long now = base + step * 1000L / 60;
                List<Integer> order = new ArrayList<>(List.of(0, 1, 2, 3)); Collections.shuffle(order, shuffle);   // pods race
                for (int pod : order) {
                    if (locals[pod].allow(now - base)) local[pod]++;
                    if (redis.evalshaAllowed(sha, "rl:pricing", 100, 100, now)) global[pod]++;
                }
            }
            System.out.printf("   local limiter per pod (100/s each)   : allowed %d in 3 s → %.0f req/s reach pricing%n", Arrays.stream(local).sum(), Arrays.stream(local).sum() / 3.0);
            System.out.printf("   shared Redis token bucket (Lua)      : allowed %d in 3 s → %.0f req/s reach pricing  (per pod %s)%n",
                    Arrays.stream(global).sum(), Arrays.stream(global).sum() / 3.0, Arrays.toString(global));
        }
    }

    /** KEYS[1]=bucket; ARGV: rate/s, capacity, now_ms. Atomic in Redis: refill, take one token if available. */
    static final String TOKEN_BUCKET_LUA = """
            local b = redis.call('HMGET', KEYS[1], 't', 'ts')
            local rate, cap, now = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3])
            local tokens = tonumber(b[1]) or cap
            local ts = tonumber(b[2]) or now
            tokens = math.min(cap, tokens + math.max(0, now - ts) * rate / 1000)
            local ok = 0
            if tokens >= 1 then tokens = tokens - 1; ok = 1 end
            redis.call('HSET', KEYS[1], 't', tokens, 'ts', now)
            redis.call('PEXPIRE', KEYS[1], math.ceil(cap / rate * 1000) + 1000)
            return ok""";

    /** Minimal RESP client: enough for SCRIPT LOAD, EVALSHA, DEL. */
    static final class Redis implements Closeable {
        final Socket s; final OutputStream out; final BufferedInputStream in;
        Redis(String host, int port) throws IOException { s = new Socket(host, port); out = s.getOutputStream(); in = new BufferedInputStream(s.getInputStream()); }
        Object cmd(String... parts) throws IOException {
            StringBuilder sb = new StringBuilder("*").append(parts.length).append("\r\n");
            for (String p : parts) { byte[] b = p.getBytes(StandardCharsets.UTF_8); sb.append('$').append(b.length).append("\r\n").append(p).append("\r\n"); }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8)); out.flush();
            return read();
        }
        Object read() throws IOException {
            int type = in.read(); String line = line();
            return switch (type) {
                case '+' -> line; case ':' -> Long.parseLong(line);
                case '$' -> { int n = Integer.parseInt(line); if (n < 0) yield null; byte[] b = in.readNBytes(n); line(); yield new String(b, StandardCharsets.UTF_8); }
                case '-' -> throw new IOException("redis error: " + line);
                default -> throw new IOException("unexpected reply type " + (char) type);
            };
        }
        String line() throws IOException { StringBuilder sb = new StringBuilder(); int c; while ((c = in.read()) != '\r') sb.append((char) c); in.read(); return sb.toString(); }
        String scriptLoad(String lua) throws IOException { return (String) cmd("SCRIPT", "LOAD", lua); }
        void del(String key) throws IOException { cmd("DEL", key); }
        boolean evalshaAllowed(String sha, String key, int rate, int cap, long now) throws IOException {
            return ((Long) cmd("EVALSHA", sha, "1", key, Integer.toString(rate), Integer.toString(cap), Long.toString(now))) == 1L;
        }
        public void close() throws IOException { s.close(); }
    }
}
