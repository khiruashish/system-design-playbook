// ProjectionL3.java — run with: java -Duser.timezone=UTC -cp postgresql-42.7.4.jar ProjectionL3.java   (PostgreSQL on :5551)
// CQRS read models built by projectors that tail the event store WHILE writers are appending.
// A bigserial position is assigned at INSERT time but becomes visible at COMMIT time, so positions can
// appear out of order. A projector that checkpoints "max position seen" silently skips late committers.
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ProjectionL3 {

    static Connection conn() throws SQLException { return DriverManager.getConnection("jdbc:postgresql://localhost:5551/eventstore", "postgres", ""); }

    static void exec(Connection c, String sql) throws SQLException { try (Statement s = c.createStatement()) { s.execute(sql); } }

    /** One projector pass: read a batch after the checkpoint, apply it to the read model, store the checkpoint — one txn. */
    static int projectOnce(Connection c, String model, boolean safe) throws SQLException {
        c.setAutoCommit(false);
        try {
            long cpTx = 0, cpPos = 0;
            try (var rs = c.createStatement().executeQuery("select tx_id::text::bigint, position from checkpoints where projection = '" + model + "' for update")) {
                rs.next(); cpTx = rs.getLong(1); cpPos = rs.getLong(2);
            }
            String sql = safe
                ? """
                  select stream_id, version, global_position, tx_id::text::bigint from events
                   where (tx_id, global_position) > (?::text::xid8, ?)
                     and tx_id < pg_snapshot_xmin(pg_current_snapshot())      -- only transactions that can no longer commit "behind" us
                   order by tx_id, global_position limit 500"""
                : """
                  select stream_id, version, global_position, tx_id::text::bigint from events
                   where global_position > ?
                   order by global_position limit 500""";
            int n = 0; long lastTx = cpTx, lastPos = cpPos;
            try (var ps = c.prepareStatement(sql)) {
                if (safe) { ps.setLong(1, cpTx); ps.setLong(2, cpPos); } else ps.setLong(1, cpPos);
                try (var rs = ps.executeQuery();
                     var up = c.prepareStatement("insert into " + model + "(order_id, last_version, events_applied) values (?, ?, 1) " +
                             "on conflict (order_id) do update set last_version = greatest(" + model + ".last_version, excluded.last_version), " +
                             "events_applied = " + model + ".events_applied + 1")) {
                    while (rs.next()) {
                        up.setString(1, rs.getString(1)); up.setInt(2, rs.getInt(2)); up.executeUpdate();
                        lastPos = rs.getLong(3); lastTx = rs.getLong(4); n++;
                    }
                }
            }
            try (var ps = c.prepareStatement("update checkpoints set tx_id = ?::text::xid8, position = ? where projection = ?")) {
                ps.setLong(1, lastTx); ps.setLong(2, lastPos); ps.setString(3, model); ps.executeUpdate();
            }
            c.commit();
            return n;
        } catch (SQLException e) { c.rollback(); throw e; }
        finally { c.setAutoCommit(true); }
    }

    public static void main(String[] args) throws Exception {
        try (Connection c = conn()) {
            exec(c, "drop table if exists events, checkpoints, order_status_naive, order_status_safe");
            exec(c, """
                create table events (
                  global_position bigserial,
                  tx_id xid8 not null default pg_current_xact_id(),
                  stream_id text not null,
                  version int not null,
                  type text not null,
                  payload jsonb not null default '{}',
                  primary key (stream_id, version))""");
            exec(c, "create index on events (global_position)");
            exec(c, "create index on events (tx_id, global_position)");
            exec(c, "create table checkpoints (projection text primary key, tx_id xid8 not null, position bigint not null)");
            exec(c, "insert into checkpoints values ('order_status_naive', '0', 0), ('order_status_safe', '0', 0)");
            for (String m : List.of("order_status_naive", "order_status_safe"))
                exec(c, "create table " + m + " (order_id text primary key, last_version int not null, events_applied int not null)");
        }
        int writers = 8, perWriter = 1_500;
        AtomicBoolean writing = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(writers + 2);
        List<Future<?>> ws = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            int ww = w;
            ws.add(pool.submit(() -> {
                Random rnd = new Random(ww);
                Map<String, Integer> versions = new HashMap<>();
                try (Connection c = conn()) {
                    c.setAutoCommit(false);
                    for (int i = 0; i < perWriter; i++) {
                        String stream = "ord-" + ww + "-" + rnd.nextInt(200);
                        int v = versions.merge(stream, 1, Integer::sum);
                        try (var ps = c.prepareStatement("insert into events(stream_id, version, type) values (?, ?, 'OrderStatusChanged')")) {
                            ps.setString(1, stream); ps.setInt(2, v); ps.executeUpdate();
                        }
                        Thread.sleep(rnd.nextInt(4));                               // position taken; commit comes a bit later
                        c.commit();
                    }
                }
                return null;
            }));
        }
        AtomicInteger passes = new AtomicInteger();
        List<Future<?>> ps = new ArrayList<>();
        for (String model : List.of("order_status_naive", "order_status_safe")) {
            boolean safe = model.endsWith("safe");
            ps.add(pool.submit(() -> {
                try (Connection c = conn()) {
                    while (true) {
                        int n = projectOnce(c, model, safe);
                        passes.incrementAndGet();
                        if (n == 0) { if (!writing.get() && projectOnce(c, model, safe) == 0) break; Thread.sleep(5); }
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : ws) f.get();
        writing.set(false);
        for (Future<?> f : ps) f.get();
        pool.shutdown();

        try (Connection c = conn()) {
            long total;
            try (var rs = c.createStatement().executeQuery("select count(*) from events")) { rs.next(); total = rs.getLong(1); }
            System.out.printf("%d writers appended %,d events to %,d order streams while two projectors tailed the store%n",
                    writers, total, writers * 200);
            for (String model : List.of("order_status_naive", "order_status_safe")) {
                try (var rs = c.createStatement().executeQuery("""
                        select coalesce(sum(m.events_applied), 0),
                               count(*) filter (where m.last_version is distinct from e.max_version)
                          from (select stream_id, max(version) max_version from events group by stream_id) e
                          left join %s m on m.order_id = e.stream_id""".formatted(model))) {
                    rs.next();
                    System.out.printf("   %-20s events applied %,6d of %,d   missed %,5d   orders with a wrong current version %,4d%n",
                            model, rs.getLong(1), total, total - rs.getLong(1), rs.getLong(2));
                }
            }
            // rebuild: a read model is disposable — drop it and replay from position 0
            exec(c, "truncate order_status_naive");
            exec(c, "update checkpoints set tx_id = '0', position = 0 where projection = 'order_status_naive'");
            long t0 = System.nanoTime();
            while (projectOnce(c, "order_status_naive", true) > 0) { }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            try (var rs = c.createStatement().executeQuery("select sum(events_applied) from order_status_naive")) {
                rs.next(); System.out.printf("   rebuild of the broken read model from the log: %,d events re-applied in %d ms → correct again%n", rs.getLong(1), ms);
            }
        }
        System.exit(0);
    }
}
