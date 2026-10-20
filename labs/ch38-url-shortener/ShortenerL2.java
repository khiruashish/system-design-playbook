// ShortenerL2.java — run with (PostgreSQL 16 on :5581, db "shop"):
//   java -cp postgresql-42.7.4.jar -Dsun.net.httpserver.nodelay=true ShortenerL2.java
// A REAL SHORTENER: JDK HttpServer + PostgreSQL. Create links with POST /api/links, follow them with GET /{code}.
// What makes it production-shaped rather than a toy:
//   • codes come from a database SEQUENCE handed out in BLOCKS of 1,000 per node (one round-trip per 1,000 links), then a
//     keyed permutation (L1 strategy D) — unique by construction, not guessable
//   • create is IDEMPOTENT per (owner, url): the notification service retries on timeouts, and must not mint a second code
//   • destinations are VALIDATED: https only, host on an allow-list — a public shortener on our domain is a phishing kit,
//     and India's DLT rules for SMS require that links in templates resolve to registered domains
//   • expired links return 410 Gone, unknown ones 404; redirects are 302 (not 301) so every click reaches us (analytics)
//   • a small read-through cache in front of the database for the redirect path
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

public class ShortenerL2 {
    static final String DB = "jdbc:postgresql://127.0.0.1:5581/shop?user=claude";
    static final Set<String> ALLOWED_HOSTS = Set.of("grocery.in", "track.grocery.in", "pay.grocery.in", "offers.grocery.in");
    static final AtomicLong dbReads = new AtomicLong();

    // ---- code minting: block-allocated sequence + permutation (see CodeGenL1 strategy D) ----
    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    static final long SPACE7 = 3_521_614_606_208L, HALF = 1L << 21;
    static final long[] KEYS = {0x5DEECE66DL, 0x2545F491L, 0x9E3779B9L, 0x7F4A7C15L};
    static long round(long x, long k) { long h = (x * 0x9E3779B97F4A7C15L) ^ k; h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32; return h & (HALF - 1); }
    static long feistel(long v) { long l = v >>> 21, r = v & (HALF - 1); for (long k : KEYS) { long t = l ^ round(r, k); l = r; r = t; } return (l << 21) | r; }
    static String code(long counter) { long x = feistel(counter); while (x >= SPACE7) x = feistel(x); char[] c = new char[7]; for (int i = 6; i >= 0; i--) { c[i] = ALPHABET.charAt((int) (x % 62)); x /= 62; } return new String(c); }

    static long blockNext = 0, blockEnd = 0; static int blocksFetched = 0;
    static synchronized long nextCounter(Connection c) throws SQLException {
        if (blockNext == blockEnd) {
            try (ResultSet rs = c.createStatement().executeQuery("select nextval('link_block_seq')")) { rs.next(); blockNext = rs.getLong(1) * 1000; blockEnd = blockNext + 1000; blocksFetched++; }
        }
        return blockNext++;
    }

    // ---- validation ----
    static String validate(String url) {
        URI u;
        try { u = new URI(url); } catch (URISyntaxException e) { return "not a valid URL"; }
        if (!"https".equals(u.getScheme())) return "only https destinations are allowed";
        if (u.getRawUserInfo() != null) return "credentials in URL are not allowed";
        if (u.getHost() == null || !ALLOWED_HOSTS.contains(u.getHost().toLowerCase())) return "destination host '" + u.getHost() + "' is not on the allow-list";
        if (url.length() > 2048) return "URL too long";
        return null;
    }

