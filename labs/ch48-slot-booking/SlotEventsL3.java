// SlotEventsL3.java — run with (PostgreSQL on :5581, db shop):
//   java -Duser.timezone=UTC -cp /tmp/eslab/postgresql-42.7.4.jar SlotEventsL3.java   (JDK 21)
// THE SLOT AS AN EVENT-SOURCED AGGREGATE (the Ch 15 promise). 3,000 slots (100 zones × 30 hourly slots for one day);
// every change is an event: HELD (checkout holds minutes), BOOKED (hold confirmed), HOLD_EXPIRED, CANCELLED,
// RESCHEDULED_OUT, CAPACITY_CHANGED (roster changed). Busy evening slots churn thousands of holds; quiet ones a few dozen.
// Events are generated (fixed seed), bulk-loaded into a REAL PostgreSQL event table, and then:
//   1) projector v1 — the live read model — has a bug: it ignores RESCHEDULED_OUT (only CANCELLED frees capacity)
//   2) fix the projector and REBUILD the read model by replaying every event from the store
//   3) loading one aggregate to make a booking decision: full replay vs latest snapshot (every 100 events) + tail
//   4) time travel: the state of a hot slot halfway through its day, and whose hold took it to its fullest point
// Measured: real load/replay times, rows read, and read-model slots that disagree with the event history.
import java.sql.*;
import java.util.*;

public class SlotEventsL3 {
    static final String URL = "jdbc:postgresql://localhost:5581/shop?user=claude&reWriteBatchedInserts=true";
    static final int SLOTS = 3000, HELD = 1, BOOKED = 2, EXPIRED = 3, CANCELLED = 4, RESCHED = 5, CAPCHG = 6;
    record Ev(int slot, int version, int type, int customer, int minutes, long at) {}
    static final class State { int cap = 840, held, booked, version; void apply(Ev e, boolean buggy) {
        version = e.version();
        switch (e.type()) {
            case HELD -> held += e.minutes(); case BOOKED -> { held -= e.minutes(); booked += e.minutes(); }
            case EXPIRED -> held -= e.minutes(); case CANCELLED -> booked -= e.minutes();
            case RESCHED -> { if (!buggy) booked -= e.minutes(); } case CAPCHG -> cap = e.minutes();
        } }
        boolean same(State o) { return cap == o.cap && held == o.held && booked == o.booked; } }

