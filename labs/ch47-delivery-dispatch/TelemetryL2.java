// TelemetryL2.java — run with (Redis 7 on :6390):  java TelemetryL2.java   (JDK 21, no dependencies)
// RIDER GPS INGESTION. 50,000 riders send a position every 4 s (12,500 pings/s — the Ch 14 estimate). Positions must land
// in the live geo index (Redis GEO + last-seen hash) that dispatch queries (L1). We replay 20 s of pings (250,000,
// fixed seed) as fast as the pipeline allows, into a REAL Redis. Mobile networks buffer and resend, so 3% of pings
// arrive late, AFTER a newer one from the same rider. 40% of riders are standing at a store (not moving). Four ingesters:
//   T1 per ping: GEOADD, then HSET last-seen — two round trips, no ordering check
//   T2 per ping: one Lua script updates both atomically and ignores pings older than the stored timestamp
//   T3 T2, pipelined in batches of 200 (the ingester buffers ~50 ms)
//   T4 T3 + a movement filter: write only if the rider moved > 20 m or 15 s passed since the last write
//      (every raw ping still goes to Kafka for history and ETA models; only the live index is thinned)
// Measured: real ingestion throughput, Redis commands issued, and riders whose live position ends up OLDER than their
// newest ping (a stale position that dispatch would trust).
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class TelemetryL2 {
    static final String LUA = """
            local ts = tonumber(ARGV[3])
            local prev = tonumber(redis.call('HGET', KEYS[2], ARGV[4]) or '0')
            if ts <= prev then return 0 end
            redis.call('GEOADD', KEYS[1], ARGV[1], ARGV[2], ARGV[4])
            redis.call('HSET', KEYS[2], ARGV[4], ts)
            return 1""";
    record Ping(int rider, double lat, double lon, long ts) {}

    public static void main(String[] args) throws Exception {
        Random r = new Random(47); int riders = 50_000;
        double[][] pos = new double[riders][2]; double[][] vel = new double[riders][2];
        for (int i = 0; i < riders; i++) { pos[i][0] = 12.85 + r.nextDouble() * 0.3; pos[i][1] = 77.45 + r.nextDouble() * 0.3;
            if (r.nextDouble() > 0.4) { double a = r.nextDouble() * 2 * Math.PI; vel[i][0] = 5 * Math.cos(a) / 111_320; vel[i][1] = 5 * Math.sin(a) / 108_000; } }   // 5 m/s
        List<Ping> pings = new ArrayList<>(); int[] phase = new int[riders]; for (int i = 0; i < riders; i++) phase[i] = r.nextInt(4000);
        for (long t = 0; t < 20_000; t += 4000) for (int i = 0; i < riders; i++) {
            long ts = t + phase[i]; double s = ts / 1000.0;
            pings.add(new Ping(i, pos[i][0] + vel[i][0] * s + r.nextGaussian() * 3 / 111_320, pos[i][1] + vel[i][1] * s + r.nextGaussian() * 3 / 108_000, ts));
        }
        pings.sort(Comparator.comparingLong(Ping::ts));
        int late = 0; for (int i = 0; i < pings.size() - 1; i++) if (r.nextDouble() < 0.03) { int j = Math.min(pings.size() - 1, i + 1 + r.nextInt(60_000)); Collections.swap(pings, i, j); late++; }  // delayed delivery
        long[] newest = new long[riders]; for (Ping p : pings) newest[p.rider()] = Math.max(newest[p.rider()], p.ts());
        System.out.printf("%,d pings from %,d riders (20 s at 1 ping / 4 s); ~%,d delivered out of order; 40%% of riders stationary.%n%n", pings.size(), riders, late);
        System.out.printf("   %-58s %14s %16s %22s%n", "ingester", "pings/s", "Redis commands", "riders left with an");
        System.out.printf("   %-58s %14s %16s %22s%n", "", "(real)", "", "older position");
        String[] names = {"T1) GEOADD + HSET per ping, no ordering check", "T2) one Lua script per ping, drops older pings", "T3) T2 pipelined in batches of 200",
                "T4) T3 + write only if moved > 20 m or 15 s passed"};
        try (Redis red = new Redis()) {
            String sha = (String) red.cmd("SCRIPT", "LOAD", LUA);
            for (int d = 0; d < 4; d++) {
                red.cmd("DEL", "geo:riders", "seen:riders");
                long cmds = 0; long t0 = System.nanoTime(); List<String[]> batch = new ArrayList<>();
                double[][] lastWritten = new double[riders][]; long[] lastWrittenTs = new long[riders];
                for (Ping p : pings) {
                    String id = "r" + p.rider(), lon = String.valueOf(p.lon()), lat = String.valueOf(p.lat()), ts = String.valueOf(p.ts() + 1);
                    if (d == 0) { red.cmd("GEOADD", "geo:riders", lon, lat, id); red.cmd("HSET", "seen:riders", id, ts); cmds += 2; continue; }
                    if (d == 3) {
                        double[] lw = lastWritten[p.rider()];
                        if (lw != null && p.ts() - lastWrittenTs[p.rider()] < 15_000 && dist(lw, p) < 20) continue;
                        if (lw == null || p.ts() > lastWrittenTs[p.rider()]) { lastWritten[p.rider()] = new double[]{p.lat(), p.lon()}; lastWrittenTs[p.rider()] = p.ts(); }
                    }
                    String[] c = {"EVALSHA", sha, "2", "geo:riders", "seen:riders", lon, lat, ts, id};
                    if (d == 1) { red.cmd(c); cmds++; } else { batch.add(c); cmds++; if (batch.size() == 200) { red.pipeline(batch); batch.clear(); } }
                }
                if (!batch.isEmpty()) red.pipeline(batch);
                double secs = (System.nanoTime() - t0) / 1e9;
                // audit: which riders' stored timestamp is older than their newest ping (T4 compares against newest WRITTEN, see note)
                int stale = 0; List<String[]> q = new ArrayList<>(); String[] hm = new String[riders + 2]; hm[0] = "HMGET"; hm[1] = "seen:riders"; for (int i = 0; i < riders; i++) hm[2 + i] = "r" + i;
                List<?> seen = (List<?>) red.cmd(hm);
                for (int i = 0; i < riders; i++) { long st = seen.get(i) == null ? 0 : Long.parseLong((String) seen.get(i)) - 1; if (st < newest[i] && (d != 3 || st < lastWrittenTs[i])) stale++; }
                System.out.printf("   %-58s %,14.0f %,16d %,22d%n", names[d], pings.size() / secs, cmds, stale);
            }
            red.cmd("DEL", "geo:riders", "seen:riders");
        }
        System.out.println("   (T4 deliberately skips pings that didn't move a rider; its check counts only positions older than its own last write.)");
        System.out.println("\n   → one round trip per update caps ingestion far below what one Redis can do; pipelining multiplies it. Without an");
        System.out.println("     ordering check, a late ping silently moves a rider back to where they were seconds ago — and dispatch trusts");
        System.out.println("     it. A Lua script that compares timestamps makes the update atomic and order-safe. Finally, most pings carry");
        System.out.println("     no news (riders waiting at the store): thinning the LIVE index by movement cuts writes sharply, while the raw");
        System.out.println("     stream still goes to Kafka for history, ETA training and dispute resolution.");
    }
    static double dist(double[] a, Ping p) { double dx = (a[0] - p.lat()) * 111_320, dy = (a[1] - p.lon()) * 108_000; return Math.sqrt(dx * dx + dy * dy); }

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
