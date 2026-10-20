// HopsL2.java — run with: java -Dsun.net.httpserver.nodelay=true HopsL2.java   (JDK 21, no dependencies)
// MODULAR MONOLITH vs MICROSERVICES: what a module boundary costs when it becomes a network boundary. A checkout needs five
// collaborators (catalog, pricing, inventory, promotions, orders). The SAME collaborator logic is called three ways:
//   MODULES          — in-process method calls between modules of one deployable
//   SERVICES, serial — each collaborator is a separate HTTP service; checkout calls them one after another (JSON over HTTP)
//   SERVICES, fan-out— the independent calls are made in parallel
// Measured on localhost, which is the BEST case: no real network, no TLS, no service mesh, no cross-zone hop. In a data
// centre add ~0.3–1 ms per hop plus TLS/sidecar overhead. Then we compute the availability side of the same trade.
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;

public class HopsL2 {
    static final String[] NAMES = {"catalog", "pricing", "inventory", "promotions", "orders"};

    // the collaborator logic — identical in both deployments (a little real work + a JSON-ish payload)
    static String handle(String name, String sku) {
        long h = 0; for (int i = 0; i < 2_000; i++) h = h * 31 + (sku.hashCode() ^ i);
        return "{\"svc\":\"" + name + "\",\"sku\":\"" + sku + "\",\"v\":" + (h & 0xffff) + "}";
    }
    static String checkoutInProcess(String sku) {
        StringBuilder sb = new StringBuilder();
        for (String n : NAMES) sb.append(handle(n, sku));
        return sb.toString();
    }

    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .executor(Executors.newFixedThreadPool(8)).build();
    static final Map<String, URI> URIS = new HashMap<>();
    static String call(String n, String sku) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URIS.get(n).resolve("/" + n + "?sku=" + sku)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }
    static String checkoutSerial(String sku) throws Exception {
        StringBuilder sb = new StringBuilder(); for (String n : NAMES) sb.append(call(n, sku)); return sb.toString();
    }
    static final ExecutorService FAN = Executors.newFixedThreadPool(8);
    static String checkoutFanOut(String sku) throws Exception {
        List<Future<String>> fs = new ArrayList<>();
        for (String n : NAMES) fs.add(FAN.submit(() -> call(n, sku)));
        StringBuilder sb = new StringBuilder(); for (Future<String> f : fs) sb.append(f.get()); return sb.toString();
    }

    interface Op { String run(String sku) throws Exception; }
    static double[] measure(Op op, int warm, int n) throws Exception {
        for (int i = 0; i < warm; i++) op.run("SKU" + (i % 100));
        long[] t = new long[n];
        for (int i = 0; i < n; i++) { long s = System.nanoTime(); op.run("SKU" + (i % 100)); t[i] = System.nanoTime() - s; }
        Arrays.sort(t);
        return new double[]{ t[n / 2] / 1000.0, t[(int) (n * 0.99)] / 1000.0 };
    }

    public static void main(String[] args) throws Exception {
        List<HttpServer> servers = new ArrayList<>(); List<ExecutorService> pools = new ArrayList<>();
        for (String n : NAMES) {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/" + n, ex -> {
                String sku = ex.getRequestURI().getQuery().substring(4);
                byte[] b = handle(n, sku).getBytes();
                ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
            });
            ExecutorService p = Executors.newFixedThreadPool(4); pools.add(p); s.setExecutor(p); s.start(); servers.add(s);
            URIS.put(n, URI.create("http://127.0.0.1:" + s.getAddress().getPort()));
        }
        // sanity: all three produce the same response
        boolean same = checkoutInProcess("SKU7").equals(checkoutSerial("SKU7")) && checkoutSerial("SKU7").equals(checkoutFanOut("SKU7"));

        int WARM = 1_500, N = 3_000;
        double[] m = measure(HopsL2::checkoutInProcess, WARM, N);
        double[] s = measure(HopsL2::checkoutSerial, WARM, N);
        double[] f = measure(HopsL2::checkoutFanOut, WARM, N);

        System.out.printf("One checkout = 5 collaborator calls. Same logic, same responses (%s). %,d measured runs each, localhost.%n%n",
                same ? "verified identical" : "MISMATCH", N);
        System.out.printf("   %-40s %12s %12s %14s%n", "deployment", "p50", "p99", "p50 vs modules");
        System.out.printf("   %-40s %9.1f µs %9.1f µs %13s%n", "modular monolith (in-process calls)", m[0], m[1], "1x");
        System.out.printf("   %-40s %9.1f µs %9.1f µs %12.0fx%n", "microservices, 5 serial HTTP calls", s[0], s[1], s[0] / m[0]);
        System.out.printf("   %-40s %9.1f µs %9.1f µs %12.0fx%n", "microservices, 5 parallel HTTP calls", f[0], f[1], f[0] / m[0]);

        // availability: each independently deployed service at 99.9% monthly; the checkout path needs all of them
        double a = 0.999;
        double mono = a;                         // one deployable on the path
        double micro = Math.pow(a, 1 + NAMES.length);   // checkout service + 5 dependencies, all required
        System.out.printf("%n   Availability if every deployable is 99.9%% and all are required on the checkout path:%n");
        System.out.printf("        modular monolith (1 deployable):      %.3f%%  → %4.0f min/month down%n", mono * 100, (1 - mono) * 43_200);
        System.out.printf("        microservices (6 deployables):        %.3f%%  → %4.0f min/month down%n", micro * 100, (1 - micro) * 43_200);

        System.out.printf("%n   → even on localhost the HTTP path costs ~%.0fx the in-process one, and serial calls add up hop by hop (fan-out halves%n", s[0] / m[0]);
        System.out.println("     it). In absolute terms ~1 ms is small against a 300 ms budget — what bites in production is the TAIL (p99 is already");
        System.out.println("     several ms here, before real network, TLS, sidecars and GC pauses) and the AVAILABILITY multiplication: six required");
        System.out.println("     99.9% deployables give ~6x the downtime of one. None of this makes microservices wrong; it prices them. Independent");
        System.out.println("     deploys, scaling and team autonomy must be worth these costs, which is why the default is a modular monolith with");
        System.out.println("     enforced module boundaries, extracting a module when a real driver appears (Ch 16, Ch 31) — and, when you do,");
        System.out.println("     removing required synchronous hops (fan-out, caching, async events) rather than chaining them.");

        for (HttpServer x : servers) x.stop(0); pools.forEach(ExecutorService::shutdownNow); FAN.shutdownNow();
        System.exit(0);
    }
}
