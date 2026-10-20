// FeatureFetchL3.java — run with (Redis 7 on :6390):  java FeatureFetchL3.java   (JDK 21, no dependencies)
// THE FEATURE-FETCH FAN-OUT. To rank a home feed the service scores ~400 candidates, and every candidate needs features:
// item-in-store features (price, in stock?, popularity) and customer-item features (times bought, weeks since last).
// They live in a REAL Redis (the online feature store): 3,000 items × 20 dark stores as strings, and one hash per customer.
// We serve 500 feed requests four ways and measure the real time spent fetching features:
//   F1 one GET per candidate + one HGET per candidate          — 800 round trips
//   F2 MGET / HMGET in batches of 100                          — 8 round trips
//   F3 one MGET + one HMGET for everything                     — 2 round trips
//   F4 item features from an in-process cache refreshed every 30 s, one HMGET for the customer — 1 round trip
// While requests run, 2% of items change stock status every second (a writer thread updates Redis), so F4's cache can
// show a stale "in stock" flag; we count those against Redis's current value.
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class FeatureFetchL3 {
    public static void main(String[] args) throws Exception {
        int items = 3_000, stores = 20, users = 2_000, cands = 400; Random r = new Random(43);
        try (Redis rd = new Redis()) {
            rd.cmd("FLUSHALL"); List<String[]> batch = new ArrayList<>();
            for (int s = 0; s < stores; s++) for (int i = 0; i < items; i++) {
                batch.add(new String[]{"SET", "f:" + s + ":" + i, (49 + r.nextInt(500)) + "|" + (r.nextDouble() < 0.9 ? 1 : 0) + "|" + r.nextInt(1000)});
                if (batch.size() == 2000) { rd.pipeline(batch); batch.clear(); }
            }
            for (int u = 0; u < users; u++) {
                String[] c = new String[2 + 2 * 60]; c[0] = "HSET"; c[1] = "u:" + u;
                for (int k = 0; k < 60; k++) { c[2 + 2 * k] = Integer.toString(r.nextInt(items)); c[3 + 2 * k] = (1 + r.nextInt(12)) + "|" + r.nextInt(12); }
                batch.add(c); if (batch.size() == 500) { rd.pipeline(batch); batch.clear(); }
            }
            rd.pipeline(batch);
        }
        // writer: 2% of items per second flip stock in every store
        AtomicBoolean stop = new AtomicBoolean(); Thread writer = new Thread(() -> {
            try (Redis w = new Redis()) { Random wr = new Random(7);
                while (!stop.get()) { List<String[]> b = new ArrayList<>(); for (int k = 0; k < items * 2 / 100 / 10; k++) { int i = wr.nextInt(items); for (int s = 0; s < stores; s++) b.add(new String[]{"SET", "f:" + s + ":" + i, (49 + wr.nextInt(500)) + "|" + (wr.nextBoolean() ? 1 : 0) + "|" + wr.nextInt(1000)}); }
                    w.pipeline(b); Thread.sleep(100); }
            } catch (Exception ignored) {} });
        writer.start();

        System.out.printf("Redis feature store: %,d item-store keys, %,d customer hashes. %d candidates per request, 500 requests each.%n%n", items * stores, users, cands);
        System.out.printf("   %-58s %12s %12s %12s %20s %14s%n", "strategy", "round trips", "p50", "p99", "at 0.5 ms RTT (est.)", "stale stock");
        String[] names = {"F1) GET + HGET per candidate", "F2) MGET / HMGET in batches of 100", "F3) one MGET + one HMGET", "F4) local item cache (30 s) + one HMGET"};
        Map<String, String> local = new HashMap<>(); long[] localLoadedAt = {0};
        try (Redis rd = new Redis()) {
            for (int s = 0; s < 4; s++) {
                Random q = new Random(100 + s); long[] t = new long[500]; long rts = 0, stale = 0, flags = 0;
                if (s == 3) { loadLocal(rd, local, items, stores); localLoadedAt[0] = System.nanoTime(); }
                for (int req = 0; req < 500; req++) {
                    int user = q.nextInt(users), store = q.nextInt(stores); String[] cand = new String[cands];
                    for (int k = 0; k < cands; k++) cand[k] = Integer.toString(q.nextInt(items));
                    long t0 = System.nanoTime(); int trips = 0; List<Object> itemF = new ArrayList<>();
                    switch (s) {
                        case 0 -> { for (String c : cand) { itemF.add(rd.cmd("GET", "f:" + store + ":" + c)); rd.cmd("HGET", "u:" + user, c); trips += 2; } }
                        case 1 -> { for (int b = 0; b < cands; b += 100) { String[] mg = new String[101]; mg[0] = "MGET"; String[] hm = new String[102]; hm[0] = "HMGET"; hm[1] = "u:" + user;
                                        for (int k = 0; k < 100; k++) { mg[1 + k] = "f:" + store + ":" + cand[b + k]; hm[2 + k] = cand[b + k]; }
                                        itemF.addAll((List<?>) rd.cmd(mg)); rd.cmd(hm); trips += 2; } }
                        case 2 -> { String[] mg = new String[cands + 1]; mg[0] = "MGET"; String[] hm = new String[cands + 2]; hm[0] = "HMGET"; hm[1] = "u:" + user;
                                    for (int k = 0; k < cands; k++) { mg[1 + k] = "f:" + store + ":" + cand[k]; hm[2 + k] = cand[k]; }
                                    itemF.addAll((List<?>) rd.cmd(mg)); rd.cmd(hm); trips = 2; }
                        default -> { if (System.nanoTime() - localLoadedAt[0] > 30e9) { loadLocal(rd, local, items, stores); localLoadedAt[0] = System.nanoTime(); }
                                     for (String c : cand) itemF.add(local.get("f:" + store + ":" + c));
                                     String[] hm = new String[cands + 2]; hm[0] = "HMGET"; hm[1] = "u:" + user; for (int k = 0; k < cands; k++) hm[2 + k] = cand[k];
                                     rd.cmd(hm); trips = 1; }
                    }
                    t[req] = System.nanoTime() - t0; rts += trips;
                    if (s == 3 && req % 10 == 0) {                               // audit: compare the cached stock flag with Redis now
                        String[] mg = new String[cands + 1]; mg[0] = "MGET"; for (int k = 0; k < cands; k++) mg[1 + k] = "f:" + store + ":" + cand[k];
                        List<?> now = (List<?>) rd.cmd(mg);
                        for (int k = 0; k < cands; k++) { flags++; if (!stockOf((String) now.get(k)).equals(stockOf((String) itemF.get(k)))) stale++; }
                    }
                    Thread.sleep(s == 3 ? 20 : 0);                              // F4 runs over ~10 s so the cache ages and refreshes
                }
                Arrays.sort(t); double avgTrips = (double) rts / 500;
                System.out.printf("   %-58s %12.0f %9.2f ms %9.2f ms %17.1f ms %14s%n", names[s], avgTrips, t[250] / 1e6, t[495] / 1e6,
                        t[250] / 1e6 + avgTrips * 0.5, s == 3 ? String.format("%.2f%%", 100.0 * stale / flags) : "0%");
            }
        }
        stop.set(true); writer.join();
        System.out.println("   (p50/p99 are real localhost timings and vary by machine; the 0.5 ms column adds a typical cross-AZ round trip");
        System.out.println("    per trip to the measured p50 — an estimate.)");
        System.out.println("\n   → the cost of features is round trips, not bytes: hundreds of tiny GETs cost far more than one large MGET,");
        System.out.println("     and across availability zones the difference is the whole latency budget. Batch everything a request needs");
        System.out.println("     into as few calls as possible (MGET/HMGET/pipelines, or a feature service that does it). Caching slowly-");
        System.out.println("     changing item features in process removes another trip (invisible on localhost, worth ~0.5 ms per request across");
        System.out.println("     zones), at the price of staleness — fine for popularity,");
        System.out.println("     not for stock: re-check availability for the items actually shown, or subscribe to stock changes.");
    }

    static String stockOf(String v) { return v == null ? "?" : v.split("\\|")[1]; }
    static void loadLocal(Redis rd, Map<String, String> local, int items, int stores) throws IOException {
        for (int s = 0; s < stores; s++) { String[] mg = new String[items + 1]; mg[0] = "MGET"; for (int i = 0; i < items; i++) mg[1 + i] = "f:" + s + ":" + i;
            List<?> v = (List<?>) rd.cmd(mg); for (int i = 0; i < items; i++) local.put("f:" + s + ":" + i, (String) v.get(i)); }
    }

    /** Minimal RESP client (Ch 18) with pipelining and arrays. */
    static final class Redis implements Closeable {
        final Socket s; final OutputStream out; final InputStream in;
        Redis() throws IOException { s = new Socket("127.0.0.1", 6390); s.setTcpNoDelay(true); out = new BufferedOutputStream(s.getOutputStream(), 1 << 16); in = new BufferedInputStream(s.getInputStream(), 1 << 16); }
        void write(String... parts) throws IOException {
            StringBuilder sb = new StringBuilder("*").append(parts.length).append("\r\n");
            for (String p : parts) { byte[] b = p.getBytes(StandardCharsets.UTF_8); sb.append('$').append(b.length).append("\r\n").append(p).append("\r\n"); }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        Object cmd(String... parts) throws IOException { write(parts); out.flush(); return read(); }
        void pipeline(List<String[]> cmds) throws IOException { for (String[] c : cmds) write(c); out.flush(); for (int i = 0; i < cmds.size(); i++) read(); }
        Object read() throws IOException {
            int type = in.read(); String line = line();
            return switch (type) {
                case '+' -> line; case ':' -> Long.parseLong(line);
                case '$' -> { int n = Integer.parseInt(line); if (n < 0) yield null; byte[] b = in.readNBytes(n); line(); yield new String(b, StandardCharsets.UTF_8); }
                case '*' -> { int n = Integer.parseInt(line); List<Object> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(read()); yield l; }
                case '-' -> throw new IOException("redis error: " + line);
                default -> throw new IOException("unexpected reply type " + (char) type);
            };
        }
        String line() throws IOException { StringBuilder sb = new StringBuilder(); int c; while ((c = in.read()) != '\r') sb.append((char) c); in.read(); return sb.toString(); }
        public void close() throws IOException { s.close(); }
    }
}
