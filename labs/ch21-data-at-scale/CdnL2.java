// CdnL2.java — run with: java -Dsun.net.httpserver.nodelay=true CdnL2.java
// A pull-through edge cache (a tiny CDN node) in front of a real origin, both JDK HttpServers on virtual threads.
// A. cache keys: full URL vs normalised (tracking params dropped, params sorted) under a Zipf workload
// B. a hot object expires under 400 concurrent requests: no coalescing vs request coalescing
// C. a price changes: TTL-only vs purge vs versioned (content-hashed) URLs
// D. origin outage: stale-if-error vs strict TTL
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class CdnL2 {
    // ---------------- origin ----------------
    static final AtomicInteger originHits = new AtomicInteger();
    static final Map<String, String> prices = new ConcurrentHashMap<>();
    static volatile boolean originDown = false;
    static final byte[] IMAGE = new byte[50_000];

    static HttpServer origin() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 19501), 2000);
        s.createContext("/", ex -> {
            try {
                originHits.incrementAndGet();
                if (originDown) { ex.sendResponseHeaders(503, -1); return; }
                String path = ex.getRequestURI().getPath();
                Thread.sleep(20);                                                          // render/resize/db work
                byte[] body; String cc;
                if (path.startsWith("/img/")) { body = IMAGE; cc = "public, max-age=3600"; }
                else if (path.startsWith("/v/")) { body = prices.getOrDefault(path.substring(path.lastIndexOf('/') + 1), "?").getBytes(); cc = "public, max-age=31536000, immutable"; }
                else { String sku = path.substring(path.lastIndexOf('/') + 1); body = ("{\"sku\":\"" + sku + "\",\"price\":" + prices.getOrDefault(sku, "0") + "}").getBytes(); cc = "public, max-age=2"; }
                ex.getResponseHeaders().set("Cache-Control", cc);
                ex.sendResponseHeaders(200, body.length); ex.getResponseBody().write(body);
            } catch (Exception e) { } finally { ex.close(); }
        });
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); s.start(); return s;
    }

    // ---------------- edge ----------------
    record Entry(byte[] body, int status, long freshUntil, long staleUntil) {}
    static final Map<String, Entry> cache = new ConcurrentHashMap<>();
    static final Map<String, CompletableFuture<Entry>> inFlight = new ConcurrentHashMap<>();
    static volatile boolean normaliseKeys, coalesce, staleIfError;
    static final HttpClient TO_ORIGIN = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).executor(Executors.newVirtualThreadPerTaskExecutor()).build();

    static String cacheKey(URI u) {
        if (!normaliseKeys || u.getQuery() == null) return u.toString();
        var params = new TreeSet<String>();
        for (String p : u.getQuery().split("&")) if (!p.startsWith("utm_") && !p.startsWith("fbclid") && !p.startsWith("gclid")) params.add(p);
        return u.getPath() + (params.isEmpty() ? "" : "?" + String.join("&", params));
    }

    static Entry fetch(URI u) throws Exception {
        var r = TO_ORIGIN.send(HttpRequest.newBuilder(URI.create("http://localhost:19501" + u.getPath())).build(), HttpResponse.BodyHandlers.ofByteArray());
        long maxAge = r.headers().firstValue("cache-control").map(cc -> Long.parseLong(cc.replaceAll(".*max-age=(\\d+).*", "$1"))).orElse(0L);
        long now = System.currentTimeMillis();
        return new Entry(r.body(), r.statusCode(), now + maxAge * 1000, now + maxAge * 1000 + 60_000);   // keep stale copy 60 s
    }

    static HttpServer edge() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 19502), 2000);
        s.createContext("/", ex -> {
            try {
                if (ex.getRequestMethod().equals("PURGE")) { cache.keySet().removeIf(k -> k.startsWith(ex.getRequestURI().getPath())); ex.sendResponseHeaders(204, -1); return; }
                URI u = ex.getRequestURI(); String key = cacheKey(u);
                Entry e = cache.get(key); long now = System.currentTimeMillis(); String status = "HIT";
                if (e == null || now > e.freshUntil()) {
                    status = "MISS";
                    Entry fresh;
                    if (coalesce) {
                        var mine = new CompletableFuture<Entry>();
                        var existing = inFlight.putIfAbsent(key, mine);
                        if (existing != null) { fresh = existing.get(); status = "HIT-COALESCED"; }
                        else { try { fresh = fetch(u); mine.complete(fresh); } catch (Exception err) { mine.completeExceptionally(err); throw err; } finally { inFlight.remove(key); } }
                    } else fresh = fetch(u);
                    if (fresh.status() >= 500 && staleIfError && e != null && now < e.staleUntil()) status = "STALE";   // serve stale on origin error
                    else { e = fresh; if (fresh.status() == 200) cache.put(key, fresh); }
                }
                ex.getResponseHeaders().set("X-Cache", status);
                ex.sendResponseHeaders(e.status(), e.status() == 200 ? e.body().length : -1);
                if (e.status() == 200) ex.getResponseBody().write(e.body());
            } catch (Exception err) { try { ex.sendResponseHeaders(502, -1); } catch (Exception ignored) { } }
            finally { ex.close(); }
        });
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); s.start(); return s;
    }

    // ---------------- client ----------------
    static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).executor(Executors.newVirtualThreadPerTaskExecutor()).build();
    static HttpResponse<byte[]> get(int port, String pathAndQuery) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    static int zipf(Random r, int n) {                                                     // rank 1..n, s = 1
        double h = 0; for (int i = 1; i <= n; i++) h += 1.0 / i;
        double x = r.nextDouble() * h, acc = 0;
        for (int i = 1; i <= n; i++) { acc += 1.0 / i; if (acc >= x) return i; }
        return n;
    }

    public static void main(String[] args) throws Exception {
        HttpServer o = origin(), e = edge();
        for (int i = 1; i <= 500; i++) prices.put("sku-" + i, String.valueOf(40 + i % 60));

        System.out.println("A. 20,000 image requests, 500 products (Zipf), 40 % of URLs carry tracking params in random order");
        List<String> urls = new ArrayList<>(); Random r = new Random(3);
        for (int i = 0; i < 20_000; i++) {
            int sku = zipf(r, 500);
            String u = "/img/sku-" + sku + ".jpg?w=400";
            if (r.nextDouble() < 0.4) {
                var ps = new ArrayList<>(List.of("w=400", "utm_source=push", "utm_campaign=diwali-" + r.nextInt(20), "fbclid=" + Integer.toHexString(r.nextInt())));
                Collections.shuffle(ps, r); u = "/img/sku-" + sku + ".jpg?" + String.join("&", ps);
            }
            urls.add(u);
        }
        System.out.printf("   %-34s %13s %10s %16s%n", "setup", "origin hits", "hit ratio", "origin egress");
        for (String setup : List.of("no CDN", "CDN, key = full URL", "CDN, normalised key")) {
            cache.clear(); originHits.set(0); normaliseKeys = setup.endsWith("normalised key"); coalesce = true;
            int port = setup.equals("no CDN") ? 19501 : 19502;
            long[] lat = new long[urls.size()];
            try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
                Semaphore conc = new Semaphore(200);
                for (int i = 0; i < urls.size(); i++) {
                    int ii = i; conc.acquire();
                    ex.submit(() -> { long t0 = System.nanoTime(); try { get(port, urls.get(ii)); } catch (Exception err) { } finally { lat[ii] = System.nanoTime() - t0; conc.release(); } });
                }
            }
            System.out.printf("   %-34s %,13d %9.1f %% %,13d MB%n", setup, originHits.get(), 100.0 * (urls.size() - originHits.get()) / urls.size(),
                    (long) originHits.get() * IMAGE.length >> 20);
        }

        System.out.println("B. The hottest product's JSON expires while 400 requests arrive at once");
        normaliseKeys = true;
        for (boolean c : List.of(false, true)) {
            coalesce = c; cache.clear(); get(19502, "/api/products/sku-1"); Thread.sleep(2_100);   // cached, then expired (max-age=2)
            originHits.set(0);
            var start = new CountDownLatch(1);
            try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 400; i++) ex.submit(() -> { start.await(); return get(19502, "/api/products/sku-1"); });
                start.countDown();
            }
            System.out.printf("   %-26s origin requests for one key: %d%n", c ? "with request coalescing" : "without coalescing", originHits.get());
        }

        System.out.println("C. sku-7 price changes from 47 to 39 while 50 req/s read it for 3 s");
        for (String mode : List.of("TTL only (max-age=2)", "TTL + purge on change", "versioned URL")) {
            cache.clear(); prices.put("sku-7", "47"); coalesce = true;
            String v1 = "/v/p47/sku-7"; prices.put("p47", "47");                         // versioned: the URL names the content
            get(19502, mode.startsWith("versioned") ? v1 : "/api/products/sku-7");
            prices.put("sku-7", "39"); prices.put("p39", "39");                          // the change at t = 0
            if (mode.contains("purge")) CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:19502/api/products/sku-7")).method("PURGE", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
            int stale = 0, total = 0; long lastStaleMs = 0, t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 3_000) {
                String path = mode.startsWith("versioned") ? "/v/p39/sku-7" : "/api/products/sku-7";   // pages now link the new version
                String body = new String(get(19502, path).body());
                total++;
                if (body.contains("47")) { stale++; lastStaleMs = System.currentTimeMillis() - t0; }
                Thread.sleep(20);
            }
            System.out.printf("   %-24s stale responses %3d of %3d, last stale at %,5d ms%n", mode, stale, total, lastStaleMs);
        }

        System.out.println("D. Origin goes down for 5 s; product JSON has max-age=2");
        for (boolean sie : List.of(false, true)) {
            staleIfError = sie; cache.clear(); originDown = false; get(19502, "/api/products/sku-3"); Thread.sleep(2_100);
            originDown = true;
            int ok = 0, failed = 0;
            for (int i = 0; i < 50; i++) { int st = get(19502, "/api/products/sku-3").statusCode(); if (st == 200) ok++; else failed++; Thread.sleep(100); }
            originDown = false;
            System.out.printf("   %-24s served %d, errors %d%n", sie ? "stale-if-error (60 s)" : "strict TTL", ok, failed);
        }
        o.stop(0); e.stop(0);
    }
}
