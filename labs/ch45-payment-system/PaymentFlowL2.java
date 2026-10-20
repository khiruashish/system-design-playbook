// PaymentFlowL2.java — run with (PostgreSQL 16 on :5581, db "shop"):
//   java -cp postgresql-42.7.4.jar -Duser.timezone=UTC -Dsun.net.httpserver.nodelay=true PaymentFlowL2.java
// A PAYMENT FLOW WITH A REAL (FAKE) PAYMENT GATEWAY. Two real HTTP servers on localhost:
//   PSP        a Razorpay-style gateway: POST /v1/orders creates an order; GET /v1/orders/{id} returns its true status;
//              it sends signed WEBHOOKS (payment.captured / payment.failed) to the merchant — at least once (10% sent
//              twice), sometimes out of order (a failed first attempt's event arriving after the successful retry's),
//              and sometimes never (5% dropped)
//   MERCHANT   our payment service: PostgreSQL tables for payments and fulfilments, a webhook endpoint, and an endpoint
//              where the customer's app reports "payment success" after the checkout screen (2% of those are tampered)
// 2,000 checkouts: 85% pay (some after a failed first attempt), 10% fail, 5% abandon. Three merchant designs:
//   A) trust the app's success callback; apply every webhook as it comes
//   B) app callback is only a UI hint; guarded state machine (CREATED → CAPTURED | FAILED, FAILED → CAPTURED allowed,
//      CAPTURED terminal); webhook events deduplicated by event id; fulfil only on the transition INTO CAPTURED
//   C) B + a reconciliation poller: payments not yet CAPTURED (CREATED or FAILED — a retry can still succeed) after 2 s
//      are checked against the PSP's order status API, within the order's 30-minute payment window
// Afterwards we compare every payment with the PSP's truth.
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class PaymentFlowL2 {
    static final String DB = "jdbc:postgresql://127.0.0.1:5581/shop?user=claude";
    static final Map<String, String> pspTruth = new ConcurrentHashMap<>();          // psp order id -> captured | failed | created
    static final Map<String, String> pspToOurs = new ConcurrentHashMap<>();
    static HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).executor(Executors.newVirtualThreadPerTaskExecutor()).build();
    static int design; static BlockingQueue<Connection> pool = new LinkedBlockingQueue<>();

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 12; i++) pool.add(DriverManager.getConnection(DB));
        HttpServer psp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 512); psp.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        AtomicLong pspSeq = new AtomicLong(1000);
        psp.createContext("/v1/orders", ex -> {
            String body;
            if (ex.getRequestMethod().equals("POST")) { String id = "order_" + pspSeq.incrementAndGet(); pspTruth.put(id, "created"); body = id; }
            else { String id = ex.getRequestURI().getPath().substring("/v1/orders/".length()); body = pspTruth.getOrDefault(id, "unknown"); }
            byte[] b = body.getBytes(); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        psp.start();
        HttpServer merchant = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1024); merchant.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        merchant.createContext("/webhooks/psp", ex -> { String[] f = new String(ex.getRequestBody().readAllBytes()).split("\\|"); handleEvent(f[0], f[1], f[2]); ex.sendResponseHeaders(200, -1); ex.close(); });
        merchant.createContext("/app/payment-success", ex -> { String ours = new String(ex.getRequestBody().readAllBytes()); if (design == 0) appSuccess(ours); ex.sendResponseHeaders(200, -1); ex.close(); });
        merchant.start();
        String pspUrl = "http://127.0.0.1:" + psp.getAddress().getPort(), hook = "http://127.0.0.1:" + merchant.getAddress().getPort();

        System.out.println("2,000 checkouts through a fake Razorpay-style PSP over real HTTP; merchant state in PostgreSQL.");
        System.out.println("Webhooks: 10% duplicated, some out of order, 5% never delivered; 2% of app 'success' calls are tampered.\n");
        System.out.printf("   %-52s %9s %10s %13s %10s %10s %13s%n", "merchant design", "captured", "fulfilled", "charged, NOT", "fulfilled", "fulfilled", "paid, status");
        System.out.printf("   %-52s %9s %10s %13s %10s %10s %13s%n", "", "at PSP", "correctly", "fulfilled", "twice", "unpaid", "says FAILED");
        String[] names = {"A) trust app callback, apply every webhook", "B) guarded state machine + event dedupe", "C) B + reconciliation poller (CREATED/FAILED, 2 s)"};
        for (design = 0; design < 3; design++) {
            reset(); pspTruth.clear(); pspToOurs.clear();
            Random r = new Random(45); List<Thread> ts = new ArrayList<>();
            ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
            if (design == 2) poller.scheduleAtFixedRate(() -> reconcile(pspUrl), 500, 500, TimeUnit.MILLISECONDS);
            try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 2_000; i++) {
                    String ours = "pay_" + i; long seed = r.nextLong();
                    ex.submit(() -> { checkout(ours, pspUrl, hook, new Random(seed)); return null; });
                }
            }
            Thread.sleep(design == 2 ? 3_500 : 1_500);                               // let late webhooks and the poller finish
            poller.shutdownNow();
            report(names[design]);
        }
        System.out.println("\n   → the app's 'success' is a claim from an untrusted device, and webhooks are at-least-once, unordered and");
        System.out.println("     occasionally missing. Applying them naively fulfils orders twice, cancels paid orders when a stale failure");
        System.out.println("     arrives last, and ships to customers who never paid. A guarded state machine (transitions checked in the");
        System.out.println("     UPDATE itself, events deduplicated, fulfilment only on entering CAPTURED) removes duplicates and wrong turns —");
        System.out.println("     but a dropped webhook still leaves a paid customer waiting forever. Only asking the PSP closes that gap: a");
        System.out.println("     reconciliation poller for anything not yet captured — including FAILED, because a retry on the same order can");
        System.out.println("     still succeed — and daily settlement reconciliation (L3) as the final backstop.");
        System.exit(0);
    }

    // ---------------- the customer's checkout, as the PSP experiences it ----------------
    static void checkout(String ours, String psp, String hook, Random r) throws Exception {
        String pspOrder = send(HttpRequest.newBuilder(URI.create(psp + "/v1/orders")).POST(HttpRequest.BodyPublishers.ofString(ours)).build());
        pspToOurs.put(pspOrder, ours);
        withConn(c -> { try (PreparedStatement ps = c.prepareStatement("insert into payments values (?,?,'CREATED', now())")) { ps.setString(1, ours); ps.setString(2, pspOrder); ps.executeUpdate(); } });
        double x = r.nextDouble(); List<String[]> events = new ArrayList<>();
        if (x < 0.85) {
            if (r.nextDouble() < 0.2) events.add(new String[]{"evt_" + pspOrder + "_1", pspOrder, "payment.failed"});   // first attempt failed
            events.add(new String[]{"evt_" + pspOrder + "_2", pspOrder, "payment.captured"}); pspTruth.put(pspOrder, "captured");
        } else if (x < 0.95) { events.add(new String[]{"evt_" + pspOrder + "_1", pspOrder, "payment.failed"}); pspTruth.put(pspOrder, "failed"); }
        // else abandoned: no events
        // app callback after the checkout screen: honest on success, occasionally tampered
        boolean appSaysSuccess = pspTruth.get(pspOrder).equals("captured") || (x >= 0.85 && r.nextDouble() < 0.02 / 0.15);
        if (appSaysSuccess) send(HttpRequest.newBuilder(URI.create(hook + "/app/payment-success")).POST(HttpRequest.BodyPublishers.ofString(ours)).build());
        // webhook delivery: shuffled order sometimes, duplicates, drops
        if (events.size() == 2 && r.nextDouble() < 0.3) Collections.swap(events, 0, 1);                   // stale failure arrives last
        for (String[] e : events) {
            if (r.nextDouble() < 0.05) continue;                                                            // dropped
            int copies = r.nextDouble() < 0.10 ? 2 : 1;
            for (int k = 0; k < copies; k++) { Thread.sleep(r.nextInt(30)); send(HttpRequest.newBuilder(URI.create(hook + "/webhooks/psp")).POST(HttpRequest.BodyPublishers.ofString(String.join("|", e))).build()); }
        }
    }
    static String send(HttpRequest req) throws Exception { return http.send(req, HttpResponse.BodyHandlers.ofString()).body(); }

    // ---------------- merchant logic ----------------
    static void appSuccess(String ours) { fulfilIfA(ours, "CAPTURED"); }
    static void handleEvent(String eventId, String pspOrder, String type) {
        String ours = pspToOurs.get(pspOrder); String to = type.equals("payment.captured") ? "CAPTURED" : "FAILED";
        if (design == 0) { fulfilIfA(ours, to); return; }
        withConn(c -> {
            try {
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement("insert into processed_events values (?) on conflict do nothing")) { ps.setString(1, eventId); if (ps.executeUpdate() == 0) { c.rollback(); return; } }
                transition(c, ours, to); c.commit();
            } catch (SQLException e) { c.rollback(); } finally { c.setAutoCommit(true); }
        });
    }
    static void transition(Connection c, String ours, String to) throws SQLException {
        String allowedFrom = to.equals("CAPTURED") ? "('CREATED','FAILED')" : "('CREATED')";                  // CAPTURED is terminal
        try (PreparedStatement ps = c.prepareStatement("update payments set status = ? where id = ? and status in " + allowedFrom)) {
            ps.setString(1, to); ps.setString(2, ours);
            if (ps.executeUpdate() == 1 && to.equals("CAPTURED")) {
                try (PreparedStatement f = c.prepareStatement("insert into fulfilments(payment_id) values (?)")) { f.setString(1, ours); f.executeUpdate(); }
            }
        }
    }
    static void fulfilIfA(String ours, String to) {                                         // design A: last write wins, fulfil on every success
        withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement("update payments set status = ? where id = ?")) { ps.setString(1, to); ps.setString(2, ours); ps.executeUpdate(); }
            if (to.equals("CAPTURED")) try (PreparedStatement f = c.prepareStatement("insert into fulfilments(payment_id) values (?)")) { f.setString(1, ours); f.executeUpdate(); }
        });
    }
    static void reconcile(String psp) {
        withConn(c -> {
            List<String[]> stale = new ArrayList<>();
            try (ResultSet rs = c.createStatement().executeQuery("select id, psp_order from payments where status in ('CREATED','FAILED') and created_at < now() - interval '2 seconds' and created_at > now() - interval '30 minutes'")) { while (rs.next()) stale.add(new String[]{rs.getString(1), rs.getString(2)}); }
            for (String[] p : stale) {
                String truth; try { truth = send(HttpRequest.newBuilder(URI.create(psp + "/v1/orders/" + p[1])).GET().build()); } catch (Exception e) { continue; }
                if (truth.equals("captured") || truth.equals("failed")) {
                    c.setAutoCommit(false); try { transition(c, p[0], truth.equals("captured") ? "CAPTURED" : "FAILED"); c.commit(); } catch (SQLException e) { c.rollback(); } finally { c.setAutoCommit(true); }
                }
            }
        });
    }

    interface SqlWork { void run(Connection c) throws SQLException; }
    static void withConn(SqlWork w) {                                                   // borrow a pooled connection, always return it
        Connection c; try { c = pool.take(); } catch (InterruptedException e) { return; }
        try { w.run(c); } catch (SQLException e) { System.err.println("sql: " + e.getMessage()); } finally { pool.add(c); }
    }
    static void reset() throws SQLException {
        try (Connection c = DriverManager.getConnection(DB); Statement s = c.createStatement()) {
            s.execute("drop table if exists fulfilments"); s.execute("drop table if exists processed_events"); s.execute("drop table if exists payments");
            s.execute("create table payments(id text primary key, psp_order text unique, status text not null, created_at timestamptz not null)");
            s.execute("create table processed_events(event_id text primary key)");
            s.execute("create table fulfilments(id bigserial primary key, payment_id text not null)");
        }
    }
    static void report(String name) throws SQLException {
        long captured = pspTruth.values().stream().filter("captured"::equals).count();
        int ok = 0, chargedNotFulfilled = 0, twice = 0, unpaid = 0, wrongStatus = 0;
        try (Connection c = DriverManager.getConnection(DB)) {
            Map<String, Integer> f = new HashMap<>(); Map<String, String> st = new HashMap<>();
            try (ResultSet rs = c.createStatement().executeQuery("select id, status from payments")) { while (rs.next()) st.put(rs.getString(1), rs.getString(2)); }
            try (ResultSet rs = c.createStatement().executeQuery("select payment_id, count(*) from fulfilments group by payment_id")) { while (rs.next()) f.put(rs.getString(1), rs.getInt(2)); }
            for (var e : pspToOurs.entrySet()) {
                boolean paid = "captured".equals(pspTruth.get(e.getKey())); int n = f.getOrDefault(e.getValue(), 0);
                if (paid && n == 1) ok++; if (paid && n == 0) chargedNotFulfilled++; if (n > 1) twice++; if (!paid && n > 0) unpaid++; if (paid && "FAILED".equals(st.get(e.getValue()))) wrongStatus++;
            }
        }
        System.out.printf("   %-52s %,9d %,10d %,13d %,10d %,10d %,13d%n", name, captured, ok, chargedNotFulfilled, twice, unpaid, wrongStatus);
    }
}
