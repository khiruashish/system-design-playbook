// IsolationL1.java — run with: java -Duser.timezone=UTC -cp /tmp/pgjdbc.jar IsolationL1.java   (real PostgreSQL 16 on :5581)
// The three canonical multi-tenant DATA ISOLATION models, built on a real database, and the failure that motivates the
// stronger ones: a CROSS-TENANT LEAK. Three tenants (acme, globex, initech) share the platform. We store each tenant's
// orders three ways and show what a query that FORGETS the tenant boundary returns in each:
//   A) SHARED TABLE  — one `orders` table with a tenant_id column. Cheapest, but isolation depends entirely on every
//                      query remembering `WHERE tenant_id = ?`. Forget it once and you leak every tenant's data.
//   B) SCHEMA-PER-TENANT — one schema per tenant (acme.orders, globex.orders...). The search_path scopes queries; a
//                      forgotten filter can't reach another tenant's schema. Middle ground on cost and isolation.
//   C) DATABASE-PER-TENANT — a separate database per tenant. Strongest isolation (and easiest per-tenant backup/restore
//                      and residency), highest overhead. A connection to one DB literally cannot see another's data.
import java.sql.*;

public class IsolationL1 {
    static final String BASE = "jdbc:postgresql://localhost:5581/";
    static final String URL = BASE + "shop?user=postgres";
    static final String[] TENANTS = {"acme", "globex", "initech"};

    public static void main(String[] args) throws Exception {
        System.out.println("Three multi-tenant isolation models on real PostgreSQL 16. Tenants: acme, globex, initech.\n");
        sharedTable();
        schemaPerTenant();
        databasePerTenant();
        System.out.println("\n   → SHARED TABLE is cheapest but a single forgotten `WHERE tenant_id=?` leaks every tenant (see the bug below);");
        System.out.println("     mitigate with Row-Level Security (L2). SCHEMA-PER-TENANT scopes via search_path so a forgotten filter stays");
        System.out.println("     inside the tenant. DATABASE-PER-TENANT is strongest (separate backups, residency, blast radius) but costliest");
        System.out.println("     to run at 10,000 tenants. Most SaaS uses shared-table + RLS for small tenants and dedicated DBs for large ones.");
    }

    // ---------- A) SHARED TABLE with tenant_id ----------
    static void sharedTable() throws Exception {
        System.out.println("   A) SHARED TABLE (one table, tenant_id column):");
        try (Connection c = DriverManager.getConnection(URL); Statement st = c.createStatement()) {
            st.execute("drop table if exists shared_orders");
            st.execute("create table shared_orders(tenant_id text not null, id bigserial, total_paise bigint, primary key(tenant_id,id))");
            for (String t : TENANTS)
                for (int i = 0; i < 3; i++)
                    st.execute("insert into shared_orders(tenant_id,total_paise) values ('" + t + "'," + (1000 + i) + ")");
            // the CORRECT, scoped query for acme
            long acme = scalar(c, "select count(*) from shared_orders where tenant_id='acme'");
            // the BUG: a repository method that forgot the tenant filter
            long leaked = scalar(c, "select count(*) from shared_orders");
            System.out.printf("        correct query (WHERE tenant_id='acme') sees %d rows.%n", acme);
            System.out.printf("        BUG — a query that forgot the tenant filter sees %d rows (ALL tenants) → cross-tenant leak.%n", leaked);
        }
    }

    // ---------- B) SCHEMA-PER-TENANT ----------
    static void schemaPerTenant() throws Exception {
        System.out.println("   B) SCHEMA-PER-TENANT (acme.orders, globex.orders, ...):");
        try (Connection c = DriverManager.getConnection(URL); Statement st = c.createStatement()) {
            for (String t : TENANTS) {
                st.execute("drop schema if exists " + t + " cascade");
                st.execute("create schema " + t);
                st.execute("create table " + t + ".orders(id bigserial primary key, total_paise bigint)");
                for (int i = 0; i < 3; i++) st.execute("insert into " + t + ".orders(total_paise) values (" + (2000 + i) + ")");
            }
        }
        // a request for acme sets its search_path; the SAME "forgot the filter" query is now safe
        try (Connection c = DriverManager.getConnection(URL); Statement st = c.createStatement()) {
            st.execute("set search_path to acme");
            long acme = scalar(c, "select count(*) from orders");            // no tenant filter at all...
            System.out.printf("        request scoped to acme (search_path=acme): 'select count(*) from orders' sees %d rows — only acme's.%n", acme);
            st.execute("set search_path to globex");
            long globex = scalar(c, "select count(*) from orders");
            System.out.printf("        same query after switching search_path to globex: %d rows — only globex's. The filter can't be forgotten.%n", globex);
        }
    }

    // ---------- C) DATABASE-PER-TENANT ----------
    static void databasePerTenant() throws Exception {
        System.out.println("   C) DATABASE-PER-TENANT (tenant_acme, tenant_globex, ...):");
        try (Connection c = DriverManager.getConnection(URL); Statement st = c.createStatement()) {
            for (String t : TENANTS) {
                st.execute("drop database if exists tenant_" + t);
                st.execute("create database tenant_" + t);
            }
        }
        for (String t : TENANTS) {
            try (Connection c = DriverManager.getConnection(BASE + "tenant_" + t + "?user=postgres"); Statement st = c.createStatement()) {
                st.execute("create table orders(id bigserial primary key, total_paise bigint)");
                for (int i = 0; i < 3; i++) st.execute("insert into orders(total_paise) values (" + (3000 + i) + ")");
            }
        }
        // a connection to tenant_acme physically cannot query tenant_globex.orders
        try (Connection c = DriverManager.getConnection(BASE + "tenant_acme?user=postgres")) {
            long acme = scalar(c, "select count(*) from orders");
            System.out.printf("        connected to database tenant_acme: sees %d rows.%n", acme);
            try (Statement st = c.createStatement()) { st.executeQuery("select * from tenant_globex.orders"); }
            catch (SQLException e) { System.out.printf("        attempt to read tenant_globex from the acme connection: REJECTED (%s) — cross-DB access is impossible.%n", e.getMessage().strip().split("\n")[0]); }
        }
    }

    static long scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }
}
