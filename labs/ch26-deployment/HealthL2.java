// HealthL2.java — run with: java -Dsun.net.httpserver.nodelay=true HealthL2.java   (JDK 21, no dependencies)
// Health probes done right, against REAL instances. Each instance has /healthz (liveness), /readyz (readiness) and a
// startup period. An orchestrator (like kubelet) probes them and the load balancer routes only to READY instances; a
// liveness failure triggers a restart. We measure user-visible errors in three scenarios with probes on vs off:
//   A. a slow-starting instance (20 s warmup) — readiness/startup probe vs routing immediately.
//   B. an instance that goes unready under load (loses its DB) — readiness drains it vs keeps taking traffic.
//   C. a hung instance (deadlocked) — liveness restarts it vs it black-holes requests forever.
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class HealthL2 {
    static final HttpClient C = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build();
    static final class Inst {
        final int port; HttpServer s;
        volatile boolean started, dbUp = true, hung = false; volatile long restarts = 0;
        Inst(int port, long startupMs) throws Exception {
            this.port = port;
            s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            s.createContext("/", ex -> { if (hung) { try { Thread.sleep(60_000); } catch (Exception e) {} } handle(ex, started && dbUp ? 200 : 503); });
            s.createContext("/healthz", ex -> handle(ex, hung ? 500 : 200));                 // liveness: is the process alive & not deadlocked?
            s.createContext("/readyz", ex -> handle(ex, started && dbUp && !hung ? 200 : 503)); // readiness: can it serve right now?
            s.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); s.start();
            new Thread(() -> { try { Thread.sleep(startupMs); } catch (Exception e) {} started = true; }).start();
        }
        static void handle(HttpExchange ex, int code) throws java.io.IOException { ex.sendResponseHeaders(code, -1); ex.close(); }
    }

    static boolean probe(int port, String path) { try { return C.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofMillis(200)).build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200; } catch (Exception e) { return false; } }

    static final Map<Inst, Boolean> ready = new ConcurrentHashMap<>();     // the LB's view, driven by readiness probes
    static volatile boolean probing = true;
    static Thread orchestrator(List<Inst> insts, boolean probesOn, int livenessFailsToRestart) {
        Map<Inst, Integer> livenessFails = new HashMap<>();
        return new Thread(() -> {
            while (probing) {
                for (Inst i : insts) {
                    if (!probesOn) { ready.put(i, true); continue; }                          // no probes: LB assumes everything is ready
                    ready.put(i, probe(i.port, "/readyz"));                                    // readiness gates routing
                    boolean live = probe(i.port, "/healthz");                                  // liveness gates restart
                    livenessFails.merge(i, live ? 0 : 1, (a, b) -> b == 0 ? 0 : a + b);
                    if (livenessFails.getOrDefault(i, 0) >= livenessFailsToRestart) { i.hung = false; i.restarts++; livenessFails.put(i, 0); }  // "restart"
                }
                try { Thread.sleep(100); } catch (Exception e) {}
            }
        });
    }

    static long[] traffic(List<Inst> insts, long ms) throws Exception {
        AtomicLong ok = new AtomicLong(), err = new AtomicLong(); AtomicInteger rr = new AtomicInteger();
        long end = System.currentTimeMillis() + ms;
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore conc = new Semaphore(15);
            while (System.currentTimeMillis() < end) {
                conc.acquire();
                ex.submit(() -> { try {
                    List<Inst> avail = new ArrayList<>(); for (Inst i : insts) if (ready.getOrDefault(i, false)) avail.add(i);
                    if (avail.isEmpty()) { err.incrementAndGet(); return; }
                    Inst i = avail.get(Math.floorMod(rr.getAndIncrement(), avail.size()));
                    try { int c = C.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + i.port + "/")).timeout(Duration.ofMillis(500)).build(), HttpResponse.BodyHandlers.discarding()).statusCode(); if (c == 200) ok.incrementAndGet(); else err.incrementAndGet(); }
                    catch (Exception e) { err.incrementAndGet(); }
                } finally { conc.release(); } });
                Thread.sleep(2);
            }
        }
        return new long[]{ok.get(), err.get()};
    }

    static int PORT = 19900;
    public static void main(String[] args) throws Exception {
        System.out.printf("   %-46s %10s %10s %12s%n", "scenario", "ok", "failed", "fail rate");
        // A. slow start (20s warmup), short test — with probes the LB waits; without, it routes into a not-yet-ready instance
        for (boolean probes : new boolean[]{false, true}) {
            probing = true; ready.clear();
            List<Inst> insts = List.of(new Inst(PORT++, 1500), new Inst(PORT++, 0));           // one instance takes 1.5s to start
            Thread orch = orchestrator(insts, probes, 3); orch.start();
            long[] r = traffic(insts, 1800);
            probing = false; orch.join(); for (Inst i : insts) i.s.stop(0);
            report((probes ? "A. slow start, readiness probe ON" : "A. slow start, NO probes (route immediately)"), r);
        }
        // B. an instance loses its DB mid-test (becomes unready) — readiness drains it; without probes it keeps failing
        for (boolean probes : new boolean[]{false, true}) {
            probing = true; ready.clear();
            List<Inst> insts = List.of(new Inst(PORT++, 0), new Inst(PORT++, 0), new Inst(PORT++, 0));
            Thread orch = orchestrator(insts, probes, 3); orch.start();
            var f = Executors.newSingleThreadExecutor().submit(() -> { try { return traffic(insts, 2500); } catch (Exception e) { return new long[]{0,0}; } });
            Thread.sleep(800); insts.get(0).dbUp = false;                                       // one backend loses its DB
            long[] r = f.get();
            probing = false; orch.join(); for (Inst i : insts) i.s.stop(0);
            report((probes ? "B. lost DB, readiness ON (drains it)" : "B. lost DB, NO probes (keeps routing to it)"), r);
        }
        // C. an instance hangs (deadlock) — liveness restarts it; without, it black-holes its share forever
        for (boolean probes : new boolean[]{false, true}) {
            probing = true; ready.clear();
            List<Inst> insts = List.of(new Inst(PORT++, 0), new Inst(PORT++, 0), new Inst(PORT++, 0));
            Thread orch = orchestrator(insts, probes, 3); orch.start();
            var f = Executors.newSingleThreadExecutor().submit(() -> { try { return traffic(insts, 2500); } catch (Exception e) { return new long[]{0,0}; } });
            Thread.sleep(700); insts.get(0).hung = true;                                        // one backend deadlocks
            long[] r = f.get();
            probing = false; orch.join(); long restarts = insts.get(0).restarts; for (Inst i : insts) i.s.stop(0);
            report((probes ? "C. hung, liveness ON (restarts, " + restarts + "x)" : "C. hung, NO probes (black-holes requests)"), r);
        }
        System.out.println("\n   → readiness gates ROUTING (don't send traffic to an instance that's starting or has lost a dependency);");
        System.out.println("     liveness gates RESTARTS (kill and replace a hung/deadlocked instance); a startup probe protects a slow boot");
        System.out.println("     from a trigger-happy liveness check. Getting these wrong causes the errors above — or restart loops.");
    }
    static void report(String name, long[] r) { long tot = r[0]+r[1]; System.out.printf("   %-46s %10d %10d %11.1f%%%n", name, r[0], r[1], tot==0?0:100.0*r[1]/tot); }
}
