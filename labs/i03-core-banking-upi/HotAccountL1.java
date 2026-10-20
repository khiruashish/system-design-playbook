// L1 — The hot merchant account. A large merchant's settlement account receives thousands of UPI credits per second
// while refunds (debits) must never overdraw it. Real PostgreSQL 16, fsync on, 16 client threads, 5 s per run.
// A: one balance row, UPDATE per credit/debit (debit guarded by balance >= amount).
// B: append-only journal + roll-up every 200 ms (credits never touch the hot row; debits check rolled-up balance +
//    unrolled journal rows under a per-account advisory lock, which the roll-up also takes).
// C: 16 sub-account balance rows; credits go to a random sub-account; debits take a lock on the account and draw from
//    sub-accounts in turn. Checks the invariant: final balance == opening + credits − debits, never negative.
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class HotAccountL1 {
    static final String URL = "jdbc:postgresql://localhost:5581/shop?user=claude";
    static final int THREADS = 16, SECONDS = 5, SUBS = 16;
    static final long OPENING = 1_000_000_00L;          // ₹10 lakh in paise

    public static void main(String[] a) throws Exception {
        System.out.printf("one merchant account, %d threads, %d s per run, credits ₹1–2,000; fsync on%n", THREADS, SECONDS);
        for (double share : new double[]{0.05, 0.30}) {
            DEBIT = share;
            System.out.printf("%n%s: %.0f%% debits (refunds, payouts)%n", share < 0.1 ? "normal day" : "refund storm", share * 100);
            System.out.printf("%-40s %10s %9s %9s %9s %9s %s%n", "design", "txn/s", "p50 ms", "p99 ms", "refused", "errors", "invariant");
            for (String d : new String[]{"A single balance row", "B journal + roll-up every 200 ms", "C 16 sub-account rows"}) run(d);
        }
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) { s.execute("DROP TABLE IF EXISTS acct, sub_acct, journal"); }
    }

    static volatile double DEBIT = 0.05;
    static void setup(Statement s) throws SQLException {
        s.execute("DROP TABLE IF EXISTS acct, sub_acct, journal");
        s.execute("CREATE TABLE acct (id int PRIMARY KEY, balance bigint NOT NULL CHECK (balance >= 0))");
        s.execute("CREATE TABLE sub_acct (id int, sub int, balance bigint NOT NULL CHECK (balance >= 0), PRIMARY KEY (id, sub))");
        s.execute("CREATE TABLE journal (seq bigserial PRIMARY KEY, id int NOT NULL, amount bigint NOT NULL, rolled boolean NOT NULL DEFAULT false, created timestamptz DEFAULT now())");
        s.execute("CREATE INDEX journal_unrolled ON journal (id) WHERE NOT rolled");
        s.execute("INSERT INTO acct VALUES (1, " + OPENING + ")");
        for (int i = 0; i < SUBS; i++) s.execute("INSERT INTO sub_acct VALUES (1, " + i + ", " + OPENING / SUBS + ")");
    }

    static void run(String design) throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) { setup(s); }
        char mode = design.charAt(0);
        AtomicLong n = new AtomicLong(), refused = new AtomicLong(), errors = new AtomicLong(), credited = new AtomicLong(), debited = new AtomicLong();
        ConcurrentLinkedQueue<long[]> lat = new ConcurrentLinkedQueue<>();
        long end = System.nanoTime() + SECONDS * 1_000_000_000L;
        ExecutorService ex = Executors.newFixedThreadPool(THREADS + 1); List<Future<?>> fs = new ArrayList<>();
        if (mode == 'B') fs.add(ex.submit(() -> { Connection c = DriverManager.getConnection(URL);    // roll-up worker
            try { c.setAutoCommit(false);
                while (System.nanoTime() < end + 300_000_000L) { rollUp(c); Thread.sleep(200); }
                rollUp(c);
            } finally { c.close(); } return null; }));
        for (int t = 0; t < THREADS; t++) { int tid = t; fs.add(ex.submit(() -> {
            Connection c = DriverManager.getConnection(URL); c.setAutoCommit(false);
            try {
                Random r = new Random(tid * 13L + mode); long[] mine = new long[500_000]; int k = 0;
                while (System.nanoTime() < end) {
                    boolean debit = r.nextDouble() < DEBIT; long amt = 100 + r.nextInt(200_000);
                    long t0 = System.nanoTime();
                    try {
                        boolean ok = switch (mode) { case 'A' -> a(c, debit, amt, r); case 'B' -> b(c, debit, amt); default -> cc(c, debit, amt, r); };
                        c.commit();
                        if (ok) { if (debit) debited.addAndGet(amt); else credited.addAndGet(amt); } else refused.incrementAndGet();
                    } catch (SQLException e) { c.rollback(); errors.incrementAndGet(); }
                    if (k < mine.length) mine[k++] = System.nanoTime() - t0; n.incrementAndGet();
                }
                lat.add(Arrays.copyOf(mine, k));
            } finally { c.close(); }
            return null; })); }
        for (Future<?> f : fs) f.get(); ex.shutdown();
        long[] all = lat.stream().flatMapToLong(Arrays::stream).sorted().toArray();
        long bal;
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery(mode == 'C' ? "SELECT sum(balance) FROM sub_acct" : "SELECT balance FROM acct WHERE id = 1"); rs.next(); bal = rs.getLong(1);
            if (mode == 'B') { rs = s.executeQuery("SELECT count(*) FROM journal WHERE NOT rolled"); rs.next(); if (rs.getLong(1) > 0) bal = -1; }
        }
        long expect = OPENING + credited.get() - debited.get();
        System.out.printf("%-40s %,10.0f %9.2f %9.2f %9d %9d %s%n", design, n.get() / (double) SECONDS, all[all.length / 2] / 1e6, all[(int) (all.length * 0.99)] / 1e6,
                refused.get(), errors.get(), bal == expect ? "holds" : "BROKEN (" + bal + " vs " + expect + ")");
    }

    // A: the classic hot row
    static boolean a(Connection c, boolean debit, long amt, Random r) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(debit ? "UPDATE acct SET balance = balance - ? WHERE id = 1 AND balance >= ?" : "UPDATE acct SET balance = balance + ? WHERE id = 1")) {
            p.setLong(1, amt); if (debit) p.setLong(2, amt); return p.executeUpdate() == 1; }
    }
    // B: credits are inserts only; debits serialise on an advisory lock and check the effective balance
    static boolean b(Connection c, boolean debit, long amt) throws SQLException {
        if (!debit) { try (PreparedStatement p = c.prepareStatement("INSERT INTO journal (id, amount) VALUES (1, ?)")) { p.setLong(1, amt); p.executeUpdate(); } return true; }
        try (Statement s = c.createStatement()) { s.execute("SELECT pg_advisory_xact_lock(1)"); }
        try (PreparedStatement p = c.prepareStatement("SELECT a.balance + coalesce((SELECT sum(amount) FROM journal j WHERE j.id = 1 AND NOT j.rolled), 0) FROM acct a WHERE a.id = 1")) {
            ResultSet rs = p.executeQuery(); rs.next(); if (rs.getLong(1) < amt) return false; }
        try (PreparedStatement p = c.prepareStatement("INSERT INTO journal (id, amount) VALUES (1, ?)")) { p.setLong(1, -amt); p.executeUpdate(); }
        return true;
    }
    // roll-up: fold committed, not-yet-rolled journal rows into the balance and mark them, in one statement. Marking rows
    // (instead of remembering "rolled up to seq N") avoids the sequence-gap trap: a lower seq can still be in flight
    // when a higher one has committed, and a high-water mark would skip it forever.
    static void rollUp(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("SELECT pg_advisory_xact_lock(1)");
            s.executeUpdate("WITH r AS (UPDATE journal SET rolled = true WHERE id = 1 AND NOT rolled RETURNING amount) " +
                            "UPDATE acct SET balance = balance + (SELECT coalesce(sum(amount), 0) FROM r) WHERE id = 1");
        }
        c.commit();
    }
    // C: credits spread over sub-accounts; debits lock the account and draw across sub-accounts
    static boolean cc(Connection c, boolean debit, long amt, Random r) throws SQLException {
        if (!debit) { try (PreparedStatement p = c.prepareStatement("UPDATE sub_acct SET balance = balance + ? WHERE id = 1 AND sub = ?")) { p.setLong(1, amt); p.setInt(2, r.nextInt(SUBS)); p.executeUpdate(); } return true; }
        try (Statement s = c.createStatement()) { s.execute("SELECT pg_advisory_xact_lock(1)"); }
        long need = amt;
        try (PreparedStatement p = c.prepareStatement("SELECT sub, balance FROM sub_acct WHERE id = 1 ORDER BY balance DESC FOR UPDATE")) {
            ResultSet rs = p.executeQuery(); List<long[]> subs = new ArrayList<>(); long total = 0;
            while (rs.next()) { subs.add(new long[]{rs.getInt(1), rs.getLong(2)}); total += rs.getLong(2); }
            if (total < amt) return false;
            try (PreparedStatement u = c.prepareStatement("UPDATE sub_acct SET balance = balance - ? WHERE id = 1 AND sub = ?")) {
                for (long[] sb : subs) { if (need == 0) break; long take = Math.min(need, sb[1]); if (take == 0) continue; u.setLong(1, take); u.setInt(2, (int) sb[0]); u.executeUpdate(); need -= take; }
            }
        }
        return true;
    }
}