    public static void main(String[] args) throws Exception {
        Random r = new Random(48); List<Ev> all = new ArrayList<>(); long day0 = 1_790_000_000_000L; Map<Integer, State> truth = new HashMap<>();
        for (int s = 0; s < SLOTS; s++) {
            boolean hot = s % 30 >= 11 && s % 30 <= 14 && r.nextDouble() < 0.5;                 // some evening slots are hot
            int target = hot ? 1500 + r.nextInt(2500) : 20 + r.nextInt(150);
            State st = new State(); int v = 0; long t = day0 + r.nextInt(3_600_000); List<int[]> bookedList = new ArrayList<>();
            while (v < target) {
                t += hot ? 500 + r.nextInt(3000) : 60_000 + r.nextInt(900_000);
                double x = r.nextDouble(); Ev e;
                if (x < 0.01) e = new Ev(s, ++v, CAPCHG, 0, 700 + 20 * r.nextInt(15), t);
                else if (x < 0.05 && !bookedList.isEmpty()) { int[] b = bookedList.remove(r.nextInt(bookedList.size())); e = new Ev(s, ++v, r.nextBoolean() ? CANCELLED : RESCHED, b[0], b[1], t); }
                else {
                    int m = 15 + r.nextInt(21), cust = r.nextInt(5_000_000);
                    if (st.held + st.booked + m > st.cap) continue;                               // full: the hold is refused, no event
                    Ev h = new Ev(s, ++v, HELD, cust, m, t); st.apply(h, false); all.add(h);
                    t += 20_000 + r.nextInt(400_000);
                    if (r.nextDouble() < 0.7) { e = new Ev(s, ++v, BOOKED, cust, m, t); bookedList.add(new int[]{cust, m}); } else e = new Ev(s, ++v, EXPIRED, cust, m, t);
                }
                st.apply(e, false); all.add(e);
            }
            truth.put(s, st);
        }
        System.out.printf("%,d events for %,d slots (busiest slot %,d events).%n%n", all.size(), SLOTS, truth.values().stream().mapToInt(x -> x.version).max().orElse(0));
        try (Connection c = DriverManager.getConnection(URL)) {
            try (Statement s = c.createStatement()) {
                s.execute("DROP TABLE IF EXISTS slot_ev, slot_view, slot_snap");
                s.execute("CREATE TABLE slot_ev (slot_id int, version int, type smallint NOT NULL, customer int NOT NULL, minutes int NOT NULL, at timestamptz NOT NULL, PRIMARY KEY (slot_id, version))");
                s.execute("CREATE TABLE slot_view (slot_id int PRIMARY KEY, cap int, held int, booked int, version int)");
                s.execute("CREATE TABLE slot_snap (slot_id int, version int, cap int, held int, booked int, PRIMARY KEY (slot_id, version))");
            }
            c.setAutoCommit(false);
            long t0 = System.nanoTime();
            try (PreparedStatement p = c.prepareStatement("INSERT INTO slot_ev VALUES (?,?,?,?,?,?)")) {
                int n = 0; for (Ev e : all) { p.setInt(1, e.slot()); p.setInt(2, e.version()); p.setShort(3, (short) e.type()); p.setInt(4, e.customer()); p.setInt(5, e.minutes()); p.setTimestamp(6, new Timestamp(e.at())); p.addBatch(); if (++n % 5000 == 0) p.executeBatch(); }
                p.executeBatch();
            }
            c.commit(); try (Statement s = c.createStatement()) { s.execute("ANALYZE slot_ev"); } c.commit();
            System.out.printf("   load: %,d events appended in %.1f s (%,.0f events/s, batched)%n%n", all.size(), (System.nanoTime() - t0) / 1e9, all.size() / ((System.nanoTime() - t0) / 1e9));

            // 1) + 2) projector v1 (buggy) vs rebuilt v2
            for (int ver = 1; ver <= 2; ver++) {
                t0 = System.nanoTime(); Map<Integer, State> view = replayAll(c, ver == 1);
                double secs = (System.nanoTime() - t0) / 1e9;
                try (Statement s = c.createStatement()) { s.execute("TRUNCATE slot_view"); }
                try (PreparedStatement p = c.prepareStatement("INSERT INTO slot_view VALUES (?,?,?,?,?)")) {
                    for (var en : view.entrySet()) { State st = en.getValue(); p.setInt(1, en.getKey()); p.setInt(2, st.cap); p.setInt(3, st.held); p.setInt(4, st.booked); p.setInt(5, st.version); p.addBatch(); }
                    p.executeBatch();
                }
                c.commit();
                int wrong = 0, phantom = 0; for (int s = 0; s < SLOTS; s++) { State v = view.get(s), tr = truth.get(s); if (!v.same(tr)) wrong++;
                    if (v.held + v.booked + 35 > v.cap && tr.held + tr.booked + 35 <= tr.cap) phantom++; }
                System.out.printf("   %s: replayed %,d events in %.2f s → %,d of %,d slots disagree with history; %,d shown FULL that have room%n",
                        ver == 1 ? "projector v1 (ignores RESCHEDULED_OUT)" : "projector v2 rebuilt from the event store", all.size(), secs, wrong, SLOTS, phantom);
            }
            // snapshots every 100 events
            try (PreparedStatement p = c.prepareStatement("INSERT INTO slot_snap VALUES (?,?,?,?,?)")) {
                State st = null; int cur = -1;
                for (Ev e : all) { if (e.slot() != cur) { cur = e.slot(); st = new State(); } st.apply(e, false);
                    if (e.version() % 100 == 0) { p.setInt(1, cur); p.setInt(2, e.version()); p.setInt(3, st.cap); p.setInt(4, st.held); p.setInt(5, st.booked); p.addBatch(); } }
                p.executeBatch();
            }
            c.commit();
            // 3) aggregate loads
            List<Integer> hot = new ArrayList<>(); for (int s = 0; s < SLOTS; s++) if (truth.get(s).version > 1000) hot.add(s);
            System.out.println("\n   loading one aggregate for a booking decision (2,000 loads each, real queries):");
            System.out.printf("   %-44s %18s %12s %12s%n", "", "rows read / load", "p50", "p99");
            for (boolean hotSet : new boolean[]{false, true}) for (boolean snap : new boolean[]{false, true}) {
                long[] lat = new long[2000]; long rows = 0; Random q = new Random(7);
                for (int i = 0; i < 2000; i++) {
                    int s = hotSet ? hot.get(q.nextInt(hot.size())) : q.nextInt(SLOTS);
                    long s0 = System.nanoTime(); int[] res = load(c, s, snap); lat[i] = System.nanoTime() - s0; rows += res[0];
                    if (res[1] != truth.get(s).booked) throw new IllegalStateException("load mismatch " + s);
                }
                c.commit(); Arrays.sort(lat);
                System.out.printf("   %-44s %,18.1f %9.2f ms %9.2f ms%n", (hotSet ? "hot slot, " : "any slot, ") + (snap ? "snapshot + tail" : "full replay"), rows / 2000.0, lat[1000] / 1e6, lat[1980] / 1e6);
            }
            // 4) time travel + audit
            int s = hot.get(0); long first = all.stream().filter(e -> e.slot() == s).mapToLong(Ev::at).min().orElse(0), last = all.stream().filter(e -> e.slot() == s).mapToLong(Ev::at).max().orElse(0), at = (first + last) / 2;
            t0 = System.nanoTime(); State past = new State(); int n = 0;
            try (PreparedStatement p = c.prepareStatement("SELECT slot_id, version, type, customer, minutes, at FROM slot_ev WHERE slot_id = ? AND at <= ? ORDER BY version")) {
                p.setInt(1, s); p.setTimestamp(2, new Timestamp(at));
                try (ResultSet rs = p.executeQuery()) { while (rs.next()) { past.apply(ev(rs), false); n++; } }
            }
            double ms = (System.nanoTime() - t0) / 1e6;
            String sql = """
                    SELECT customer, minutes, version FROM (
                      SELECT customer, minutes, version, type,
                             sum(CASE type WHEN 1 THEN minutes WHEN 3 THEN -minutes WHEN 4 THEN -minutes WHEN 5 THEN -minutes ELSE 0 END)
                               OVER (ORDER BY version) AS taken
                      FROM slot_ev WHERE slot_id = ? AND type <> 6) x
                    WHERE type = 1 ORDER BY taken DESC, version LIMIT 1""";
            long t1 = System.nanoTime(); String who;
            try (PreparedStatement p = c.prepareStatement(sql)) { p.setInt(1, s); try (ResultSet rs = p.executeQuery()) { rs.next(); who = "customer " + rs.getInt(1) + " (" + rs.getInt(2) + " min, event v" + rs.getInt(3) + ")"; } }
            System.out.printf("%n   time travel: slot %d as of %tR UTC (midway through its history) — held %d, booked %d of %d min, from %,d events in %.1f ms%n", s, new java.util.Date(at), past.held, past.booked, past.cap, n, ms);
            System.out.printf("   audit: the hold that took this slot to its fullest point was %s — %.1f ms%n", who, (System.nanoTime() - t1) / 1e6);
        }
        System.out.println("\n   → the read model is a cache of the history, so a projector bug is a data bug you can FIX: rebuild from the events");
        System.out.println("     and every slot is right again — a mutable counter would have kept the wrong number forever. Busy slots grow");
        System.out.println("     long histories and full replay slows down with them; a snapshot bounds the tail to under 100 events. For quiet");
        System.out.println("     slots the difference is noise. Questions nobody planned for — what did this slot look like an hour ago, whose");
        System.out.println("     hold filled it — are queries over the history, not forensics.");
    }

