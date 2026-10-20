// SlotBookingL2.java — run with (PostgreSQL on :5581, db shop):
//   java -Duser.timezone=UTC -cp /tmp/eslab/postgresql-42.7.4.jar SlotBookingL2.java   (JDK 21)
// SLOTS OPEN FOR DIWALI EVE. 60 evening slots (12 zones × 5 hours) open at once; each has 840 rider-minutes (14 riders).
// 32 concurrent booking workers (the checkout pods) make 12,000 attempts (fixed seed): 70% of customers want one of
// 8 hot slots; each order costs 15–35 minutes (its L1 estimate); if the preferred slot is full the customer tries up to
// 2 others. Four ways to take capacity, all against a REAL PostgreSQL (fsync on):
//   B1 read the slot's used minutes, check in Java, INSERT booking, UPDATE used = <value read> + minutes  (READ COMMITTED)
//   B2 the same logic in a SERIALIZABLE transaction, retried on serialization failure (SQLState 40001)
//   B3 one conditional statement: UPDATE slot SET used = used + m WHERE id = ? AND used + m <= cap — then INSERT booking
//   B4 event-sourced: rebuild the slot from its events (SUM, MAX(version)), append event version+1; the primary key
//      (slot_id, version) rejects a concurrent append at the same version → reload and retry (optimistic concurrency)
// Measured: bookings accepted, slots over capacity (audited from the booking/event rows), retries, attempts/s, p99.
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class SlotBookingL2 {
    static final String URL = "jdbc:postgresql://localhost:5581/shop?user=claude";
    static final int SLOTS = 60, CAP = 840, WORKERS = 32, ATTEMPTS = 12_000;
    record Attempt(int customer, int[] slots, int minutes) {}

    public static void main(String[] args) throws Exception {
        Random r = new Random(48); List<Attempt> work = new ArrayList<>();
        for (int i = 0; i < ATTEMPTS; i++) {
            int pref = r.nextDouble() < 0.7 ? r.nextInt(8) : 8 + r.nextInt(SLOTS - 8);
            work.add(new Attempt(i, new int[]{pref, r.nextInt(SLOTS), r.nextInt(SLOTS)}, 15 + r.nextInt(21)));
        }
        System.out.printf("60 slots × 840 rider-minutes; %d workers; %,d booking attempts (70%% want 8 hot slots); real PostgreSQL.%n%n", WORKERS, ATTEMPTS);
        System.out.printf("   %-58s %9s %12s %9s %11s %9s%n", "booking method", "booked", "slots over", "retries", "attempts/s", "p99");
        System.out.printf("   %-58s %9s %12s %9s %11s %9s%n", "", "", "capacity", "", "", "");
        String[] names = {"B1) read, check in Java, write back (READ COMMITTED)", "B2) same, SERIALIZABLE + retry",
                "B3) conditional UPDATE ... WHERE used + m <= cap", "B4) event-sourced append, PK(slot, version) + retry"};
        for (int m = 0; m < 4; m++) run(m, names[m], work);
        System.out.println("\n   → B1 is the classic check-then-act race: two workers read the same 'used', both see room, both book. SERIALIZABLE");
        System.out.println("     detects it but pays in aborted transactions on exactly the hot slots. A conditional UPDATE makes the check and");
        System.out.println("     the write one atomic step — the row lock serialises bookings per slot and nothing retries. The event-sourced");
        System.out.println("     aggregate is equally correct (the version key is the guard) and keeps who-took-what as history (L3); its");
        System.out.println("     retries on hot slots are the price of optimistic concurrency.");
    }

    static void setup(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS slot_booking, slot, slot_event");
            s.execute("CREATE TABLE slot (id int PRIMARY KEY, cap int NOT NULL, used int NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE slot_booking (id bigserial PRIMARY KEY, slot_id int NOT NULL, customer int NOT NULL, minutes int NOT NULL)");
            s.execute("CREATE TABLE slot_event (slot_id int, version int, customer int NOT NULL, minutes int NOT NULL, PRIMARY KEY (slot_id, version))");
            s.execute("INSERT INTO slot SELECT g, " + CAP + ", 0 FROM generate_series(0," + (SLOTS - 1) + ") g");
        }
    }

    static void run(int method, String name, List<Attempt> work) throws Exception {
        try (Connection c = DriverManager.getConnection(URL)) { setup(c); }
        AtomicInteger next = new AtomicInteger(), booked = new AtomicInteger(); AtomicLong retries = new AtomicLong();
        long[] lat = new long[work.size()];
        ExecutorService ex = Executors.newFixedThreadPool(WORKERS); List<Future<?>> fs = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int w = 0; w < WORKERS; w++) fs.add(ex.submit(() -> {
            try (Connection c = DriverManager.getConnection(URL)) {
                c.setAutoCommit(false);
                if (method == 1) c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                int i;
                while ((i = next.getAndIncrement()) < work.size()) {
                    Attempt a = work.get(i); long s0 = System.nanoTime();
                    for (int slot : a.slots()) { if (book(c, method, slot, a, retries)) { booked.incrementAndGet(); break; } }
                    lat[i] = System.nanoTime() - s0;
                }
            }
            return null;
        }));
        for (Future<?> f : fs) f.get(); ex.shutdown();
        double secs = (System.nanoTime() - t0) / 1e9;
        int over = 0;
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(method == 3 ? "SELECT count(*) FROM (SELECT slot_id FROM slot_event GROUP BY slot_id HAVING sum(minutes) > " + CAP + ") x"
                     : "SELECT count(*) FROM (SELECT slot_id FROM slot_booking GROUP BY slot_id HAVING sum(minutes) > " + CAP + ") x")) { rs.next(); over = rs.getInt(1); }
        Arrays.sort(lat);
        System.out.printf("   %-58s %,9d %12d %,9d %,11.0f %6.1f ms%n", name, booked.get(), over, retries.get(), work.size() / secs, lat[(int) (lat.length * 0.99)] / 1e6);
    }

    static boolean book(Connection c, int method, int slot, Attempt a, AtomicLong retries) throws SQLException {
        while (true) {
            try {
                boolean ok;
                switch (method) {
                    case 0, 1 -> {
                        int used;
                        try (PreparedStatement p = c.prepareStatement("SELECT used FROM slot WHERE id = ?")) { p.setInt(1, slot); try (ResultSet rs = p.executeQuery()) { rs.next(); used = rs.getInt(1); } }
                        ok = used + a.minutes() <= CAP;
                        if (ok) {
                            insertBooking(c, slot, a);
                            try (PreparedStatement p = c.prepareStatement("UPDATE slot SET used = ? WHERE id = ?")) { p.setInt(1, used + a.minutes()); p.setInt(2, slot); p.executeUpdate(); }
                        }
                    }
                    case 2 -> {
                        try (PreparedStatement p = c.prepareStatement("UPDATE slot SET used = used + ? WHERE id = ? AND used + ? <= cap")) {
                            p.setInt(1, a.minutes()); p.setInt(2, slot); p.setInt(3, a.minutes()); ok = p.executeUpdate() == 1;
                        }
                        if (ok) insertBooking(c, slot, a);
                    }
                    default -> {
                        int version, used;
                        try (PreparedStatement p = c.prepareStatement("SELECT coalesce(max(version),0), coalesce(sum(minutes),0) FROM slot_event WHERE slot_id = ?")) {
                            p.setInt(1, slot); try (ResultSet rs = p.executeQuery()) { rs.next(); version = rs.getInt(1); used = rs.getInt(2); }
                        }
                        ok = used + a.minutes() <= CAP;
                        if (ok) try (PreparedStatement p = c.prepareStatement("INSERT INTO slot_event VALUES (?,?,?,?)")) {
                            p.setInt(1, slot); p.setInt(2, version + 1); p.setInt(3, a.customer()); p.setInt(4, a.minutes()); p.executeUpdate();
                        }
                    }
                }
                c.commit(); return ok;
            } catch (SQLException e) {
                c.rollback();
                if ("40001".equals(e.getSQLState()) || "23505".equals(e.getSQLState()) || "40P01".equals(e.getSQLState())) { retries.incrementAndGet(); continue; }
                throw e;
            }
        }
    }
    static void insertBooking(Connection c, int slot, Attempt a) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO slot_booking (slot_id, customer, minutes) VALUES (?,?,?)")) { p.setInt(1, slot); p.setInt(2, a.customer()); p.setInt(3, a.minutes()); p.executeUpdate(); }
    }
}
