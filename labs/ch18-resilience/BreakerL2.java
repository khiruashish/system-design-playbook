// BreakerL2.java — run with: java BreakerL2.java
// A circuit breaker (count-based sliding window, like Resilience4j's COUNT_BASED) in front of a real HTTP dependency
// that fails slowly for 6 seconds and then recovers. Compare callers with and without the breaker.
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

public class BreakerL2 {

    /** CLOSED → (failure rate ≥ threshold over the last N calls) → OPEN → (after wait) → HALF_OPEN → probes → CLOSED/OPEN. */
    static final class CircuitBreaker {
        enum State { CLOSED, OPEN, HALF_OPEN }
        private final int window, minCalls, halfOpenCalls; private final double failureRate; private final long openNanos;
        private final Deque<Boolean> outcomes = new ArrayDeque<>();
        private State state = State.CLOSED; private long openedAt; private int halfOpenInFlight, halfOpenOk, halfOpenDone;
        final List<String> transitions = Collections.synchronizedList(new ArrayList<>());
        private final long t0 = System.nanoTime();

        CircuitBreaker(int window, int minCalls, double failureRate, Duration openFor, int halfOpenCalls) {
            this.window = window; this.minCalls = minCalls; this.failureRate = failureRate; this.openNanos = openFor.toNanos(); this.halfOpenCalls = halfOpenCalls;
        }
        private void to(State s) { state = s; transitions.add(String.format("%.1fs %s", (System.nanoTime() - t0) / 1e9, s)); }

        synchronized boolean tryAcquire() {
            if (state == State.OPEN && System.nanoTime() - openedAt >= openNanos) { to(State.HALF_OPEN); halfOpenInFlight = halfOpenOk = halfOpenDone = 0; }
            if (state == State.OPEN) return false;                                   // fail fast, don't touch the dependency
            if (state == State.HALF_OPEN) { if (halfOpenInFlight >= halfOpenCalls) return false; halfOpenInFlight++; }
            return true;
        }
        synchronized void record(boolean success) {
            if (state == State.OPEN) return;           // late results of calls admitted before opening must not re-open (and extend) it
            if (state == State.HALF_OPEN) {
                halfOpenDone++; if (success) halfOpenOk++;
                if (halfOpenDone == halfOpenCalls) {
                    if (halfOpenOk == halfOpenCalls) { outcomes.clear(); to(State.CLOSED); } else { openedAt = System.nanoTime(); to(State.OPEN); }
                }
                return;
            }
            outcomes.addLast(success); if (outcomes.size() > window) outcomes.removeFirst();
            long failures = outcomes.stream().filter(o -> !o).count();
            if (outcomes.size() >= minCalls && failures >= failureRate * outcomes.size()) { openedAt = System.nanoTime(); to(State.OPEN); }
        }

        <T> T call(Supplier<T> action, Supplier<T> fallback) {
            if (!tryAcquire()) return fallback.get();
            try { T r = action.get(); record(true); return r; }
            catch (RuntimeException e) { record(false); return fallback.get(); }
        }
    }

    static volatile boolean pricingBroken = false;

    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.httpserver.nodelay", "true");
        AtomicInteger pricingCallsDuringOutage = new AtomicInteger();
        HttpServer pricing = HttpServer.create(new InetSocketAddress("127.0.0.1", 19101), 1000);
        pricing.createContext("/", ex -> {
            try {
                int code = 200;
                if (pricingBroken) { pricingCallsDuringOutage.incrementAndGet(); Thread.sleep(800); code = 500; }   // slow failure (DB timeouts)
                else Thread.sleep(10);
                ex.sendResponseHeaders(code, 2); ex.getResponseBody().write("ok".getBytes());
            } catch (Exception ignored) { } finally { ex.close(); }
        });
        pricing.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); pricing.start();
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofMillis(200))
                .executor(Executors.newVirtualThreadPerTaskExecutor()).build();

        for (boolean useBreaker : new boolean[]{false, true}) {
            pricingCallsDuringOutage.set(0);
            CircuitBreaker cb = new CircuitBreaker(20, 10, 0.5, Duration.ofSeconds(2), 3);
            Supplier<String> callPricing = () -> {
                try {
                    var r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:19101/price")).timeout(Duration.ofSeconds(1)).build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() != 200) throw new IllegalStateException("5xx");
                    return "live price";
                } catch (Exception e) { throw new RuntimeException(e); }
            };
            Supplier<String> fallback = () -> "cached MRP";
            List<long[]> samples = Collections.synchronizedList(new ArrayList<>());   // {t_ms, latency_ms, live?1:0}
            ExecutorService load = Executors.newVirtualThreadPerTaskExecutor(); List<Future<?>> fs = new ArrayList<>();
            long start = System.nanoTime();
            for (int i = 0; i < 600; i++) {                                            // 50 req/s for 12 s
                long tMs = (System.nanoTime() - start) / 1_000_000;
                pricingBroken = tMs >= 2_000 && tMs < 8_000;                            // outage window 2 s – 8 s
                fs.add(load.submit(() -> {
                    long t0 = System.nanoTime();
                    String r = useBreaker ? cb.call(callPricing, fallback) : callWithoutBreaker(callPricing, fallback);
                    samples.add(new long[]{(t0 - start) / 1_000_000, (System.nanoTime() - t0) / 1_000_000, r.equals("live price") ? 1 : 0});
                    return null;
                }));
                Thread.sleep(20);
            }
            for (var f : fs) f.get();
            pricingBroken = false;
            long[] outageLat = samples.stream().filter(s -> s[0] >= 2_000 && s[0] < 8_000).mapToLong(s -> s[1]).sorted().toArray();
            long firstLiveAfter = samples.stream().filter(s -> s[0] >= 8_000 && s[2] == 1).mapToLong(s -> s[0]).min().orElse(-1);
            System.out.printf("%s%n   calls that reached the failing pricing-service: %d%n   caller latency during the outage: p50 %d ms, p99 %d ms%n   first live price after recovery at 8.0 s: %.1f s%n",
                    useBreaker ? "WITH circuit breaker (window 20, ≥50 % failures, open 2 s, 3 half-open probes)" : "WITHOUT circuit breaker (timeout 1 s + fallback)",
                    pricingCallsDuringOutage.get(), outageLat[outageLat.length / 2], outageLat[outageLat.length * 99 / 100], firstLiveAfter / 1000.0);
            if (useBreaker) System.out.println("   breaker transitions: " + cb.transitions);
        }
        pricing.stop(0);
        System.exit(0);
    }

    static String callWithoutBreaker(Supplier<String> action, Supplier<String> fallback) {
        try { return action.get(); } catch (RuntimeException e) { return fallback.get(); }
    }
}
