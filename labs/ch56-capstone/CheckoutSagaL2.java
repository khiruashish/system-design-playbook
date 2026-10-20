// CheckoutSagaL2.java — run with (PostgreSQL on :5581, db shop):
//   java -Dsun.net.httpserver.nodelay=true -Duser.timezone=UTC -cp /tmp/eslab/postgresql-42.7.4.jar CheckoutSagaL2.java
// THE WHOLE CHECKOUT, END TO END, WITH THINGS GOING WRONG. Three REAL services over HTTP, each with its own tables in
// a REAL PostgreSQL: inventory (reserve / release, conditional stock update), payment (a gateway stub: 5% of charges
// are declined; 8% succeed but the response is lost — the client times out after 1 s), and order (create order).
// 2,000 checkouts from 20 concurrent customers for 40 SKUs with limited stock (sell-outs happen). The checkout
// orchestrator also CRASHES in 3% of checkouts right after the payment call (a pod dies mid-saga). Three designs:
//   S1 naive: reserve → charge → create order; on a payment timeout, retry the charge once (new request); if it still
//      fails, release stock and tell the customer "payment failed"; a crashed checkout is simply gone
//   S2 saga done right: every step idempotent on the order id; a payment timeout is resolved by ASKING the gateway
//      for the charge status (never by guessing); a saga log records each step; decline → release stock
//   S3 S2 + a recovery sweeper that finishes or compensates sagas abandoned by crashed pods
// Invariants checked in the databases afterwards: customers charged with no order, orders charged twice, orders
// with no successful charge, oversold stock, and stock left reserved for orders that don't exist.
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class CheckoutSagaL2 {
    static final String URL = "jdbc:postgresql://localhost:5581/shop?user=claude";
    static final ArrayBlockingQueue<Connection> pool = new ArrayBlockingQueue<>(40);
    static final Random rnd = new Random(56);
    static int design;

    interface Sql<T> { T run(Connection c) throws SQLException; }
    static <T> T db(Sql<T> f) throws Exception { Connection c = pool.take(); try { return f.run(c); } finally { pool.put(c); } }   // never close pooled connections

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 40; i++) pool.add(DriverManager.getConnection(URL));
        int port = startServices();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).executor(Executors.newFixedThreadPool(32)).build();
        System.out.println("3 real HTTP services on real PostgreSQL; 2,000 checkouts, 20 concurrent, 40 SKUs with limited stock;");
        System.out.println("payment: 5% declined, 8% succeed but the reply is lost (1 s timeout); orchestrator crashes in 3% of checkouts.\n");
        System.out.printf("   %-46s %8s %10s %10s %10s %10s %9s %10s%n", "design", "orders", "charged,", "charged", "order w/o", "oversold", "leaked", "told");
        System.out.printf("   %-46s %8s %10s %10s %10s %10s %9s %10s%n", "", "placed", "NO order", "twice", "charge", "units", "reserv.", "'failed'");
        String[] names = {"S1) naive: retry charge, release on failure", "S2) idempotent steps + status check + saga log", "S3) S2 + recovery sweeper for crashed sagas"};
        for (design = 1; design <= 3; design++) {
            reset();
            AtomicInteger toldFailed = new AtomicInteger(); ExecutorService customers = Executors.newFixedThreadPool(20); List<Future<?>> fs = new ArrayList<>();
            Random plan = new Random(5600 + 0);
            for (int i = 0; i < 2000; i++) { final String oid = "o" + i; final int sku = plan.nextInt(40), qty = 1 + plan.nextInt(3); final boolean crash = plan.nextDouble() < 0.03;
                fs.add(customers.submit(() -> { if (!checkout(http, port, oid, sku, qty, crash)) toldFailed.incrementAndGet(); return null; })); }
            for (Future<?> f : fs) f.get(); customers.shutdown();
            Thread.sleep(2500);                                                                  // let lost-reply charges land
            if (design == 3) sweep(http, port);
            System.out.printf("   %-46s %8d %10d %10d %10d %10d %9d %10d%n", names[design - 1], q("SELECT count(*) FROM orders"),
                    q("SELECT count(DISTINCT split_part(p.order_id,'#',1)) FROM payment p WHERE p.status='CAPTURED' AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.order_id = split_part(p.order_id,'#',1))"),
                    q("SELECT count(*) FROM (SELECT split_part(order_id,'#',1) FROM payment WHERE status='CAPTURED' GROUP BY 1 HAVING count(*) > 1) x"),
                    q("SELECT count(*) FROM orders o WHERE NOT EXISTS (SELECT 1 FROM payment p WHERE split_part(p.order_id,'#',1) = o.order_id AND p.status='CAPTURED')"),
                    q("SELECT coalesce(-sum(least(qty,0)),0) FROM stock"),
                    q("SELECT count(*) FROM reservation r WHERE r.status='RESERVED' AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.order_id = r.order_id)"),
                    toldFailed.get());
        }
        System.out.println("\n   → a timeout is not a failure — it is 'unknown'. The naive design turns every lost reply into a second charge");
        System.out.println("     or a refund-shaped support ticket, and every crashed pod into money taken with no order and stock locked");
        System.out.println("     forever. Idempotency keys make retries safe; asking the gateway for the charge status resolves 'unknown'");
        System.out.println("     into a fact; a saga log records how far each checkout got. But a log nobody reads doesn't finish anything:");
        System.out.println("     the sweeper that resumes or compensates abandoned sagas is what brings every invariant to zero. The");
        System.out.println("     conditional stock update keeps overselling at zero in every design — the database invariant does that job.");
        System.out.println("     (Some customers the crashed pods told 'failed' get an order from the sweeper — they must be notified.)");
        System.exit(0);
    }

    static long q(String sql) throws Exception { return db(c -> { try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) { rs.next(); return rs.getLong(1); } }); }

    static void reset() throws Exception {
        db(c -> { try (Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS stock, reservation, payment, orders, saga");
            s.execute("CREATE TABLE stock (sku int PRIMARY KEY, qty int NOT NULL)");
            s.execute("CREATE TABLE reservation (order_id text PRIMARY KEY, sku int, qty int, status text)");
            s.execute("CREATE TABLE payment (order_id text PRIMARY KEY, amount bigint, status text)");
            s.execute("CREATE TABLE orders (order_id text PRIMARY KEY, sku int, qty int)");
            s.execute("CREATE TABLE saga (order_id text PRIMARY KEY, step text, sku int, qty int, updated timestamptz DEFAULT now())");
            s.execute("INSERT INTO stock SELECT g, 60 + (g % 5) * 20 FROM generate_series(0,39) g"); } return null; });
    }

    // ---------------- the orchestrator ----------------
    static boolean checkout(HttpClient http, int port, String oid, int sku, int qty, boolean crash) throws Exception {
        String base = "http://127.0.0.1:" + port;
        if (design >= 2) db(c -> exec(c, "INSERT INTO saga (order_id, step, sku, qty) VALUES ('" + oid + "','STARTED'," + sku + "," + qty + ") ON CONFLICT DO NOTHING"));
        if (call(http, base + "/inventory/reserve?order=" + oid + "&sku=" + sku + "&qty=" + qty) != 200) { step(oid, "SOLD_OUT"); return false; }
        step(oid, "RESERVED");
        int pay;
        if (design == 1) {
            pay = call(http, base + "/payment/charge?order=" + oid + "%231&amount=" + qty * 5000);
            if (pay == -1) pay = call(http, base + "/payment/charge?order=" + oid + "%232&amount=" + qty * 5000);   // retry = a NEW request
        } else {
            pay = call(http, base + "/payment/charge?order=" + oid + "&amount=" + qty * 5000);
            if (pay == -1) for (int i = 0; i < 10 && pay != 200 && pay != 402; i++) { Thread.sleep(300); pay = call(http, base + "/payment/status?order=" + oid); }   // resolve 'unknown'
        }
        if (crash) return false;                                                                  // the pod dies here; the customer sees an error
        if (pay == 200) { step(oid, "CHARGED"); call(http, base + "/order/create?order=" + oid + "&sku=" + sku + "&qty=" + qty); call(http, base + "/inventory/consume?order=" + oid); step(oid, "DONE"); return true; }
        call(http, base + "/inventory/release?order=" + oid); step(oid, "COMPENSATED"); return false;
    }
    static void step(String oid, String s) throws Exception { if (design >= 2) db(c -> exec(c, "UPDATE saga SET step='" + s + "', updated=now() WHERE order_id='" + oid + "'")); }
    static int call(HttpClient http, String url) {
        try { return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(1)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode(); }
        catch (Exception e) { return -1; }                                                       // timeout / connection error = UNKNOWN
    }
    static void sweep(HttpClient http, int port) throws Exception {
        String base = "http://127.0.0.1:" + port;
        List<String[]> stuck = db(c -> { List<String[]> l = new ArrayList<>(); try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT order_id, sku, qty FROM saga WHERE step IN ('STARTED','RESERVED','CHARGED')")) {
            while (rs.next()) l.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}); } return l; });
        for (String[] s : stuck) {
            int st = call(http, base + "/payment/status?order=" + s[0]);
            if (st == 200) { call(http, base + "/order/create?order=" + s[0] + "&sku=" + s[1] + "&qty=" + s[2]); call(http, base + "/inventory/consume?order=" + s[0]); step(s[0], "DONE"); }
            else { call(http, base + "/inventory/release?order=" + s[0]); step(s[0], "COMPENSATED"); }
        }
    }
    static Object exec(Connection c, String sql) throws SQLException { try (Statement s = c.createStatement()) { s.execute(sql); } return null; }

    // ---------------- the services ----------------
    static int startServices() throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 256); srv.setExecutor(Executors.newFixedThreadPool(32));
        srv.createContext("/inventory/reserve", ex -> respond(ex, () -> { var p = params(ex); String oid = p.get("order"); int sku = Integer.parseInt(p.get("sku")), qty = Integer.parseInt(p.get("qty"));
            return db(c -> { c.setAutoCommit(false); try (Statement s = c.createStatement()) {
                int ins = s.executeUpdate("INSERT INTO reservation VALUES ('" + oid + "'," + sku + "," + qty + ",'RESERVED') ON CONFLICT DO NOTHING");
                if (ins == 0) { c.commit(); return 200; }                                          // idempotent: already reserved
                int ok = s.executeUpdate("UPDATE stock SET qty = qty - " + qty + " WHERE sku = " + sku + " AND qty >= " + qty);
                if (ok == 0) { c.rollback(); return 409; } c.commit(); return 200; } finally { c.setAutoCommit(true); } }); }));
        srv.createContext("/inventory/release", ex -> respond(ex, () -> { String oid = params(ex).get("order");
            return db(c -> { c.setAutoCommit(false); try (Statement s = c.createStatement()) {
                try (ResultSet rs = s.executeQuery("UPDATE reservation SET status='RELEASED' WHERE order_id='" + oid + "' AND status='RESERVED' RETURNING sku, qty")) {
                    if (rs.next()) { int sku = rs.getInt(1), qty = rs.getInt(2); s.executeUpdate("UPDATE stock SET qty = qty + " + qty + " WHERE sku = " + sku); } }
                c.commit(); return 200; } finally { c.setAutoCommit(true); } }); }));
        srv.createContext("/inventory/consume", ex -> respond(ex, () -> { String oid = params(ex).get("order"); return db(c -> { exec(c, "UPDATE reservation SET status='CONSUMED' WHERE order_id='" + oid + "' AND status='RESERVED'"); return 200; }); }));
        srv.createContext("/payment/charge", ex -> respond(ex, () -> { var p = params(ex); String oid = p.get("order"); long amt = Long.parseLong(p.get("amount"));
            double x; synchronized (rnd) { x = rnd.nextDouble(); }
            String existing = db(c -> { try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT status FROM payment WHERE order_id='" + oid + "'")) { return rs.next() ? rs.getString(1) : null; } });
            if (existing != null) return existing.equals("CAPTURED") ? 200 : 402;                   // gateway is idempotent per key
            String status = x < 0.05 ? "DECLINED" : "CAPTURED";
            db(c -> exec(c, "INSERT INTO payment VALUES ('" + oid + "'," + amt + ",'" + status + "') ON CONFLICT DO NOTHING"));
            if (status.equals("CAPTURED") && x < 0.13) Thread.sleep(2000);                         // captured, but the reply is lost to the client's 1 s timeout
            return status.equals("CAPTURED") ? 200 : 402; }));
        srv.createContext("/payment/status", ex -> respond(ex, () -> { String oid = params(ex).get("order");
            return db(c -> { try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT status FROM payment WHERE order_id='" + oid + "'")) { return !rs.next() ? 404 : rs.getString(1).equals("CAPTURED") ? 200 : 402; } }); }));
        srv.createContext("/order/create", ex -> respond(ex, () -> { var p = params(ex);
            return db(c -> { exec(c, "INSERT INTO orders VALUES ('" + p.get("order") + "'," + p.get("sku") + "," + p.get("qty") + ") ON CONFLICT DO NOTHING"); return 200; }); }));
        srv.start(); return srv.getAddress().getPort();
    }
    interface Handler { int handle() throws Exception; }
    static void respond(HttpExchange ex, Handler h) { int code; try { code = h.handle(); } catch (Exception e) { code = 500; } try { ex.sendResponseHeaders(code, -1); } catch (Exception e) { } ex.close(); }
    static Map<String, String> params(HttpExchange ex) { Map<String, String> m = new HashMap<>(); String q = ex.getRequestURI().getQuery(); if (q != null) for (String kv : q.split("&")) { String[] a = kv.split("=", 2); m.put(a[0], a.length > 1 ? a[1] : ""); } return m; }
}
