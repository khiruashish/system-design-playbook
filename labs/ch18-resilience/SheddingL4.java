// SheddingL4.java — run with: java SheddingL4.java
// A real HTTP service with capacity ~400 req/s (8 workers × 20 ms per request) is offered 800 req/s for 8 s.
// Clients give up after 500 ms. Compare: unbounded queue, bounded queue (reject fast), and deadline-aware shedding.
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class SheddingL4 {

    enum Mode { UNBOUNDED_QUEUE, BOUNDED_QUEUE_40, DROP_IF_WAITED_OVER_250MS }

    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.httpserver.nodelay", "true");
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1))
                .executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        System.out.println("capacity ~400 req/s (8 workers × 20 ms), offered 800 req/s for 8 s, client timeout 500 ms");
        System.out.printf("   %-28s %9s %11s %9s %10s %22s%n", "server policy", "goodput", "shed (503)", "timed out", "p99 (ok)", "work wasted on quitters");
        int port = 19200;
        for (Mode mode : Mode.values()) {
            Semaphore workers = new Semaphore(8, true);
            AtomicInteger waiting = new AtomicInteger(), wasted = new AtomicInteger();
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", ++port), 4096);
            s.createContext("/", ex -> {
                long arrived = System.nanoTime(); int code = 200;
                try {
                    if (mode == Mode.BOUNDED_QUEUE_40 && waiting.get() >= 40) code = 503;          // admission control: reject fast
                    else {
                        waiting.incrementAndGet();
                        workers.acquire();
                        waiting.decrementAndGet();
                        try {
                            long waitedMs = (System.nanoTime() - arrived) / 1_000_000;
                            if (mode == Mode.DROP_IF_WAITED_OVER_250MS && waitedMs > 250) code = 503;   // stale: the client is probably gone
                            else {
                                Thread.sleep(20);                                                     // the actual work
                                if ((System.nanoTime() - arrived) / 1_000_000 > 500) wasted.incrementAndGet();   // client already gave up
                            }
                        } finally { workers.release(); }
                    }
                    ex.sendResponseHeaders(code, -1);
                } catch (Exception ignored) { } finally { ex.close(); }
            });
            s.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); s.start();

            AtomicInteger ok = new AtomicInteger(), shed = new AtomicInteger(), timedOut = new AtomicInteger();
            ConcurrentLinkedQueue<Long> okLat = new ConcurrentLinkedQueue<>();
            ExecutorService load = Executors.newVirtualThreadPerTaskExecutor();
            long start = System.nanoTime(); int n = 6_400; List<Future<?>> fs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                long due = start + i * 1_250_000L;                                                    // 800 req/s
                while (System.nanoTime() < due) Thread.onSpinWait();
                int p = port;
                fs.add(load.submit(() -> {
                    long t0 = System.nanoTime();
                    try {
                        int sc = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + p + "/price")).timeout(Duration.ofMillis(500)).build(),
                                HttpResponse.BodyHandlers.discarding()).statusCode();
                        if (sc == 200) { ok.incrementAndGet(); okLat.add((System.nanoTime() - t0) / 1_000_000); } else shed.incrementAndGet();
                    } catch (HttpTimeoutException e) { timedOut.incrementAndGet(); } catch (Exception e) { timedOut.incrementAndGet(); }
                    return null;
                }));
            }
            for (var f : fs) f.get();
            Thread.sleep(1_500);                                                                     // let the server drain
            long[] lat = okLat.stream().mapToLong(Long::longValue).sorted().toArray();
            System.out.printf("   %-28s %6.0f/s %11d %9d %7d ms %22d%n", mode, ok.get() / 8.0, shed.get(), timedOut.get(),
                    lat.length == 0 ? 0 : lat[lat.length * 99 / 100], wasted.get());
            s.stop(0);
        }
        System.exit(0);
    }
}
