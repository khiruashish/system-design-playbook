// EventStoreL2.java — run with: java -Duser.timezone=UTC -cp postgresql-42.7.4.jar EventStoreL2.java   (PostgreSQL on :5551)
// An event-sourced store-credit wallet on PostgreSQL. State = fold(events). Writes append events with an
// EXPECTED VERSION (optimistic concurrency); PRIMARY KEY (stream_id, version) rejects a concurrent append.
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class EventStoreL2 {

    // ---------- events (the source of truth) ----------
    sealed interface WalletEvent permits Opened, Credited, Debited { }
    record Opened(String customerId) implements WalletEvent { }
    record Credited(long paise, String reason) implements WalletEvent { }
    record Debited(long paise, String orderId) implements WalletEvent { }

    // ---------- the aggregate: rebuilt from events, decides new events ----------
    static final class Wallet {
        long balance; int version; boolean open;
        void apply(WalletEvent e) {
            switch (e) {
                case Opened o -> open = true;
                case Credited c -> balance += c.paise();
                case Debited d -> balance -= d.paise();
            }
            version++;
        }
        /** Business rule lives here, against state rebuilt from events. */
        List<WalletEvent> debit(long paise, String orderId) {
            if (!open) throw new IllegalStateException("wallet not open");
            if (paise > balance) throw new InsufficientFunds();
            return List.of(new Debited(paise, orderId));
        }
    }
    static final class InsufficientFunds extends RuntimeException { }
    static final class ConcurrencyConflict extends RuntimeException { }

    // ---------- the event store ----------
    static final class EventStore {
        final String url = "jdbc:postgresql://localhost:5551/eventstore";
        final boolean checkVersion;
        EventStore(boolean checkVersion) { this.checkVersion = checkVersion; }
        Connection conn() throws SQLException { return DriverManager.getConnection(url, "postgres", ""); }

        void init() throws SQLException {
            try (Connection c = conn(); Statement s = c.createStatement()) {
                s.execute("drop table if exists events");
                s.execute(checkVersion
                    ? """
                      create table events (
                        global_position bigserial,
                        stream_id text not null,
                        version int not null,
                        type text not null,
                        payload jsonb not null,
                        recorded_at timestamptz not null default now(),
                        primary key (stream_id, version))"""
                    : """
                      create table events (
                        global_position bigserial primary key,
                        stream_id text not null,
                        version int not null,
                        type text not null,
                        payload jsonb not null,
                        recorded_at timestamptz not null default now())""");
            }
        }

        Wallet load(Connection c, String stream) throws SQLException {
            Wallet w = new Wallet();
            try (var ps = c.prepareStatement("select type, payload->>'paise', payload->>'id' from events where stream_id = ? order by version")) {
                ps.setString(1, stream);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) w.apply(switch (rs.getString(1)) {
                        case "Opened" -> new Opened(rs.getString(3));
                        case "Credited" -> new Credited(Long.parseLong(rs.getString(2)), "");
                        default -> new Debited(Long.parseLong(rs.getString(2)), rs.getString(3));
                    });
                }
            }
            return w;
        }

        /** Append events after expectedVersion. With the version check, a concurrent append makes this fail. */
        void append(Connection c, String stream, int expectedVersion, List<WalletEvent> events) throws SQLException {
            c.setAutoCommit(false);
            try (var ps = c.prepareStatement("insert into events(stream_id, version, type, payload) values (?, ?, ?, ?::jsonb)")) {
                int v = expectedVersion;
                for (WalletEvent e : events) {
                    ps.setString(1, stream); ps.setInt(2, ++v); ps.setString(3, e.getClass().getSimpleName());
                    ps.setString(4, switch (e) {
                        case Opened o -> "{\"id\":\"" + o.customerId() + "\"}";
                        case Credited cr -> "{\"paise\":" + cr.paise() + "}";
                        case Debited d -> "{\"paise\":" + d.paise() + ",\"id\":\"" + d.orderId() + "\"}";
                    });
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException ex) {
                c.rollback();
                if ("23505".equals(ex.getSQLState())) throw new ConcurrencyConflict();   // unique_violation on (stream_id, version)
                throw ex;
            } finally { c.setAutoCommit(true); }
        }
    }

    static Map<String, Object> run(boolean checkVersion) throws Exception {
        EventStore store = new EventStore(checkVersion);
        store.init();
        String stream = "wallet-cust-42";
        try (Connection c = store.conn()) {
            store.append(c, stream, 0, List.of(new Opened("cust-42"), new Credited(10_000, "refund ord-900")));
        }
        AtomicInteger ok = new AtomicInteger(), rejected = new AtomicInteger(), conflicts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 16; t++) {
            int tt = t;
            fs.add(pool.submit(() -> {
                try (Connection c = store.conn()) {
                    for (int i = 0; i < 20; i++) {
                        String order = "ord-" + tt + "-" + i;
                        while (true) {                                            // load → decide → append, retry on conflict
                            Wallet w = store.load(c, stream);
                            List<WalletEvent> decided;
                            try { decided = w.debit(100, order); } catch (InsufficientFunds e) { rejected.incrementAndGet(); break; }
                            try { store.append(c, stream, w.version, decided); ok.incrementAndGet(); break; }
                            catch (ConcurrencyConflict e) { conflicts.incrementAndGet(); }
                        }
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        Map<String, Object> out = new LinkedHashMap<>();
        try (Connection c = store.conn()) {
            Wallet w = store.load(c, stream);
            out.put("debits accepted", ok.get());
            out.put("rejected (insufficient funds)", rejected.get());
            out.put("version conflicts retried", conflicts.get());
            out.put("final balance (paise)", w.balance);
            try (var rs = c.createStatement().executeQuery("select count(*) - count(distinct version) from events where stream_id = '" + stream + "'")) {
                rs.next(); out.put("events sharing a version", rs.getLong(1));
            }
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Wallet credited 10,000 paise; 16 threads each try 20 debits of 100 paise (32,000 requested)");
        System.out.println("no expected-version check: " + run(false));
        System.out.println("expected-version check   : " + run(true));

        // time travel: the balance as it was after any event, from the same log
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5551/eventstore", "postgres", "");
             var rs = c.createStatement().executeQuery("""
                 select version, type, balance from (
                   select version, type, sum(case type when 'Credited' then (payload->>'paise')::bigint
                                                       when 'Debited' then -(payload->>'paise')::bigint else 0 end)
                                   over (order by version) as balance
                     from events where stream_id = 'wallet-cust-42') running
                  where version in (1, 2, 3, 52, 101, 102) order by version""")) {
            System.out.println("Audit / time travel from the same events (running balance at selected versions):");
            while (rs.next()) System.out.printf("   v%-4d %-9s balance %,d%n", rs.getInt(1), rs.getString(2), rs.getLong(3));
        }
    }
}
