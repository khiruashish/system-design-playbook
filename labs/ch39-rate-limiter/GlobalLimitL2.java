// GlobalLimitL2.java — run with (Redis 7 on :6390):  java GlobalLimitL2.java   (JDK 21, no dependencies)
// ENFORCING ONE GLOBAL LIMIT FROM 8 GATEWAY NODES. A logistics partner's API key may call us at 1,000 req/s (burst 100).
// Requests arrive at 8 gateway nodes; the limit is for the key as a whole. Three ways to enforce it, all executed for real
// (the Lua scripts run in a real Redis; time is a simulated clock passed in, so runs are reproducible):
//   A) LOCAL ONLY: each node enforces 1,000/8 = 125 req/s with its own GCRA           — no network calls
//   B) CENTRAL GCRA: every request runs one Lua call in Redis                          — exact, one round-trip per request
//   C) TOKEN LEASING: a node takes a lease of tokens (sized to ~50 ms of its own traffic, 1–20) from the Redis GCRA
//      and spends them locally; unused tokens are given back when the lease expires after 100 ms; leases are at least
//      5 tokens (or the node's size), and a denied node caches the denial until they are due — few round-trips
// Two traffic scenarios over 10 s: (1) UNDER the limit but SKEWED — 900 req/s, 60% of it landing on node 0 (a sticky
// load-balancer or one partner data centre); (2) OVER the limit — 2,000 req/s spread evenly.
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class GlobalLimitL2 {
    static final long EMISSION_US = 1_000, TOLERANCE_US = 100 * EMISSION_US;     // 1,000/s, burst 100
    static final String LEASE = """
            local tat = tonumber(redis.call('GET', KEYS[1]) or '0')
            local now, em, tol, want = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3]), tonumber(ARGV[4])
            local minGrant = math.min(want, 5)                   -- don't take leases smaller than 5 tokens
            local base = math.max(tat, now)
            local room = math.floor((now + tol - base) / em)
            local n = math.min(want, room)
            if n < minGrant then return -(base + minGrant * em - now - tol) end   -- denied: µs until minGrant tokens fit
            redis.call('SET', KEYS[1], base + n * em, 'PX', 60000)
            return n""";
    static final String GIVE_BACK = """
            local tat = tonumber(redis.call('GET', KEYS[1]) or '0')
            local now, back = tonumber(ARGV[1]), tonumber(ARGV[2])
            if tat > now then redis.call('SET', KEYS[1], math.max(now, tat - back), 'PX', 60000) end
            return 1""";

    static String shaBack; static final String KEY = "rl:partner:logistics-7";
    record Result(double allowedPerSec, long rejected, long redisCalls, long requests, long wasted, double maxSecond) {}

    public static void main(String[] args) throws Exception {
        try (Redis r = new Redis("127.0.0.1", 6390)) {
            String sha = (String) r.cmd("SCRIPT", "LOAD", LEASE); shaBack = (String) r.cmd("SCRIPT", "LOAD", GIVE_BACK);
            System.out.println("Global limit 1,000 req/s (burst 100) for one partner key, 8 gateway nodes, 10 s simulated, real Redis Lua.\n");
            long t0 = System.nanoTime(); long calls0 = 0;
            for (int sc = 0; sc < 2; sc++) {
                double total = sc == 0 ? 900 : 2000; double[] share = new double[8];
                for (int n = 0; n < 8; n++) share[n] = sc == 0 ? (n == 0 ? 0.60 : 0.40 / 7) : 1.0 / 8;
                long[][] arrivals = arrivals(total, share, 10_000_000L, new Random(39 + sc));
                long requests = Arrays.stream(arrivals).mapToLong(a -> a.length).sum();
                System.out.printf("   Scenario %d — %s: %,.0f req/s offered%s%n", sc + 1, sc == 0 ? "UNDER the limit, skewed" : "OVER the limit, even",
                        total, sc == 0 ? " (node 0 gets 60% = 540 req/s)" : "");
                System.out.printf("      %-38s %13s %11s %13s %16s %15s%n", "strategy", "allowed/s", "rejected", "Redis calls", "calls/request", "busiest second");
                for (int st = 0; st < 3; st++) {
                    r.cmd("DEL", KEY);
                    Result res = run(st, arrivals, r, sha);
                    calls0 += res.redisCalls();
                    System.out.printf("      %-38s %,13.0f %,11d %,13d %16.3f %,15.0f%n", new String[]{"A) local only (125/s per node)", "B) central GCRA in Redis", "C) leasing (adaptive 1-20, 100 ms)"}[st],
                            res.allowedPerSec(), res.rejected(), res.redisCalls(), (double) res.redisCalls() / res.requests(), res.maxSecond());
                    if (st == 2) System.out.printf("         leased tokens expired unused and given back: %,d%n", res.wasted());
                }
                System.out.println();
            }
            // real round-trip cost of one Lua call
            long n = 20_000, s = System.nanoTime();
            for (int i = 0; i < n; i++) r.cmd("EVALSHA", sha, "1", "rl:bench", Long.toString(i * 2_000L), Long.toString(EMISSION_US), Long.toString(TOLERANCE_US), "1");
            double us = (System.nanoTime() - s) / 1e3 / n;
            System.out.printf("   Measured cost of one Lua call on localhost: %.0f µs round-trip (cross-AZ adds ~0.5–1 ms; VERIFY in your network).%n", us);
        }
        System.out.println("\n   → local limits need no network but are wrong under skew: they rejected thousands of requests from a partner");
        System.out.println("     within its contract, because node 0 saw 540 req/s against a 125 share; they cap the total only when load is");
        System.out.println("     even. A central GCRA is exact but costs a Redis round-trip on every request. Leasing admitted exactly what");
        System.out.println("     the central limiter did under skew with about a sixth of the calls, and held the cap when over the limit.");
        System.out.println("     It needed three refinements to get there (in earlier runs of this lab, fixed 20-token leases stranded tokens");
        System.out.println("     on quiet nodes and rejected in-contract traffic): size leases to each node's rate, give unused tokens back,");
        System.out.println("     and cache denials. Over the limit, calls rise again because every lease is small — tokens are scarce.");
    }

    static long[][] arrivals(double totalRate, double[] share, long horizonUs, Random rnd) {
        long[][] a = new long[share.length][];
        for (int n = 0; n < share.length; n++) {
            List<Long> l = new ArrayList<>(); double rate = totalRate * share[n] / 1e6;
            for (double t = 0; ; ) { t += -Math.log(1 - rnd.nextDouble()) / rate; if (t >= horizonUs) break; l.add((long) t); }
            a[n] = l.stream().mapToLong(Long::longValue).toArray();
        }
        return a;
    }

    static Result run(int strategy, long[][] arrivals, Redis r, String sha) throws IOException {
        int nodes = arrivals.length; int[] idx = new int[nodes];
        long[] localTat = new long[nodes]; long[] leaseTokens = new long[nodes]; long[] leaseExpiry = new long[nodes]; long[] deniedUntil = new long[nodes]; double[] rateEst = new double[nodes]; long[] lastSeen = new long[nodes];
        long allowed = 0, rejected = 0, calls = 0, requests = 0, wasted = 0; long[] perSecond = new long[10];
        while (true) {                                                 // merge arrivals across nodes in time order
            int n = -1; long t = Long.MAX_VALUE;
            for (int k = 0; k < nodes; k++) if (idx[k] < arrivals[k].length && arrivals[k][idx[k]] < t) { t = arrivals[k][idx[k]]; n = k; }
            if (n < 0) break; idx[n]++; requests++;
            boolean ok;
            switch (strategy) {
                case 0 -> { long em = EMISSION_US * nodes, tol = TOLERANCE_US;           // 125/s each
                            long nt = Math.max(localTat[n], t) + em; ok = nt - t <= tol + em; if (ok) localTat[n] = nt; }
                case 1 -> { calls++; ok = lease(r, sha, t, 1) == 1; }
                default -> {
                    // lease size follows this node's recent rate: ~50 ms of its traffic, between 1 and 20 tokens
                    rateEst[n] = rateEst[n] * 0.98 + 0.02 * (lastSeen[n] == 0 ? 0 : 1e6 / Math.max(1, t - lastSeen[n])); lastSeen[n] = t;
                    int want = (int) Math.max(1, Math.min(20, Math.round(rateEst[n] * 0.05)));
                    if (leaseTokens[n] > 0 && t > leaseExpiry[n]) {                // expired lease: give the unused tokens back
                        calls++; wasted += leaseTokens[n];
                        r.cmd("EVALSHA", shaBack, "1", KEY, Long.toString(t), Long.toString(leaseTokens[n] * EMISSION_US)); leaseTokens[n] = 0;
                    }
                    if (leaseTokens[n] == 0 && t >= deniedUntil[n]) {
                        calls++; long got = lease(r, sha, t, want);
                        if (got > 0) { leaseTokens[n] = got; leaseExpiry[n] = t + 100_000; } else deniedUntil[n] = t - got;   // cache the denial
                    }
                    ok = leaseTokens[n] > 0; if (ok) leaseTokens[n]--;
                }
            }
            if (ok) { allowed++; perSecond[(int) (t / 1_000_000)]++; } else rejected++;
        }
        for (int k = 0; k < nodes; k++) wasted += leaseTokens[k];
        double maxSec = Arrays.stream(perSecond).max().orElse(0);
        return new Result(allowed / 10.0, rejected, calls, requests, wasted, maxSec);
    }

    static long lease(Redis r, String sha, long nowUs, int want) throws IOException {
        return (Long) r.cmd("EVALSHA", sha, "1", KEY, Long.toString(nowUs), Long.toString(EMISSION_US), Long.toString(TOLERANCE_US), Integer.toString(want));
    }

    /** Minimal RESP client (Ch 18). */
    static final class Redis implements Closeable {
        final Socket s; final OutputStream out; final BufferedInputStream in;
        Redis(String host, int port) throws IOException { s = new Socket(host, port); s.setTcpNoDelay(true); out = new BufferedOutputStream(s.getOutputStream()); in = new BufferedInputStream(s.getInputStream()); }
        Object cmd(String... parts) throws IOException {
            StringBuilder sb = new StringBuilder("*").append(parts.length).append("\r\n");
            for (String p : parts) { byte[] b = p.getBytes(StandardCharsets.UTF_8); sb.append('$').append(b.length).append("\r\n").append(p).append("\r\n"); }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8)); out.flush(); return read();
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
        public void close() throws IOException { s.close(); }
    }
}
