// SagaL3.java — run with: java -Duser.timezone=UTC -cp postgresql-42.7.4.jar SagaL3.java     (PostgreSQL lab on :5541)
// Checkout saga across three databases (orders, inventory, payments) with an orchestrator whose state is persisted.
// Injected faults: orchestrator crashes between steps, payment gateway declines and timeouts (outcome unknown).
// Every step and compensation is idempotent, keyed by the saga id. Invariants are checked at the end.
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class SagaL3 {

    // ---------- Db helper (same as L2) ----------
    static final class Db {
        private final BlockingQueue<Connection> pool = new LinkedBlockingQueue<>();
        Db(String database, int size) throws SQLException {
            for (int i = 0; i < size; i++) {
                Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5541/" + database, "postgres", "");
                c.setAutoCommit(false); pool.add(c);
            }
        }
        interface Work<T> { T apply(Connection c) throws SQLException; }
        <T> T tx(Work<T> work) {
            Connection c;
            try { c = pool.take(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            try { T r = work.apply(c); c.commit(); return r; }
            catch (SQLException | RuntimeException e) {
                try { c.rollback(); } catch (SQLException ignored) { }
                throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
            } finally { pool.add(c); }
        }
        void exec(String sql) { tx(c -> { try (Statement s = c.createStatement()) { s.execute(sql); } return null; }); }
        long one(String sql) { return tx(c -> { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { r.next(); return r.getLong(1); } }); }
        static String str(Connection c, String sql, Object... args) throws SQLException {
            try (var ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                try (var rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
            }
        }
        static int upd(Connection c, String sql, Object... args) throws SQLException {
            try (var ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                return ps.executeUpdate();
            }
        }
    }

    static final class Crash extends RuntimeException { Crash() { super("orchestrator crashed"); } }
    static final class GatewayTimeout extends RuntimeException { GatewayTimeout() { super("payment gateway timeout: outcome unknown"); } }

    // ---------- inventory-service (participant) ----------
    static final class Inventory {
        final Db db; final boolean idempotent;
        Inventory(Db db, boolean idempotent) { this.db = db; this.idempotent = idempotent; }

        /** Reserve stock for a saga. Returns HELD or REJECTED. Safe to call again with the same saga id. */
        String reserve(String sagaId, String sku, int qty) {
            return db.tx(c -> {
                if (idempotent) {
                    String existing = Db.str(c, "select status from reservations where saga_id = ?", sagaId);
                    if (existing != null) return existing;                              // retry: return the first outcome
                }
                int ok = Db.upd(c, "update stock set qty = qty - ? where sku = ? and qty >= ?", qty, sku, qty);
                String status = ok == 1 ? "HELD" : "REJECTED";
                Db.upd(c, "insert into reservations(saga_id, sku, qty, status) values (?, ?, ?, ?)", sagaId, sku, qty, status);
                return status;
            });
        }
        /** Compensation: give HELD stock back. Idempotent: the status guard makes a second call a no-op. */
        void release(String sagaId) {
            db.tx(c -> {
                String sku = Db.str(c, "update reservations set status = 'RELEASED' where saga_id = ? and status = 'HELD' returning sku || ':' || qty", sagaId);
                if (sku != null) { String[] p = sku.split(":"); Db.upd(c, "update stock set qty = qty + ? where sku = ?", Integer.parseInt(p[1]), p[0]); }
                return null;
            });
        }
        void confirm(String sagaId) { db.tx(c -> Db.upd(c, "update reservations set status = 'CONFIRMED' where saga_id = ? and status = 'HELD'", sagaId)); }
    }

    // ---------- payment-service + gateway (participant) ----------
    static final class Payments {
        final Db db; final boolean idempotent; final Random rnd;
        Payments(Db db, boolean idempotent, long seed) { this.db = db; this.idempotent = idempotent; this.rnd = new Random(seed); }

        /** Capture with the saga id as the idempotency key (Razorpay-style). 10 % decline, 10 % timeout. */
        String capture(String sagaId, long amountPaise) {
            double r; synchronized (rnd) { r = rnd.nextDouble(); }
            if (r < 0.05) throw new GatewayTimeout();                                  // request lost: nothing happened
            String status = db.tx(c -> {
                if (idempotent) {
                    String existing = Db.str(c, "select status from payments where idempotency_key = ?", sagaId);
                    if (existing != null) return existing;
                }
                String s = r < 0.15 ? "DECLINED" : "CAPTURED";
                Db.upd(c, "insert into payments(idempotency_key, amount_paise, status) values (?, ?, ?)", sagaId, amountPaise, s);
                return s;
            });
            if (r >= 0.15 && r < 0.20) throw new GatewayTimeout();                     // response lost: it DID happen
            return status;
        }
        void refund(String sagaId) { db.tx(c -> Db.upd(c, "update payments set status = 'REFUNDED' where idempotency_key = ? and status = 'CAPTURED'", sagaId)); }
    }

    // ---------- the orchestrator: a persisted state machine ----------
    enum State { STARTED, RESERVED, PAID, CONFIRMED, COMPENSATING, CANCELLED }

    static final class Orchestrator {
        final Db orders; final Inventory inventory; final Payments payments; final Random rnd; final double crashRate;
        final AtomicInteger crashes = new AtomicInteger(), timeouts = new AtomicInteger();
        Orchestrator(Db orders, Inventory inv, Payments pay, long seed, double crashRate) {
            this.orders = orders; this.inventory = inv; this.payments = pay; this.rnd = new Random(seed); this.crashRate = crashRate;
        }
        void maybeCrash() { boolean c; synchronized (rnd) { c = rnd.nextDouble() < crashRate; } if (c) { crashes.incrementAndGet(); throw new Crash(); } }

        void start(String sagaId, String customer, String sku, int qty, long amount) {
            orders.tx(c -> {
                Db.upd(c, "insert into orders(id, customer_id, sku, qty, amount_paise, status) values (?, ?, ?, ?, ?, 'PENDING')", sagaId, customer, sku, qty, amount);
                Db.upd(c, "insert into saga(id, state) values (?, 'STARTED')", sagaId);
                return null;
            });
        }

        State load(String sagaId) { return State.valueOf(orders.tx(c -> Db.str(c, "select state from saga where id = ?", sagaId))); }

        void save(String sagaId, State from, State to) {
            int n = orders.tx(c -> Db.upd(c, "update saga set state = ?, updated_at = now(), transitions = transitions + 1 where id = ? and state = ?", to.name(), sagaId, from.name()));
            if (n != 1) throw new IllegalStateException("saga " + sagaId + " was not in " + from);
        }

        /** Drive a saga until it reaches a terminal state. Safe to call again after a crash at ANY point. */
        void drive(String sagaId) {
            while (true) {
                State s = load(sagaId);
                String sku = orders.tx(c -> Db.str(c, "select sku || ':' || qty || ':' || amount_paise from orders where id = ?", sagaId));
                String[] p = sku.split(":");
                switch (s) {
                    case STARTED -> {
                        String r = inventory.reserve(sagaId, p[0], Integer.parseInt(p[1]));
                        maybeCrash();                                                  // effect done, state not saved yet
                        if (r.equals("REJECTED")) finishCancelled(sagaId, State.STARTED, "OUT_OF_STOCK");
                        else save(sagaId, State.STARTED, State.RESERVED);
                    }
                    case RESERVED -> {
                        String pay;
                        try { pay = payments.capture(sagaId, Long.parseLong(p[2])); }
                        catch (GatewayTimeout t) { timeouts.incrementAndGet(); continue; }   // unknown outcome → retry same key
                        maybeCrash();
                        if (pay.equals("CAPTURED")) save(sagaId, State.RESERVED, State.PAID);
                        else save(sagaId, State.RESERVED, State.COMPENSATING);
                    }
                    case PAID -> {
                        inventory.confirm(sagaId);
                        maybeCrash();
                        orders.tx(c -> {                                                // order status + saga state together
                            Db.upd(c, "update orders set status = 'CONFIRMED' where id = ?", sagaId);
                            Db.upd(c, "update saga set state = 'CONFIRMED', updated_at = now() where id = ? and state = 'PAID'", sagaId);
                            return null;
                        });
                    }
                    case COMPENSATING -> {                                              // payment failed: undo in reverse order
                        payments.refund(sagaId);                                        // no-op unless captured
                        inventory.release(sagaId);
                        maybeCrash();
                        finishCancelled(sagaId, State.COMPENSATING, "PAYMENT_FAILED");
                    }
                    case CONFIRMED, CANCELLED -> { return; }
                }
            }
        }

        void finishCancelled(String sagaId, State from, String reason) {
            orders.tx(c -> {
                Db.upd(c, "update orders set status = 'CANCELLED', cancel_reason = ? where id = ?", reason, sagaId);
                Db.upd(c, "update saga set state = 'CANCELLED', updated_at = now() where id = ? and state = ?", sagaId, from.name());
                return null;
            });
        }
    }

    static Map<String, Object> run(boolean idempotent) throws Exception {
        Db orders = new Db("orders", 16), inv = new Db("inventory", 16), pay = new Db("payments", 16);
        orders.exec("drop table if exists saga, orders, outbox");
        orders.exec("create table orders (id text primary key, customer_id text, sku text, qty int, amount_paise bigint, status text, cancel_reason text)");
        orders.exec("create table saga (id text primary key, state text not null, transitions int not null default 0, updated_at timestamptz default now())");
        inv.exec("drop table if exists reservations, stock, inbox");
        inv.exec("create table stock (sku text primary key, qty int not null check (qty >= 0))");
        inv.exec("insert into stock values ('ATTA-5KG', 1700)");
        inv.exec(idempotent ? "create table reservations (saga_id text primary key, sku text, qty int, status text)"
                            : "create table reservations (id bigserial primary key, saga_id text, sku text, qty int, status text)");
        pay.exec("drop table if exists payments, wallet");
        pay.exec(idempotent ? "create table payments (idempotency_key text primary key, amount_paise bigint, status text)"
                            : "create table payments (id bigserial primary key, idempotency_key text, amount_paise bigint, status text)");

        Orchestrator o = new Orchestrator(orders, new Inventory(inv, idempotent), new Payments(pay, idempotent, 11), 5, 0.05);
        int N = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            String id = "saga-" + i; long amount = 20_000 + (i % 50) * 1_000;
            fs.add(pool.submit(() -> {
                o.start(id, "cust-" + (Math.abs(id.hashCode()) % 500), "ATTA-5KG", 1, amount);
                try { o.drive(id); } catch (Crash ignored) { }                         // crashed: left for recovery
                return null;
            }));
        }
        for (Future<?> f : fs) f.get();
        // "restart": the recovery worker re-drives every non-terminal saga until none is left
        int recoveryRounds = 0; long recovered = 0;
        while (true) {
            List<String> stuck = orders.tx(c -> {
                List<String> l = new ArrayList<>();
                try (var rs = c.createStatement().executeQuery("select id from saga where state not in ('CONFIRMED', 'CANCELLED')")) { while (rs.next()) l.add(rs.getString(1)); }
                return l;
            });
            if (stuck.isEmpty()) break;
            recoveryRounds++; recovered += stuck.size();
            List<Future<?>> rf = new ArrayList<>();
            for (String id : stuck) rf.add(pool.submit(() -> { try { o.drive(id); } catch (Crash ignored) { } return null; }));
            for (Future<?> f : rf) f.get();
        }
        pool.shutdown();

        // ---------- invariants, checked across the three databases ----------
        Map<String, String> orderStatus = new HashMap<>();
        orders.tx(c -> { try (var rs = c.createStatement().executeQuery("select id, status from orders")) { while (rs.next()) orderStatus.put(rs.getString(1), rs.getString(2)); } return null; });
        Map<String, List<String>> resv = new HashMap<>(), paysBy = new HashMap<>();
        inv.tx(c -> { try (var rs = c.createStatement().executeQuery("select saga_id, status from reservations")) { while (rs.next()) resv.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2)); } return null; });
        pay.tx(c -> { try (var rs = c.createStatement().executeQuery("select idempotency_key, status from payments")) { while (rs.next()) paysBy.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2)); } return null; });
        int bad = 0; int doubleCharged = 0; int leakedHolds = 0;
        for (var e : orderStatus.entrySet()) {
            List<String> r = resv.getOrDefault(e.getKey(), List.of()), p = paysBy.getOrDefault(e.getKey(), List.of());
            long captured = p.stream().filter("CAPTURED"::equals).count();
            long held = r.stream().filter(x -> x.equals("HELD") || x.equals("CONFIRMED")).count();
            if (captured > 1) doubleCharged++;
            if (e.getValue().equals("CONFIRMED") ? !(captured == 1 && held == 1 && r.contains("CONFIRMED")) : !(captured == 0 && held == 0)) bad++;
            if (e.getValue().equals("CANCELLED") && held > 0) leakedHolds++;
        }
        long confirmed = orderStatus.values().stream().filter("CONFIRMED"::equals).count();
        long stockLeft = inv.one("select qty from stock");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("confirmed", confirmed);
        out.put("cancelled", orders.tx(c -> Db.str(c, "select string_agg(cancel_reason || '=' || n, ', ' order by cancel_reason) from (select cancel_reason, count(*) n from orders where status = 'CANCELLED' group by 1) x")));
        out.put("orchestrator crashes", o.crashes.get());
        out.put("gateway timeouts", o.timeouts.get());
        out.put("recovery rounds", recoveryRounds);
        out.put("sagas resumed", recovered);
        out.put("stock 1700 - confirmed == left", (1700 - confirmed) == stockLeft ? "yes (" + stockLeft + ")" : "NO: left " + stockLeft);
        out.put("double-charged orders", doubleCharged);
        out.put("orders violating invariants", bad);
        return out;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("2,000 checkouts, 8 threads, 1,700 units in stock; orchestrator crashes 5 % after each step; gateway: 10 % decline, 10 % timeout");
        System.out.println("steps NOT idempotent: " + run(false));
        System.out.println("idempotent steps    : " + run(true));
        System.exit(0);
    }
}