    // ---- read-through cache (bounded LRU) ----
    record Link(String url, Instant expires) {}
    static final Map<String, Link> cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, Link> e) { return size() > 5_000; } });

    static BlockingQueue<Connection> pool = new LinkedBlockingQueue<>();

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 16; i++) pool.add(DriverManager.getConnection(DB));
        try (Statement s = pool.peek().createStatement()) {
            s.execute("drop table if exists links"); s.execute("drop sequence if exists link_block_seq");
            s.execute("create sequence link_block_seq start 100000");
            s.execute("""
                create table links(code char(7) primary key, long_url text not null, owner text not null,
                  url_hash bytea not null, created_at timestamptz not null default now(), expires_at timestamptz,
                  unique(owner, url_hash))""");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 512);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api/links", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String url = field(body, "url"), owner = field(body, "owner"), ttl = field(body, "ttlDays");
            String err = url == null ? "url is required" : validate(url);
            if (err != null) { send(ex, 422, "{\"error\":\"" + err + "\"}"); return; }
            Connection c = take();
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
                String code = code(nextCounter(c));
                try (PreparedStatement ps = c.prepareStatement("insert into links(code,long_url,owner,url_hash,expires_at) values (?,?,?,?,?) on conflict (owner,url_hash) do nothing")) {
                    ps.setString(1, code); ps.setString(2, url); ps.setString(3, owner); ps.setBytes(4, hash);
                    ps.setObject(5, ttl == null ? null : OffsetDateTime.now(ZoneOffset.UTC).plusDays(Long.parseLong(ttl)));
                    if (ps.executeUpdate() == 1) { send(ex, 201, "{\"code\":\"" + code + "\",\"short\":\"https://gro.cr/" + code + "\"}"); return; }
                }
                try (PreparedStatement ps = c.prepareStatement("select code from links where owner=? and url_hash=?")) {   // idempotent replay
                    ps.setString(1, owner); ps.setBytes(2, hash); ResultSet rs = ps.executeQuery(); rs.next();
                    send(ex, 200, "{\"code\":\"" + rs.getString(1) + "\",\"short\":\"https://gro.cr/" + rs.getString(1) + "\",\"existing\":true}");
                }
            } catch (Exception e) { send(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}"); } finally { pool.add(c); }
        });
        server.createContext("/", ex -> {
            String code = ex.getRequestURI().getPath().substring(1);
            if (!code.matches("[0-9A-Za-z]{7}")) { send(ex, 404, ""); return; }
            Link l = cache.get(code);
            if (l == null) {
                Connection c = take();
                try (PreparedStatement ps = c.prepareStatement("select long_url, expires_at from links where code=?")) {
                    dbReads.incrementAndGet(); ps.setString(1, code); ResultSet rs = ps.executeQuery();
                    if (!rs.next()) { send(ex, 404, ""); return; }
                    Timestamp t = rs.getTimestamp(2); l = new Link(rs.getString(1), t == null ? null : t.toInstant()); cache.put(code, l);
                } catch (SQLException e) { send(ex, 500, ""); return; } finally { pool.add(c); }
            }
            if (l.expires() != null && l.expires().isBefore(Instant.now())) { send(ex, 410, ""); return; }
            ex.getResponseHeaders().add("Location", l.url()); ex.getResponseHeaders().add("Cache-Control", "private, max-age=0");
            ex.sendResponseHeaders(302, -1); ex.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
                .executor(Executors.newVirtualThreadPerTaskExecutor()).build();

        System.out.println("Shortener on " + base + " (PostgreSQL 16, JDK HttpServer on virtual threads)\n");
        System.out.println("   Creating links:");
        String track = post(http, base, "{\"url\":\"https://track.grocery.in/orders/ORD-88213?t=f3a9\",\"owner\":\"notification-svc\",\"ttlDays\":\"30\"}");
        post(http, base, "{\"url\":\"https://track.grocery.in/orders/ORD-88213?t=f3a9\",\"owner\":\"notification-svc\",\"ttlDays\":\"30\"}");
        post(http, base, "{\"url\":\"https://offers.grocery.in/diwali?utm_source=sms\",\"owner\":\"marketing\"}");
        post(http, base, "{\"url\":\"http://track.grocery.in/orders/ORD-1\",\"owner\":\"notification-svc\"}");
        post(http, base, "{\"url\":\"https://grocery.in@evil.example/login\",\"owner\":\"marketing\"}");
        post(http, base, "{\"url\":\"https://grocery-in.example/kyc-update\",\"owner\":\"marketing\"}");
        try (Statement s = pool.peek().createStatement()) {   // an expired reminder link, created 31 days ago with a 30-day TTL
            s.execute("insert into links values ('Zz0exp1','https://grocery.in/slots/remind','notification-svc','\\x00'::bytea, now()-interval '31 days', now()-interval '1 day')");
        }
        String trackCode = field(track, "code");
        System.out.println("\n   Following links:");
        for (String c : List.of(trackCode, "Zz0exp1", "aaaaaaa", "../etc")) {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(base + "/" + c)).build(), HttpResponse.BodyHandlers.discarding());
            System.out.printf("        GET /%-8s → %d %s%n", c, r.statusCode(), r.headers().firstValue("Location").map(x -> "Location: " + x).orElse(""));
        }

        // ---- load: 10,000 links, then 50,000 redirects with a skewed (Zipf-like) popularity, 200 concurrent clients ----
        List<String> codes = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(DB); PreparedStatement ps = c.prepareStatement("insert into links(code,long_url,owner,url_hash) values (?,?,?,?)")) {
            c.setAutoCommit(false);
            for (int i = 0; i < 10_000; i++) {
                String code = code(nextCounter(c)); String url = "https://track.grocery.in/orders/ORD-" + (200_000 + i);
                ps.setString(1, code); ps.setString(2, url); ps.setString(3, "bulk"); ps.setBytes(4, MessageDigest.getInstance("SHA-256").digest(url.getBytes())); ps.addBatch(); codes.add(code);
            }
            ps.executeBatch(); c.commit();
        }
        double[] cdf = new double[codes.size()]; double sum = 0;
        for (int i = 0; i < cdf.length; i++) { sum += 1.0 / (i + 1); cdf[i] = sum; }
        Random rnd = new Random(38); int total = 50_000; String[] picks = new String[total];
        for (int i = 0; i < total; i++) { double x = rnd.nextDouble() * sum; int idx = Arrays.binarySearch(cdf, x); picks[i] = codes.get(idx < 0 ? -idx - 1 : idx); }
        cache.clear(); dbReads.set(0);
        long[] lat = new long[total]; AtomicInteger ok = new AtomicInteger(); Semaphore conc = new Semaphore(200);
        long t0 = System.nanoTime();
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < total; i++) {
                final int k = i; conc.acquire();
                exec.submit(() -> {
                    try { long s = System.nanoTime();
                        HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(base + "/" + picks[k])).build(), HttpResponse.BodyHandlers.discarding());
                        lat[k] = System.nanoTime() - s; if (r.statusCode() == 302) ok.incrementAndGet();
                    } catch (Exception e) { lat[k] = Long.MAX_VALUE; } finally { conc.release(); }
                });
            }
        }
        double secs = (System.nanoTime() - t0) / 1e9; Arrays.sort(lat);
        long distinct = Arrays.stream(picks).distinct().count();
        System.out.printf("%n   Load: %,d redirects over 10,000 links (Zipf popularity, %,d distinct links hit), 200 concurrent clients%n", total, distinct);
        System.out.printf("        302s: %,d   throughput: %,.0f redirects/s   p50 %.2f ms   p99 %.2f ms   (timings vary by machine)%n",
                ok.get(), total / secs, lat[total / 2] / 1e6, lat[(int) (total * 0.99)] / 1e6);
        System.out.printf("        database reads: %,d for %,d redirects — the cache absorbed %.1f%%%n", dbReads.get(), total, 100.0 * (total - dbReads.get()) / total);
        System.out.printf("        but only %,d distinct links were requested: %,d reads were CONCURRENT misses for a code another request was%n", distinct, dbReads.get() - distinct);
        System.out.println("        already loading — a small thundering herd on cold, popular links (fix: coalesce misses per key, Ch 7)");
        System.out.printf("        counter values used: %,d via %d sequence round-trips (blocks of 1,000)%n", codes.size() + 3, blocksFetched);

        System.out.println("\n   → the retry returned the SAME code (200, existing) instead of minting a second link; http, a userinfo trick");
        System.out.println("     that really points at evil.example, and a look-alike domain were all refused before anything was stored.");
        System.out.println("     Expired links say 410 (gone for good), unknown ones 404. Redirects are 302 with no browser caching, so every");
        System.out.println("     click reaches us — the price of analytics is that we must serve every one, which is why the cache matters.");
        server.stop(0); System.exit(0);
    }

    static Connection take() { try { return pool.take(); } catch (InterruptedException e) { throw new RuntimeException(e); } }
    static String field(String json, String k) { Matcher m = Pattern.compile("\"" + k + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json); return m.find() ? m.group(1) : null; }
    static void send(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8); ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length); if (b.length > 0) ex.getResponseBody().write(b); ex.close();
    }
    static String post(HttpClient http, String base, String json) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/links")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        System.out.printf("        POST %-58s → %d %s%n", field(json, "url"), r.statusCode(), r.body());
        return r.body();
    }
}
