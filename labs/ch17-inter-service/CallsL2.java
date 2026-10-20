// CallsL2.java — run with: java CallsL2.java
// Real HTTP on localhost (JDK HttpServer + HttpClient):
//   A. a slow dependency without timeouts exhausts the caller's thread pool and takes down an UNRELATED endpoint
//   B. per-hop timeouts vs a propagated deadline: work that continues after the user has already given up
//   C. retries at every layer multiply load on a failing leaf
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class CallsL2 {

    static final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(500)).executor(Executors.newVirtualThreadPerTaskExecutor()).build();

    interface Handler { String handle(HttpExchange ex) throws Exception; }

    static HttpServer server(int port, ExecutorService pool, Handler h) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 1000);
        s.createContext("/", ex -> {
            int code = 200; String body;
            try { body = h.handle(ex); } catch (Exception e) { code = 503; body = e.getClass().getSimpleName(); }
            byte[] b = body.getBytes();
            try { ex.sendResponseHeaders(code, b.length); ex.getResponseBody().write(b); } catch (Exception ignored) { }
            ex.close();
        });
        s.setExecutor(pool); s.start(); return s;
    }

    static HttpResponse<String> call(String url, Duration timeout, Map<String, String> headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url));
        if (timeout != null) b.timeout(timeout);
        headers.forEach(b::header);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ---------------- A ----------------
    static void experimentA(boolean withTimeout) throws Exception {
        HttpServer pricing = server(19002, Executors.newVirtualThreadPerTaskExecutor(), ex -> { Thread.sleep(5_000); return "price"; });  // degraded
        HttpServer checkout = server(19001, Executors.newFixedThreadPool(20), ex -> {                  // the caller: 20 worker threads
            if (ex.getRequestURI().getPath().startsWith("/cart")) return "cart ok";                   // local work only
            try {
                return call("http://127.0.0.1:19002/price", withTimeout ? Duration.ofMillis(300) : null, Map.of()).body();
            } catch (HttpTimeoutException e) { return "price unavailable (cached MRP shown)"; }       // fallback
        });
        int n = 1_000; long[] cartLat = new long[n / 2]; AtomicInteger cartOk = new AtomicInteger(), priceOk = new AtomicInteger();
        ExecutorService load = Executors.newVirtualThreadPerTaskExecutor(); List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int ii = i; boolean cart = i % 2 == 0;
            fs.add(load.submit(() -> {
                long t0 = System.nanoTime();
                try {
                    var r = call("http://127.0.0.1:19001/" + (cart ? "cart" : "price"), Duration.ofSeconds(2), Map.of());   // the app gives up after 2 s
                    if (r.statusCode() == 200) (cart ? cartOk : priceOk).incrementAndGet();
                } catch (Exception ignored) { }
                if (cart) cartLat[ii / 2] = (System.nanoTime() - t0) / 1_000_000;
                return null;
            }));
            Thread.sleep(10);                                                                          // 100 requests/s for 10 s
        }
        for (var f : fs) f.get();
        Arrays.sort(cartLat);
        System.out.printf("   %-26s /cart ok %3d/500 (p99 %5d ms)   /price ok %3d/500%n", withTimeout ? "300 ms timeout + fallback" : "no timeout",
                cartOk.get(), cartLat[cartLat.length * 99 / 100], priceOk.get());
        checkout.stop(0); pricing.stop(0);
    }

    // ---------------- B ----------------
    static void experimentB(boolean propagate) throws Exception {
        AtomicLong orphanMs = new AtomicLong(); AtomicInteger orphanRequests = new AtomicInteger(), abandoned = new AtomicInteger();
        ConcurrentHashMap<String, Long> userGaveUpAt = new ConcurrentHashMap<>();
        Random rnd = new Random(4);
        HttpServer promo = server(19013, Executors.newVirtualThreadPerTaskExecutor(), ex -> {
            String id = ex.getRequestHeaders().getFirst("X-Request-Id");
            long work; synchronized (rnd) { work = rnd.nextDouble() < 0.3 ? 1_500 : 60; }              // 30 % slow coupon evaluation
            long deadline = propagate ? Long.parseLong(ex.getRequestHeaders().getFirst("X-Deadline-Epoch-Ms")) : Long.MAX_VALUE;
            long end = System.currentTimeMillis() + work;
            while (System.currentTimeMillis() < end) {                                                // work in slices; check the deadline
                if (System.currentTimeMillis() > deadline) { abandoned.incrementAndGet(); throw new TimeoutException(); }
                Thread.sleep(10);
            }
            Long gaveUp = userGaveUpAt.get(id);
            if (gaveUp != null) { orphanRequests.incrementAndGet(); orphanMs.addAndGet(System.currentTimeMillis() - gaveUp); }
            return "discount 5%";
        });
        Handler hop = ex -> {                                                                          // checkout → pricing → promotion
            String id = ex.getRequestHeaders().getFirst("X-Request-Id");
            String next = ex.getLocalAddress().getPort() == 19011 ? "http://127.0.0.1:19012/" : "http://127.0.0.1:19013/";
            Map<String, String> h = new HashMap<>(Map.of("X-Request-Id", id));
            Duration timeout = Duration.ofSeconds(1);                                                  // each hop's own fixed timeout
            if (propagate) {
                String dl = ex.getRequestHeaders().getFirst("X-Deadline-Epoch-Ms");
                long remaining = Long.parseLong(dl) - System.currentTimeMillis() - 20;                // keep 20 ms for our own reply
                if (remaining <= 0) throw new TimeoutException();                                      // don't even start
                h.put("X-Deadline-Epoch-Ms", dl); timeout = Duration.ofMillis(remaining);
            }
            Thread.sleep(15);                                                                          // own work
            return call(next, timeout, h).body();
        };
        HttpServer checkout = server(19011, Executors.newVirtualThreadPerTaskExecutor(), hop);
        HttpServer pricing = server(19012, Executors.newVirtualThreadPerTaskExecutor(), hop);
        int n = 300, ok = 0;
        for (int i = 0; i < n; i++) {
            String id = "req-" + i; long budget = 500;                                                 // the app waits 500 ms, then shows a fallback
            Map<String, String> h = new HashMap<>(Map.of("X-Request-Id", id));
            if (propagate) h.put("X-Deadline-Epoch-Ms", Long.toString(System.currentTimeMillis() + budget));
            try { if (call("http://127.0.0.1:19011/", Duration.ofMillis(budget), h).statusCode() == 200) ok++; }
            catch (HttpTimeoutException e) { userGaveUpAt.put(id, System.currentTimeMillis()); }
        }
        Thread.sleep(2_000);                                                                          // let orphans finish
        System.out.printf("   %-30s answered %3d/%d   work finished AFTER the user gave up: %3d requests, %,6d ms   abandoned early: %d%n",
                propagate ? "propagated deadline" : "fixed 1 s timeout per hop", ok, n, orphanRequests.get(), orphanMs.get(), abandoned.get());
        checkout.stop(0); pricing.stop(0); promo.stop(0);
    }

    // ---------------- C ----------------
    static void experimentC(int attemptsPerLayer) throws Exception {
        AtomicInteger leafCalls = new AtomicInteger();
        HttpServer leaf = server(19023, Executors.newVirtualThreadPerTaskExecutor(), ex -> { leafCalls.incrementAndGet(); throw new IllegalStateException("down"); });
        Handler retrying = ex -> {
            String next = ex.getLocalAddress().getPort() == 19021 ? "http://127.0.0.1:19022/" : "http://127.0.0.1:19023/";
            int attempts = attemptsPerLayer;                                                           // retries at THIS layer
            for (int a = 1; a <= attempts; a++) {
                var r = call(next, Duration.ofSeconds(1), Map.of());
                if (r.statusCode() == 200) return r.body();
            }
            throw new IllegalStateException("gave up");
        };
        HttpServer l1 = server(19021, Executors.newVirtualThreadPerTaskExecutor(), retrying);
        HttpServer l2 = server(19022, Executors.newVirtualThreadPerTaskExecutor(), retrying);
        int edgeAttempts = 3;
        for (int i = 0; i < 100; i++) for (int a = 0; a < edgeAttempts; a++) if (call("http://127.0.0.1:19021/", Duration.ofSeconds(5), Map.of()).statusCode() == 200) break;
        System.out.printf("   %-44s leaf received %,5d calls for 100 user requests (%.0fx)%n",
                attemptsPerLayer == 3 ? "3 attempts at the edge AND at each of 2 layers" : "3 attempts at the edge only", leafCalls.get(), leafCalls.get() / 100.0);
        l1.stop(0); l2.stop(0); leaf.stop(0);
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.httpserver.nodelay", "true");
        System.out.println("A. checkout-service (20 worker threads) serves /cart (local) and /price (calls pricing); pricing now takes 5 s");
        experimentA(false);
        experimentA(true);
        System.out.println("B. app (500 ms budget) → checkout → pricing → promotion; 30 % of promotion calls take 1.5 s");
        experimentB(false);
        experimentB(true);
        System.out.println("C. edge → layer-1 → layer-2 → leaf, leaf is down");
        experimentC(3);
        experimentC(1);
        System.exit(0);
    }
}
