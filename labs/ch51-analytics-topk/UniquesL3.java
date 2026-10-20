// UniquesL3.java — run with (Redis 7 on :6390): java UniquesL3.java   (JDK 21, no dependencies)
// "HOW MANY DIFFERENT CUSTOMERS OPENED THE APP TODAY — PER STORE, PER HOUR, AND CITY-WIDE?" 1,500,000 customers, 50
// dark stores, 24 hours (fixed seed). Each customer has a home store (90% of visits) and opens the app 1–8 times at
// random hours: ~6.8M events. Written to a REAL Redis two ways, 1,200 keys each (store × hour):
//   exact: a SET of customer ids per key (SADD)
//   HyperLogLog: PFADD per key (≈0.81% standard error, ≤12 KB per key)
// Then three questions: uniques for one store-hour, uniques for the whole city today, and the tempting shortcut of
// adding up the per-store-hour unique counts.
// Measured: Redis memory, real query time, and the answers against the exact truth.
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class UniquesL3 {
    public static void main(String[] args) throws Exception {
        Random r = new Random(51); int customers = 1_500_000, stores = 50;
        List<int[]> events = new ArrayList<>();                                                  // {customer, store, hour}
        for (int c = 0; c < customers; c++) { int home = r.nextInt(stores), n = 1 + r.nextInt(8);
            for (int k = 0; k < n; k++) events.add(new int[]{c, r.nextDouble() < 0.9 ? home : r.nextInt(stores), r.nextInt(24)}); }
        Set<Integer> dayTruth = new HashSet<>(); Map<String, Set<Integer>> keyTruth = new HashMap<>();
        for (int[] e : events) { dayTruth.add(e[0]); keyTruth.computeIfAbsent(e[1] + ":" + e[2], k -> new HashSet<>()).add(e[0]); }
        long sumOfKeys = keyTruth.values().stream().mapToLong(Set::size).sum();
        System.out.printf("%,d events from %,d customers across 50 stores × 24 hours (1,200 keys).%n%n", events.size(), dayTruth.size());
        try (Redis red = new Redis()) {
            red.cmd("FLUSHALL");
            for (String kind : new String[]{"set", "hll"}) {
                long m0 = usedMemory(red); long t0 = System.nanoTime(); List<String[]> batch = new ArrayList<>();
                for (int[] e : events) { String key = kind + ":" + e[1] + ":" + e[2];
                    batch.add(kind.equals("set") ? new String[]{"SADD", key, String.valueOf(e[0])} : new String[]{"PFADD", key, String.valueOf(e[0])});
                    if (batch.size() == 2000) { red.pipeline(batch); batch.clear(); } }
                red.pipeline(batch);
                double loadS = (System.nanoTime() - t0) / 1e9; long mem = usedMemory(red) - m0;
                String[] keys = new String[1200]; int i = 0; for (int s = 0; s < stores; s++) for (int h = 0; h < 24; h++) keys[i++] = kind + ":" + s + ":" + h;
                // one store-hour
                long tq = System.nanoTime(); long one = (Long) red.cmd(kind.equals("set") ? "SCARD" : "PFCOUNT", kind + ":17:19"); double oneMs = (System.nanoTime() - tq) / 1e6;
                // city-wide day
                tq = System.nanoTime(); long day;
                if (kind.equals("set")) { String[] c = new String[keys.length + 2]; c[0] = "SUNIONSTORE"; c[1] = "tmp:day"; System.arraycopy(keys, 0, c, 2, keys.length); day = (Long) red.cmd(c); red.cmd("DEL", "tmp:day"); }
                else { String[] c = new String[keys.length + 1]; c[0] = "PFCOUNT"; System.arraycopy(keys, 0, c, 1, keys.length); day = (Long) red.cmd(c); }
                double dayMs = (System.nanoTime() - tq) / 1e6;
                // tempting shortcut: sum per-key counts
                long sum = 0; for (String k : keys) sum += (Long) red.cmd(kind.equals("set") ? "SCARD" : "PFCOUNT", k);
                long trueOne = keyTruth.get("17:19").size();
                System.out.printf("   %s (%s)%n", kind.equals("set") ? "EXACT SETS" : "HYPERLOGLOG", kind.equals("set") ? "SADD / SCARD / SUNIONSTORE" : "PFADD / PFCOUNT over 1,200 keys");
                System.out.printf("      memory in Redis %12s   load %5.1f s%n", String.format("%.1f MB", mem / 1048576.0), loadS);
                System.out.printf("      store 17, 19:00  %,10d (truth %,d, %+.2f%%) in %.2f ms%n", one, trueOne, 100.0 * (one - trueOne) / trueOne, oneMs);
                System.out.printf("      city, today      %,10d (truth %,d, %+.2f%%) in %.1f ms%n", day, dayTruth.size(), 100.0 * (day - dayTruth.size()) / dayTruth.size(), dayMs);
                System.out.printf("      sum of the 1,200 per-store-hour counts: %,d (%+.0f%% vs truth) ← not a unique count%n%n", sum, 100.0 * (sum - dayTruth.size()) / dayTruth.size());
            }
            red.cmd("FLUSHALL");
        }
        System.out.println("   → distinct counts don't add: the same customer appears in many store-hours, so summing them counts people");
        System.out.println("     several times over. Exact sets answer correctly but hold every id in every key and must union big sets at");
        System.out.println("     query time. HyperLogLog keeps a small fixed-size sketch per key and MERGES correctly (the union of sketches");
        System.out.println("     is the sketch of the union), so hourly per-store sketches roll up to any store set and any time range, with");
        System.out.println("     about 1% error. Pre-aggregate sketches, not counts.");
    }

    static long usedMemory(Redis r) throws IOException {
        String info = (String) r.cmd("INFO", "memory");
        for (String line : info.split("\r\n")) if (line.startsWith("used_memory:")) return Long.parseLong(line.substring(12));
        return 0;
    }

    static final class Redis implements Closeable {
        final Socket s; final OutputStream out; final InputStream in;
        Redis() throws IOException { s = new Socket("127.0.0.1", 6390); s.setTcpNoDelay(true); out = new BufferedOutputStream(s.getOutputStream(), 1 << 16); in = new BufferedInputStream(s.getInputStream(), 1 << 16); }
        void write(String... parts) throws IOException { StringBuilder sb = new StringBuilder("*").append(parts.length).append("\r\n"); for (String p : parts) { byte[] b = p.getBytes(StandardCharsets.UTF_8); sb.append('$').append(b.length).append("\r\n").append(p).append("\r\n"); } out.write(sb.toString().getBytes(StandardCharsets.UTF_8)); }
        Object cmd(String... parts) throws IOException { write(parts); out.flush(); return read(); }
        void pipeline(List<String[]> cmds) throws IOException { for (String[] c : cmds) write(c); out.flush(); for (int i = 0; i < cmds.size(); i++) read(); }
        Object read() throws IOException {
            int type = in.read(); String line = line();
            return switch (type) {
                case '+' -> line; case ':' -> Long.parseLong(line);
                case '$' -> { int n = Integer.parseInt(line); if (n < 0) yield null; byte[] b = in.readNBytes(n); line(); yield new String(b, StandardCharsets.UTF_8); }
                case '*' -> { int n = Integer.parseInt(line); List<Object> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(read()); yield l; }
                case '-' -> throw new IOException(line);
                default -> throw new IOException("unexpected " + (char) type);
            };
        }
        String line() throws IOException { StringBuilder sb = new StringBuilder(); int c; while ((c = in.read()) != '\r') sb.append((char) c); in.read(); return sb.toString(); }
        public void close() throws IOException { s.close(); }
    }
}
