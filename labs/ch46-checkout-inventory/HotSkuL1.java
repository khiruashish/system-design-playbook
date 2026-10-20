// HotSkuL1.java — run with (PostgreSQL 16 on :5581 db "shop", Redis 7 on :6390):
//   java -cp postgresql-42.7.4.jar -Duser.timezone=UTC HotSkuL1.java
// THE NATIONAL ₹1 ONION DEAL. 5,000 units, one SKU, and 64 concurrent checkout workers hammering it (Ch 02 flagged this
// as the case where one row approaches its lock limit). Each reservation must also be recorded durably. Four designs:
//   A) ONE ROW, atomic:   UPDATE stock SET qty = qty - 1 WHERE sku = ? AND qty > 0, + INSERT reservation, one txn
//   B) ONE ROW, locked:   SELECT … FOR UPDATE, check, UPDATE, INSERT — the lock is held across two round trips
//   C) 16 BUCKETS:        stock split across 16 rows; a worker tries a random bucket, then the others if it's empty
//   D) REDIS FRONT-GATE:  a Lua DECR-if-positive in Redis admits or rejects instantly; admitted requests then write
//                         the reservation to PostgreSQL (the database never sees the rejected ones)
// 20,000 attempts in total (4x the stock). Measured: time to sell out, reservations/s while stock lasts, p99 latency,
// OVERSOLD units (must be 0) and how many database transactions were attempted.
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class HotSkuL1 {
    static final String DB = "jdbc:postgresql://127.0.0.1:5581/shop?user=claude";
    static final int STOCK = 5_000, ATTEMPTS = 20_000, WORKERS = 64, BUCKETS = 16;
    static final String GATE = "local v = tonumber(redis.call('GET', KEYS[1]) or '0') if v > 0 then redis.call('DECR', KEYS[1]) return 1 end return 0";

    public static void main(String[] args) throws Exception {
        System.out.printf("One SKU, %,d units, %,d reservation attempts from %d concurrent workers (PostgreSQL 16, durable commits).%n%n", STOCK, ATTEMPTS, WORKERS);
        System.out.printf("   %-44s %10s %14s %11s %10s %14s %16s%n", "design", "sold", "sold out in", "reserv./s", "p99", "oversold", "DB transactions");
        System.out.printf("   %-44s %10s %14s %11s %10s %14s %16s%n", "", "", "", "", "", "", "attempted");
        String[] names = {"A) one row, atomic conditional UPDATE", "B) one row, SELECT FOR UPDATE then UPDATE", "C) 16 buckets, random then fallback", "D) Redis Lua front-gate + DB write"};
        for (int d = 0; d < 4; d++) run(d, names[d]);
        System.out.println("\n   → one row is correct and simple, and every buyer queues on its lock: ~800 reservations/s here, with a p99 over");
        System.out.println("     a quarter of a second. Holding the lock across two round trips (FOR UPDATE, then UPDATE) is slower still.");
        System.out.println("     Sixteen buckets spread the lock and sold out about 4x faster — but once stock ran out, every attempt probed all");
        System.out.println("     16 buckets before saying 'sold out', twelve times the database transactions of A. Cache a sold-out flag.");
        System.out.println("     The Redis front-gate kept the 15,000 losers away from the database entirely (5,000 transactions), yet sold out");
        System.out.println("     no faster: the winners still queue on the one row behind it. Combine them — gate plus buckets — for the");
        System.out.println("     biggest deals, and remember the gate is a second copy of the stock count that must be seeded and reconciled.");
    }

    static void run(int design, String name) throws Exception {
        try (Connection c = DriverManager.getConnection(DB); Statement s = c.createStatement()) {
            s.execute("drop table if exists reservations"); s.execute("drop table if exists stock");
            s.execute("create table stock(sku text, bucket int, qty int not null check (qty >= 0), primary key (sku, bucket))");
            s.execute("create table reservations(id bigserial primary key, sku text, bucket int, customer int, created_at timestamptz default now())");
            if (design == 2) s.execute("insert into stock select 'ONION-1KG', g, " + (STOCK / BUCKETS) + " + case when g < " + (STOCK % BUCKETS) + " then 1 else 0 end from generate_series(0," + (BUCKETS - 1) + ") g");
            else s.execute("insert into stock values ('ONION-1KG', 0, " + STOCK + ")");
        }
        String sha;
        try (Redis r = new Redis()) { r.cmd("SET", "gate:ONION-1KG", String.valueOf(STOCK)); sha = (String) r.cmd("SCRIPT", "LOAD", GATE); }
        AtomicInteger next = new AtomicInteger(), sold = new AtomicInteger(); long[] lat = new long[ATTEMPTS]; AtomicLong soldOutAt = new AtomicLong();
        AtomicInteger dbTx = new AtomicInteger(); DB_TX.set(dbTx);
        long t0 = System.nanoTime();
        try (var pool = Executors.newFixedThreadPool(WORKERS)) {
            for (int w = 0; w < WORKERS; w++) pool.submit(() -> {
                try (Connection c = DriverManager.getConnection(DB); Redis r = new Redis()) {
                    Random rnd = new Random(Thread.currentThread().threadId());
                    int i;
                    while ((i = next.getAndIncrement()) < ATTEMPTS) {
                        long s = System.nanoTime(); boolean ok = switch (design) {
                            case 0 -> atomic(c, 0, i); case 1 -> locked(c, i); case 2 -> buckets(c, rnd, i);
                            default -> ((Long) r.cmd("EVALSHA", sha, "1", "gate:ONION-1KG")) == 1 && atomic(c, 0, i);
                        };
                        lat[i] = System.nanoTime() - s;
                        if (ok) { if (sold.incrementAndGet() == STOCK) soldOutAt.set(System.nanoTime() - t0); }
                    }
                }
                return null;
            });
        }
        int left; try (Connection c = DriverManager.getConnection(DB)) { left = remaining(c); }
        int reservations; try (Connection c = DriverManager.getConnection(DB); ResultSet rs = c.createStatement().executeQuery("select count(*) from reservations")) { rs.next(); reservations = rs.getInt(1); }
        Arrays.sort(lat); double out = soldOutAt.get() / 1e9;
        System.out.printf("   %-44s %,10d %12.2f s %,11.0f %7.1f ms %,14d %,16d%n", name, reservations, out, STOCK / out, lat[(int) (ATTEMPTS * 0.99)] / 1e6,
                Math.max(0, reservations - STOCK), dbTx.get());
    }

    static int remaining(Connection c) throws SQLException { try (ResultSet rs = c.createStatement().executeQuery("select coalesce(sum(qty),0) from stock")) { rs.next(); return rs.getInt(1); } }
    static final java.util.concurrent.atomic.AtomicReference<AtomicInteger> DB_TX = new java.util.concurrent.atomic.AtomicReference<>();
    static boolean atomic(Connection c, int bucket, int customer) throws SQLException {
        DB_TX.get().incrementAndGet(); c.setAutoCommit(false);
        try (PreparedStatement u = c.prepareStatement("update stock set qty = qty - 1 where sku = 'ONION-1KG' and bucket = ? and qty > 0")) {
            u.setInt(1, bucket);
            if (u.executeUpdate() == 0) { c.rollback(); return false; }
            try (PreparedStatement ins = c.prepareStatement("insert into reservations(sku, bucket, customer) values ('ONION-1KG', ?, ?)")) { ins.setInt(1, bucket); ins.setInt(2, customer); ins.executeUpdate(); }
            c.commit(); return true;
        } catch (SQLException e) { c.rollback(); throw e; } finally { c.setAutoCommit(true); }
    }
    static boolean locked(Connection c, int customer) throws SQLException {
        DB_TX.get().incrementAndGet(); c.setAutoCommit(false);
        try {
            int qty; try (ResultSet rs = c.createStatement().executeQuery("select qty from stock where sku = 'ONION-1KG' and bucket = 0 for update")) { rs.next(); qty = rs.getInt(1); }
            if (qty <= 0) { c.rollback(); return false; }
            c.createStatement().executeUpdate("update stock set qty = qty - 1 where sku = 'ONION-1KG' and bucket = 0");
            try (PreparedStatement ins = c.prepareStatement("insert into reservations(sku, bucket, customer) values ('ONION-1KG', 0, ?)")) { ins.setInt(1, customer); ins.executeUpdate(); }
            c.commit(); return true;
        } catch (SQLException e) { c.rollback(); throw e; } finally { c.setAutoCommit(true); }
    }
    static boolean buckets(Connection c, Random rnd, int customer) throws SQLException {
        int start = rnd.nextInt(BUCKETS);
        for (int k = 0; k < BUCKETS; k++) if (atomic(c, (start + k) % BUCKETS, customer)) return true;   // all buckets empty → truly sold out
        return false;
    }

    static final class Redis implements Closeable {
        final Socket s; final OutputStream out; final InputStream in;
        Redis() throws IOException { s = new Socket("127.0.0.1", 6390); s.setTcpNoDelay(true); out = new BufferedOutputStream(s.getOutputStream()); in = new BufferedInputStream(s.getInputStream()); }
        Object cmd(String... parts) throws IOException {
            StringBuilder sb = new StringBuilder("*").append(parts.length).append("\r\n");
            for (String p : parts) { byte[] b = p.getBytes(StandardCharsets.UTF_8); sb.append('$').append(b.length).append("\r\n").append(p).append("\r\n"); }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8)); out.flush(); return read();
        }
        Object read() throws IOException {
            int type = in.read(); String line = line();
            return switch (type) {
                case '+' -> line; case ':' -> Long.parseLong(line);
                case '$' -> { int n = Integer.parseInt(line); if (n < 0) yield null; byte[] b = in.readNBytes(n); line(); yield new String(b, StandardCharsets.UTF_8); }
                case '-' -> throw new IOException(line);
                default -> throw new IOException("unexpected " + (char) type);
            };
        }
        String line() throws IOException { StringBuilder sb = new StringBuilder(); int c; while ((c = in.read()) != '\r') sb.append((char) c); in.read(); return sb.toString(); }
        public void close() throws IOException { s.close(); }
    }
}
