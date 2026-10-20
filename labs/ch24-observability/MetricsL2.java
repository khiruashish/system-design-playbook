// MetricsL2.java — run with: java -Dsun.net.httpserver.nodelay=true MetricsL2.java
// A service that exposes Prometheus-format metrics on :19701/metrics (counters, gauge, and a latency histogram with
// explicit buckets), drives a realistic load with a slow tail, and — while it runs — a REAL Prometheus scrapes it.
// A. RED metrics from counters. B. histogram_quantile accuracy vs exact percentiles (why bucket choice matters).
// C. why an average hides the tail. D. an exemplar linking a bucket to a trace id.
import com.sun.net.httpserver.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class MetricsL2 {
    // classic Prometheus client-style histogram: cumulative buckets (le), _sum, _count
    static final double[] BUCKETS = {5, 10, 25, 50, 75, 100, 150, 250, 500, 1000, 2500}; // ms
    static final AtomicLong[] bucketCounts = new AtomicLong[BUCKETS.length];
    static final AtomicLong reqTotal = new AtomicLong(), errTotal = new AtomicLong();
    static final DoubleAdder latencySum = new DoubleAdder();
    static final AtomicInteger inFlight = new AtomicInteger();
    static final Map<String, AtomicLong> byRoute = new ConcurrentHashMap<>();
    static final List<Double> exactSample = Collections.synchronizedList(new ArrayList<>());  // for the accuracy check only
    static volatile String lastSlowTrace = "none";
    static { for (int i = 0; i < BUCKETS.length; i++) bucketCounts[i] = new AtomicLong(); }

    static void observe(double ms, String route, boolean error, String trace) {
        reqTotal.incrementAndGet(); if (error) errTotal.incrementAndGet();
        latencySum.add(ms); byRoute.computeIfAbsent(route, k -> new AtomicLong()).incrementAndGet();
        for (int i = 0; i < BUCKETS.length; i++) if (ms <= BUCKETS[i]) bucketCounts[i].incrementAndGet();
        exactSample.add(ms);
        if (ms > 250) lastSlowTrace = trace;
    }

    static String metrics() {
        var sb = new StringBuilder();
        sb.append("# HELP http_requests_total Total HTTP requests\n# TYPE http_requests_total counter\n");
        byRoute.forEach((r, c) -> sb.append("http_requests_total{route=\"").append(r).append("\"} ").append(c.get()).append("\n"));
        sb.append("# HELP http_request_errors_total Total errors\n# TYPE http_request_errors_total counter\nhttp_request_errors_total ").append(errTotal.get()).append("\n");
        sb.append("# HELP http_inflight_requests In-flight requests\n# TYPE http_inflight_requests gauge\nhttp_inflight_requests ").append(inFlight.get()).append("\n");
        sb.append("# HELP http_request_duration_ms Request latency\n# TYPE http_request_duration_ms histogram\n");
        for (int i = 0; i < BUCKETS.length; i++) sb.append("http_request_duration_ms_bucket{le=\"").append((long) BUCKETS[i]).append("\"} ").append(bucketCounts[i].get()).append("\n");
        sb.append("http_request_duration_ms_bucket{le=\"+Inf\"} ").append(reqTotal.get()).append("\n");
        sb.append("http_request_duration_ms_sum ").append(String.format("%.1f", latencySum.sum())).append("\n");
        sb.append("http_request_duration_ms_count ").append(reqTotal.get()).append("\n");
        // an exemplar: which trace hit the slow tail (OpenMetrics exemplars ride on a bucket line)
        sb.append("# trace of the last request over 250 ms (exemplar): ").append(lastSlowTrace).append("\n");
        return sb.toString();
    }

    // Prometheus' histogram_quantile: linear interpolation within the bucket that contains the quantile rank
    static double histogramQuantile(double q) {
        long total = reqTotal.get(); double rank = q * total; double lower = 0, cumLower = 0;
        for (int i = 0; i < BUCKETS.length; i++) {
            long cum = bucketCounts[i].get();
            if (cum >= rank) return lower + (BUCKETS[i] - lower) * (rank - cumLower) / (cum - cumLower);
            lower = BUCKETS[i]; cumLower = cum;
        }
        return BUCKETS[BUCKETS.length - 1];   // in +Inf bucket: can't do better than the last finite edge
    }
    static double exactQuantile(double q) {
        double[] a = exactSample.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return a[Math.min(a.length - 1, (int) Math.ceil(q * a.length) - 1)];
    }

    public static void main(String[] args) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 19701), 0);
        s.createContext("/metrics", ex -> { byte[] b = metrics().getBytes(); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); s.start();

        // drive load for ~12s so a real Prometheus (1s scrape) gets a dozen samples; log-normal body + a heavy tail
        Thread load = new Thread(() -> {
            Random r = new Random(7); long end = System.currentTimeMillis() + 12_000;
            try (var ex = Executors.newFixedThreadPool(8)) {
                while (System.currentTimeMillis() < end) {
                    ex.submit(() -> {
                        inFlight.incrementAndGet();
                        double ms = Math.exp(r.nextGaussian() * 0.5 + 3.0);          // ~median 20ms
                        if (r.nextDouble() < 0.05) ms += 300 + r.nextDouble() * 1500;  // 5% slow tail (a slow dependency)
                        boolean err = r.nextDouble() < 0.02;
                        String route = r.nextDouble() < 0.7 ? "/products" : (r.nextDouble() < 0.5 ? "/cart" : "/checkout");
                        try { Thread.sleep((long) ms); } catch (InterruptedException ignored) {}
                        observe(ms, route, err, String.format("%016x", r.nextLong() & 0x7fffffffffffffffL));
                        inFlight.decrementAndGet();
                    });
                    try { Thread.sleep(2); } catch (InterruptedException ignored) {}
                }
            }
        });
        load.start(); load.join();

        System.out.println("A. RED metrics (from counters/gauge on /metrics, scraped by Prometheus)");
        System.out.printf("   Rate    : %,d requests over ~12 s%n", reqTotal.get());
        System.out.printf("   Errors  : %,d (%.2f%%)%n", errTotal.get(), 100.0 * errTotal.get() / reqTotal.get());
        System.out.printf("   Duration: avg %.1f ms  |  by route %s%n", latencySum.sum() / reqTotal.get(),
                byRoute.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get())));

        System.out.println("\nB. histogram_quantile vs exact percentile (bucket boundaries decide the error)");
        System.out.printf("   %-8s %-16s %-16s %s%n", "quantile", "from histogram", "exact", "error");
        for (double q : new double[]{0.50, 0.90, 0.95, 0.99, 0.999}) {
            double hq = histogramQuantile(q), eq = exactQuantile(q);
            System.out.printf("   p%-7s %-16.1f %-16.1f %+.1f ms (%.0f%%)%n", (q * 100 == (long)(q*100) ? String.valueOf((long)(q*100)) : String.valueOf(q*100)), hq, eq, hq - eq, 100 * (hq - eq) / eq);
        }
        System.out.println("   → p99/p99.9 land in the wide 1000–2500 ms bucket (and beyond +Inf), so the estimate is coarse there.");
        System.out.println("     Add buckets where your SLO lives; you cannot recover accuracy the buckets didn't capture.");

        System.out.println("\nC. Why the average lies");
        System.out.printf("   average %.1f ms would meet a \"200 ms\" SLO, but p99 is %.0f ms and p99.9 is %.0f ms — %.0f%% of users are fine, the tail is not.%n",
                latencySum.sum() / reqTotal.get(), exactQuantile(0.99), exactQuantile(0.999), 99.0);

        System.out.println("\nD. Exemplar: jump from the slow bucket to a trace");
        System.out.printf("   a request in the >250 ms range carried trace_id=%s — click it in the dashboard to open the trace (Ch 24 L3).%n", lastSlowTrace);
        System.out.println("\n(Prometheus scraped :19701/metrics live; see prom_out.txt for its own view of the same counters.)");
        s.stop(0);
    }
}
