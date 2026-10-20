// ClusterL3.java — run with: ./cluster-setup.sh && java ClusterL3.java   (JDK 21; redis-server/redis-cli 7 on the PATH)
// SCALING A REAL REDIS CLUSTER OUT, WHILE IT SERVES TRAFFIC. A 3-master Redis Cluster (16,384 hash slots, key → slot by
// CRC16(key) mod 16384) holds 30,000 product-card keys. We add a 4th master and let `redis-cli --cluster rebalance` move
// slots to it — live — while two client threads keep reading random keys:
//   NAIVE client  loads the slot map once and never updates it; a MOVED or ASK reply is an error
//   SMART client  follows MOVED (updates that slot in its map, retries) and ASK (sends ASKING to the target for this one
//                 request without updating the map) — the protocol real cluster clients (Lettuce, Jedis) implement
// We count successful reads, redirects handled, errors and misses; then show where the keys ended up, and compare with
// what naive MODULO hashing (hash % nodes) would have moved.
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

public class ClusterL3 {
    static final int KEYS = 30_000;

    public static void main(String[] args) throws Exception {
        int[] slotPort = slotMap(7001);
        Map<Integer, Redis> conns = new HashMap<>();
        for (int i = 0; i < KEYS; i++) { String k = "card:{sku" + i + "}"; smartCmd(conns, slotPort, new AtomicLong(), new AtomicLong(), "SET", k, "v" + i); }
        System.out.printf("3-master Redis Cluster, %,d keys loaded. Adding a 4th master and rebalancing live...%n%n", KEYS);

        AtomicBoolean done = new AtomicBoolean(); long[][] stats = new long[2][5];   // ok, moved, ask, errors, misses
        Thread[] clients = new Thread[2];
        for (int c = 0; c < 2; c++) {
            final int cid = c;
            clients[c] = new Thread(() -> {
                int[] map = slotMap(7001); Map<Integer, Redis> cc = new HashMap<>(); Random r = new Random(44 + cid);
                AtomicLong moved = new AtomicLong(), ask = new AtomicLong();
                while (!done.get()) {
                    String k = "card:{sku" + r.nextInt(KEYS) + "}";
                    try {
                        Object v = cid == 0 ? naiveGet(cc, map, k) : smartCmd(cc, map, moved, ask, "GET", k);
                        if (v instanceof String s && s.startsWith("-")) stats[cid][3]++; else if (v == null) stats[cid][4]++; else stats[cid][0]++;
                    } catch (IOException e) { stats[cid][3]++; }
                }
                stats[cid][1] = moved.get(); stats[cid][2] = ask.get();
            });
            clients[c].start();
        }
        Thread.sleep(500);
        long t0 = System.nanoTime();
        run("redis-cli", "--cluster", "add-node", "127.0.0.1:7004", "127.0.0.1:7001");
        Thread.sleep(2500);
        run("redis-cli", "--cluster", "rebalance", "127.0.0.1:7001", "--cluster-use-empty-masters", "--cluster-yes");
        double secs = (System.nanoTime() - t0) / 1e9;
        Thread.sleep(500); done.set(true); for (Thread t : clients) t.join();

        System.out.printf("   Rebalance took %.1f s. Reads during the whole run:%n%n", secs);
        System.out.printf("   %-44s %12s %14s %12s %10s %10s%n", "client", "successful", "MOVED handled", "ASK handled", "errors", "misses");
        String[] names = {"NAIVE (static slot map)", "SMART (follows MOVED and ASK)"};
        for (int c = 0; c < 2; c++) System.out.printf("   %-44s %,12d %,14d %,12d %,10d %,10d%n", names[c], stats[c][0], c == 0 ? 0 : stats[c][1], c == 0 ? 0 : stats[c][2], stats[c][3], stats[c][4]);

        System.out.println("\n   Keys per master after rebalancing:");
        int total = 0; for (int p = 7001; p <= 7004; p++) { try (Redis r = new Redis(p)) { long n = (Long) r.cmd("DBSIZE"); total += n; System.out.printf("        127.0.0.1:%d   %,6d keys  %s%n", p, n, "█".repeat((int) (n / 400))); } }
        long onNew; try (Redis r = new Redis(7004)) { onNew = (Long) r.cmd("DBSIZE"); }
        int modMoved = 0; for (int i = 0; i < KEYS; i++) { int h = crc16(("sku" + i).getBytes()); if (h % 3 != h % 4) modMoved++; }
        System.out.printf("%n   Keys that moved: %,d of %,d (%.0f%%) — all of them to the new node. Modulo hashing (hash %% 3 → %% 4) would have%n", onNew, total, 100.0 * onNew / total);
        System.out.printf("   moved %,d (%.0f%%), mostly between the OLD nodes, emptying most of every cache at once.%n", modMoved, 100.0 * modMoved / KEYS);
        System.out.println("\n   → hash slots make scale-out a gradual, key-by-key migration: only the slots that move change owner, and only");
        System.out.println("     to the new node. During the move the cluster answers MOVED (slot now lives elsewhere) or ASK (this key is");
        System.out.println("     mid-migration); a cluster-aware client follows both and never sees an error, while a client with a stale map");
        System.out.println("     fails on every key that moved. The {sku…} hash tag controls which keys share a slot, so multi-key commands");
        System.out.println("     on related keys keep working. Resharding is routine only if every client speaks the redirect protocol.");
        System.exit(0);
    }

