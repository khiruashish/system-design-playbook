// TccL4.java — run with: java -Duser.timezone=UTC -cp postgresql-42.7.4.jar TccL4.java     (PostgreSQL lab on :5541)
// Try-Confirm-Cancel for flash-sale stock: TRY holds stock with an expiry, CONFIRM makes it permanent,
// CANCEL (or the expiry sweeper) gives it back. Confirm and the sweeper race; conditional updates pick one winner.
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class TccL4 {

    static Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5541/inventory", "postgres", "");
        c.setAutoCommit(true);
        return c;
    }

    /** TRY: take one unit and record a hold that expires. Returns false when sold out. */
    static boolean tryHold(Connection c, String holdId, int ttlMillis) throws SQLException {
        c.setAutoCommit(false);
        try (var take = c.prepareStatement("update flash_stock set available = available - 1 where sku = 'MANGO-1KG' and available > 0");
             var hold = c.prepareStatement("insert into holds(id, sku, status, expires_at) values (?, 'MANGO-1KG', 'HELD', clock_timestamp() + make_interval(secs => ? / 1000.0))")) {
            if (take.executeUpdate() == 0) { c.rollback(); return false; }
            hold.setString(1, holdId); hold.setInt(2, ttlMillis); hold.executeUpdate();
            c.commit(); return true;
        } finally { c.setAutoCommit(true); }
    }

    /** CONFIRM: only a hold that is still HELD and not expired. The DB clock decides, in the same statement. */
    static boolean confirm(Connection c, String holdId, boolean guarded) throws SQLException {
        try (var ps = c.prepareStatement(guarded
                ? "update holds set status = 'CONFIRMED' where id = ? and status = 'HELD' and expires_at > clock_timestamp()"
                : "update holds set status = 'CONFIRMED' where id = ?")) {                 // ❌ no status/expiry guard
            ps.setString(1, holdId); return ps.executeUpdate() == 1;
        }
    }

    /** CANCEL / expiry sweep: move HELD → RELEASED and return the unit, atomically, exactly once per hold. */
    static int sweepExpired(Connection c) throws SQLException {
        try (var ps = c.prepareStatement("""
                with released as (
                    update holds set status = 'RELEASED'
                     where status = 'HELD' and expires_at <= clock_timestamp()
                     returning sku)
                update flash_stock s set available = available + r.n
                  from (select sku, count(*) n from released group by sku) r
                 where s.sku = r.sku
                returning r.n""");
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    static void run(boolean guarded) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("drop table if exists holds, flash_stock");
            s.execute("create table flash_stock (sku text primary key, available int not null check (available >= 0))");
            s.execute("create table holds (id text primary key, sku text not null, status text not null, expires_at timestamptz not null)");
            s.execute("create index holds_expiry on holds (expires_at) where status = 'HELD'");
            s.execute("insert into flash_stock values ('MANGO-1KG', 500)");
        }
        int buyers = 2_000, ttl = 300;
        AtomicInteger soldOut = new AtomicInteger(), confirmed = new AtomicInteger(), lateConfirm = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger swept = new AtomicInteger();
        Thread sweeper = Thread.ofVirtual().start(() -> {
            try (Connection c = connect()) { while (!stop.get()) { swept.addAndGet(sweepExpired(c)); Thread.sleep(20); } }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        ExecutorService pool = Executors.newFixedThreadPool(32);
        List<Future<?>> fs = new ArrayList<>();
        Random seeds = new Random(3);
        for (int i = 0; i < buyers; i++) {
            String id = "hold-" + i; long payMillis = 50 + seeds.nextInt(400);   // payment takes 50–450 ms; TTL is 300 ms
            fs.add(pool.submit(() -> {
                try (Connection c = connect()) {
                    if (!tryHold(c, id, ttl)) { soldOut.incrementAndGet(); return null; }
                    Thread.sleep(payMillis);                                        // customer pays
                    if (confirm(c, id, guarded)) confirmed.incrementAndGet();
                    else lateConfirm.incrementAndGet();                             // expired: refund the payment (saga step)
                }
                return null;
            }));
        }
        for (Future<?> f : fs) f.get();
        Thread.sleep(ttl + 100); stop.set(true); sweeper.join();
        try (Connection c = connect()) { swept.addAndGet(sweepExpired(c)); }
        pool.shutdown();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            ResultSet r = s.executeQuery("""
                select (select available from flash_stock),
                       count(*) filter (where status = 'CONFIRMED'), count(*) filter (where status = 'RELEASED'), count(*) filter (where status = 'HELD')
                  from holds""");
            r.next();
            int available = r.getInt(1), conf = r.getInt(2), rel = r.getInt(3), held = r.getInt(4);
            System.out.println(guarded ? "guarded CONFIRM (status = HELD and not expired):" : "unguarded CONFIRM (by id only):");
            System.out.printf("   confirmed %d, confirm refused (hold expired → refund) %d, turned away (none available at TRY) %d%n", confirmed.get(), lateConfirm.get(), soldOut.get());
            System.out.printf("   holds: CONFIRMED %d, RELEASED %d, still HELD %d; units returned by the sweeper %d%n", conf, rel, held, swept.get());
            System.out.printf("   conservation: available %d + confirmed %d = %d (must be 500)%n", available, conf, available + conf);
            if (guarded) System.out.printf("   every refused confirm was released exactly once: %s%n", rel == lateConfirm.get() ? "yes" : "NO (" + rel + " vs " + lateConfirm.get() + ")");
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("500 mangoes, 2,000 buyers, hold TTL 300 ms, payment takes 50–450 ms, expiry sweeper every 20 ms");
        run(false);
        run(true);
        System.exit(0);
    }
}
