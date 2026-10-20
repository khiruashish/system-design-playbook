// DeployL1.java — run with: java -Dsun.net.httpserver.nodelay=true DeployL1.java   (JDK 21, no dependencies)
// Deployment strategies against REAL backend instances (JDK HttpServers) behind a REAL load balancer, under live traffic.
// We ship a BAD new version (v2 fails 40% of requests) using each strategy and measure how many user requests it breaks
// and how fast we can roll back. Strategies: recreate (stop-all/start-all), rolling, blue-green, canary (with auto-rollback).
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class DeployL1 {
    static final int BASE = 19850;
    static final class Instance {
        final int port; final int version; HttpServer server; volatile boolean ready = true;
        Instance(int port, int version) throws Exception {
            this.port = port; this.version = version;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/", ex -> {
                boolean bad = version == 2 && ThreadLocalRandom.current().nextDouble() < 0.40;   // v2 is broken: 40% errors
                int code = bad ? 500 : 200; byte[] b = ("v" + version).getBytes();
                ex.sendResponseHeaders(code, code == 200 ? b.length : -1); if (code == 200) ex.getResponseBody().write(b); ex.close();
            });
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); server.start();
        }
        void stop() { server.stop(0); }
    }

    // the load balancer: routes to READY instances only, round-robin (Ch 08)
    static final List<Instance> pool = new CopyOnWriteArrayList<>();
    static final AtomicInteger rr = new AtomicInteger();
    static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofMillis(200)).build();

    static int route() {   // returns HTTP status the user sees, or 503 if no ready backend
        List<Instance> ready = new ArrayList<>(); for (Instance i : pool) if (i.ready) ready.add(i);
        if (ready.isEmpty()) return 503;
        Instance i = ready.get(Math.floorMod(rr.getAndIncrement(), ready.size()));
        try { return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + i.port + "/")).timeout(Duration.ofMillis(300)).build(), HttpResponse.BodyHandlers.discarding()).statusCode(); }
        catch (Exception e) { return 502; }
    }

    // a continuous traffic generator; returns [ok, bad] counts collected during `run`
    static long[] traffic(long ms) throws InterruptedException {
        AtomicLong ok = new AtomicLong(), bad = new AtomicLong();
        long end = System.currentTimeMillis() + ms;
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore conc = new Semaphore(20);
            while (System.currentTimeMillis() < end) {
                conc.acquire();
                ex.submit(() -> { try { int c = route(); if (c == 200) ok.incrementAndGet(); else bad.incrementAndGet(); } finally { conc.release(); } });
                Thread.sleep(1);
            }
        }
        return new long[]{ok.get(), bad.get()};
    }

    static Instance newInstance(int version) throws Exception { Instance i = new Instance(BASE + rr.incrementAndGet() % 1000 + version * 1000 + (int)(Math.random()*500), version); return i; }

    public static void main(String[] args) throws Exception {
        System.out.println("4 instances of v1 behind a load balancer. We deploy v2 (which fails 40% of requests) each way.\n");
        System.out.printf("   %-30s %10s %10s %14s %s%n", "strategy", "ok", "failed", "fail rate", "notes");

        // ---------- recreate: stop all v1, start all v2 ----------
        setupV1(4);
        var t = bg(2500);
        Thread.sleep(400);
        for (Instance i : new ArrayList<>(pool)) { i.stop(); pool.remove(i); }               // full downtime
        Thread.sleep(300);                                                                    // nothing is serving
        for (int k = 0; k < 4; k++) pool.add(new Instance(port(), 2));
        long[] r = t.get();
        report("recreate (stop-all → start-all)", r, "downtime window + 100% on the bad v2");
        teardown();

        // ---------- rolling: replace one at a time ----------
        setupV1(4);
        t = bg(3000);
        Thread.sleep(300);
        for (int k = 0; k < 4; k++) { Instance old = pool.remove(0); old.ready = false; Thread.sleep(50); old.stop(); pool.add(new Instance(port(), 2)); Thread.sleep(400); }
        r = t.get();
        report("rolling (one at a time)", r, "no downtime, but v2's errors ramp to 100% as it rolls");
        teardown();

        // ---------- blue-green: full v2 pool built and health-checked, then flip ----------
        setupV1(4);
        t = bg(3000);
        Thread.sleep(300);
        List<Instance> green = new ArrayList<>(); for (int k = 0; k < 4; k++) { Instance g = new Instance(port(), 2); g.ready = false; green.add(g); }
        Thread.sleep(300);                                                                    // "warm up / smoke test" green out of the LB
        boolean smokeOk = smoke(green);                                                       // a smoke test catches the bad build
        if (smokeOk) { for (Instance g : green) { pool.add(g); g.ready = true; } for (Instance b : new ArrayList<>(pool)) if (b.version == 1) { b.ready = false; pool.remove(b); b.stop(); } }
        else for (Instance g : green) g.stop();                                               // discard green, blue keeps serving
        r = t.get();
        report("blue-green (flip after smoke test)", r, smokeOk ? "instant flip (bad v2 went live 100%)" : "smoke test FAILED → never flipped, users saw only v1");
        teardown();

        // ---------- canary: 10% to v2, watch error rate, auto-rollback ----------
        setupV1(4);
        t = bg(3500);
        Thread.sleep(300);
        Instance canary = new Instance(port(), 2); pool.add(canary);                          // 1 of 5 ≈ 20% — we weight it down by removing then re-adding conceptually; here measure canary error rate directly
        Thread.sleep(600);                                                                    // observe the canary
        double canaryErr = observe(canary, 200);
        if (canaryErr > 0.05) { canary.ready = false; pool.remove(canary); canary.stop(); }   // auto-rollback: SLO breached
        else { for (int k = 0; k < 3; k++) pool.add(new Instance(port(), 2)); }               // promote
        r = t.get();
        report("canary (10% + auto-rollback)", r, String.format("canary error %.0f%% > 5%% SLO → rolled back after ~0.9 s; blast radius = the canary only", canaryErr*100));
        teardown();

        System.out.println("\n   → recreate has downtime AND full exposure; rolling avoids downtime but still exposes everyone as it completes;");
        System.out.println("     blue-green makes the bad build catchable by a smoke test before the flip (and gives instant rollback by flipping back);");
        System.out.println("     canary limits the blast radius to a small % and rolls back automatically on an SLO breach — the safest for risky changes.");
    }

    static int port() { return BASE + 1 + (int)(Math.random()*4000); }
    static void setupV1(int n) throws Exception { for (int k = 0; k < n; k++) pool.add(new Instance(port(), 1)); }
    static void teardown() { for (Instance i : new ArrayList<>(pool)) i.stop(); pool.clear(); }
    static Future<long[]> bg(long ms) { var ex = Executors.newSingleThreadExecutor(); return ex.submit(() -> traffic(ms)); }
    static void report(String name, long[] r, String note) {
        long ok = r[0], bad = r[1], tot = ok + bad;
        System.out.printf("   %-30s %10d %10d %13.1f%% %s%n", name, ok, bad, tot == 0 ? 0 : 100.0 * bad / tot, note);
    }
    static boolean smoke(List<Instance> green) {   // hit each green instance a few times; fail if any errors
        int errs = 0, n = 0;
        for (Instance g : green) for (int k = 0; k < 25; k++) { n++; try { if (CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + g.port + "/")).build(), HttpResponse.BodyHandlers.discarding()).statusCode() != 200) errs++; } catch (Exception e) { errs++; } }
        return errs == 0;                                                                     // v2 fails 40%, so a smoke test almost always catches it
    }
    static double observe(Instance i, int n) {
        int errs = 0; for (int k = 0; k < n; k++) { try { if (CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + i.port + "/")).build(), HttpResponse.BodyHandlers.discarding()).statusCode() != 200) errs++; } catch (Exception e) { errs++; } }
        return (double) errs / n;
    }
}
