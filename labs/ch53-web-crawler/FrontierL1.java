// FrontierL1.java — run with: java -Dsun.net.httpserver.nodelay=true FrontierL1.java   (JDK 21, no dependencies)
// A POLITE CRAWLER'S URL FRONTIER. A REAL local HTTP server plays 100 websites (/h{host}/p{page}); each host has 300
// pages; every page links to 8 others (70% same host, 30% other hosts, host popularity Zipf — a few big sites get most
// links). Each site allows ONE request per 500 ms from us: faster requests get HTTP 429, and after 10 of those the site
// bans us (403 for everything). 64 crawler threads, 20 seconds per strategy, same seed URLs.
//   C1 one global FIFO queue; fetch immediately
//   C2 one global FIFO queue; before fetching, wait until that host's next allowed time (be polite in place)
//   C3 a queue per host + a scheduler that hands out only hosts whose next allowed time has come (Mercator-style
//      back queues); a worker takes a ready host, fetches one URL, reschedules the host 500 ms later
// Measured: pages fetched successfully, 429s sent to sites, sites that banned us, distinct sites crawled.
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class FrontierL1 {
    static final int HOSTS = 100, PAGES = 300, GAP_MS = 500, WORKERS = 64, RUN_S = 20;
    static final ConcurrentHashMap<Integer, Long> lastHit = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<Integer, AtomicInteger> strikes = new ConcurrentHashMap<>();
    static double[] hostCdf = new double[HOSTS];

    public static void main(String[] args) throws Exception {
        double acc = 0; for (int h = 0; h < HOSTS; h++) { acc += 1 / Math.pow(h + 1, 1.2); hostCdf[h] = acc; } for (int h = 0; h < HOSTS; h++) hostCdf[h] /= acc;
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 512);
        srv.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); srv.createContext("/", FrontierL1::page); srv.start();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        HttpClient http = HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        System.out.println("100 sites × 300 pages, 1 request / 500 ms per site allowed (429 otherwise, ban after 10); 64 threads, 20 s.\n");
        System.out.printf("   %-62s %10s %8s %12s %10s%n", "frontier", "pages ok", "429s", "sites that", "sites");
        System.out.printf("   %-62s %10s %8s %12s %10s%n", "", "", "", "banned us", "crawled");
        String[] names = {"C1) global FIFO, fetch immediately", "C2) global FIFO, wait for the host's turn before fetching", "C3) per-host queues + ready-host scheduler (back queues)"};
        for (int s = 0; s < 3; s++) run(s, names[s], base, http);
        System.out.println("\n   → the web is lopsided: a few sites hold most links, so a global FIFO is mostly URLs of the same few sites.");
        System.out.println("     Fetching them immediately hammers those sites until they ban us, and banned sites stop yielding new links.");
        System.out.println("     Waiting for each site's turn in place mostly fixes the manners (a few 429s still slip through from timing");
        System.out.println("     jitter) but not the throughput: threads sit blocked on the busy sites while other sites' URLs wait behind");
        System.out.println("     them. Separate queues per site, and a scheduler that hands out SITES when they may next be contacted, keep");
        System.out.println("     every thread on a ready site: close to the 4,000-page politeness ceiling (100 sites × 2/s × 20 s), no 429s.");
        srv.stop(0); System.exit(0);
    }

    static void page(HttpExchange ex) throws java.io.IOException {
        String[] p = ex.getRequestURI().getPath().split("/"); int host = Integer.parseInt(p[1].substring(1)), pg = Integer.parseInt(p[2].substring(1));
        String run = ex.getRequestURI().getQuery(); int key = host * 10 + (run == null ? 0 : run.charAt(run.length() - 1) - '0');
        long now = System.nanoTime(); int code = 200;
        AtomicInteger st = strikes.computeIfAbsent(key, k -> new AtomicInteger());
        if (st.get() >= 10) code = 403;
        else { Long prev = lastHit.put(key, now); if (prev != null && now - prev < GAP_MS * 1_000_000L) { st.incrementAndGet(); code = 429; } }
        StringBuilder b = new StringBuilder();
        if (code == 200) { Random r = new Random(host * 100_003L + pg);
            for (int i = 0; i < 8; i++) { int h = r.nextDouble() < 0.7 ? host : pickHost(r); b.append("<a href=\"/h").append(h).append("/p").append(r.nextInt(PAGES)).append("\">x</a>\n"); } }
        byte[] out = b.toString().getBytes(); ex.sendResponseHeaders(code, out.length == 0 ? -1 : out.length); if (out.length > 0) ex.getResponseBody().write(out); ex.close();
    }
    static int pickHost(Random r) { int k = Arrays.binarySearch(hostCdf, r.nextDouble()); return Math.min(HOSTS - 1, k < 0 ? -k - 1 : k); }

    static void run(int strategy, String name, String base, HttpClient http) throws Exception {
        Set<String> seen = ConcurrentHashMap.newKeySet(); AtomicInteger ok = new AtomicInteger(), tooMany = new AtomicInteger(); Set<Integer> crawled = ConcurrentHashMap.newKeySet();
        LinkedBlockingDeque<String> fifo = new LinkedBlockingDeque<>();
        ConcurrentHashMap<Integer, ConcurrentLinkedQueue<String>> perHost = new ConcurrentHashMap<>();
        DelayQueue<HostTurn> ready = new DelayQueue<>(); Set<Integer> scheduled = ConcurrentHashMap.newKeySet();
        ConcurrentHashMap<Integer, Long> nextAllowed = new ConcurrentHashMap<>();
        java.util.function.Consumer<String> add = url -> { if (!seen.add(url)) return; if (strategy < 2) fifo.add(url); else {
            int h = hostOf(url); perHost.computeIfAbsent(h, k -> new ConcurrentLinkedQueue<>()).add(url); if (scheduled.add(h)) ready.add(new HostTurn(h, nextAllowed.getOrDefault(h, 0L))); } };
        for (int h = 0; h < HOSTS; h += 10) add.accept("/h" + h + "/p0");                            // 10 seed sites
        long end = System.currentTimeMillis() + RUN_S * 1000L; ExecutorService ex = Executors.newFixedThreadPool(WORKERS); List<Future<?>> fs = new ArrayList<>();
        for (int w = 0; w < WORKERS; w++) fs.add(ex.submit(() -> {
            while (System.currentTimeMillis() < end) {
                String url; int h;
                if (strategy < 2) { url = fifo.poll(100, TimeUnit.MILLISECONDS); if (url == null) continue; h = hostOf(url);
                    if (strategy == 1) { long wait; synchronized (nextAllowed) { long na = Math.max(nextAllowed.getOrDefault(h, 0L), System.currentTimeMillis()); nextAllowed.put(h, na + GAP_MS + 5); wait = na - System.currentTimeMillis(); }
                        if (wait > 0) Thread.sleep(wait); } }
                else { HostTurn t = ready.poll(100, TimeUnit.MILLISECONDS); if (t == null) continue; h = t.host; url = perHost.get(h).poll();
                    if (url == null) { scheduled.remove(h); if (!perHost.get(h).isEmpty() && scheduled.add(h)) ready.add(new HostTurn(h, System.currentTimeMillis())); continue; } }
                try {
                    HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(base + url + "?run=" + strategy)).build(), HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) { ok.incrementAndGet(); crawled.add(h);
                        for (String line : resp.body().split("\n")) { int i = line.indexOf("href=\""); if (i >= 0) add.accept(line.substring(i + 6, line.indexOf('"', i + 6))); } }
                    else if (resp.statusCode() == 429) tooMany.incrementAndGet();
                } catch (Exception e) { }
                if (strategy == 2) { long na = System.currentTimeMillis() + GAP_MS + 5; nextAllowed.put(h, na);
                    if (!perHost.get(h).isEmpty()) ready.add(new HostTurn(h, na)); else { scheduled.remove(h); if (!perHost.get(h).isEmpty() && scheduled.add(h)) ready.add(new HostTurn(h, na)); } }
            }
            return null; }));
        for (Future<?> f : fs) f.get(); ex.shutdown();
        int banned = 0; for (int h = 0; h < HOSTS; h++) { AtomicInteger st = strikes.get(h * 10 + strategy); if (st != null && st.get() >= 10) banned++; }
        System.out.printf("   %-62s %,10d %,8d %12d %10d%n", name, ok.get(), tooMany.get(), banned, crawled.size());
    }
    static int hostOf(String url) { return Integer.parseInt(url.substring(2, url.indexOf('/', 1))); }
    record HostTurn(int host, long at) implements Delayed {
        public long getDelay(TimeUnit u) { return u.convert(at - System.currentTimeMillis(), TimeUnit.MILLISECONDS); }
        public int compareTo(Delayed o) { return Long.compare(at, ((HostTurn) o).at); }
    }
}