    static Object naiveGet(Map<Integer, Redis> cc, int[] map, String k) throws IOException { return conn(cc, map[slot(k)]).cmd("GET", k); }
    static Object smartCmd(Map<Integer, Redis> cc, int[] map, AtomicLong moved, AtomicLong ask, String... cmd) throws IOException {
        int port = map[slot(cmd[1])];
        for (int attempt = 0; attempt < 5; attempt++) {
            Object v = conn(cc, port).cmd(cmd);
            if (v instanceof String s && s.startsWith("-MOVED")) { String[] f = s.split(" "); int p = Integer.parseInt(f[2].split(":")[1]); map[Integer.parseInt(f[1])] = p; port = p; moved.incrementAndGet(); continue; }
            if (v instanceof String s && s.startsWith("-ASK")) { int p = Integer.parseInt(s.split(" ")[2].split(":")[1]); Redis t = conn(cc, p); t.cmd("ASKING"); ask.incrementAndGet(); return t.cmd(cmd); }
            if (v instanceof String s && s.startsWith("-TRYAGAIN")) { try { Thread.sleep(5); } catch (InterruptedException e) { } continue; }
            return v;
        }
        return "-ERR too many redirects";
    }
    static Redis conn(Map<Integer, Redis> cc, int port) throws IOException { Redis r = cc.get(port); if (r == null) { r = new Redis(port); cc.put(port, r); } return r; }

    static int[] slotMap(int port) {
        int[] map = new int[16384];
        try (Redis r = new Redis(port)) {
            for (Object o : (List<?>) r.cmd("CLUSTER", "SLOTS")) { List<?> e = (List<?>) o; int from = ((Long) e.get(0)).intValue(), to = ((Long) e.get(1)).intValue(); int p = ((Long) ((List<?>) e.get(2)).get(1)).intValue(); for (int s = from; s <= to; s++) map[s] = p; }
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return map;
    }
    static int slot(String key) {
        int s = key.indexOf('{'), e = s >= 0 ? key.indexOf('}', s + 1) : -1;
        String tag = (s >= 0 && e > s + 1) ? key.substring(s + 1, e) : key;
        return crc16(tag.getBytes(StandardCharsets.UTF_8)) & 16383;
    }
    static int crc16(byte[] b) {                                         // CRC16-CCITT (XMODEM), as Redis Cluster uses
        int crc = 0; for (byte x : b) { crc ^= (x & 0xff) << 8; for (int i = 0; i < 8; i++) crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1; crc &= 0xffff; } return crc;
    }
    static void run(String... cmd) throws Exception { Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start(); p.getInputStream().readAllBytes(); p.waitFor(); }

    /** Minimal RESP client; error replies are returned as "-..." strings so the caller can see MOVED/ASK. */
    static final class Redis implements Closeable {
        final Socket s; final OutputStream out; final InputStream in;
        Redis(int port) throws IOException { s = new Socket("127.0.0.1", port); s.setTcpNoDelay(true); out = new BufferedOutputStream(s.getOutputStream()); in = new BufferedInputStream(s.getInputStream()); }
        Object cmd(String... parts) throws IOException {
            StringBuilder sb = new StringBuilder("*").append(parts.length).append("\r\n");
            for (String p : parts) { byte[] b = p.getBytes(StandardCharsets.UTF_8); sb.append('$').append(b.length).append("\r\n").append(p).append("\r\n"); }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8)); out.flush(); return read();
        }
        Object read() throws IOException {
            int type = in.read(); String line = line();
            return switch (type) {
                case '+' -> line; case ':' -> Long.parseLong(line); case '-' -> "-" + line;
                case '$' -> { int n = Integer.parseInt(line); if (n < 0) yield null; byte[] b = in.readNBytes(n); line(); yield new String(b, StandardCharsets.UTF_8); }
                case '*' -> { int n = Integer.parseInt(line); List<Object> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(read()); yield l; }
                default -> throw new IOException("unexpected reply type " + (char) type);
            };
        }
        String line() throws IOException { StringBuilder sb = new StringBuilder(); int c; while ((c = in.read()) != '\r') { if (c < 0) throw new EOFException(); sb.append((char) c); } in.read(); return sb.toString(); }
        public void close() throws IOException { s.close(); }
    }
}
