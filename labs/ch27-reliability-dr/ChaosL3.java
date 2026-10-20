// ChaosL3.java — run with: java -Dsun.net.httpserver.nodelay=true ChaosL3.java   (JDK 21, no dependencies)
// Chaos engineering: inject real faults into a real dependency and measure whether the caller's SLO holds — with the
// resilience patterns of Ch 18 (timeout + circuit breaker + fallback) OFF vs ON. A product service depends on a
// "recommendations" service (non-critical: a fallback exists) over real HTTP. We run 4 game-day experiments:
//   1. dependency crashes (connection refused)   2. dependency slow (adds 3 s)   3. 50% errors   4. recovery.
// The hypothesis each time: "a failure in a non-critical dependency must not breach the product-page SLO (p99 < 300 ms,
// error rate < 1%)." Chaos engineering is running the experiment to see if the hypothesis actually holds.
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ChaosL3 {
    // ---- the dependency, with a fault switch ----
    static volatile String fault = "none";      // none | crash | slow | errors
    static HttpServer dep;
    static void startDep() throws Exception {
        dep = HttpServer.create(new InetSocketAddress("127.0.0.1", 19950), 0);
        dep.createContext("/recos", ex -> {
            String f = fault;
            try {
                if (f.equals("slow")) Thread.sleep(3000);
                if (f.equals("errors") && ThreadLocalRandom.current().nextBoolean()) { ex.sendResponseHeaders(500, -1); ex.close(); return; }
                byte[] b = "[reco1,reco2,reco3]".getBytes(); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b);
            } catch (Exception e) {} finally { ex.close(); }
        });
        dep.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); dep.start();
    }
    static void crashDep() { if (dep != null) { dep.stop(0); dep = null; } }   // connection refused

    // ---- the product service calling the dependency, with resilience OFF/ON ----
    static final HttpClient RAW = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    // circuit breaker state (Ch 18)
    static final AtomicInteger consecFail = new AtomicInteger(); static volatile long openUntil = 0;
    static String recos(boolean resilient) {
        if (resilient && System.currentTimeMillis() < openUntil) return "[]";      // breaker OPEN → instant fallback
        try {
            var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:19950/recos"));
            if (resilient) req.timeout(Duration.ofMillis(150));                    // tight timeout (Ch 17/18)
            var r = RAW.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new RuntimeException("5xx");
            if (resilient) consecFail.set(0);
            return r.body();
        } catch (Exception e) {
            if (resilient) { if (consecFail.incrementAndGet() >= 20) openUntil = System.currentTimeMillis() + 2000; return "[]"; }  // fallback: empty recos
            throw new RuntimeException(e);                                          // non-resilient: the failure propagates
        }
    }

    static HttpServer product;
    static void startProduct(boolean resilient) throws Exception {
        product = HttpServer.create(new InetSocketAddress("127.0.0.1", 19951), 0);
        product.createContext("/product", ex -> {
            try {
                String recos = recos(resilient);                                   // the product page always needs the core data; recos are optional
                byte[] b = ("{product, recos:" + recos + "}").getBytes();
                ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b);
            } catch (Exception e) { try { ex.sendResponseHeaders(500, -1); } catch (Exception ignored) {} }
            finally { ex.close(); }
        });
        product.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); product.start();
    }

    static final HttpClient USER = HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor()).build();
    static double[] load(long ms) throws Exception {
        List<Long> lat = Collections.synchronizedList(new ArrayList<>()); AtomicLong err = new AtomicLong(), ok = new AtomicLong();
        long end = System.currentTimeMillis() + ms;
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore c = new Semaphore(30);
            while (System.currentTimeMillis() < end) { c.acquire(); ex.submit(() -> {
                long t0 = System.nanoTime();
                try { int s = USER.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:19951/product")).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.discarding()).statusCode(); if (s==200) ok.incrementAndGet(); else err.incrementAndGet(); }
                catch (Exception e) { err.incrementAndGet(); } finally { lat.add(System.nanoTime()-t0); c.release(); }
            }); Thread.sleep(2); }
        }
        long[] a = lat.stream().mapToLong(Long::longValue).sorted().toArray();
        long tot = ok.get()+err.get();
        return new double[]{ a[(int)(a.length*0.99)]/1e6, 100.0*err.get()/tot, tot };
    }

    static void experiment(String name, boolean resilient, Runnable inject) throws Exception {
        fault = "none"; consecFail.set(0); openUntil = 0; if (dep == null) startDep();
        inject.run();
        double[] r = load(2500);
        boolean pass = r[0] < 300 && r[1] < 1.0;
        System.out.printf("   %-34s %-10s p99 %7.0f ms  errors %5.1f%%   %s%n", name, resilient?"resilient":"naive", r[0], r[1], pass?"SLO HELD":"SLO BREACHED");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Game day: the product page depends on a NON-critical 'recommendations' service. Hypothesis each time:");
        System.out.println("\"a failure there must not breach the product-page SLO (p99 < 300 ms, errors < 1%).\" We inject the fault and check.\n");
        for (boolean resilient : new boolean[]{false, true}) {
            startDep(); startProduct(resilient);
            System.out.printf("   --- resilience patterns %s (timeout 150ms + breaker + empty-recos fallback) ---%n", resilient?"ON":"OFF");
            experiment("baseline (no fault)", resilient, () -> fault = "none");
            experiment("dependency SLOW (+3 s)", resilient, () -> fault = "slow");
            experiment("dependency 50% ERRORS", resilient, () -> fault = "errors");
            experiment("dependency CRASHED (conn refused)", resilient, ChaosL3::crashDep);
            fault = "none"; if (dep == null) startDep();
            experiment("recovery (fault cleared)", resilient, () -> fault = "none");
            product.stop(0); crashDep(); Thread.sleep(200);
        }
        System.out.println("\n   → naive: a slow/broken NON-critical dependency drags the whole product page past its SLO — the failure propagates.");
        System.out.println("     resilient: a tight timeout caps the slow case, the breaker stops hammering a dead dependency, and the fallback (empty");
        System.out.println("     recommendations) keeps the page within SLO. Chaos engineering is how you KNOW this before a real outage proves it.");
    }
}