    static Ev ev(ResultSet rs) throws SQLException { return new Ev(rs.getInt(1), rs.getInt(2), rs.getShort(3), rs.getInt(4), rs.getInt(5), rs.getTimestamp(6).getTime()); }

    static Map<Integer, State> replayAll(Connection c, boolean buggy) throws SQLException {
        Map<Integer, State> m = new HashMap<>();
        try (Statement s = c.createStatement()) { s.setFetchSize(10_000);
            try (ResultSet rs = s.executeQuery("SELECT slot_id, version, type, customer, minutes, at FROM slot_ev ORDER BY slot_id, version")) {
                while (rs.next()) { Ev e = ev(rs); m.computeIfAbsent(e.slot(), k -> new State()).apply(e, buggy); } } }
        c.commit(); return m;
    }

    static int[] load(Connection c, int slot, boolean snap) throws SQLException {
        State st = new State(); int from = 0, rows = 0;
        if (snap) try (PreparedStatement p = c.prepareStatement("SELECT version, cap, held, booked FROM slot_snap WHERE slot_id = ? ORDER BY version DESC LIMIT 1")) {
            p.setInt(1, slot); try (ResultSet rs = p.executeQuery()) { if (rs.next()) { from = rs.getInt(1); st.cap = rs.getInt(2); st.held = rs.getInt(3); st.booked = rs.getInt(4); rows++; } }
        }
        try (PreparedStatement p = c.prepareStatement("SELECT slot_id, version, type, customer, minutes, at FROM slot_ev WHERE slot_id = ? AND version > ? ORDER BY version")) {
            p.setInt(1, slot); p.setInt(2, from); try (ResultSet rs = p.executeQuery()) { while (rs.next()) { st.apply(ev(rs), false); rows++; } }
        }
        return new int[]{rows, st.booked};
    }
}
