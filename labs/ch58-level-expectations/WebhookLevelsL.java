// WebhookLevelsL.java — run with (PostgreSQL on :5581, db shop):
//   java -Duser.timezone=UTC -cp /tmp/eslab/postgresql-42.7.4.jar WebhookLevelsL.java   (JDK 21)
// ONE QUESTION, FOUR LEVELS OF ANSWER: "Handle the payment gateway's webhooks." 5,000 payments; the gateway sends
// status events (AUTHORIZED → CAPTURED, 20% later REFUNDED) at least once: 25% are duplicated, 15% arrive out of order
// (a later status before an earlier one), and 2% are never delivered at all. Two consumer nodes process concurrently;
// node 1 restarts halfway. Each accepted "CAPTURED" triggers a side effect (credit the order + send a receipt), each
// "REFUNDED" a reverse effect. Four implementations, each the natural answer at one level (REAL PostgreSQL):
//   B1 in-memory "seen" set per node; apply the event's status to the payment row
//   B2 dedup in the database: INSERT event_id (primary key) in the SAME transaction as the effect
//   B3 B2 + ordering: a status only moves forward along AUTHORIZED < CAPTURED < REFUNDED (guarded UPDATE); effects
//      go to an outbox in the same transaction
//   B4 B3 + reconciliation: after the stream, compare with the gateway's "list payments" API and repair drift
// Measured against the gateway's truth: receipts/credits applied twice, payments whose stored status went BACKWARDS,
// payments whose final status is wrong, and money effects missing.
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class WebhookLevelsL {
    static final String URL = "jdbc:postgresql://localhost:5581/shop?user=claude";
    static final String[] ST = {"AUTHORIZED", "CAPTURED", "REFUNDED"};
    record Ev(String id, int payment, int status) {}

    public static void main(String[] args) throws Exception {
        Random r = new Random(58); int P = 5000; int[] truth = new int[P]; List<Ev> stream = new ArrayList<>(); Set<String> lost = new HashSet<>();
        for (int p = 0; p < P; p++) { int last = r.nextDouble() < 0.2 ? 2 : 1; truth[p] = last; List<Ev> evs = new ArrayList<>();
            for (int s = 0; s <= last; s++) evs.add(new Ev("evt-" + p + "-" + s, p, s));
            if (r.nextDouble() < 0.15 && evs.size() > 1) { int i = r.nextInt(evs.size() - 1); Collections.swap(evs, i, i + 1); }      // out of order
            for (Ev e : evs) { if (r.nextDouble() < 0.02) { lost.add(e.id()); continue; } stream.add(e); if (r.nextDouble() < 0.25) stream.add(e); } }   // lost / duplicated
        // interleave per payment roughly in time, preserve per-payment order of the list above
        List<Ev> ordered = new ArrayList<>(); Map<Integer, Deque<Ev>> per = new HashMap<>(); for (Ev e : stream) per.computeIfAbsent(e.payment(), k -> new ArrayDeque<>()).add(e);
        List<Integer> keys = new ArrayList<>(per.keySet()); while (!keys.isEmpty()) { int k = r.nextInt(keys.size()); Ev e = per.get(keys.get(k)).poll(); ordered.add(e); if (per.get(keys.get(k)).isEmpty()) { keys.set(k, keys.get(keys.size() - 1)); keys.remove(keys.size() - 1); } }
        System.out.printf("5,000 payments, %,d webhook deliveries (25%% duplicated, 15%% of payments out of order, %d events never delivered); 2 nodes, one restart.%n%n", ordered.size(), lost.size());
        System.out.printf("   %-58s %14s %16s %14s %14s%n", "answer", "effects applied", "status went", "final status", "money effects");
        System.out.printf("   %-58s %14s %16s %14s %14s%n", "", "twice", "backwards", "wrong", "missing");
        String[] names = {"B1) in-memory seen-set per node, apply status", "B2) dedup by event id in the same DB transaction", "B3) B2 + forward-only status + outbox", "B4) B3 + reconciliation with the gateway's list API"};
        for (int lvl = 1; lvl <= 4; lvl++) run(lvl, names[lvl - 1], ordered, truth);
        System.out.println("\n   → every answer 'handles webhooks'; they differ in which failure modes they have thought about. B1 works in a demo");
        System.out.println("     and double-credits in production (two nodes, restarts). B2 makes duplicates harmless but trusts arrival order.");
        System.out.println("     B3 encodes the domain's state machine, so late events can't move a payment backwards. B4 accepts that an");
        System.out.println("     at-least-once channel can still lose messages and closes the loop with reconciliation. That progression —");
        System.out.println("     correct once, correct under concurrency, correct under reordering, correct under loss — is what the levels mean.");
    }

    static void run(int lvl, String name, List<Ev> events, int[] truth) throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS pay, processed_event, effect, outbox");
            s.execute("CREATE TABLE pay (id int PRIMARY KEY, status int NOT NULL DEFAULT -1, regressions int NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE processed_event (event_id text PRIMARY KEY)");
            s.execute("CREATE TABLE effect (id bigserial PRIMARY KEY, payment int, kind text)");
            s.execute("CREATE TABLE outbox (id bigserial PRIMARY KEY, payment int, kind text)");
            s.execute("INSERT INTO pay (id) SELECT g FROM generate_series(0," + (truth.length - 1) + ") g");
        }
        AtomicInteger next = new AtomicInteger(); int half = events.size() / 2;
        ExecutorService ex = Executors.newFixedThreadPool(2); List<Future<?>> fs = new ArrayList<>();
        for (int node = 0; node < 2; node++) { final int nd = node; fs.add(ex.submit(() -> {
            Set<String> seen = new HashSet<>(); boolean restarted = false;
            try (Connection c = DriverManager.getConnection(URL)) { c.setAutoCommit(false); int i;
                while ((i = next.getAndIncrement()) < events.size()) {
                    if (nd == 1 && !restarted && i > half) { seen.clear(); restarted = true; }        // node 1 restarts: memory gone
                    Ev e = events.get(i);
                    if (lvl == 1) { if (!seen.add(e.id())) continue; apply(c, lvl, e); c.commit(); continue; }
                    try (PreparedStatement p = c.prepareStatement("INSERT INTO processed_event VALUES (?) ON CONFLICT DO NOTHING")) { p.setString(1, e.id()); if (p.executeUpdate() == 0) { c.rollback(); continue; } }
                    apply(c, lvl, e); c.commit();
                }
            }
            return null; })); }
        for (Future<?> f : fs) f.get(); ex.shutdown();
        if (lvl == 4) try (Connection c = DriverManager.getConnection(URL)) {                        // reconciliation: gateway list API = truth
            c.setAutoCommit(false);
            for (int p = 0; p < truth.length; p++) { int cur; try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT status FROM pay WHERE id = " + p)) { rs.next(); cur = rs.getInt(1); }
                for (int st = cur + 1; st <= truth[p]; st++) apply(c, 3, new Ev("recon-" + p + "-" + st, p, st)); }
            c.commit();
        }
        long twice, back, wrong, missing;
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            String tbl = lvl >= 3 ? "outbox" : "effect";
            twice = one(s, "SELECT count(*) FROM (SELECT payment, kind FROM " + tbl + " GROUP BY 1,2 HAVING count(*) > 1) x");
            back = one(s, "SELECT count(*) FROM pay WHERE regressions > 0");
            StringBuilder vals = new StringBuilder(); for (int p = 0; p < truth.length; p++) vals.append(p == 0 ? "" : ",").append("(").append(p).append(",").append(truth[p]).append(")");
            wrong = one(s, "SELECT count(*) FROM pay JOIN (VALUES " + vals + ") t(id, st) USING (id) WHERE pay.status <> t.st");
            long expected = 0; for (int t : truth) expected += t >= 1 ? (t == 2 ? 2 : 1) : 0;
            missing = expected - one(s, "SELECT count(*) FROM (SELECT DISTINCT payment, kind FROM " + tbl + ") x");
        }
        System.out.printf("   %-58s %,14d %,16d %,14d %,14d%n", name, twice, back, wrong, missing);
    }
    static long one(Statement s, String q) throws SQLException { try (ResultSet rs = s.executeQuery(q)) { rs.next(); return rs.getLong(1); } }

    static void apply(Connection c, int lvl, Ev e) throws SQLException {
        String effTable = lvl >= 3 ? "outbox" : "effect";
        if (lvl >= 3) {                                                                                // forward-only transition
            try (PreparedStatement p = c.prepareStatement("UPDATE pay SET status = ? WHERE id = ? AND status < ?")) { p.setInt(1, e.status()); p.setInt(2, e.payment()); p.setInt(3, e.status()); if (p.executeUpdate() == 0) return; }
        } else {
            int cur; try (PreparedStatement p = c.prepareStatement("SELECT status FROM pay WHERE id = ? FOR UPDATE")) { p.setInt(1, e.payment()); try (ResultSet rs = p.executeQuery()) { rs.next(); cur = rs.getInt(1); } }
            try (PreparedStatement p = c.prepareStatement("UPDATE pay SET status = ?, regressions = regressions + ? WHERE id = ?")) { p.setInt(1, e.status()); p.setInt(2, e.status() < cur ? 1 : 0); p.setInt(3, e.payment()); p.executeUpdate(); }
        }
        if (e.status() >= 1) try (PreparedStatement p = c.prepareStatement("INSERT INTO " + effTable + " (payment, kind) VALUES (?, ?)")) { p.setInt(1, e.payment()); p.setString(2, e.status() == 1 ? "CREDIT+RECEIPT" : "REFUND"); p.executeUpdate(); }
        if (lvl >= 3 && e.status() == 2) {                                                             // refund after a skipped capture: capture effect also owed
            try (PreparedStatement p = c.prepareStatement("INSERT INTO outbox (payment, kind) SELECT ?, 'CREDIT+RECEIPT' WHERE NOT EXISTS (SELECT 1 FROM outbox WHERE payment = ? AND kind = 'CREDIT+RECEIPT')")) { p.setInt(1, e.payment()); p.setInt(2, e.payment()); p.executeUpdate(); }
        }
    }
}
