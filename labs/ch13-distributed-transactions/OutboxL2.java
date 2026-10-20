// OutboxL2.java — run with: java -cp postgresql-42.7.4.jar OutboxL2.java      (PostgreSQL lab on :5541)
// order-service writes an order and an outbox row in ONE local transaction; relays publish the outbox to a broker
// (at-least-once, with injected crashes); inventory-service consumes with or without an INBOX (dedup) table.
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class OutboxL2 {

    // ---------- tiny connection pool + transaction helper ----------
    static final class Db {
        private final BlockingQueue<Connection> pool = new LinkedBlockingQueue<>();
        Db(String database, int size) throws SQLException {
            for (int i = 0; i < size; i++) {
                Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5541/" + database, "postgres", "");
                c.setAutoCommit(false);
                pool.add(c);
            }
        }
        interface Work<T> { T apply(Connection c) throws SQLException; }
        <T> T tx(Work<T> work) {
            Connection c;
            try { c = pool.take(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            try {
                T result = work.apply(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try { c.rollback(); } catch (SQLException ignored) { }
                throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
            } finally { pool.add(c); }
        }
        void exec(String sql) { tx(c -> { try (Statement s = c.createStatement()) { s.execute(sql); } return null; }); }
        long count(String sql) { return tx(c -> { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { r.next(); return r.getLong(1); } }); }
    }

    static final class Crash extends RuntimeException { Crash(String where) { super("injected crash: " + where); } }

    // ---------- the broker: at-least-once, keeps order of publish calls ----------
    record Message(long outboxId, String aggregateId, String type, int seq) { }
    static final BlockingQueue<Message> broker = new LinkedBlockingQueue<>();

    // ---------- order-service: order + outbox row in one transaction ----------
    static void placeOrder(Db orders, String orderId, String sku, int qty) {
        orders.tx(c -> {
            try (var ps = c.prepareStatement("insert into orders(id, sku, qty, status) values (?, ?, ?, 'PLACED')")) {
                ps.setString(1, orderId); ps.setString(2, sku); ps.setInt(3, qty); ps.executeUpdate();
            }
            try (var ps = c.prepareStatement("insert into outbox(aggregate_id, type, seq, payload) values (?, 'order.order.placed.v1', 1, ?::jsonb)")) {
                ps.setString(1, orderId); ps.setString(2, "{\"sku\":\"" + sku + "\",\"qty\":" + qty + "}"); ps.executeUpdate();
            }
            try (var ps = c.prepareStatement("insert into outbox(aggregate_id, type, seq, payload) values (?, 'order.order.confirmed.v1', 2, '{}'::jsonb)")) {
                ps.setString(1, orderId); ps.executeUpdate();
            }
            return null;
        });
    }

    // ---------- relay: claim a batch with SKIP LOCKED, publish, mark published ----------
    static int relayOnce(Db orders, Random rnd, double crashAfterPublish) {
        return orders.tx(c -> {
            List<Message> batch = new ArrayList<>();
            try (var ps = c.prepareStatement("""
                    select id, aggregate_id, type, seq from outbox
                     where published_at is null
                     order by id
                     limit 50
                     for update skip locked""");
                 var rs = ps.executeQuery()) {
                while (rs.next()) batch.add(new Message(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
            }
            for (Message m : batch) broker.add(m);                                   // publish (broker acks)
            if (!batch.isEmpty() && rnd.nextDouble() < crashAfterPublish) throw new Crash("relay after publish, before mark");
            try (var ps = c.prepareStatement("update outbox set published_at = now() where id = any(?)")) {
                ps.setArray(1, c.createArrayOf("bigint", batch.stream().map(Message::outboxId).toArray()));
                ps.executeUpdate();
            }
            return batch.size();
        });
    }

    // ---------- inventory-service consumer ----------
    static void consume(Db inventory, Message m, boolean useInbox, Random rnd, double crashBeforeAck) {
        if (!m.type().equals("order.order.placed.v1")) return;
        inventory.tx(c -> {
            if (useInbox) {
                try (var ps = c.prepareStatement("insert into inbox(message_id) values (?) on conflict do nothing")) {
                    ps.setLong(1, m.outboxId());
                    if (ps.executeUpdate() == 0) return null;                          // duplicate: already applied
                }
            }
            try (var ps = c.prepareStatement("insert into reservations(order_id, qty) values (?, 1)")) {
                ps.setString(1, m.aggregateId()); ps.executeUpdate();
            }
            try (var ps = c.prepareStatement("update stock set qty = qty - 1 where sku = 'ATTA-5KG'")) { ps.executeUpdate(); }
            return null;
        });
        if (rnd.nextDouble() < crashBeforeAck) throw new Crash("consumer after commit, before ack");   // → redelivery
    }

    static Map<String, Object> run(int relays, boolean useInbox) throws Exception {
        Db orders = new Db("orders", 12), inventory = new Db("inventory", 6);
        orders.exec("drop table if exists outbox, orders");
        orders.exec("create table orders (id text primary key, sku text, qty int, status text)");
        orders.exec("create table outbox (id bigserial primary key, aggregate_id text not null, type text not null, seq int not null, payload jsonb not null, published_at timestamptz)");
        orders.exec("create index outbox_unpublished on outbox (id) where published_at is null");
        inventory.exec("drop table if exists inbox, reservations, stock");
        inventory.exec("create table inbox (message_id bigint primary key, processed_at timestamptz default now())");
        inventory.exec("create table reservations (id bigserial primary key, order_id text not null, qty int not null)");
        inventory.exec("create table stock (sku text primary key, qty int not null)");
        inventory.exec("insert into stock values ('ATTA-5KG', 1000000)");
        broker.clear();

        int N = 2_000;
        AtomicInteger relayCrashes = new AtomicInteger(), consumerCrashes = new AtomicInteger(), inversions = new AtomicInteger(), duplicates = new AtomicInteger();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        // producers
        List<Future<?>> producers = new ArrayList<>();
        for (int t = 0; t < 8; t++) { int tt = t; producers.add(pool.submit(() -> { for (int i = tt; i < N; i += 8) placeOrder(orders, "ord-" + i, "ATTA-5KG", 1); return null; })); }
        // relays
        AtomicBoolean stop = new AtomicBoolean();
        for (int r = 0; r < relays; r++) {
            Random rnd = new Random(100 + r);
            pool.submit(() -> { while (!stop.get()) { try { if (relayOnce(orders, rnd, 0.10) == 0) Thread.sleep(5); } catch (Crash e) { relayCrashes.incrementAndGet(); } } return null; });
        }
        // consumer: redelivers a message if it "crashed" before acking; tracks per-order arrival order
        Map<String, Integer> lastSeq = new ConcurrentHashMap<>();
        Set<Long> delivered = ConcurrentHashMap.newKeySet();
        Random crnd = new Random(7);
        Future<?> consumer = pool.submit(() -> {
            while (!stop.get() || !broker.isEmpty()) {
                Message m = broker.poll(20, TimeUnit.MILLISECONDS);
                if (m == null) continue;
                if (!delivered.add(m.outboxId())) duplicates.incrementAndGet();            // redelivery of a message seen before
                else {
                    Integer prev = lastSeq.put(m.aggregateId(), Math.max(m.seq(), lastSeq.getOrDefault(m.aggregateId(), 0)));
                    if (prev != null && prev > m.seq()) inversions.incrementAndGet();      // first 'placed' arrived after 'confirmed'
                }
                while (true) {
                    try { consume(inventory, m, useInbox, crnd, 0.10); break; }
                    catch (Crash e) { consumerCrashes.incrementAndGet(); }                  // broker redelivers the same message
                }
            }
            return null;
        });
        for (Future<?> f : producers) f.get();
        while (orders.count("select count(*) from outbox where published_at is null") > 0) Thread.sleep(20);
        while (!broker.isEmpty()) Thread.sleep(20);
        stop.set(true); consumer.get(); pool.shutdown();

        long reservations = inventory.count("select count(*) from reservations");
        long distinct = inventory.count("select count(distinct order_id) from reservations");
        long stockUsed = 1_000_000 - inventory.count("select qty from stock");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orders", orders.count("select count(*) from orders"));
        out.put("events", orders.count("select count(*) from outbox"));
        out.put("relay crashes", relayCrashes.get());
        out.put("consumer crashes", consumerCrashes.get());
        out.put("orders reserved", distinct);
        out.put("reservation rows", reservations);
        out.put("stock used", stockUsed);
        out.put("duplicate deliveries", duplicates.get());
        out.put("out-of-order (placed after confirmed)", inversions.get());
        return out;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("2,000 orders (2 events each), 8 producer threads; relays crash 10 % of batches after publishing; consumer crashes 10 % after commit");
        System.out.println("no inbox,   1 relay : " + run(1, false));
        System.out.println("with inbox, 1 relay : " + run(1, true));
        System.out.println("with inbox, 3 relays: " + run(3, true));
        System.exit(0);
    }
}
