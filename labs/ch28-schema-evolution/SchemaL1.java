// SchemaL1.java — run with: java -Duser.timezone=UTC -cp /tmp/pgjdbc.jar SchemaL1.java
// Online schema changes on a REAL PostgreSQL table under concurrent traffic. A background workload issues SELECT/UPDATE on
// `orders` continuously; meanwhile we run several DDL changes and measure how long each one BLOCKS that traffic (query
// latency during the DDL, and max lock wait). Unsafe forms take a strong lock and stall everyone; safe forms don't.
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class SchemaL1 {
    static final String URL = "jdbc:postgresql://localhost:5581/shop?user=postgres";
    static volatile boolean running = true;

    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement st = c.createStatement()) {
            st.execute("drop table if exists orders");
            st.execute("create table orders(id bigserial primary key, customer_id bigint, total_paise bigint, status text default 'NEW')");
            st.execute("insert into orders(customer_id, total_paise) select (random()*100000)::bigint, (random()*500000)::bigint from generate_series(1,2000000)");
            st.execute("analyze orders");
        }
        System.out.println("2,000,000 orders. A background workload runs SELECT-by-id + UPDATE continuously while we alter the schema.");
        System.out.printf("   %-64s %12s %12s%n", "DDL operation", "max stall", "queries errored");

        run("ADD COLUMN with a volatile DEFAULT (rewrites the whole table)",
            "alter table orders add column created_at timestamptz default clock_timestamp()");
        drop("created_at");

        run("ADD COLUMN with a constant DEFAULT (metadata-only in PG 11+, fast)",
            "alter table orders add column channel text default 'web'");
        drop("channel");

        run("ADD NOT NULL by validating existing rows (scans + locks the table)",
            "alter table orders add column region text; update orders set region='IN' where id % 1 = 0; alter table orders alter column region set not null");
        drop("region");

        run("CREATE INDEX (blocks writes for the whole build)",
            "create index idx_orders_customer on orders(customer_id)");
        dropIdx("idx_orders_customer");

        run("CREATE INDEX CONCURRENTLY (builds without blocking writes)",
            "CONCURRENTLY:create index concurrently idx_orders_customer2 on orders(customer_id)");
        dropIdx("idx_orders_customer2");

        System.out.println("\n   → the volatile-default add, the NOT NULL validation and the plain CREATE INDEX all take a strong lock and stall live");
        System.out.println("     traffic for the duration of a full-table operation. The constant-default add is metadata-only, and CREATE INDEX");
        System.out.println("     CONCURRENTLY / adding a CHECK ... NOT VALID then VALIDATE avoid the stall. Same end state, very different blocking.");
    }

    static void run(String label, String ddl) throws Exception {
        List<long[]> lat = Collections.synchronizedList(new ArrayList<>());   // {startNanos, latencyNanos}
        AtomicLong stalled = new AtomicLong();
        running = true;
        // background traffic
        var pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 8; i++) pool.submit(() -> {
            try (Connection c = DriverManager.getConnection(URL)) {
                var sel = c.prepareStatement("select total_paise from orders where id = ?");
                var upd = c.prepareStatement("update orders set total_paise = total_paise + 1 where id = ?");
                var r = ThreadLocalRandom.current();
                while (running) {
                    long id = 1 + r.nextLong(2_000_000); long t0 = System.nanoTime();
                    try { sel.setLong(1, id); sel.executeQuery().close(); if (r.nextBoolean()) { upd.setLong(1, id); upd.executeUpdate(); } }
                    catch (SQLException e) { stalled.incrementAndGet(); }
                    lat.add(new long[]{t0, System.nanoTime() - t0});
                }
            } catch (Exception e) {}
        });
        Thread.sleep(400);                                    // let traffic settle
        long d0 = System.nanoTime();
        boolean concurrently = ddl.startsWith("CONCURRENTLY:");
        String sql = concurrently ? ddl.substring("CONCURRENTLY:".length()) : ddl;
        try (Connection admin = DriverManager.getConnection(URL)) {
            admin.setAutoCommit(true);
            for (String stmt : sql.split(";")) if (!stmt.isBlank()) { try (Statement s = admin.createStatement()) { s.execute(stmt.trim()); } }
        }
        long d1 = System.nanoTime();
        long ddlMs = (d1 - d0) / 1_000_000;
        running = false; pool.shutdown(); pool.awaitTermination(30, TimeUnit.SECONDS);
        // the worst stall of any query that was in flight during the DDL (started after d0 and overlapped the DDL)
        double maxStall = 0;
        for (long[] e : lat) { if (e[0] >= d0 && e[0] <= d1) maxStall = Math.max(maxStall, e[1] / 1e6); }
        System.out.printf("   %-64s %9.0f ms %12d   (DDL took %,d ms)%n", label, maxStall, stalled.get(), ddlMs);
    }

    static void drop(String col) throws Exception { try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) { s.execute("alter table orders drop column if exists " + col); } }
    static void dropIdx(String idx) throws Exception { try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) { s.execute("drop index if exists " + idx); } }
}
