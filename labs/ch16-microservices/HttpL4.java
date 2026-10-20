// HttpL4.java — run with: java HttpL4.java
// Real HTTP on localhost (JDK HttpServer + HttpClient, virtual threads):
//   A. service discovery with heartbeats/TTL vs a static instance list, while an instance dies
//   B. API composition for the order-details page: sequential vs parallel vs parallel + timeout with partial results
//   C. strangler fig: a routing facade, shadow traffic that diffs the new service against the monolith, then cutover
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

public class HttpL4 {

    static final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofMillis(200))
            .executor(Executors.newVirtualThreadPerTaskExecutor()).build();

    static HttpServer serve(int port, Function<String, String> handler, long delayMs, double slowShare, long slowMs) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 200);
        Random r = new Random(port);
        s.createContext("/", ex -> {
            try {
                double x; synchronized (r) { x = r.nextDouble(); }
                Thread.sleep(x < slowShare ? slowMs : delayMs);
                byte[] body = handler.apply(ex.getRequestURI().getPath()).getBytes();
                ex.sendResponseHeaders(200, body.length); ex.getResponseBody().write(body);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { ex.close(); }
        });
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.start();
        return s;
    }

    static String get(String url, Duration timeout) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    // ---------- A. discovery ----------
    static final class Registry {                               // Eureka/Consul-style: instances heartbeat, entries expire
        private final Map<String, Long> lastBeat = new ConcurrentHashMap<>();
        void heartbeat(String url) { lastBeat.put(url, System.nanoTime()); }
        List<String> healthy(Duration ttl) {
            long now = System.nanoTime();
            return lastBeat.entrySet().stream().filter(e -> now - e.getValue() < ttl.toNanos()).map(Map.Entry::getKey).sorted().toList();
        }
    }

    static int[] trafficDuringFailure(boolean useRegistry) throws Exception {
        List<HttpServer> inst = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (int p = 18081; p <= 18083; p++) { inst.add(serve(p, path -> "catalog", 2, 0, 0)); urls.add("http://127.0.0.1:" + p); }
        Registry reg = new Registry();
        AtomicBoolean[] alive = {new AtomicBoolean(true), new AtomicBoolean(true), new AtomicBoolean(true)};
        ScheduledExecutorService beats = Executors.newScheduledThreadPool(1);
        beats.scheduleAtFixedRate(() -> { for (int i = 0; i < 3; i++) if (alive[i].get()) reg.heartbeat(urls.get(i)); }, 0, 100, TimeUnit.MILLISECONDS);
        Thread.sleep(150);
        int failed = 0, ok = 0, rr = 0;
        for (int i = 0; i < 3_000; i++) {
            if (i == 500) { inst.get(1).stop(0); alive[1].set(false); }                 // instance 2 crashes
            List<String> targets = useRegistry ? reg.healthy(Duration.ofMillis(300)) : urls;
            String target = targets.get(rr++ % targets.size());
            try { get(target + "/products/ATTA-5KG", Duration.ofMillis(500)); ok++; }
            catch (Exception e) {
                failed++;
                if (useRegistry) {                                                      // retry once on another instance
                    List<String> others = reg.healthy(Duration.ofMillis(300)).stream().filter(u -> !u.equals(target)).toList();
                    try { get(others.get(0) + "/products/ATTA-5KG", Duration.ofMillis(500)); ok++; failed--; } catch (Exception ignored) { }
                }
            }
        }
        beats.shutdownNow(); inst.forEach(s -> s.stop(0));
        return new int[]{ok, failed};
    }

    // ---------- B. composition ----------
    static long[] compose(String mode, int pages) throws Exception {
        long[] lat = new long[pages]; int partial = 0;
        for (int i = 0; i < pages; i++) {
            long t0 = System.nanoTime();
            switch (mode) {
                case "sequential" -> {
                    get("http://127.0.0.1:18091/orders/1", Duration.ofSeconds(5));
                    get("http://127.0.0.1:18092/payments/1", Duration.ofSeconds(5));
                    get("http://127.0.0.1:18093/tracking/1", Duration.ofSeconds(5));
                }
                case "parallel" -> {
                    var fs = List.of(http.sendAsync(req("http://127.0.0.1:18091/orders/1", 5000), HttpResponse.BodyHandlers.ofString()),
                                     http.sendAsync(req("http://127.0.0.1:18092/payments/1", 5000), HttpResponse.BodyHandlers.ofString()),
                                     http.sendAsync(req("http://127.0.0.1:18093/tracking/1", 5000), HttpResponse.BodyHandlers.ofString()));
                    for (var f : fs) f.join();
                }
                default -> {                                                         // parallel + 250 ms budget for the optional part
                    var order = http.sendAsync(req("http://127.0.0.1:18091/orders/1", 1000), HttpResponse.BodyHandlers.ofString());
                    var pay = http.sendAsync(req("http://127.0.0.1:18092/payments/1", 1000), HttpResponse.BodyHandlers.ofString());
                    var track = http.sendAsync(req("http://127.0.0.1:18093/tracking/1", 250), HttpResponse.BodyHandlers.ofString());
                    order.join(); pay.join();                                         // required parts
                    try { track.join(); } catch (CompletionException e) { partial++; } // optional: show "tracking unavailable"
                }
            }
            lat[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        Arrays.sort(lat);
        return new long[]{lat[pages / 2], lat[pages * 99 / 100], partial};
    }
    static HttpRequest req(String url, long timeoutMs) { return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeoutMs)).build(); }

    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.httpserver.nodelay", "true");                // avoid Nagle/delayed-ACK stalls on tiny responses
        System.out.println("A. 3,000 calls to catalog-service (3 instances); instance 2 crashes after call 500");
        int[] st = trafficDuringFailure(false), dy = trafficDuringFailure(true);
        System.out.printf("   static instance list        : %,d ok, %,d failed%n", st[0], st[1]);
        System.out.printf("   registry (TTL 300 ms) + retry: %,d ok, %,d failed%n", dy[0], dy[1]);

        System.out.println("B. Order-details page = order (20 ms) + payment (30 ms) + tracking (25 ms, 3 % of calls take 2 s); 500 page loads each");
        List<HttpServer> svc = List.of(serve(18091, p -> "{\"order\":1}", 20, 0, 0), serve(18092, p -> "{\"paid\":true}", 30, 0, 0),
                                       serve(18093, p -> "{\"eta\":\"12 min\"}", 25, 0.03, 2_000));
        for (String m : List.of("sequential", "parallel", "parallel + 250 ms budget for tracking")) {
            long[] r = compose(m, 500);
            System.out.printf("   %-40s p50 %4d ms  p99 %5d ms  partial pages %d%n", m, r[0], r[1], r[2]);
        }
        svc.forEach(s -> s.stop(0));

        System.out.println("C. Strangler fig for GET /orders/{id}/history: monolith vs new order-history-service");
        HttpServer monolith = serve(18101, p -> { int id = Integer.parseInt(p.split("/")[2]); return "total=" + (id % 17) * 1000; }, 5, 0, 0);
        HttpServer newSvc = serve(18102, p -> { int id = Integer.parseInt(p.split("/")[2]);
            return "total=" + ((id % 17) > 12 ? (id % 17) * 1000 - 1 : (id % 17) * 1000); }, 3, 0, 0);   // bug for large baskets
        int mismatches = 0; List<Integer> examples = new ArrayList<>();
        for (int id = 1; id <= 2_000; id++) {                                    // phase 1: monolith answers, new service shadowed
            String a = get("http://127.0.0.1:18101/orders/" + id + "/history", Duration.ofSeconds(1));
            String b = get("http://127.0.0.1:18102/orders/" + id + "/history", Duration.ofSeconds(1));
            if (!a.equals(b)) { mismatches++; if (examples.size() < 3) examples.add(id); }
        }
        System.out.printf("   shadow phase: 2,000 requests served by the monolith, new service diffed → %d mismatches (e.g. orders %s)%n", mismatches, examples);
        newSvc.stop(0);
        HttpServer fixed = serve(18102, p -> { int id = Integer.parseInt(p.split("/")[2]); return "total=" + (id % 17) * 1000; }, 3, 0, 0);
        mismatches = 0; int toNew = 0; Random r = new Random(3);
        for (int id = 1; id <= 2_000; id++) {                                    // phase 2: canary 10 % → new, still shadow-diffing
            boolean canary = r.nextDouble() < 0.10;
            String primary = get("http://127.0.0.1:" + (canary ? 18102 : 18101) + "/orders/" + id + "/history", Duration.ofSeconds(1));
            String other = get("http://127.0.0.1:" + (canary ? 18101 : 18102) + "/orders/" + id + "/history", Duration.ofSeconds(1));
            if (canary) toNew++;
            if (!primary.equals(other)) mismatches++;
        }
        System.out.printf("   after the fix, canary: %d of 2,000 served by the new service, %d mismatches → route 100 %%, then retire the monolith path%n", toNew, mismatches);
        monolith.stop(0); fixed.stop(0);
        System.exit(0);
    }
}
