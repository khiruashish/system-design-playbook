// MeiliL3.java — run with: /opt/jdk25/bin/java -Duser.timezone=UTC -cp pgjdbc.jar MeiliL3.java   (uses Catalog.java)
// A real search engine (Meilisearch 1.11 on :7700) fed from real PostgreSQL (:5543).
// A. relevance on the 20 judged queries: engine defaults → + synonyms → + index-time enrichment (Hinglish/Devanagari aliases)
// B. facets and filters
// C. keeping the index in sync: outbox → indexer → engine; end-to-end lag under 200 updates/s
// D. two parallel indexers: unordered batches vs key-partitioned (per-product order preserved)
// E. zero-downtime reindex with index swap while queries keep running
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

public class MeiliL3 {
    static final String MEILI = "http://127.0.0.1:7700", KEY = "grocery-master-key-0123456789";
    static final String PG = "jdbc:postgresql://localhost:5543/orders?user=postgres";
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    static String call(String method, String path, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(MEILI + path)).header("Authorization", "Bearer " + KEY).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60)).method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
    }
    static String q(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    static long taskUid(String resp) { Matcher m = Pattern.compile("\"taskUid\":(\\d+)").matcher(resp); if (!m.find()) throw new IllegalStateException(resp); return Long.parseLong(m.group(1)); }
    static void await(long uid) throws Exception {
        while (true) {
            String t = call("GET", "/tasks/" + uid, null);
            if (t.contains("\"status\":\"succeeded\"")) return;
            if (t.contains("\"status\":\"failed\"")) throw new IllegalStateException(t);
            Thread.sleep(5);
        }
    }

    static void dropIndex(String uid) throws Exception {
        try { await(taskUid(call("DELETE", "/indexes/" + uid, null))); } catch (IllegalStateException e) { if (!e.getMessage().contains("index_not_found")) throw e; }
    }

    // index-time enrichment: aliases per product type (what a merchandising team maintains)
    static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("atta", "aata atta आटा gehun flour"), Map.entry("milk", "doodh dudh दूध"), Map.entry("curd", "dahi curd दही"),
        Map.entry("sugar", "chini cheeni चीनी"), Map.entry("tea", "chai chay चाय patti"), Map.entry("jaggery", "gur gud गुड़"),
        Map.entry("eggs", "anda ande अंडा अंडे"), Map.entry("turmeric", "haldi हल्दी"), Map.entry("cumin", "jeera जीरा"),
        Map.entry("moong-dal", "moong mung daal मूंग दाल"), Map.entry("toor-dal", "toor tur arhar daal तूर अरहर दाल"),
        Map.entry("chana-dal", "chana daal चना दाल"), Map.entry("basmati", "chawal chaval चावल"), Map.entry("potato", "aloo alu आलू"),
        Map.entry("tomato", "tamatar टमाटर tomatoes"), Map.entry("onion", "pyaz pyaaz प्याज"), Map.entry("sunflower-oil", "tel तेल cooking oil"),
        Map.entry("mustard-oil", "sarson tel सरसों तेल cooking oil"), Map.entry("groundnut-oil", "moongphali tel तेल cooking oil"),
        Map.entry("paneer", "पनीर"), Map.entry("namkeen", "नमकीन"), Map.entry("shampoo", "शैम्पू"), Map.entry("ghee", "घी"),
        Map.entry("salt", "namak नमक"), Map.entry("banana", "kela केला"), Map.entry("butter", "makhan मक्खन"));

    static String docJson(Catalog.Product p, boolean enrich, long version) {
        return "{\"id\":" + p.id() + ",\"type\":" + q(p.type()) + ",\"name\":" + q(p.name()) + ",\"brand\":" + q(p.brand()) + ",\"category\":" + q(p.category())
                + ",\"size\":" + q(p.size()) + ",\"popularity\":" + p.popularity() + ",\"in_stock\":" + p.inStock() + ",\"price_paise\":" + p.pricePaise()
                + ",\"version\":" + version + (enrich ? ",\"aliases\":" + q(ALIASES.getOrDefault(p.type(), "")) : "") + "}";
    }

    static void createIndex(String uid, List<Catalog.Product> docs, boolean synonyms, boolean enrich) throws Exception {
        dropIndex(uid);
        await(taskUid(call("POST", "/indexes", "{\"uid\":" + q(uid) + ",\"primaryKey\":\"id\"}")));
        String searchable = enrich ? "[\"name\",\"aliases\",\"brand\",\"category\",\"size\"]" : "[\"name\",\"brand\",\"category\",\"size\"]";
        String syn = synonyms ? ",\"synonyms\":{\"curd\":[\"dahi\"],\"dahi\":[\"curd\"],\"doodh\":[\"milk\"],\"chini\":[\"sugar\"],\"chawal\":[\"rice\"],\"haldi\":[\"turmeric\"],"
                + "\"gur\":[\"jaggery\"],\"anda\":[\"eggs\"],\"chai\":[\"tea\"],\"daal\":[\"dal\"],\"aata\":[\"atta\"],\"tel\":[\"oil\"]}" : "";
        await(taskUid(call("PATCH", "/indexes/" + uid + "/settings", "{\"searchableAttributes\":" + searchable
                + ",\"filterableAttributes\":[\"category\",\"brand\",\"in_stock\",\"type\"],\"sortableAttributes\":[\"popularity\",\"price_paise\"]" + syn + "}")));
        for (int i = 0; i < docs.size(); i += 5_000) {
            var sb = new StringJoiner(",", "[", "]");
            for (var p : docs.subList(i, Math.min(docs.size(), i + 5_000))) sb.add(docJson(p, enrich, 1));
            await(taskUid(call("POST", "/indexes/" + uid + "/documents", sb.toString())));
        }
    }

    static List<Integer> searchIds(String uid, String query, String extra) throws Exception {
        String r = call("POST", "/indexes/" + uid + "/search", "{\"q\":" + q(query) + ",\"limit\":10,\"attributesToRetrieve\":[\"id\"]" + extra + "}");
        List<Integer> ids = new ArrayList<>();
        Matcher m = Pattern.compile("\\{\"id\":(\\d+)\\}").matcher(r);
        while (m.find()) ids.add(Integer.parseInt(m.group(1)));
        return ids;
    }

    public static void main(String[] args) throws Exception {
        List<Catalog.Product> docs = Catalog.products(20_000);
        Map<Integer, Catalog.Product> byId = new HashMap<>(); for (var p : docs) byId.put(p.id(), p);

        System.out.println("A. Meilisearch 1.11 on the 20 judged queries (typo tolerance and prefix search are on by default)");
        System.out.printf("   %-46s %8s %13s %14s   %s%n", "configuration", "nDCG@10", "zero results", "index time", "queries still failing (nDCG < 0.5)");
        for (Object[] cfg : new Object[][]{{"engine defaults", false, false}, {"+ synonyms setting", true, false}, {"+ enrichment: aliases field (Hinglish, Hindi)", true, true}}) {
            long t0 = System.nanoTime();
            createIndex("products", docs, (Boolean) cfg[1], (Boolean) cfg[2]);
            long indexMs = (System.nanoTime() - t0) / 1_000_000;
            double sum = 0; int zero = 0; List<String> failing = new ArrayList<>();
            for (var jq : Catalog.QUERIES) {
                var ids = searchIds("products", jq.query(), "");
                var ranked = ids.stream().map(byId::get).toList();
                double n = Catalog.ndcg(jq, ranked, docs, 10); sum += n;
                if (ids.isEmpty()) zero++;
                if (n < 0.5) failing.add(jq.query());
            }
            System.out.printf("   %-46s %8.3f %13d %,11d ms   %s%n", cfg[0], sum / 20, zero, indexMs, failing.isEmpty() ? "-" : String.join(", ", failing));
        }
        String r = call("POST", "/indexes/products/search", "{\"q\":\"shampo\",\"limit\":1}");
        System.out.printf("   engine-reported processing time for 'shampo': %s ms%n", r.replaceAll(".*\"processingTimeMs\":(\\d+).*", "$1"));

        System.out.println("B. Facets and filters");
        r = call("POST", "/indexes/products/search", "{\"q\":\"masala\",\"limit\":0,\"facets\":[\"category\",\"in_stock\"]}");
        System.out.printf("   'masala' → estimatedTotalHits %s, facetDistribution %s%n", r.replaceAll(".*\"estimatedTotalHits\":(\\d+).*", "$1"),
                r.replaceAll(".*\"facetDistribution\":(\\{.*?\\}\\}).*", "$1"));
        r = call("POST", "/indexes/products/search", "{\"q\":\"masala\",\"limit\":0,\"filter\":\"category = Masala AND in_stock = true\"}");
        System.out.printf("   'masala' + filter category = Masala AND in_stock = true → %s hits%n", r.replaceAll(".*\"estimatedTotalHits\":(\\d+).*", "$1"));

        // ---------------- C + D: sync from PostgreSQL ----------------
        try (Connection c = DriverManager.getConnection(PG)) {
            try (var st = c.createStatement()) {
                st.execute("drop table if exists catalog_item, catalog_outbox");
                st.execute("create table catalog_item(id int primary key, price_paise int not null, in_stock boolean not null, version bigint not null)");
                st.execute("create table catalog_outbox(seq bigserial primary key, item_id int not null, price_paise int, in_stock boolean, version bigint, committed_at timestamptz default clock_timestamp())");
                st.execute("insert into catalog_item select id, 10000, true, 1 from generate_series(1, 20000) id");
            }
            System.out.println("C. Sync: 200 price/stock updates per second for 10 s → outbox → indexer (poll every 50 ms) → Meilisearch");
            runSync(c, 1, false, 200, 10, 20_000, 20, "1 indexer, ≤ 20/batch, waits each task");
            runSync(c, 1, false, 200, 10, 20_000, 20, "1 indexer, ≤ 20/batch, pipelined");
            runSync(c, 1, false, 200, 10, 20_000, 500, "1 indexer, ≤ 500/batch, pipelined");
            System.out.println("D. Two indexers in parallel, 20 hot products, 100 updates/s for 4 s (indexer 2 is sometimes slower)");
            runSync(c, 2, false, 100, 4, 20, 10, "2 indexers, unordered chunks");
            runSync(c, 2, true, 100, 4, 20, 10, "2 indexers, partitioned by product id");
        }

        System.out.println("E. Reindex with new settings into products_v2, then swap, while 4 clients search continuously");
        AtomicInteger ok = new AtomicInteger(), empty = new AtomicInteger(), errors = new AtomicInteger(); AtomicBoolean run = new AtomicBoolean(true);
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 4; i++) ex.submit(() -> {
                while (run.get()) {
                    try { var ids = searchIds("products", "paneer", ""); if (ids.isEmpty()) empty.incrementAndGet(); else ok.incrementAndGet(); }
                    catch (Exception e) { errors.incrementAndGet(); }
                }
                return null;
            });
            long t0 = System.nanoTime();
            createIndex("products_v2", docs, true, true);
            await(taskUid(call("POST", "/swap-indexes", "[{\"indexes\":[\"products\",\"products_v2\"]}]")));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            Thread.sleep(300); run.set(false);
            System.out.printf("   rebuild + swap took %,d ms; searches during it: %,d ok, %d empty, %d errors%n", ms, ok.get(), empty.get(), errors.get());
        }
    }

    /** Writers update catalog_item + outbox in one transaction; indexers read the outbox and push batches to the engine. */
    static void runSync(Connection admin, int indexers, boolean partitioned, int perSecond, int seconds, int hotSet, int batchSize, String label) throws Exception {
        String uid = "prices";
        dropIndex(uid);
        await(taskUid(call("POST", "/indexes", "{\"uid\":\"prices\",\"primaryKey\":\"id\"}")));
        try (var st = admin.createStatement()) { st.execute("truncate catalog_outbox"); st.execute("update catalog_item set price_paise = 10000, version = 1"); }
        AtomicBoolean writing = new AtomicBoolean(true);
        List<Long> lags = Collections.synchronizedList(new ArrayList<>());
        List<long[]> applied = Collections.synchronizedList(new ArrayList<>());          // {taskUid, productId, version} in engine order
        AtomicLong nextSeq = new AtomicLong(0);
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            ex.submit(() -> {                                                                   // writer
                try (Connection c = DriverManager.getConnection(PG)) {
                    Random rnd = new Random(5); long t0 = System.nanoTime();
                    for (int i = 0; i < perSecond * seconds; i++) {
                        long due = t0 + i * 1_000_000_000L / perSecond; while (System.nanoTime() < due) Thread.sleep(1);
                        int id = 1 + rnd.nextInt(hotSet);
                        c.setAutoCommit(false);
                        try (var ps = c.prepareStatement("update catalog_item set price_paise = ?, in_stock = ?, version = version + 1 where id = ? returning price_paise, in_stock, version")) {
                            ps.setInt(1, 5_000 + rnd.nextInt(90_000)); ps.setBoolean(2, rnd.nextDouble() > 0.1); ps.setInt(3, id);
                            var rs = ps.executeQuery(); rs.next();
                            try (var ob = c.prepareStatement("insert into catalog_outbox(item_id, price_paise, in_stock, version) values (?, ?, ?, ?)")) {
                                ob.setInt(1, id); ob.setInt(2, rs.getInt(1)); ob.setBoolean(3, rs.getBoolean(2)); ob.setLong(4, rs.getLong(3)); ob.executeUpdate();
                            }
                        }
                        c.commit();
                    }
                } finally { writing.set(false); }
                return null;
            });
            for (int w = 0; w < indexers; w++) {
                int worker = w;
                ex.submit(() -> {
                    try (Connection c = DriverManager.getConnection(PG)) {
                        long myLast = 0;
                        while (true) {
                            boolean done = !writing.get();
                            // unordered: workers claim alternating chunks of the outbox; partitioned: each worker owns item_id % n
                            String sql = partitioned
                                    ? "select seq, item_id, price_paise, in_stock, version, extract(epoch from committed_at) * 1000 from catalog_outbox where seq > ? and item_id % ? = ? order by seq limit " + batchSize
                                    : "select seq, item_id, price_paise, in_stock, version, extract(epoch from committed_at) * 1000 from catalog_outbox where seq > ? order by seq limit " + batchSize;
                            long from = partitioned ? myLast : nextSeq.get();
                            var batch = new StringJoiner(",", "[", "]"); List<Long> commits = new ArrayList<>(); List<long[]> versions = new ArrayList<>(); long maxSeq = from;
                            try (var ps = c.prepareStatement(sql)) {
                                ps.setLong(1, from); if (partitioned) { ps.setInt(2, indexers); ps.setInt(3, worker); }
                                var rs = ps.executeQuery();
                                while (rs.next()) {
                                    maxSeq = rs.getLong(1);
                                    batch.add("{\"id\":" + rs.getInt(2) + ",\"price_paise\":" + rs.getInt(3) + ",\"in_stock\":" + rs.getBoolean(4) + ",\"version\":" + rs.getLong(5) + "}");
                                    commits.add((long) rs.getDouble(6)); versions.add(new long[]{rs.getInt(2), rs.getLong(5)});
                                }
                            }
                            if (commits.isEmpty()) { if (done) break; Thread.sleep(50); continue; }
                            if (!partitioned && !nextSeq.compareAndSet(from, maxSeq)) continue;       // another worker took this chunk
                            if (worker == 1) Thread.sleep(ThreadLocalRandom.current().nextInt(400));   // indexer 2 is sometimes slower (GC, network)
                            myLast = maxSeq;
                            long task = taskUid(call("PUT", "/indexes/" + uid + "/documents", batch.toString()));   // partial update (upsert)
                            for (int i = 0; i < versions.size(); i++) applied.add(new long[]{task, versions.get(i)[0], versions.get(i)[1], commits.get(i)});
                            // pipelined: the engine queues (and auto-batches) tasks; lag is computed from each task's finishedAt afterwards
                            if (label.contains("waits")) await(task);
                        }
                    }
                    return null;
                });
            }
        }
        long lastTask = applied.stream().mapToLong(a -> a[0]).max().orElseThrow();
        await(lastTask);
        Map<Long, Long> finished = new HashMap<>();
        for (long t : applied.stream().map(a -> a[0]).distinct().toList())
            finished.put(t, java.time.Instant.parse(call("GET", "/tasks/" + t, null).replaceAll(".*\"finishedAt\":\"([^\"]+)\".*", "$1")).toEpochMilli());
        for (long[] a : applied) lags.add(finished.get(a[0]) - a[3]);
        // the engine applies tasks in taskUid order: count writes that replaced a newer version with an older one
        Map<Long, Long> current = new HashMap<>(); int regressions = 0;
        var ordered = new ArrayList<>(applied); ordered.sort(Comparator.comparingLong((long[] a) -> a[0]));
        for (long[] a : ordered) { long cur = current.getOrDefault(a[1], 0L); if (a[2] < cur) regressions++; current.put(a[1], a[2]); }
        // compare engine vs database
        int stale = 0, checked = 0;
        try (var st = admin.createStatement(); var rs = st.executeQuery("select id, price_paise, version from catalog_item where version > 1")) {
            while (rs.next()) {
                String d = call("GET", "/indexes/" + uid + "/documents/" + rs.getInt(1), null);
                checked++;
                if (!d.contains("\"price_paise\":" + rs.getInt(2) + ",") && !d.contains("\"price_paise\":" + rs.getInt(2) + "}")) stale++;
            }
        }
        long[] l = lags.stream().mapToLong(Long::longValue).sorted().toArray();
        System.out.printf("   %-40s updates %,5d | commit→searchable p50 %,6d ms, p99 %,6d ms | newer overwritten by older: %3d | stale at end: %d of %d%n",
                label, l.length, l[l.length / 2], l[(int) (l.length * 0.99)], regressions, stale, checked);
    }
}
