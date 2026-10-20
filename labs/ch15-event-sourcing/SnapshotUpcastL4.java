// SnapshotUpcastL4.java — run with: java -Duser.timezone=UTC -cp postgresql-42.7.4.jar SnapshotUpcastL4.java   (PostgreSQL on :5551)
// A. Loading a long-lived aggregate: full replay vs snapshot + tail.
// B. Upcasting: v1 events stored amounts in rupees as JSON decimals; v2 uses integer paise. Old events are never
//    rewritten — an upcaster converts them on read. Doing the conversion with double arithmetic loses paise.
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

public class SnapshotUpcastL4 {

    static Connection conn() throws SQLException { return DriverManager.getConnection("jdbc:postgresql://localhost:5551/eventstore", "postgres", ""); }

    record Loaded(long balance, int version, int eventsRead) { }

    /** Fold events of a wallet stream starting after `fromVersion` onto (balance, version). */
    static Loaded fold(Connection c, String stream, long balance, int fromVersion) throws SQLException {
        int v = fromVersion, n = 0;
        try (var ps = c.prepareStatement("select version, type, (payload->>'paise')::bigint from wallet_events where stream_id = ? and version > ? order by version")) {
            ps.setString(1, stream); ps.setInt(2, fromVersion);
            ps.setFetchSize(5_000);
            c.setAutoCommit(false);                                    // fetch size needs a transaction in pgjdbc
            try (var rs = ps.executeQuery()) {
                while (rs.next()) { balance += rs.getString(2).equals("Credited") ? rs.getLong(3) : -rs.getLong(3); v = rs.getInt(1); n++; }
            }
            c.commit(); c.setAutoCommit(true);
        }
        return new Loaded(balance, v, n);
    }

    static Loaded loadWithSnapshot(Connection c, String stream) throws SQLException {
        long bal = 0; int ver = 0;
        try (var ps = c.prepareStatement("select balance, version from wallet_snapshots where stream_id = ? order by version desc limit 1")) {
            ps.setString(1, stream);
            try (var rs = ps.executeQuery()) { if (rs.next()) { bal = rs.getLong(1); ver = rs.getInt(2); } }
        }
        return fold(c, stream, bal, ver);
    }

    /** Upcaster: turn any stored version of a Credited event into the current shape (paise as long). */
    static long creditedPaise(int schemaVersion, String rawAmount, boolean useDouble) {
        return switch (schemaVersion) {
            case 1 -> useDouble
                    ? (long) (Double.parseDouble(rawAmount) * 100)                     // ❌ 19.99 * 100 = 1998.9999… → 1998
                    : new BigDecimal(rawAmount).movePointRight(2).longValueExact();    // ✅ exact decimal → 1999
            case 2 -> Long.parseLong(rawAmount);
            default -> throw new IllegalArgumentException("unknown Credited schema v" + schemaVersion);
        };
    }

    public static void main(String[] args) throws Exception {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute("drop table if exists wallet_events, wallet_snapshots, legacy_events");
            s.execute("create table wallet_events (stream_id text, version int, type text, payload jsonb, primary key (stream_id, version))");
            s.execute("create table wallet_snapshots (stream_id text, version int, balance bigint, primary key (stream_id, version))");
            // a busy B2B customer's wallet: 200,000 events over two years
            s.execute("""
                insert into wallet_events
                select 'wallet-b2b-7', g, case when g % 3 = 0 then 'Debited' else 'Credited' end,
                       jsonb_build_object('paise', 100 + g % 900)
                  from generate_series(1, 200000) g""");
            // snapshot every 1,000 events (a projector or the command side writes these; they are disposable)
            s.execute("""
                insert into wallet_snapshots
                select 'wallet-b2b-7', v, sum(case when type = 'Credited' then (payload->>'paise')::bigint else -(payload->>'paise')::bigint end)
                         over (order by version)
                  from (select version as v, version, type, payload from wallet_events where stream_id = 'wallet-b2b-7') x
                """);
            s.execute("delete from wallet_snapshots where version % 1000 <> 0");
            s.execute("insert into wallet_events select 'wallet-b2b-7', 200000 + g, 'Credited', jsonb_build_object('paise', 500) from generate_series(1, 437) g");
            s.execute("analyze");
        }
        System.out.println("A. Load a wallet with 200,437 events (snapshots every 1,000 events)");
        try (Connection c = conn()) {
            for (int warm = 0; warm < 2; warm++) { fold(c, "wallet-b2b-7", 0, 0); loadWithSnapshot(c, "wallet-b2b-7"); }
            long t0 = System.nanoTime(); Loaded full = fold(c, "wallet-b2b-7", 0, 0); long tFull = System.nanoTime() - t0;
            t0 = System.nanoTime(); Loaded snap = loadWithSnapshot(c, "wallet-b2b-7"); long tSnap = System.nanoTime() - t0;
            System.out.printf("   full replay     : read %,7d events, balance %,d at v%d in %6.1f ms%n", full.eventsRead(), full.balance(), full.version(), tFull / 1e6);
            System.out.printf("   snapshot + tail : read %,7d events, balance %,d at v%d in %6.1f ms%n", snap.eventsRead(), snap.balance(), snap.version(), tSnap / 1e6);
            System.out.println("   same state: " + (full.balance() == snap.balance() && full.version() == snap.version()));
        }

        System.out.println("B. Upcasting 2019-era Credited events (v1: rupees as decimals) to v2 (paise)");
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute("create table legacy_events (id serial, schema_version int, payload jsonb)");
            s.execute("""
                insert into legacy_events(schema_version, payload)
                select 1, jsonb_build_object('amount', ((10 + g % 5000) / 100.0)::numeric(12,2)) from generate_series(1, 100000) g""");
            s.execute("insert into legacy_events(schema_version, payload) select 2, jsonb_build_object('paise', 10 + g % 5000) from generate_series(1, 100000) g");
            long exactSum = 0, doubleSum = 0, wrong = 0;
            try (var rs = s.executeQuery("select schema_version, coalesce(payload->>'amount', payload->>'paise') from legacy_events")) {
                while (rs.next()) {
                    long ok = creditedPaise(rs.getInt(1), rs.getString(2), false), bad = creditedPaise(rs.getInt(1), rs.getString(2), true);
                    exactSum += ok; doubleSum += bad; if (ok != bad) wrong++;
                }
            }
            System.out.printf("   200,000 events (100,000 v1 + 100,000 v2): BigDecimal upcaster total ₹%,.2f, double upcaster total ₹%,.2f%n", exactSum / 100.0, doubleSum / 100.0);
            System.out.printf("   events where the double upcaster lost a paisa: %,d  (e.g. \"%s\" → %d paise)%n", wrong, "19.99", creditedPaise(1, "19.99", true));
        }
    }
}
