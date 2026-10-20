// CellRouterL4.java — run with: java -Dsun.net.httpserver.nodelay=true CellRouterL4.java   (JDK 21, no dependencies)
// A REAL CELL ROUTER, and moving a city between cells under live traffic. Three cells are separate HTTP servers, each with
// its own cart store (no shared database — that's the point of cells). A thin routing layer maps city → cell and proxies
// requests. Client threads keep adding items to carts in Pune (and Nagpur) the whole time, recording every write the
// platform ACKNOWLEDGED. Mid-run we move Pune from cell-1 to cell-3 (rebalancing a hot cell), two ways:
//   NAIVE  — flip the routing entry. New requests go to cell-3, which has none of Pune's data.
//   PROPER — hold Pune's requests at the router (wait for in-flight ones to drain), copy Pune's data from cell-1 to
//            cell-3, flip the route, release the hold, then delete from cell-1.
// At the end we read every cart back through the router and check that every acknowledged item is still there.
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

public class CellRouterL4 {
    static final List<ExecutorService> POOLS = new ArrayList<>();
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .executor(pool(16)).build();
    static ExecutorService pool(int n) { ExecutorService p = Executors.newFixedThreadPool(n); POOLS.add(p); return p; }

    // ---------------- a cell: its own store, its own server ----------------
    static final class Cell {
        final String name; final HttpServer server;
        final Map<String, Map<String, List<String>>> store = new ConcurrentHashMap<>();   // city -> cart -> skus
        Cell(String name) throws Exception {
            this.name = name;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/add", ex -> { Map<String, String> q = q(ex);
                store.computeIfAbsent(q.get("city"), c -> new ConcurrentHashMap<>())
                     .computeIfAbsent(q.get("cart"), c -> Collections.synchronizedList(new ArrayList<>())).add(q.get("sku"));
                reply(ex, 200, "OK"); });
            server.createContext("/get", ex -> { Map<String, String> q = q(ex);
                List<String> l = store.getOrDefault(q.get("city"), Map.of()).getOrDefault(q.get("cart"), List.of());
                synchronized (l) { reply(ex, 200, String.join(",", l)); } });
            server.createContext("/export", ex -> { Map<String, String> q = q(ex); StringBuilder sb = new StringBuilder();
                store.getOrDefault(q.get("city"), Map.of()).forEach((cart, l) -> { synchronized (l) { for (String s : l) sb.append(cart).append('|').append(s).append('\n'); } });
                reply(ex, 200, sb.toString()); });
            server.createContext("/import", ex -> { Map<String, String> q = q(ex);
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); int n = 0;
                for (String line : body.split("\n")) { if (line.isBlank()) continue; String[] p = line.split("\\|");
                    store.computeIfAbsent(q.get("city"), c -> new ConcurrentHashMap<>())
                         .computeIfAbsent(p[0], c -> Collections.synchronizedList(new ArrayList<>())).add(p[1]); n++; }
                reply(ex, 200, String.valueOf(n)); });
            server.createContext("/drop", ex -> { store.remove(q(ex).get("city")); reply(ex, 200, "OK"); });
            server.setExecutor(pool(8)); server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    }

    // ---------------- the router: city -> cell, with a per-city hold used during migration ----------------
    static final class Router {
        final Map<String, Cell> route = new ConcurrentHashMap<>();
        final Map<String, ReentrantReadWriteLock> hold = new ConcurrentHashMap<>();
        final HttpServer server;
        Router() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                String city = q(ex).get("city");
                Lock l = hold.computeIfAbsent(city, c -> new ReentrantReadWriteLock()).readLock();
                l.lock();                                   // blocks only while this city is being migrated
                try {
                    Cell target = route.get(city);
                    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(target.url() + ex.getRequestURI())).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    reply(ex, r.statusCode(), r.body());
                } catch (Exception e) { reply(ex, 502, "cell unreachable"); }
                finally { l.unlock(); }
            });
            server.setExecutor(pool(16)); server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        void migrateNaive(String city, Cell to) { route.put(city, to); }

        long migrateProper(String city, Cell to) throws Exception {
            Cell from = route.get(city);
            Lock w = hold.computeIfAbsent(city, c -> new ReentrantReadWriteLock()).writeLock();
            long t0 = System.nanoTime();
            w.lock();                                        // 1. hold new requests for this city, wait for in-flight ones
            try {
                String dump = HTTP.send(HttpRequest.newBuilder(URI.create(from.url() + "/export?city=" + city)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).body();                       // 2. copy
                HTTP.send(HttpRequest.newBuilder(URI.create(to.url() + "/import?city=" + city)).POST(HttpRequest.BodyPublishers.ofString(dump)).build(),
                        HttpResponse.BodyHandlers.ofString());
                route.put(city, to);                                                          // 3. flip
            } finally { w.unlock(); }                                                         // 4. release
            long heldMs = (System.nanoTime() - t0) / 1_000_000;
            HTTP.send(HttpRequest.newBuilder(URI.create(from.url() + "/drop?city=" + city)).GET().build(), HttpResponse.BodyHandlers.ofString()); // 5. clean up
            return heldMs;
        }
    }

    static Map<String, String> q(HttpExchange ex) {
        Map<String, String> m = new HashMap<>(); String s = ex.getRequestURI().getRawQuery();
        if (s != null) for (String kv : s.split("&")) { String[] p = kv.split("=", 2); m.put(p[0], p.length > 1 ? p[1] : ""); }
        return m;
    }
    static void reply(HttpExchange ex, int code, String body) {
        try { byte[] b = body.getBytes(StandardCharsets.UTF_8); ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
              if (b.length > 0) ex.getResponseBody().write(b); } catch (Exception ignored) {} finally { ex.close(); }
    }

    // ---------------- one scenario ----------------
    static void scenario(String label, boolean proper, boolean print) throws Exception {
        Cell c1 = new Cell("cell-1"), c2 = new Cell("cell-2"), c3 = new Cell("cell-3");
        Router router = new Router();
        router.route.put("pune", c1); router.route.put("nagpur", c1); router.route.put("delhi", c2);

        Map<String, List<String>> acked = new ConcurrentHashMap<>();           // what the platform told clients succeeded
        AtomicLong ok = new AtomicLong(), failed = new AtomicLong();
        Map<String, AtomicLong> maxLatUs = Map.of("pune", new AtomicLong(), "nagpur", new AtomicLong());
        AtomicBoolean running = new AtomicBoolean(true);
        ExecutorService clients = pool(6);
        for (int t = 0; t < 6; t++) {
            final int tid = t;
            clients.submit(() -> {
                int i = 0; String city = tid < 4 ? "pune" : "nagpur";
                while (running.get()) {
                    String cart = city + "-cart" + tid + "-" + (i % 5), sku = "SKU" + (i++);
                    long s = System.nanoTime();
                    try {
                        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(router.url() + "/add?city=" + city + "&cart=" + cart + "&sku=" + sku)).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                        if (r.statusCode() == 200) { ok.incrementAndGet(); acked.computeIfAbsent(city + "/" + cart, k -> Collections.synchronizedList(new ArrayList<>())).add(sku); }
                        else failed.incrementAndGet();
                    } catch (Exception e) { failed.incrementAndGet(); }
                    maxLatUs.get(city).accumulateAndGet((System.nanoTime() - s) / 1000, Math::max);
                }
                return null;
            });
        }
        Thread.sleep(1500);
        long puneBefore = acked.entrySet().stream().filter(e -> e.getKey().startsWith("pune/"))
                .mapToLong(e -> { synchronized (e.getValue()) { return e.getValue().size(); } }).sum();   // approx: taken just before the move
        long heldMs = -1;
        if (proper) heldMs = router.migrateProper("pune", c3); else router.migrateNaive("pune", c3);
        Thread.sleep(1500);
        running.set(false); clients.shutdown(); clients.awaitTermination(10, TimeUnit.SECONDS);

        // verify every acknowledged item through the router
        long ackedItems = 0, missing = 0;
        for (var e : acked.entrySet()) {
            String[] k = e.getKey().split("/");
            String body = HTTP.send(HttpRequest.newBuilder(URI.create(router.url() + "/get?city=" + k[0] + "&cart=" + k[1])).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            Set<String> present = new HashSet<>(Arrays.asList(body.split(",")));
            for (String sku : e.getValue()) { ackedItems++; if (!present.contains(sku)) missing++; }
        }
        if (print) {
            System.out.printf("   %-6s ok %,6d  failed %d | acked items %,6d  MISSING %,5d | %-17s | worst latency: Pune %,4d ms  Nagpur %,4d ms%n",
                    label, ok.get(), failed.get(), ackedItems, missing, proper ? "Pune held " + heldMs + " ms" : "no hold",
                    maxLatUs.get("pune").get() / 1000, maxLatUs.get("nagpur").get() / 1000);
            System.out.printf("          Pune items acknowledged before the move: ~%,d | Pune data left orphaned on cell-1: %s%n",
                    puneBefore, c1.store.containsKey("pune") ? "YES" : "no");
        }
        router.server.stop(0); c1.server.stop(0); c2.server.stop(0); c3.server.stop(0);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Cell router over 3 real HTTP cells. Pune + Nagpur on cell-1, Delhi on cell-2. 4 Pune + 2 Nagpur clients add items for 3 s;");
        System.out.println("at 1.5 s Pune is moved to cell-3. Then every acknowledged item is read back through the router.\n");
        scenario("warmup", true, false);                 // JIT warm-up, not reported
        scenario("NAIVE", false, true);
        scenario("PROPER", true, true);
        System.out.println("\n   → the naive flip returns no errors at all — which is what makes it dangerous: every Pune item acknowledged before the");
        System.out.println("     flip is now invisible, because cell-3 has none of Pune's data and cell-1's copy is orphaned. The proper move holds");
        System.out.println("     only Pune's requests at the router for the duration of the copy — Nagpur, on the SAME source cell, isn't held —");
        System.out.println("     flips the route while nothing is in flight, and loses nothing. The hold lasts exactly as long as the copy, which for");
        System.out.println("     a few thousand cart items is within normal latency noise — but it grows with the city's data, so production moves");
        System.out.println("     of large tenants use dual-write or change-data-capture to pre-copy, pause only for the final delta, verify before");
        System.out.println("     dropping the source, and replicate the routing table to every edge router.");
        POOLS.forEach(ExecutorService::shutdownNow);
        System.exit(0);
    }
}
