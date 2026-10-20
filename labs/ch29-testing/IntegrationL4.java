// IntegrationL4.java — run with: java -cp /tmp/pgjdbc.jar IntegrationL4.java   (real PostgreSQL 16 on :5581)
// INTEGRATION TESTING against a REAL database (what Testcontainers gives you when Docker is available — here we use a
// real local PostgreSQL directly). Three things unit tests with a mocked repository CANNOT do:
//   A) ISOLATION: run each test inside a transaction that is ROLLED BACK, so tests don't pollute each other or the DB.
//   B) CATCH A REAL SCHEMA BUG: a missing UNIQUE constraint lets a duplicate email through — a mock would never notice,
//      because the mock doesn't enforce constraints. The real DB does, so the integration test finds it.
//   C) FAULT INJECTION: kill the DB connection mid-transaction (pg_terminate_backend) and assert the app fails cleanly
//      and rolls back — exercising the error path that only real failures trigger.
import java.sql.*;

public class IntegrationL4 {
    static final String URL = "jdbc:postgresql://localhost:5581/shop?user=postgres";
    static int pass = 0, fail = 0;
    static void assertThat(String name, boolean cond) {
        System.out.printf("      [%s] %s%n", cond ? "PASS" : "FAIL", name);
        if (cond) pass++; else fail++;
    }

    public static void main(String[] args) throws Exception {
        try (Connection setup = DriverManager.getConnection(URL); Statement st = setup.createStatement()) {
            st.execute("drop table if exists users");
            st.execute("create table users(id bigserial primary key, email text not null, name text)");
        }

        System.out.println("Integration testing against REAL PostgreSQL 16 (not a mock). Three capabilities a mock can't give you:\n");

        // ---- A) test isolation via transaction rollback ----
        System.out.println("   A) Isolation: each test runs in a transaction that is rolled back — the table is untouched afterward.");
        long before = count();
        for (int i = 0; i < 5; i++) {
            try (Connection c = DriverManager.getConnection(URL)) {
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement("insert into users(email,name) values (?,?)")) {
                    ps.setString(1, "test" + i + "@shop.in"); ps.setString(2, "Test " + i); ps.executeUpdate();
                }
                // ... the test would assert things here against its own inserted rows ...
                c.rollback();     // undo everything this test did
            }
        }
        long after = count();
        assertThat("5 tests each inserted a row then rolled back; row count unchanged (" + before + " == " + after + ")", before == after);

        // ---- B) a real integration test that catches a MISSING UNIQUE CONSTRAINT ----
        System.out.println("\n   B) Catch a real schema bug: 'registering the same email twice must fail'.");
        System.out.println("      Run 1 — against the table AS SHIPPED (no unique constraint on email):");
        assertThat("duplicate email is rejected", duplicateEmailRejected());

        System.out.println("      Run 2 — after the migration adds `UNIQUE (email)`:");
        try (Connection c = DriverManager.getConnection(URL); Statement st = c.createStatement()) {
            st.execute("delete from users");
            st.execute("create unique index users_email_uq on users(email)");
        }
        assertThat("duplicate email is rejected", duplicateEmailRejected());

        // ---- C) fault injection: kill the connection mid-transaction ----
        System.out.println("\n   C) Fault injection: terminate the DB backend mid-transaction; the app must fail cleanly and not half-commit.");
        boolean cleanFailure = false, noPartialWrite;
        long rowsBeforeFault = count();
        try (Connection victim = DriverManager.getConnection(URL)) {
            victim.setAutoCommit(false);
            int pid; try (Statement s = victim.createStatement(); ResultSet rs = s.executeQuery("select pg_backend_pid()")) { rs.next(); pid = rs.getInt(1); }
            try (PreparedStatement ps = victim.prepareStatement("insert into users(email,name) values (?,?)")) {
                ps.setString(1, "midtxn@shop.in"); ps.setString(2, "Mid Txn"); ps.executeUpdate();   // written but NOT committed
            }
            // a "network partition / crash": kill this very backend from another connection, then try to commit
            try (Connection killer = DriverManager.getConnection(URL); Statement s = killer.createStatement()) {
                s.execute("select pg_terminate_backend(" + pid + ")");
            }
            try { victim.commit(); }
            catch (SQLException e) { cleanFailure = true; System.out.println("      → commit after backend kill threw, as it must: " + e.getClass().getSimpleName() + " (" + e.getMessage().strip().split("\n")[0] + ")"); }
        } catch (SQLException outer) { cleanFailure = true; }
        long rowsAfterFault = count();
        noPartialWrite = (rowsAfterFault == rowsBeforeFault);
        assertThat("the killed transaction surfaced an error to the app", cleanFailure);
        assertThat("no partial write survived (uncommitted row was discarded: " + rowsBeforeFault + " == " + rowsAfterFault + ")", noPartialWrite);

        System.out.printf("%n   Result: %d passed, %d failed.%n", pass, fail);
        System.out.println("   → the mock-free integration test caught a missing UNIQUE constraint (Run 1 FAILED, Run 2 PASSED after the");
        System.out.println("     migration), proved test isolation via rollback, and verified the app's behaviour when the database dies");
        System.out.println("     mid-transaction — none of which a mocked repository can tell you, because a mock enforces nothing and never fails.");
    }

    // the "registration" use case + its assertion; returns true if a duplicate was correctly rejected
    static boolean duplicateEmailRejected() throws Exception {
        try (Connection c = DriverManager.getConnection(URL)) {
            register(c, "dup@shop.in", "First");
            try { register(c, "dup@shop.in", "Second"); return false; }   // if this SUCCEEDS, the constraint is missing → bug
            catch (SQLException e) { return true; }                       // rejected → correct
        } finally {
            try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) { s.execute("delete from users where email='dup@shop.in'"); }
        }
    }
    static void register(Connection c, String email, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("insert into users(email,name) values (?,?)")) {
            ps.setString(1, email); ps.setString(2, name); ps.executeUpdate();
        }
    }
    static long count() throws Exception { try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("select count(*) from users")) { rs.next(); return rs.getLong(1); } }
}
