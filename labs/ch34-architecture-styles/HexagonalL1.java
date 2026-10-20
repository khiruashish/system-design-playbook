// HexagonalL1.java — run with: java -Dsun.net.httpserver.nodelay=true HexagonalL1.java   (JDK 21, no dependencies)
// HEXAGONAL ARCHITECTURE (ports & adapters). The domain defines PORTS — interfaces for what it needs (store orders, take
// payment) — and knows nothing about HTTP, files or SQL. ADAPTERS implement those ports for a specific technology. We wire
// the SAME checkout domain two ways and run the SAME 2,000 checkouts through each:
//   TEST wiring  — in-memory repository + fake payment adapter
//   REAL wiring  — append-only FILE repository + HTTP payment adapter calling a real local HTTP payment server
// Then we check (by reflection, ArchUnit-style) that no domain type mentions an infrastructure type.
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class HexagonalL1 {
    // ======================= DOMAIN (no infrastructure imports used here) =======================
    record OrderId(String value) {}
    record Money(long paise) {}
    enum Status { PAID, PAYMENT_DECLINED }
    record Order(OrderId id, String customer, Money total, Status status) {}
    // PORTS — owned by the domain
    interface OrderRepository { void save(Order o); Optional<Order> byId(OrderId id); long count(); }
    interface PaymentPort { boolean charge(String customer, Money amount); }
    // the use case — pure domain logic
    static final class CheckoutService {
        private final OrderRepository orders; private final PaymentPort payments;
        CheckoutService(OrderRepository orders, PaymentPort payments) { this.orders = orders; this.payments = payments; }
        Order checkout(OrderId id, String customer, Money total) {
            if (total.paise() <= 0) throw new IllegalArgumentException("empty order");
            Order o = new Order(id, customer, total, payments.charge(customer, total) ? Status.PAID : Status.PAYMENT_DECLINED);
            orders.save(o);
            return o;
        }
    }
    // a "shortcut" someone might add: the domain talks to HTTP directly — the check below must catch it
    static final class LeakyCheckoutService {
        private final HttpClient gateway;
        LeakyCheckoutService(OrderRepository orders, HttpClient gateway) { this.gateway = gateway; }
    }
    static final List<Class<?>> DOMAIN = List.of(OrderId.class, Money.class, Status.class, Order.class,
            OrderRepository.class, PaymentPort.class, CheckoutService.class);

    // ======================= ADAPTERS =======================
    static final class InMemoryOrders implements OrderRepository {
        private final Map<String, Order> m = new ConcurrentHashMap<>();
        public void save(Order o) { m.put(o.id().value(), o); }
        public Optional<Order> byId(OrderId id) { return Optional.ofNullable(m.get(id.value())); }
        public long count() { return m.size(); }
    }
    static final class FakePayments implements PaymentPort {       // declines amounts ending in 13 paise — test rule
        public boolean charge(String c, Money a) { return a.paise() % 100 != 13; }
    }
    static final class FileOrders implements OrderRepository {      // append-only log file, indexed in memory
        private final Path file; private final Map<String, Order> index = new HashMap<>();
        FileOrders(Path file) { this.file = file; }
        public synchronized void save(Order o) {
            try { Files.writeString(file, o.id().value() + "|" + o.customer() + "|" + o.total().paise() + "|" + o.status() + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (IOException e) { throw new UncheckedIOException(e); }
            index.put(o.id().value(), o);
        }
        public synchronized Optional<Order> byId(OrderId id) { return Optional.ofNullable(index.get(id.value())); }
        public synchronized long count() {
            try { return Files.readAllLines(file).size(); } catch (IOException e) { throw new UncheckedIOException(e); }
        }
    }
    static final class HttpPayments implements PaymentPort {        // talks to a real HTTP "payment gateway"
        private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(); private final URI base;
        HttpPayments(URI base) { this.base = base; }
        public boolean charge(String c, Money a) {
            try {
                var req = HttpRequest.newBuilder(base.resolve("/charge?customer=" + c + "&paise=" + a.paise())).POST(HttpRequest.BodyPublishers.noBody()).build();
                return http.send(req, HttpResponse.BodyHandlers.ofString()).body().equals("APPROVED");
            } catch (Exception e) { throw new RuntimeException("payment gateway unreachable", e); }
        }
    }

    // ======================= the "real" payment gateway (a separate process in reality) =======================
    static ExecutorService gatewayPool;
    static HttpServer startGateway() throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/charge", ex -> {
            String q = ex.getRequestURI().getQuery(); long paise = Long.parseLong(q.replaceAll(".*paise=(\\d+).*", "$1"));
            byte[] body = (paise % 100 != 13 ? "APPROVED" : "DECLINED").getBytes();
            ex.sendResponseHeaders(200, body.length); ex.getResponseBody().write(body); ex.close();
        });
        gatewayPool = Executors.newFixedThreadPool(4); s.setExecutor(gatewayPool); s.start(); return s;
    }

    static long[] runSuite(CheckoutService svc, OrderRepository repo, int n) {
        long paid = 0, declined = 0, t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            Order o = svc.checkout(new OrderId("o" + i), "c" + (i % 50), new Money(1000 + i * 7L));
            if (o.status() == Status.PAID) paid++; else declined++;
        }
        return new long[]{paid, declined, repo.count(), (System.nanoTime() - t0) / 1_000_000};
    }

    // ArchUnit-lite: every field, method parameter/return and interface of domain types must avoid infrastructure packages
    static List<String> infraLeaks() { return infraLeaks(DOMAIN); }
    static List<String> infraLeaks(List<Class<?>> types) {
        List<String> banned = List.of("java.net.", "java.io.", "java.nio.", "java.sql.", "com.sun.net.");
        List<String> leaks = new ArrayList<>();
        for (Class<?> c : types) {
            List<Class<?>> used = new ArrayList<>();
            for (Field f : c.getDeclaredFields()) used.add(f.getType());
            for (Method m : c.getDeclaredMethods()) { used.add(m.getReturnType()); used.addAll(List.of(m.getParameterTypes())); used.addAll(List.of(m.getExceptionTypes())); }
            for (Constructor<?> k : c.getDeclaredConstructors()) used.addAll(List.of(k.getParameterTypes()));
            used.addAll(List.of(c.getInterfaces()));
            for (Class<?> u : used) for (String b : banned) if (u.getName().startsWith(b)) leaks.add(c.getSimpleName() + " uses " + u.getName());
        }
        return leaks;
    }

    public static void main(String[] args) throws Exception {
        int N = Integer.getInteger("n", 2000);
        System.out.println("Hexagonal architecture: one checkout domain, two sets of adapters, the same " + String.format("%,d", N) + " checkouts.\n");

        InMemoryOrders mem = new InMemoryOrders();
        long[] t = runSuite(new CheckoutService(mem, new FakePayments()), mem, N);

        HttpServer gw = startGateway();
        Path log = Files.createTempFile("orders", ".log");
        FileOrders file = new FileOrders(log);
        long[] r = runSuite(new CheckoutService(file, new HttpPayments(URI.create("http://127.0.0.1:" + gw.getAddress().getPort()))), file, N);
        gw.stop(0); gatewayPool.shutdownNow();

        System.out.printf("   %-52s %8s %9s %12s %10s%n", "wiring", "paid", "declined", "orders saved", "time");
        System.out.printf("   %-52s %,8d %,9d %,12d %,7d ms%n", "TEST: in-memory repository + fake payment adapter", t[0], t[1], t[2], t[3]);
        System.out.printf("   %-52s %,8d %,9d %,12d %,7d ms%n", "REAL: file repository + HTTP payment adapter", r[0], r[1], r[2], r[3]);
        System.out.printf("   → identical business outcomes: %s;  real wiring is %.0fx slower%n%n",
                (t[0] == r[0] && t[1] == r[1] && t[2] == r[2]) ? "YES" : "NO", (double) r[3] / Math.max(1, t[3]));

        List<String> leaks = infraLeaks();
        System.out.println("   Dependency rule check over " + DOMAIN.size() + " domain types (no java.net / java.io / java.nio / java.sql):");
        System.out.println(leaks.isEmpty() ? "        ✓ domain references no infrastructure type" : "        ✗ " + String.join("\n        ✗ ", leaks));
        List<String> leaky = infraLeaks(List.of(LeakyCheckoutService.class));
        System.out.println("   Same check against a LeakyCheckoutService that takes an HttpClient (the shortcut someone will try):");
        System.out.println("        ✗ " + String.join("\n        ✗ ", new LinkedHashSet<>(leaky)));
        Files.deleteIfExists(log);

        System.out.println("\n   → the domain produced the same paid/declined split and the same number of saved orders with both wirings, because");
        System.out.println("     it only knows its ports. That buys two things: the business rules are tested at in-memory speed (the fast suite");
        System.out.println("     above) with a few slower contract tests per adapter, and swapping the file store for PostgreSQL or Razorpay for");
        System.out.println("     another gateway is a new adapter, not a domain change. The reflection check is the fitness function that keeps it");
        System.out.println("     that way: the day someone passes an HttpClient into CheckoutService, the build fails.");
        System.exit(0);   // HttpClient keeps non-daemon selector threads alive
    }
}
