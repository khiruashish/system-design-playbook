// WaitingRoomL4.java — run with (PostgreSQL 16 on :5581, db "shop"):
//   java -cp postgresql-42.7.4.jar -Duser.timezone=UTC -Dsun.net.httpserver.nodelay=true WaitingRoomL4.java
// THE 8 PM FLASH SALE, END TO END. A real checkout service (JDK HttpServer on virtual threads) with a 10-connection
// PostgreSQL pool: a checkout does ~20 ms of database work (price, promo, address checks), reserves stock (8 buckets)
// and inserts the order in one transaction, then calls the payment service (15 ms, simulated). A repeated request from
// a customer who already bought returns the earlier result. 2,000 boxes; 8,000 customers arrive within 2 seconds. Clients time out after 3 s
// and retry (with jitter) until they get an answer or 25 s pass. Three ways to face the crowd:
//   W1 no admission control: every request waits for a database connection
//   W2 concurrency limit: at most 40 checkouts in flight; the rest get 503 + Retry-After immediately
//   W3 virtual waiting room: customers join a queue and get a ticket; tickets are admitted at 300/s in arrival order;
//      the app polls its position every 1.5–2.5 s and checks out when admitted
// Measured: customers who got a definite answer (bought, or told "sold out"), p50/p99 time to that answer, requests the
// server absorbed, and FAIRNESS — of the 2,000 who bought, how many were among the first 2,000 to arrive.
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class WaitingRoomL4 {
    static final String DB = "jdbc:postgresql://127.0.0.1:5581/shop?user=claude";
    static final int STOCK = 2_000, CUSTOMERS = 8_000, BUCKETS = 8;
    static final BlockingQueue<Connection> pool = new LinkedBlockingQueue<>();
    static final AtomicLong ticketSeq = new AtomicLong(), admittedUpTo = new AtomicLong(), requests = new AtomicLong();
    static final Semaphore inFlight = new Semaphore(40);
    static volatile boolean soldOut; static int design;

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 10; i++) pool.add(DriverManager.getConnection(DB));
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8192); srv.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        srv.createContext("/join", ex -> { requests.incrementAndGet(); byte[] b = String.valueOf(ticketSeq.incrementAndGet()).getBytes(); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
        srv.createContext("/status", ex -> { requests.incrementAndGet(); long t = Long.parseLong(ex.getRequestURI().getQuery().substring(7));
            byte[] b = (soldOut ? "soldout" : t <= admittedUpTo.get() ? "admitted" : "wait " + (t - admittedUpTo.get())).getBytes(); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
        srv.createContext("/checkout", ex -> {
            requests.incrementAndGet(); int code;
            if (design == 1 && !inFlight.tryAcquire()) { ex.getResponseHeaders().add("Retry-After", "1"); ex.sendResponseHeaders(503, -1); ex.close(); return; }
            try { int cust = Integer.parseInt(ex.getRequestURI().getQuery().substring(9)); code = alreadyBought(cust) ? 200 : soldOut ? 409 : checkout(cust); }
            catch (Exception e) { code = 500; } finally { if (design == 1) inFlight.release(); }
            ex.sendResponseHeaders(code, -1); ex.close();
        });
        srv.start();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        System.out.printf("Checkout service (10 DB connections, ~20 ms DB work per checkout), %,d boxes, %,d customers arriving within 2 s;%nclients time out at 3 s and retry.%n%n", STOCK, CUSTOMERS);
        System.out.printf("   %-46s %10s %12s %12s %12s %14s %14s%n", "design", "bought", "told sold", "no answer", "answer p50", "answer p99", "fair (bought");
        System.out.printf("   %-46s %10s %12s %12s %12s %14s %14s%n", "", "", "out", "in 25 s", "", "", "& first 2,000)");
        String[] names = {"W1) no admission control", "W2) 40 in flight, fast 503 + Retry-After", "W3) virtual waiting room, admit 300/s FIFO"};
        for (design = 0; design < 3; design++) run(base, names[design]);
        System.out.println("\n   → without admission control the crowd queues inside the service for database connections: requests time out,");
        System.out.println("     clients retry, and the retries join the same queue — work is done for people who have already given up, and");
        System.out.println("     who gets a box is decided by retry luck. Shedding with a fast 503 protects the database, but the order in which");
        System.out.println("     people are served is still random. A waiting room turns the crowd into an ordered queue in front of the");
        System.out.println("     service: the database sees a steady, safe rate, every customer gets an answer and a position, and the first");
        System.out.println("     to arrive are the first to buy. For a flash sale, fairness is part of correctness.");
        System.exit(0);
    }

    static boolean alreadyBought(int customer) throws Exception {                        // idempotent retry: return the earlier result
        Connection c = pool.poll(20, TimeUnit.SECONDS); if (c == null) return false;
        try (PreparedStatement ps = c.prepareStatement("select 1 from orders where customer = ?")) { ps.setInt(1, customer); try (ResultSet rs = ps.executeQuery()) { return rs.next(); } }
        finally { pool.add(c); }
    }
    static int checkout(int customer) throws Exception {
        Connection c = pool.poll(20, TimeUnit.SECONDS); if (c == null) return 503;
        boolean ok = false;
        try {
            c.setAutoCommit(false); int start = customer % BUCKETS;
            try (Statement w = c.createStatement()) { w.execute("select pg_sleep(0.02)"); }       // price, promo, address checks: ~20 ms of DB work
            for (int k = 0; k < BUCKETS && !ok; k++) try (PreparedStatement u = c.prepareStatement("update stock set qty = qty - 1 where bucket = ? and qty > 0")) { u.setInt(1, (start + k) % BUCKETS); ok = u.executeUpdate() == 1; }
            if (ok) try (PreparedStatement ins = c.prepareStatement("insert into orders(customer) values (?) on conflict do nothing")) { ins.setInt(1, customer); if (ins.executeUpdate() == 0) { c.rollback(); return 200; } }   // already bought
            c.commit();
        } finally { c.setAutoCommit(true); pool.add(c); }
        if (!ok) { soldOut = true; return 409; }
        Thread.sleep(15);                                                                 // create the payment order (outside the transaction)
        return 200;
    }

    static void run(String base, String name) throws Exception {
        try (Connection c = DriverManager.getConnection(DB); Statement s = c.createStatement()) {
            s.execute("drop table if exists orders"); s.execute("drop table if exists stock");
            s.execute("create table stock(bucket int primary key, qty int not null check (qty >= 0))");
            s.execute("insert into stock select g, " + (STOCK / BUCKETS) + " from generate_series(0," + (BUCKETS - 1) + ") g");
            s.execute("create table orders(customer int primary key, created_at timestamptz default now())");
        }
        soldOut = false; ticketSeq.set(0); admittedUpTo.set(0); requests.set(0);
        ScheduledExecutorService admitter = Executors.newSingleThreadScheduledExecutor();
        if (design == 2) admitter.scheduleAtFixedRate(() -> admittedUpTo.addAndGet(30), 0, 100, TimeUnit.MILLISECONDS);
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        long[] answeredAt = new long[CUSTOMERS]; int[] outcome = new int[CUSTOMERS]; long t0 = System.nanoTime();
        Random r = new Random(48); long[] arriveAt = new long[CUSTOMERS];
        for (int i = 0; i < CUSTOMERS; i++) arriveAt[i] = (long) (r.nextDouble() * 2_000_000_000L);
        Integer[] byArrival = new Integer[CUSTOMERS]; for (int i = 0; i < CUSTOMERS; i++) byArrival[i] = i; Arrays.sort(byArrival, Comparator.comparingLong(i -> arriveAt[i]));
        int[] arrivalRank = new int[CUSTOMERS]; for (int k = 0; k < CUSTOMERS; k++) arrivalRank[byArrival[k]] = k;
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CUSTOMERS; i++) {
                final int id = i;
                ex.submit(() -> {
                    long wait = t0 + arriveAt[id] - System.nanoTime(); if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                    long start = System.nanoTime(), deadline = start + 25_000_000_000L; Random jr = new Random(id);
                    if (design == 2) {
                        long ticket = Long.parseLong(http.send(HttpRequest.newBuilder(URI.create(base + "/join")).build(), HttpResponse.BodyHandlers.ofString()).body());
                        while (System.nanoTime() < deadline) {
                            String st = http.send(HttpRequest.newBuilder(URI.create(base + "/status?ticket=" + ticket)).build(), HttpResponse.BodyHandlers.ofString()).body();
                            if (st.equals("soldout")) { outcome[id] = 409; answeredAt[id] = System.nanoTime() - start; return null; }
                            if (st.equals("admitted")) break;
                            Thread.sleep(1500 + jr.nextInt(1000));                 // poll interval the waiting room asks for
                        }
                    }
                    while (System.nanoTime() < deadline) {
                        try {
                            int code = http.send(HttpRequest.newBuilder(URI.create(base + "/checkout?customer=" + id)).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
                            if (code == 200 || code == 409) { outcome[id] = code; answeredAt[id] = System.nanoTime() - start; return null; }
                            Thread.sleep(1000 + jr.nextInt(1000));                           // 503: Retry-After, with jitter
                        } catch (HttpTimeoutException e) { Thread.sleep(200 + jr.nextInt(300)); }   // gave up waiting; try again
                    }
                    return null;
                });
            }
        }
        admitter.shutdownNow();
        int bought = 0, sold409 = 0, none = 0, fair = 0; List<Long> t = new ArrayList<>();
        int dbOrders; try (Connection c = DriverManager.getConnection(DB); ResultSet rs = c.createStatement().executeQuery("select count(*) from orders")) { rs.next(); dbOrders = rs.getInt(1); }
        Set<Integer> buyers = new HashSet<>(); try (Connection c = DriverManager.getConnection(DB); ResultSet rs = c.createStatement().executeQuery("select customer from orders")) { while (rs.next()) buyers.add(rs.getInt(1)); }
        for (int i = 0; i < CUSTOMERS; i++) {
            if (outcome[i] == 200) bought++; else if (outcome[i] == 409) sold409++; else none++;
            if (outcome[i] != 0) t.add(answeredAt[i]);
        }
        for (int b : buyers) if (arrivalRank[b] < STOCK) fair++;
        Collections.sort(t);
        System.out.printf("   %-46s %,10d %,12d %,12d %9.2f s %11.2f s %13.1f%%%n", name, bought, sold409, none, t.get(t.size() / 2) / 1e9, t.get((int) (t.size() * 0.99)) / 1e9,
                100.0 * fair / Math.max(1, dbOrders));
        if (dbOrders != bought) System.out.printf("        (%,d orders in the database — %,d buyers never heard back: their request timed out after the box was theirs)%n", dbOrders, dbOrders - bought);
    }
}
