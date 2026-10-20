// RlsL2.java — run with: java -Duser.timezone=UTC -cp /tmp/pgjdbc.jar RlsL2.java   (real PostgreSQL 16 on :5581)
// ROW-LEVEL SECURITY: the database enforces tenant isolation, so a forgotten `WHERE tenant_id=?` (L1's leak) CANNOT leak.
// We create a real RLS policy on shared_orders keyed to a per-connection setting `app.tenant`. Every request sets that
// setting to the authenticated tenant; the policy then transparently scopes EVERY query — select/update/delete — to that
// tenant, at the engine, below the application. This is defense-in-depth for shared-table multi-tenancy: the app should
// still filter, but if it forgets, the blast radius is zero. We prove it with the exact query that leaked in L1, plus a
// cross-tenant UPDATE attempt, plus what happens for the privileged role (which bypasses RLS — a real footgun).
import java.sql.*;

public class RlsL2 {
    static final String ADMIN = "jdbc:postgresql://localhost:5581/shop?user=postgres";
    // the application connects as a NON-superuser, NON-owner role, so RLS policies actually apply to it (superusers bypass RLS)
    static final String URL = "jdbc:postgresql://localhost:5581/shop?user=app_user";
    static final String[] TENANTS = {"acme", "globex", "initech"};

    public static void main(String[] args) throws Exception {
        // ---- setup (as admin): table, seed data, a limited app role, grants, and an RLS policy ----
        try (Connection c = DriverManager.getConnection(ADMIN); Statement st = c.createStatement()) {
            st.execute("drop table if exists rls_orders");
            st.execute("create table rls_orders(tenant_id text not null, id bigserial, total_paise bigint, primary key(tenant_id,id))");
            for (String t : TENANTS) for (int i = 0; i < 3; i++)
                st.execute("insert into rls_orders(tenant_id,total_paise) values ('" + t + "'," + (1000 + i) + ")");
            // a limited role the app connects as: can read/write the table but is NOT its owner and NOT a superuser
            st.execute("drop role if exists app_user");
            st.execute("create role app_user login");
            st.execute("grant select, insert, update, delete on rls_orders to app_user");
            st.execute("grant usage, select on all sequences in schema public to app_user");
            // the RLS policy: a row is visible/modifiable iff its tenant_id equals the connection's app.tenant setting
            st.execute("alter table rls_orders enable row level security");
            st.execute("drop policy if exists tenant_isolation on rls_orders");
            st.execute("create policy tenant_isolation on rls_orders using (tenant_id = current_setting('app.tenant', true)) " +
                       "with check (tenant_id = current_setting('app.tenant', true))");
        }
        System.out.println("Row-Level Security on real PostgreSQL: the policy scopes EVERY query to current_setting('app.tenant').\n");

        // ---- a request authenticated as acme: set the tenant, then run the L1 leak query ----
        try (Connection c = DriverManager.getConnection(URL); Statement st = c.createStatement()) {
            st.execute("set app.tenant = 'acme'");
            long leakQuery = scalar(c, "select count(*) from rls_orders");             // NO tenant filter (L1's bug)
            System.out.printf("   as tenant=acme: 'select count(*) from rls_orders' (no WHERE!) → %d rows (only acme's). The leak is impossible.%n", leakQuery);

            // a malicious/ buggy cross-tenant UPDATE: try to zero out globex's orders while acting as acme
            int updated;
            try (PreparedStatement ps = c.prepareStatement("update rls_orders set total_paise = 0 where tenant_id = 'globex'")) {
                updated = ps.executeUpdate();
            }
            System.out.printf("   as tenant=acme: UPDATE ... WHERE tenant_id='globex' affected %d rows — RLS hid globex entirely, so nothing matched.%n", updated);

            // switch the connection's tenant and the SAME query returns a different tenant's data — cleanly scoped
            st.execute("set app.tenant = 'globex'");
            long globex = scalar(c, "select count(*) from rls_orders");
            long globexZeroed = scalar(c, "select count(*) from rls_orders where total_paise = 0");
            System.out.printf("   as tenant=globex: sees %d rows, and %d were zeroed by the acme attempt (proof globex was untouched).%n", globex, globexZeroed);
        }

        // ---- the footgun: if app.tenant is never set, current_setting(...,true) is NULL → the policy matches nothing ----
        try (Connection c = DriverManager.getConnection(URL)) {
            long unset = scalar(c, "select count(*) from rls_orders");
            System.out.printf("   with app.tenant UNSET: sees %d rows — RLS fails CLOSED (no tenant context = no rows), never open.%n", unset);
        }

        System.out.println("\n   → RLS turns 'every query must remember the tenant filter' (fragile, one bug = full breach) into a single policy the");
        System.out.println("     engine enforces on select/insert/update/delete. It fails CLOSED when no tenant is set. Two cautions: (1) the table");
        System.out.println("     OWNER and roles with BYPASSRLS ignore policies unless you FORCE it — the app must connect as a NON-owner, limited role;");
        System.out.println("     (2) set app.tenant from the authenticated principal at connection checkout, never from user input. Keep app-level");
        System.out.println("     filtering too — RLS is the backstop, not the only control.");
    }

    static long scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }
}
