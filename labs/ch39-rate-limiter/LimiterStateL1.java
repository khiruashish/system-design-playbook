// LimiterStateL1.java — run with (Redis 7 on :6390):  java LimiterStateL1.java   (JDK 21, no dependencies)
// WHAT A RATE LIMITER COSTS PER KEY, AND HOW ACCURATE IT IS. Ch 18 compared algorithms on one client. A platform limiter
// holds state for MILLIONS of keys (every user, device, IP, phone number, API key), so state size and accuracy per key
// decide the design. Part 1 stores 100,000 keys of each representation in a REAL Redis (pipelined) and measures memory
// with INFO: a sliding-window LOG (sorted set of timestamps), a sliding-window COUNTER (two fixed-window counters),
// a TOKEN BUCKET (hash of tokens + timestamp) and GCRA (one "theoretical arrival time" per key). Each key models a user
// halfway through a 60-requests-per-minute limit (30 requests in the window).
// Part 2 replays bursty traffic for 20,000 keys through exact and approximate algorithms in memory and measures how often
// the approximations decide differently, and the worst overshoot in any true 60-second window.
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class LimiterStateL1 {
    public static void main(String[] args) throws Exception {
        int keys = 100_000;
        System.out.printf("Part 1 — %,d keys per representation in real Redis (limit 60/min, 30 requests in the current window)%n%n", keys);
        System.out.printf("   %-40s %14s %18s%n", "representation", "bytes per key", "10M keys would need");
        try (Redis r = new Redis("127.0.0.1", 6390)) {
            long now = 1_790_000_000_000L; Random rnd = new Random(39);
            String[] names = {"sliding log (ZSET of 30 timestamps)", "sliding counter (2 window counters)", "token bucket (HASH tokens + ts)", "GCRA (one TAT integer)"};
            for (int kind = 0; kind < 4; kind++) {
                r.cmd("FLUSHALL"); long before = usedMemory(r);
                List<String[]> batch = new ArrayList<>();
                for (int k = 0; k < keys; k++) {
                    String key = "rl:" + kind + ":user:" + (9_000_000 + k);
                    switch (kind) {
                        case 0 -> { String[] c = new String[2 + 60]; c[0] = "ZADD"; c[1] = key;
                                    for (int i = 0; i < 30; i++) { long ts = now - rnd.nextInt(60_000); c[2 + 2 * i] = Long.toString(ts); c[3 + 2 * i] = ts + "-" + i; }
                                    batch.add(c); }
                        case 1 -> { long w = now / 60_000; batch.add(new String[]{"SET", key + ":" + w, "18"}); batch.add(new String[]{"SET", key + ":" + (w - 1), "41"}); }
                        case 2 -> batch.add(new String[]{"HSET", key, "t", "30.000", "ts", Long.toString(now)});
                        default -> batch.add(new String[]{"SET", key, Long.toString(now + 30_000)});
                    }
                    if (batch.size() >= 1000) { r.pipeline(batch); batch.clear(); }
                }
                r.pipeline(batch);
                long bytes = usedMemory(r) - before; double perKey = (double) bytes / keys;
                System.out.printf("   %-40s %,14.0f %15.2f GB%n", names[kind], perKey, perKey * 10_000_000 / 1e9);
            }
            r.cmd("FLUSHALL");
        }

        // ---------------- Part 2: accuracy ----------------
        int nKeys = 20_000, limit = 60; long windowMs = 60_000, horizon = 10 * 60_000; Random rnd = new Random(7);
        long decisions = 0, cntFalseAllow = 0, cntFalseDeny = 0, diffCounter = 0, diffGcra = 0, worstLog = 0, worstCounter = 0, worstFixed = 0, diffFixed = 0, worstG = 0;
        for (int k = 0; k < nKeys; k++) {
            // a user: quiet background plus 0–3 bursts (app retries, pull-to-refresh storms, scripts)
            List<Long> t = new ArrayList<>();
            double rate = 0.2 + rnd.nextDouble() * 0.8;                                   // requests per second baseline... per 10 s
            for (long ms = 0; ms < horizon; ms += (long) (-Math.log(1 - rnd.nextDouble()) * 10_000 / rate)) t.add(ms);
            int bursts = rnd.nextInt(4);
            for (int b = 0; b < bursts; b++) { long at = (long) (rnd.nextDouble() * horizon); int n = 20 + rnd.nextInt(60); for (int i = 0; i < n; i++) t.add(at + i * (50 + rnd.nextInt(400))); }
            Collections.sort(t);
            Deque<Long> log = new ArrayDeque<>(); long curW = -1, cur = 0, prev = 0; long fW = -1, fCount = 0;
            long emission = windowMs / limit;                                             // 1,000 ms per request
            long tat = 0;                                                                 // GCRA state: one integer
            long milliTokens = limit * 1000L, lastTs = 0;                                 // token bucket in exact integer milli-tokens
            List<Long> allowedLog = new ArrayList<>(), allowedCounter = new ArrayList<>(), allowedFixed = new ArrayList<>(), allowedG = new ArrayList<>();
            for (long ts : t) {
                if (ts >= horizon) continue; decisions++;
                // exact sliding log
                while (!log.isEmpty() && log.peekFirst() <= ts - windowMs) log.pollFirst();
                boolean aLog = log.size() < limit; if (aLog) { log.addLast(ts); allowedLog.add(ts); }
                // sliding window counter (Cloudflare-style weighted previous window)
                long w = ts / windowMs; if (w != curW) { prev = (w == curW + 1) ? cur : 0; cur = 0; curW = w; }
                double est = prev * (1 - (double) (ts % windowMs) / windowMs) + cur;
                boolean aCnt = est < limit; if (aCnt) { cur++; allowedCounter.add(ts); }
                // fixed window
                long fw = ts / windowMs; if (fw != fW) { fW = fw; fCount = 0; }
                boolean aFix = fCount < limit; if (aFix) { fCount++; allowedFixed.add(ts); }
                // token bucket and GCRA (same parameters: 1 per second, burst 60) — should agree exactly
                milliTokens = Math.min(limit * 1000L, milliTokens + (ts - lastTs) * 1000 / emission); lastTs = ts;
                boolean aTb = milliTokens >= 1000; if (aTb) milliTokens -= 1000;
                long newTat = Math.max(tat, ts) + emission; boolean aG = newTat - ts <= windowMs; if (aG) { tat = newTat; allowedG.add(ts); }
                if (aCnt && !aLog) cntFalseAllow++; if (!aCnt && aLog) cntFalseDeny++;
                if (aCnt != aLog) diffCounter++; if (aFix != aLog) diffFixed++; if (aG != aTb) diffGcra++;
            }
            worstLog = Math.max(worstLog, maxInWindow(allowedLog, windowMs));
            worstCounter = Math.max(worstCounter, maxInWindow(allowedCounter, windowMs));
            worstFixed = Math.max(worstFixed, maxInWindow(allowedFixed, windowMs));
            worstG = Math.max(worstG, maxInWindow(allowedG, windowMs));
        }
        System.out.printf("%nPart 2 — %,d decisions for %,d bursty keys over 10 minutes, limit %d per 60 s%n%n", decisions, nKeys, limit);
        System.out.printf("   %-44s %22s %28s%n", "algorithm", "decisions ≠ exact log", "max allowed in any 60 s");
        System.out.printf("   %-44s %22s %28d%n", "sliding window log (exact reference)", "—", worstLog);
        System.out.printf("   %-44s %21.3f%% %28d%n", "sliding window counter (weighted)", 100.0 * diffCounter / decisions, worstCounter);
        System.out.printf("   %-44s %21.3f%% %28d%n", "fixed window", 100.0 * diffFixed / decisions, worstFixed);
        System.out.printf("   %-44s %22s %28d%n", "GCRA / token bucket (1/s, burst 60)", "(a rate, not a window)", worstG);
        System.out.printf("   sliding counter errors: %,d extra allows, %,d extra denials (it assumes the previous window was evenly spread)%n", cntFalseAllow, cntFalseDeny);
        System.out.printf("   GCRA vs token bucket (1/s, burst 60): %,d of %,d decisions differ.%n", diffGcra, decisions);

        System.out.println("\n   → at platform scale the limiter is a memory problem first: the exact log stores every request (~1 KB per");
        System.out.println("     key here, ~10 GB for 10M keys); GCRA stores one integer and makes exactly the token bucket's decisions.");
        System.out.println("     The sliding-window counter is cheap but only approximate, and its error depends on the traffic's SHAPE:");
        System.out.println("     it assumes last window's requests were evenly spread, so a burst at the end of one window lets a key through");
        System.out.println("     well above the limit within a true 60 s span — better than the fixed window's double, but not a hard cap.");
        System.out.println("     Use GCRA/token bucket for rates, and an exact log only where 'at most N per window' must hold (OTP per phone).");
    }

    static long maxInWindow(List<Long> ts, long w) { int j = 0; long best = 0; for (int i = 0; i < ts.size(); i++) { while (ts.get(i) - ts.get(j) >= w) j++; best = Math.max(best, i - j + 1); } return best; }
    static long usedMemory(Redis r) throws IOException {
        String info = (String) r.cmd("INFO", "memory");
        for (String l : info.split("\r\n")) if (l.startsWith("used_memory:")) return Long.parseLong(l.substring(12));
        throw new IOException("no used_memory");
    }

    /** Minimal RESP client (from Ch 18) plus pipelining. */
    static final class Redis implements Closeable {
        final Socket s; final OutputStream out; final BufferedInputStream in;
        Redis(String host, int port) throws IOException { s = new Socket(host, port); out = new BufferedOutputStream(s.getOutputStream(), 1 << 16); in = new BufferedInputStream(s.getInputStream(), 1 << 16); }
        void write(String... parts) throws IOException {
            StringBuilder sb = new StringBuilder("*").append(parts.length).append("\r\n");
            for (String p : parts) { byte[] b = p.getBytes(StandardCharsets.UTF_8); sb.append('$').append(b.length).append("\r\n").append(p).append("\r\n"); }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        Object cmd(String... parts) throws IOException { write(parts); out.flush(); return read(); }
        void pipeline(List<String[]> cmds) throws IOException { for (String[] c : cmds) write(c); out.flush(); for (int i = 0; i < cmds.size(); i++) read(); }
        Object read() throws IOException {
            int type = in.read(); String line = line();
            return switch (type) {
                case '+' -> line; case ':' -> Long.parseLong(line);
                case '$' -> { int n = Integer.parseInt(line); if (n < 0) yield null; byte[] b = in.readNBytes(n); line(); yield new String(b, StandardCharsets.UTF_8); }
                case '*' -> { int n = Integer.parseInt(line); List<Object> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(read()); yield l; }
                case '-' -> throw new IOException("redis error: " + line);
                default -> throw new IOException("unexpected reply type " + (char) type);
            };
        }
        String line() throws IOException { StringBuilder sb = new StringBuilder(); int c; while ((c = in.read()) != '\r') sb.append((char) c); in.read(); return sb.toString(); }
        public void close() throws IOException { s.close(); }
    }
}
