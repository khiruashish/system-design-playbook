// ConsumerL2.java — run with: java -Duser.timezone=UTC -cp /tmp/pgjdbc.jar ConsumerL2.java
// An at-least-once consumer of order.order.placed.v1 awards loyalty points in real PostgreSQL (:5542).
// The log contains producer-retry duplicates (2 %), offsets are committed every 100 messages, and the consumer
// crashes at random points (on average every 400 messages) and restarts from the last committed offset.
// Strategies: naive, "dedup in Redis, then write to the DB" (real Redis :6390), and an inbox table in the same transaction.
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class ConsumerL2 {
    record Event(String eventId, int customer, long points) {}
    static class Crash extends RuntimeException {}

    static final Random RND = new Random(20);
    static int crashes;

    /** Crashes with probability p at this point of the processing. */
    static void maybeCrash(double p) { if (RND.nextDouble() < p) { crashes++; throw new Crash(); } }

    // ---- a tiny RESP client, enough for SET NX and FLUSHDB ----
    static class Redis implements Closeable {
        final Socket s; final OutputStream out; final BufferedReader in;
        Redis(int port) throws IOException { s = new Socket("localhost", port); out = s.getOutputStream(); in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)); }
        String cmd(String... args) throws IOException {
            var sb = new StringBuilder("*").append(args.length).append("\r\n");
            for (String a : args) sb.append('$').append(a.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(a).append("\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8)); out.flush();
            String line = in.readLine();
            if (line.startsWith("$-1")) return null;
            if (line.startsWith("$")) return in.readLine();
            return line.substring(1);
        }
        public void close() throws IOException { s.close(); }
    }

    interface Handler { void handle(Connection c, Redis r, Event e) throws Exception; }

    // naive: apply the effect; duplicates and redeliveries are applied again
    static final Handler NAIVE = (c, r, e) -> {
        maybeCrash(0.0012);
        addPoints(c, e);
        maybeCrash(0.0012);
    };

    // dedup marker in Redis, effect in PostgreSQL: two systems, no shared transaction
    static final Handler REDIS_THEN_DB = (c, r, e) -> {
        maybeCrash(0.0008);
        String claimed = r.cmd("SET", "processed:" + e.eventId(), "1", "NX", "EX", "86400");
        if (claimed == null) return;                                   // seen before → skip
        maybeCrash(0.0008);                                            // crash here: marked processed, points never added
        addPoints(c, e);
        maybeCrash(0.0008);
    };

    // inbox: record the event id and apply the effect in ONE database transaction
    static final Handler INBOX = (c, r, e) -> {
        c.setAutoCommit(false);
        try {
            maybeCrash(0.0008);
            int inserted;
            try (var ps = c.prepareStatement("insert into inbox(consumer, event_id) values ('loyalty', ?) on conflict do nothing")) {
                ps.setString(1, e.eventId()); inserted = ps.executeUpdate();
            }
            if (inserted == 1) { maybeCrash(0.0008); addPoints(c, e); }
            maybeCrash(0.0008);
            c.commit();
        } catch (Exception ex) { c.rollback(); throw ex; }             // a crash mid-transaction = rollback
        finally { c.setAutoCommit(true); }
    };

    static void addPoints(Connection c, Event e) throws SQLException {
        try (var ps = c.prepareStatement("update loyalty set points = points + ? where customer = ?")) {
            ps.setLong(1, e.points()); ps.setInt(2, e.customer()); ps.executeUpdate();
        }
    }

    public static void main(String[] args) throws Exception {
        // The log: 5,000 orders, 2 % published twice by a retrying producer (same event id)
        List<Event> log = new ArrayList<>();
        Random gen = new Random(7);
        Map<Integer, Long> expected = new HashMap<>();
        for (int i = 0; i < 5_000; i++) {
            var e = new Event("evt-" + i, gen.nextInt(200), 10 + gen.nextInt(90));
            log.add(e); expected.merge(e.customer(), e.points(), Long::sum);
            if (gen.nextDouble() < 0.02) log.add(e);
        }
        long expectedTotal = expected.values().stream().mapToLong(Long::longValue).sum();
        System.out.printf("log: %,d messages (%,d orders + %,d producer duplicates), offsets committed every 100, random crashes%n",
                log.size(), 5_000, log.size() - 5_000);
        System.out.printf("   %-26s %8s %12s %12s %12s %10s%n", "strategy", "crashes", "points", "over-", "under-", "customers");
        System.out.printf("   %-26s %8s %12s %12s %12s %10s%n", "", "", "(expected " + expectedTotal / 1000 + "K)", "awarded", "awarded", "wrong");

        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5542/shop?user=postgres");
             Redis r = new Redis(6390)) {
            for (var entry : List.of(Map.entry("naive", NAIVE), Map.entry("Redis marker, then DB", REDIS_THEN_DB), Map.entry("inbox in same transaction", INBOX))) {
                try (var st = c.createStatement()) {
                    st.execute("drop table if exists loyalty, inbox");
                    st.execute("create table loyalty(customer int primary key, points bigint not null default 0)");
                    st.execute("insert into loyalty(customer) select g from generate_series(0, 199) g");
                    st.execute("create table inbox(consumer text, event_id text, processed_at timestamptz default now(), primary key(consumer, event_id))");
                }
                r.cmd("FLUSHDB");
                crashes = 0;
                int committed = 0;                                                  // the consumer group's committed offset
                while (committed < log.size()) {
                    int offset = committed;                                         // (re)start from the committed offset
                    try {
                        while (offset < log.size()) {
                            entry.getValue().handle(c, r, log.get(offset));
                            offset++;
                            if (offset % 100 == 0 || offset == log.size()) committed = offset;   // offset commit
                        }
                    } catch (Crash crash) { /* process dies; a new one resumes from `committed` */ }
                }
                long total = 0, over = 0, under = 0; int wrong = 0;
                try (var st = c.createStatement(); var rs = st.executeQuery("select customer, points from loyalty")) {
                    while (rs.next()) {
                        long got = rs.getLong(2), exp = expected.getOrDefault(rs.getInt(1), 0L);
                        total += got; if (got > exp) over += got - exp; if (got < exp) under += exp - got; if (got != exp) wrong++;
                    }
                }
                System.out.printf("   %-26s %8d %,12d %,12d %,12d %10d%n", entry.getKey(), crashes, total, over, under, wrong);
            }
        }
    }
}
