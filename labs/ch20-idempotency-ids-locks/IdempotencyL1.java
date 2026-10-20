// IdempotencyL1.java — run with: java -Duser.timezone=UTC -Dsun.net.httpserver.nodelay=true -cp /tmp/pgjdbc.jar IdempotencyL1.java
// A real HTTP "charge" API (JDK HttpServer, virtual threads) over real PostgreSQL (:5542, db shop).
// The client retries when a response is lost (30 % of responses arrive after the client's 250 ms timeout),
// and 10 % of customers double-click (two concurrent requests for the same payment).
// Three server strategies: naive, check-then-act, and an atomic idempotency record (unique key + effect in one transaction).
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class IdempotencyL1 {
    static final String URL = "jdbc:postgresql://localhost:5542/shop?user=postgres";
    static final BlockingQueue<Connection> POOL = new ArrayBlockingQueue<>(40);
    static final AtomicInteger mismatches = new AtomicInteger();

    // ---------- server ----------
    interface Strategy { int handle(String key, String orderId, long amount, StringBuilder body) throws Exception; }

    // 1. naive: every request is a new charge
    static final Strategy NAIVE = (key, orderId, amount, body) -> withConn(c -> {
        try (var ps = c.prepareStatement("insert into charge(order_id, amount) values (?, ?) returning id")) {
            ps.setString(1, orderId); ps.setLong(2, amount);
            var rs = ps.executeQuery(); rs.next(); body.append("charge ").append(rs.getLong(1)); return 201;
        }
    });

    // 2. check-then-act: look for the key, charge, then remember the key (no unique constraint, separate statements)
    static final Strategy CHECK_THEN_ACT = (key, orderId, amount, body) -> withConn(c -> {
        try (var q = c.prepareStatement("select response from idem_loose where key = ?")) {
            q.setString(1, key); var rs = q.executeQuery();
            if (rs.next()) { body.append(rs.getString(1)); return 200; }
        }
        long id;
        try (var ps = c.prepareStatement("insert into charge(order_id, amount) values (?, ?) returning id")) {
            ps.setString(1, orderId); ps.setLong(2, amount); var rs = ps.executeQuery(); rs.next(); id = rs.getLong(1);
        }
        try (var ps = c.prepareStatement("insert into idem_loose(key, response) values (?, ?)")) {
            ps.setString(1, key); ps.setString(2, "charge " + id); ps.executeUpdate();
        }
        body.append("charge ").append(id); return 201;
    });

    // 3. atomic idempotency record: claim the key and create the charge in ONE transaction.
    //    A concurrent duplicate blocks on the unique index until the first commits, then reads the stored response.
    static final Strategy ATOMIC = (key, orderId, amount, body) -> withConn(c -> {
        String fp = fingerprint(orderId, amount);
        c.setAutoCommit(false);
        try {
            int claimed;
            try (var ps = c.prepareStatement("insert into idem(key, fingerprint) values (?, ?) on conflict (key) do nothing")) {
                ps.setString(1, key); ps.setString(2, fp); claimed = ps.executeUpdate();
            }
            if (claimed == 0) {                                                    // seen before: replay or reject
                try (var q = c.prepareStatement("select fingerprint, status_code, response from idem where key = ?")) {
                    q.setString(1, key); var rs = q.executeQuery(); rs.next();
                    c.commit();
                    if (!rs.getString(1).equals(fp)) { mismatches.incrementAndGet(); body.append("key reused with a different request"); return 422; }
                    body.append(rs.getString(3)); return rs.getInt(2);             // same status and body as the first time
                }
            }
            long id;
            try (var ps = c.prepareStatement("insert into charge(order_id, amount) values (?, ?) returning id")) {
                ps.setString(1, orderId); ps.setLong(2, amount); var rs = ps.executeQuery(); rs.next(); id = rs.getLong(1);
            }
            try (var ps = c.prepareStatement("update idem set status_code = 201, response = ? where key = ?")) {
                ps.setString(1, "charge " + id); ps.setString(2, key); ps.executeUpdate();
            }
            c.commit();
            body.append("charge ").append(id); return 201;
        } catch (Exception e) { c.rollback(); throw e; }
        finally { c.setAutoCommit(true); }
    });

    interface SqlWork<T> { T run(Connection c) throws Exception; }
    static <T> T withConn(SqlWork<T> w) throws Exception {
        Connection c = POOL.take();
        try { return w.run(c); } finally { POOL.put(c); }
    }

    static String fingerprint(String orderId, long amount) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest((orderId + "|" + amount).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(h, 0, 8);
    }

    static HttpServer server(Map<String, Strategy> strategies) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 19401), 1000);
        for (var e : strategies.entrySet()) {
            Strategy st = e.getValue();
            s.createContext("/" + e.getKey() + "/charges", ex -> {
                try {
                    var p = new String(ex.getRequestBody().readAllBytes()).split(",");
                    String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
                    var body = new StringBuilder();
                    int code = st.handle(key, p[0], Long.parseLong(p[1]), body);
                    if (ThreadLocalRandom.current().nextDouble() < 0.30) Thread.sleep(400);   // response "lost": arrives after the client gave up
                    byte[] b = body.toString().getBytes();
                    ex.sendResponseHeaders(code, b.length); ex.getResponseBody().write(b);
                } catch (Exception err) {
                    ex.sendResponseHeaders(500, -1);
                } finally { ex.close(); }
            });
        }
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); s.start(); return s;
    }

    // ---------- client ----------
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .executor(Executors.newVirtualThreadPerTaskExecutor()).build();

    /** Pays for one order: up to 4 attempts with the SAME idempotency key; returns the final status code (or -1). */
    static int pay(String strategy, String key, String orderId, long amount) throws Exception {
        for (int attempt = 1; attempt <= 4; attempt++) {
            var req = HttpRequest.newBuilder(URI.create("http://localhost:19401/" + strategy + "/charges"))
                    .timeout(Duration.ofMillis(250)).header("Idempotency-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(orderId + "," + amount)).build();
            try {
                var resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() < 500) return resp.statusCode();
            } catch (HttpTimeoutException e) { /* lost response: retry */ }
            Thread.sleep(20L * attempt);
        }
        return -1;
    }

    static void reset() throws Exception {
        withConn(c -> {
            try (var st = c.createStatement()) {
                st.execute("drop table if exists charge, idem, idem_loose");
                st.execute("create table charge(id bigserial primary key, order_id text not null, amount bigint not null, created_at timestamptz default now())");
                st.execute("create table idem(key text primary key, fingerprint text not null, status_code int, response text, created_at timestamptz default now())");
                st.execute("create table idem_loose(key text, response text)");
            }
            return null;
        });
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 40; i++) POOL.add(DriverManager.getConnection(URL));
        HttpServer srv = server(Map.of("naive", NAIVE, "check", CHECK_THEN_ACT, "atomic", ATOMIC));
        int orders = 1_000;
        System.out.println("1,000 orders paid over real HTTP + PostgreSQL; 30 % of responses lost (client retries, same key); 10 % double-clicked");
        System.out.printf("   %-22s %8s %9s %14s %13s %10s%n", "strategy", "charges", "extra", "orders charged", "client saw", "gave up");
        System.out.printf("   %-22s %8s %9s %14s %13s %10s%n", "", "created", "charges", "more than once", "success", "");
        for (String strategy : List.of("naive", "check", "atomic")) {
            reset();
            AtomicInteger ok = new AtomicInteger(), gaveUp = new AtomicInteger();
            try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
                Semaphore inFlight = new Semaphore(100);
                for (int i = 0; i < orders; i++) {
                    String orderId = "ord-" + i, key = UUID.randomUUID().toString();
                    int clicks = (i % 10 == 0) ? 2 : 1;                           // double-click: same key, concurrently
                    for (int k = 0; k < clicks; k++) {
                        inFlight.acquire();
                        exec.submit(() -> {
                            try { int code = pay(strategy, key, orderId, 49_900); if (code == 200 || code == 201) ok.incrementAndGet(); else if (code == -1) gaveUp.incrementAndGet(); }
                            catch (Exception e) { gaveUp.incrementAndGet(); }
                            finally { inFlight.release(); }
                            return null;
                        });
                    }
                }
            }
            Thread.sleep(500);                                                        // let "lost" responses finish
            long[] r = withConn(c -> {
                try (var st = c.createStatement()) {
                    var rs = st.executeQuery("select count(*), count(distinct order_id), (select count(*) from (select order_id from charge group by order_id having count(*) > 1) d) from charge");
                    rs.next(); return new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)};
                }
            });
            String label = switch (strategy) { case "naive" -> "naive"; case "check" -> "check-then-act"; default -> "atomic idempotency key"; };
            System.out.printf("   %-22s %,8d %,9d %14d %,13d %10d%n", label, r[0], r[0] - r[1], r[2], ok.get(), gaveUp.get());
        }

        // Same key, different request: must be rejected, not replayed
        reset();
        String key = UUID.randomUUID().toString();
        int first = pay("atomic", key, "ord-x", 49_900), second = pay("atomic", key, "ord-x", 99_900);
        System.out.printf("key reuse with a different amount: first → %d, second → %d (%d mismatch)%n", first, second, mismatches.get());
        srv.stop(0);
        for (var c : POOL) c.close();
    }
}
