// LedgerL1.java — run with (PostgreSQL 16 on :5581, db "shop"):
//   java -cp postgresql-42.7.4.jar -Duser.timezone=UTC LedgerL1.java
// MONEY UNDER CONCURRENCY. The grocery wallet (cashback + refunds-to-wallet) is real money. 16 threads run 20,000
// operations against 200 customer wallets (half the traffic on 10 busy ones): wallet payments (must not overdraw),
// refunds back to the wallet and top-ups. Three designs, all on real PostgreSQL with durable commits:
//   A) BALANCE COLUMN, read-then-write: SELECT balance; if enough, UPDATE balance = new value   (READ COMMITTED)
//   B) BALANCE COLUMN + JOURNAL, locked: SELECT … FOR UPDATE, then update the balance and insert double-entry legs
//   C) APPEND-ONLY DOUBLE-ENTRY JOURNAL: balances are SUM(legs); insert legs under SERIALIZABLE, retry on conflict
// Then we audit: does every wallet's balance equal what the successful operations imply? Any negative wallet? Does
// every journal entry balance (debits = credits)?
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class LedgerL1 {
    static final String DB = "jdbc:postgresql://127.0.0.1:5581/shop?user=claude";
    static final int WALLETS = 200, OPS = 20_000, THREADS = 16; static final long START = 50_000;   // ₹500.00 in paise

    record Op(int wallet, long delta, String kind) {}   // delta applied to the wallet (negative = payment)

    public static void main(String[] args) throws Exception {
        Random r = new Random(45); List<Op> ops = new ArrayList<>();
        for (int i = 0; i < OPS; i++) {
            int w = r.nextBoolean() ? r.nextInt(10) : r.nextInt(WALLETS); double x = r.nextDouble();
            ops.add(x < 0.6 ? new Op(w, -(1_000 + r.nextInt(20_000)), "payment") : x < 0.85 ? new Op(w, 1_000 + r.nextInt(10_000), "refund") : new Op(w, 5_000 + r.nextInt(20_000), "topup"));
        }
        System.out.printf("%,d wallet operations (60%% payments, 25%% refunds, 15%% top-ups), %d threads, %d wallets, PostgreSQL 16.%n%n", OPS, THREADS, WALLETS);
        System.out.printf("   %-50s %8s %9s %8s %9s %13s %9s %11s %15s%n", "design", "ops/s", "declined", "retries", "wrong", "money created", "negative", "unbalanced", "audit trail");
        System.out.printf("   %-50s %8s %9s %8s %9s %13s %9s %11s %15s%n", "", "", "(no funds)", "", "balances", "or destroyed", "wallets", "entries", "");
        String[] names = {"A) balance column, read-then-write", "B) FOR UPDATE + balance + double-entry journal", "C) append-only journal, SERIALIZABLE + retry"};
        for (int d = 0; d < 3; d++) run(d, names[d], ops);
        System.out.println("\n   → the read-then-write balance loses updates as soon as two operations touch one wallet: most wallets end up");
        System.out.println("     wrong, money is silently created or destroyed, and there is no record of why. (No wallet went negative here —");
        System.out.println("     that needs two payments to pass the same stale check; it is possible, just rarer.) Locking the account row (B)");
        System.out.println("     makes every balance exact at half A's throughput. Deriving balances from the journal under SERIALIZABLE (C) is");
        System.out.println("     also exact, but on busy wallets the conflict detection turns into a retry storm — tens of thousands of retries");
        System.out.println("     and a fraction of the throughput. The usual production shape is B: an append-only, balanced journal as the");
        System.out.println("     record, plus a balance row per account updated under a row lock in the same transaction as a fast, checkable");
        System.out.println("     projection. Corrections are new entries, never UPDATEs to history.");
    }

    static void run(int design, String name, List<Op> ops) throws Exception {
        try (Connection c = DriverManager.getConnection(DB); Statement s = c.createStatement()) {
            s.execute("drop table if exists legs"); s.execute("drop table if exists entries"); s.execute("drop table if exists accounts");
            s.execute("create table accounts(id text primary key, balance bigint not null)");
            s.execute("create table entries(id bigserial primary key, kind text not null, created_at timestamptz default now())");
            s.execute("create table legs(entry_id bigint not null references entries, account text not null, amount bigint not null)");   // + credit, − debit
            s.execute("create index on legs(account)");
            s.execute("insert into accounts select 'wallet:' || g, " + START + " from generate_series(0," + (WALLETS - 1) + ") g");
            s.execute("insert into accounts values ('merchant_payable', 0), ('psp_clearing', 0), ('opening_balances', 0)");
            // opening balances as journal entries so C can derive balances from legs alone
            s.execute("insert into entries(kind) select 'opening' from generate_series(0," + (WALLETS - 1) + ")");
            s.execute("insert into legs select e.id, 'wallet:' || (e.id - 1), " + START + " from entries e");
            s.execute("insert into legs select e.id, 'opening_balances', -" + START + " from entries e");
        }
        AtomicInteger next = new AtomicInteger(), declined = new AtomicInteger(), retries = new AtomicInteger();
        long[] expected = new long[WALLETS]; Arrays.fill(expected, START);
        long t0 = System.nanoTime();
        try (var pool = Executors.newFixedThreadPool(THREADS)) {
            for (int t = 0; t < THREADS; t++) pool.submit(() -> {
                try (Connection c = DriverManager.getConnection(DB)) {
                    int i;
                    while ((i = next.getAndIncrement()) < ops.size()) {
                        Op op = ops.get(i);
                        boolean ok = switch (design) { case 0 -> readThenWrite(c, op); case 1 -> locked(c, op); default -> journal(c, op, retries); };
                        if (ok) synchronized (expected) { expected[op.wallet()] += op.delta(); } else declined.incrementAndGet();
                    }
                }
                return null;
            });
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        int wrong = 0, negative = 0; long unbalanced, drift = 0;
        try (Connection c = DriverManager.getConnection(DB)) {
            for (int w = 0; w < WALLETS; w++) {
                long bal = balance(c, "wallet:" + w, design == 2);
                if (bal != expected[w]) wrong++; if (bal < 0) negative++; drift += bal - expected[w];
            }
            try (ResultSet rs = c.createStatement().executeQuery("select count(*) from (select entry_id from legs group by entry_id having sum(amount) <> 0) x")) { rs.next(); unbalanced = rs.getLong(1); }
        }
        System.out.printf("   %-50s %,8.0f %,9d %,8d %,9d %13s %,9d %,11d %15s%n", name, ops.size() / secs, declined.get(), retries.get(), wrong,
                String.format("₹%,.2f", drift / 100.0), negative, unbalanced, design == 0 ? "none" : "every movement");
    }

    static long balance(Connection c, String acct, boolean fromLegs) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(fromLegs ? "select coalesce(sum(amount),0) from legs where account = ?" : "select balance from accounts where id = ?")) {
            ps.setString(1, acct); try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }
    static String counter(Op op) { return op.kind().equals("topup") ? "psp_clearing" : "merchant_payable"; }

    static boolean readThenWrite(Connection c, Op op) throws SQLException {
        long bal = balance(c, "wallet:" + op.wallet(), false);
        if (bal + op.delta() < 0) return false;
        try (PreparedStatement ps = c.prepareStatement("update accounts set balance = ? where id = ?")) { ps.setLong(1, bal + op.delta()); ps.setString(2, "wallet:" + op.wallet()); ps.executeUpdate(); }
        return true;
    }
    static boolean locked(Connection c, Op op) throws SQLException {
        c.setAutoCommit(false);
        try {
            long bal;
            try (PreparedStatement ps = c.prepareStatement("select balance from accounts where id = ? for update")) { ps.setString(1, "wallet:" + op.wallet()); try (ResultSet rs = ps.executeQuery()) { rs.next(); bal = rs.getLong(1); } }
            if (bal + op.delta() < 0) { c.rollback(); return false; }
            try (PreparedStatement ps = c.prepareStatement("update accounts set balance = balance + ? where id = ?")) { ps.setLong(1, op.delta()); ps.setString(2, "wallet:" + op.wallet()); ps.executeUpdate(); }
            insertEntry(c, op); c.commit(); return true;
        } catch (SQLException e) { c.rollback(); throw e; } finally { c.setAutoCommit(true); }
    }
    static boolean journal(Connection c, Op op, AtomicInteger retries) throws SQLException {
        while (true) {
            c.setAutoCommit(false); c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try {
                long bal = balance(c, "wallet:" + op.wallet(), true);
                if (bal + op.delta() < 0) { c.rollback(); return false; }
                insertEntry(c, op); c.commit(); return true;
            } catch (SQLException e) {
                c.rollback();
                if ("40001".equals(e.getSQLState())) { retries.incrementAndGet(); continue; }            // serialization failure: retry
                throw e;
            } finally { c.setAutoCommit(true); c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); }
        }
    }
    static void insertEntry(Connection c, Op op) throws SQLException {
        long id;
        try (PreparedStatement ps = c.prepareStatement("insert into entries(kind) values (?) returning id")) { ps.setString(1, op.kind()); try (ResultSet rs = ps.executeQuery()) { rs.next(); id = rs.getLong(1); } }
        try (PreparedStatement ps = c.prepareStatement("insert into legs values (?,?,?),(?,?,?)")) {
            ps.setLong(1, id); ps.setString(2, "wallet:" + op.wallet()); ps.setLong(3, op.delta());
            ps.setLong(4, id); ps.setString(5, counter(op)); ps.setLong(6, -op.delta()); ps.executeUpdate();
        }
    }
}
