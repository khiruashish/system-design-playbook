// FanOutL3.java — run with: /opt/jdk25/bin/java --enable-preview --source 25 FanOutL3.java
// The product page needs catalog + pricing + inventory (required) and reviews (optional), over real HTTP.
// Inventory fails fast for 20 % of pages; reviews take 2 s for 10 %. Compare sequential, CompletableFuture.allOf,
// and structured concurrency (JDK 25 preview, JEP 505): fail fast + cancel siblings + a time budget for the optional part.
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.atomic.*;

public class FanOutL3 {

    static final Map<String, Long> pageDone = new ConcurrentHashMap<>();
    static final AtomicInteger wastedCalls = new AtomicInteger(), leakedTasks = new AtomicInteger();
    static final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .executor(Executors.newVirtualThreadPerTaskExecutor()).build();

    static HttpServer service(int port, String name, long ms, double failShare, double slowShare, long slowMs) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 1000);
        s.createContext("/", ex -> {
            String page = ex.getRequestHeaders().getFirst("X-Page");
            int h = Math.floorMod(page.hashCode() * 31 + name.hashCode(), 1000);             // deterministic per page
            boolean fail = h < failShare * 1000, slow = h >= 500 && h < 500 + slowShare * 1000;
            int code = 200;
            try {
                if (fail) { Thread.sleep(5); code = 503; }
                else Thread.sleep(slow ? slowMs : ms);
                if (pageDone.containsKey(page)) wastedCalls.incrementAndGet();               // nobody needs this answer any more
                ex.sendResponseHeaders(code, -1);
            } catch (Exception ignored) { } finally { ex.close(); }
        });
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); s.start(); return s;
    }

    static String get(int port, String page) throws Exception {
        var r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).header("X-Page", page).build(),
                HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() != 200) throw new IllegalStateException("port " + port + " → " + r.statusCode());
        return "ok";
    }

    interface Page { String render(String page) throws Exception; }

    static final Page SEQUENTIAL = page -> {
        get(19301, page); get(19302, page); get(19303, page);
        try { get(19304, page); } catch (Exception e) { }                                     // reviews optional
        return "ok";
    };

    static final Page COMPLETABLE_FUTURES = page -> {
        ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();
        var c = CompletableFuture.supplyAsync(() -> call(19301, page), vt);
        var p = CompletableFuture.supplyAsync(() -> call(19302, page), vt);
        var i = CompletableFuture.supplyAsync(() -> call(19303, page), vt);
        var r = CompletableFuture.supplyAsync(() -> call(19304, page), vt);
        try {
            CompletableFuture.allOf(c, p, i).join();                                           // waits for ALL, even after one failed
            try { r.get(150, TimeUnit.MILLISECONDS); } catch (Exception e) { }                 // optional, but the task keeps running
            return "ok";
        } finally {
            for (var f : List.of(c, p, i, r)) if (!f.isDone()) leakedTasks.incrementAndGet(); // still running after we returned
        }
    };

    static final Page STRUCTURED = page -> {
        try (var required = StructuredTaskScope.open(Joiner.<String>awaitAllSuccessfulOrThrow(), cf -> cf.withTimeout(Duration.ofMillis(300)));
             var optional = StructuredTaskScope.open(Joiner.<String>awaitAll(), cf -> cf.withTimeout(Duration.ofMillis(150)))) {
            var reviews = optional.fork(() -> get(19304, page));
            required.fork(() -> get(19301, page));
            required.fork(() -> get(19302, page));
            required.fork(() -> get(19303, page));
            required.join();                                  // first failure → cancel the siblings (interrupts their HTTP calls), throw
            try { optional.join(); } catch (StructuredTaskScope.TimeoutException e) { }   // budget spent → reviews cancelled
            return reviews.state() == StructuredTaskScope.Subtask.State.SUCCESS ? "ok" : "ok (no reviews)";
        }
    };

    static String call(int port, String page) { try { return get(port, page); } catch (Exception e) { throw new CompletionException(e); } }

    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.httpserver.nodelay", "true");
        List<HttpServer> svcs = List.of(service(19301, "catalog", 20, 0, 0, 0), service(19302, "pricing", 30, 0, 0, 0),
                service(19303, "inventory", 25, 0.20, 0, 0), service(19304, "reviews", 40, 0, 0.10, 2_000));
        Map<String, Page> styles = new LinkedHashMap<>();
        styles.put("sequential", SEQUENTIAL);
        styles.put("CompletableFuture.allOf", COMPLETABLE_FUTURES);
        styles.put("StructuredTaskScope (JDK 25 preview)", STRUCTURED);
        System.out.println("300 product pages: catalog 20 ms, pricing 30 ms, inventory 25 ms (fails fast for 20 %), reviews 40 ms (2 s for 10 %)");
        System.out.printf("   %-38s %8s %8s %11s %16s %22s%n", "style", "ok p50", "ok p99", "error p50", "tasks left running", "server work after reply");
        int round = 0;
        for (var e : styles.entrySet()) {
            round++; wastedCalls.set(0); leakedTasks.set(0); pageDone.clear();
            List<Long> ok = new ArrayList<>(), err = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                String page = "p" + i;                                                       // same pages for every style
                long t0 = System.nanoTime();
                try { e.getValue().render(page); ok.add((System.nanoTime() - t0) / 1_000_000); }
                catch (Exception ex) { err.add((System.nanoTime() - t0) / 1_000_000); }
                pageDone.put(page, System.nanoTime());
            }
            Thread.sleep(2_500);                                                             // let stragglers finish and be counted
            Collections.sort(ok); Collections.sort(err);
            System.out.printf("   %-38s %5d ms %5d ms %8d ms %16d %22d%n", e.getKey(), ok.get(ok.size() / 2), ok.get(ok.size() * 99 / 100),
                    err.isEmpty() ? 0 : err.get(err.size() / 2), leakedTasks.get(), wastedCalls.get());
        }
        svcs.forEach(s -> s.stop(0));
        System.exit(0);
    }
}
