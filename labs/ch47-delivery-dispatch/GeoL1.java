// GeoL1.java — run with (Redis 7 on :6390):  java GeoL1.java   (JDK 21, no dependencies)
// THE TWO GEO QUESTIONS EVERY ORDER ASKS. Bengaluru-sized synthetic city (fixed seed):
//   1) SERVICEABILITY — "which dark store's delivery zone contains this customer?" 400 stores, each with an irregular
//      polygon (12–20 vertices) — the move from pincodes to geofences that Ch 01 deferred here. 200,000 lookups:
//        brute force ray-casting against every polygon  vs  a grid index (500 m cells → candidate polygons)
//   2) NEAREST RIDERS — "the 5 closest available riders to this store": 20,000 riders.
//        brute force distance to every rider (in memory)  vs  a geohash-style grid (in memory)  vs  REAL Redis GEOSEARCH
// Correctness is checked against brute force for every query.
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class GeoL1 {
    static final double LAT0 = 12.85, LON0 = 77.45, SPAN = 0.30;                // ~33 km square
    static final double M_PER_DEG_LAT = 111_320, M_PER_DEG_LON = 111_320 * Math.cos(Math.toRadians(12.97));

    public static void main(String[] args) throws Exception {
        Random r = new Random(47);
        // ---------- 1) serviceability polygons ----------
        List<double[][]> polys = new ArrayList<>();
        for (int s = 0; s < 400; s++) {
            double cx = LAT0 + 0.02 + r.nextDouble() * (SPAN - 0.04), cy = LON0 + 0.02 + r.nextDouble() * (SPAN - 0.04); int n = 12 + r.nextInt(9);
            double[][] p = new double[n][2];
            for (int k = 0; k < n; k++) { double a = 2 * Math.PI * k / n, rad = (1_200 + r.nextDouble() * 1_400) / M_PER_DEG_LAT; p[k][0] = cx + rad * Math.cos(a); p[k][1] = cy + rad * Math.sin(a) * M_PER_DEG_LAT / M_PER_DEG_LON; }
            polys.add(p);
        }
        double cell = 500 / M_PER_DEG_LAT; int gridN = (int) Math.ceil(SPAN / cell) + 1;
        List<List<Integer>> grid = new ArrayList<>(); for (int i = 0; i < gridN * gridN; i++) grid.add(new ArrayList<>());
        for (int s = 0; s < polys.size(); s++) {                                     // register each polygon in the cells its bounding box covers
            double[][] p = polys.get(s); double minA = 99, maxA = -99, minB = 999, maxB = -999;
            for (double[] v : p) { minA = Math.min(minA, v[0]); maxA = Math.max(maxA, v[0]); minB = Math.min(minB, v[1]); maxB = Math.max(maxB, v[1]); }
            for (int a = cellOf(minA, LAT0, cell); a <= cellOf(maxA, LAT0, cell); a++) for (int b = cellOf(minB, LON0, cell); b <= cellOf(maxB, LON0, cell); b++)
                if (a >= 0 && b >= 0 && a < gridN && b < gridN) grid.get(a * gridN + b).add(s);
        }
        double[][] pts = new double[200_000][2]; for (double[] q : pts) { q[0] = LAT0 + r.nextDouble() * SPAN; q[1] = LON0 + r.nextDouble() * SPAN; }
        long t0 = System.nanoTime(); int[] brute = new int[pts.length]; long tests = 0;
        for (int i = 0; i < pts.length; i++) { brute[i] = -1; for (int s = 0; s < polys.size(); s++) { tests++; if (inside(polys.get(s), pts[i])) { brute[i] = s; break; } } }
        double bruteMs = (System.nanoTime() - t0) / 1e6;
        t0 = System.nanoTime(); int mismatch = 0, served = 0; long gTests = 0;
        for (int i = 0; i < pts.length; i++) {
            int found = -1; int a = cellOf(pts[i][0], LAT0, cell), b = cellOf(pts[i][1], LON0, cell);
            for (int s : grid.get(a * gridN + b)) { gTests++; if (inside(polys.get(s), pts[i])) { found = s; break; } }
            if (found != -1) served++;
            if ((found == -1) != (brute[i] == -1) || (found != -1 && !inside(polys.get(brute[i]), pts[i]))) mismatch++;
        }
        double gridMs = (System.nanoTime() - t0) / 1e6;
        System.out.printf("1) Serviceability: 400 store polygons, 200,000 customer locations (%.0f%% inside some zone).%n%n", 100.0 * served / pts.length);
        System.out.printf("   %-46s %12s %22s %12s%n", "method", "total time", "polygon tests / lookup", "wrong");
        System.out.printf("   %-46s %9.0f ms %22.1f %,12d%n", "brute force: test every polygon", bruteMs, (double) tests / pts.length, 0);
        System.out.printf("   %-46s %9.0f ms %22.1f %,12d%n", "grid index (500 m cells → candidate polygons)", gridMs, (double) gTests / pts.length, mismatch);

        // ---------- 2) nearest riders ----------
        int riders = 20_000; double[][] rp = new double[riders][2];
        for (double[] q : rp) { q[0] = LAT0 + r.nextDouble() * SPAN; q[1] = LON0 + r.nextDouble() * SPAN; }
        double rc = 1_000 / M_PER_DEG_LAT; int rn = (int) Math.ceil(SPAN / rc) + 1; List<List<Integer>> rg = new ArrayList<>(); for (int i = 0; i < rn * rn; i++) rg.add(new ArrayList<>());
        for (int i = 0; i < riders; i++) rg.get(cellOf(rp[i][0], LAT0, rc) * rn + cellOf(rp[i][1], LON0, rc)).add(i);
        try (Redis red = new Redis()) {
            red.cmd("DEL", "riders:blr"); List<String[]> batch = new ArrayList<>();
            for (int i = 0; i < riders; i++) { batch.add(new String[]{"GEOADD", "riders:blr", String.valueOf(rp[i][1]), String.valueOf(rp[i][0]), "r" + i}); if (batch.size() == 1000) { red.pipeline(batch); batch.clear(); } }
            red.pipeline(batch);
            int queries = 5_000; double[][] qs = new double[queries][2]; for (double[] q : qs) { q[0] = LAT0 + 0.03 + r.nextDouble() * (SPAN - 0.06); q[1] = LON0 + 0.03 + r.nextDouble() * (SPAN - 0.06); }
            long[] tb = new long[queries], tg = new long[queries], tr = new long[queries]; int gridWrong = 0, redisWrong = 0;
            for (int i = 0; i < queries; i++) {
                long s = System.nanoTime(); List<Integer> b = bruteK(rp, qs[i], 5); tb[i] = System.nanoTime() - s;
                s = System.nanoTime(); List<Integer> g = gridK(rp, rg, rn, rc, qs[i], 5); tg[i] = System.nanoTime() - s;
                s = System.nanoTime(); List<?> res = (List<?>) red.cmd("GEOSEARCH", "riders:blr", "FROMLONLAT", String.valueOf(qs[i][1]), String.valueOf(qs[i][0]), "BYRADIUS", "3", "km", "ASC", "COUNT", "5"); tr[i] = System.nanoTime() - s;
                if (!new HashSet<>(g).equals(new HashSet<>(b))) gridWrong++;
                Set<Integer> rs = new HashSet<>(); for (Object o : res) rs.add(Integer.parseInt(((String) o).substring(1)));
                if (!rs.equals(new HashSet<>(b))) redisWrong++;
            }
            Arrays.sort(tb); Arrays.sort(tg); Arrays.sort(tr);
            System.out.printf("%n2) Nearest 5 riders to a point, %,d riders, %,d queries.%n%n", riders, queries);
            System.out.printf("   %-46s %12s %12s %30s%n", "method", "p50", "p99", "different from exact top-5");
            System.out.printf("   %-46s %9.1f µs %9.1f µs %30d%n", "brute force over every rider", tb[queries / 2] / 1e3, tb[queries * 99 / 100] / 1e3, 0);
            System.out.printf("   %-46s %9.1f µs %9.1f µs %30d%n", "grid index (1 km cells, expanding rings)", tg[queries / 2] / 1e3, tg[queries * 99 / 100] / 1e3, gridWrong);
            System.out.printf("   %-46s %9.1f µs %9.1f µs %30d%n", "Redis GEOSEARCH (network round trip incl.)", tr[queries / 2] / 1e3, tr[queries * 99 / 100] / 1e3, redisWrong);
            red.cmd("DEL", "riders:blr");
        }
        System.out.println("   (Redis uses haversine distance and 52-bit geohash-rounded positions; this lab uses a flat approximation, so riders at");
        System.out.println("    almost equal distance — the 5th and 6th — occasionally swap. Pick one distance function for ranking.)");
        System.out.println("\n   → both questions are 'find the few candidates near here, then do the exact check': a grid (or geohash, S2, H3)");
        System.out.println("     index turns 'test all 400 polygons' into ~1–3 tests, and 'measure 20,000 riders' into a few cells, with");
        System.out.println("     identical answers. Redis GEO is the same idea behind a network round trip, shared by every dispatcher. Straight-line");
        System.out.println("     distance is only the candidate filter: the final choice needs road distance and ETA (L4), and 'available' needs");
        System.out.println("     fresh positions (L2).");
    }

    static int cellOf(double v, double origin, double cell) { return (int) Math.floor((v - origin) / cell); }
    static boolean inside(double[][] poly, double[] p) {                               // ray casting
        boolean in = false;
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++)
            if ((poly[i][1] > p[1]) != (poly[j][1] > p[1]) && p[0] < (poly[j][0] - poly[i][0]) * (p[1] - poly[i][1]) / (poly[j][1] - poly[i][1]) + poly[i][0]) in = !in;
        return in;
    }
    static double dist(double[] a, double[] b) { double dx = (a[0] - b[0]) * M_PER_DEG_LAT, dy = (a[1] - b[1]) * M_PER_DEG_LON; return Math.sqrt(dx * dx + dy * dy); }
    static List<Integer> bruteK(double[][] rp, double[] q, int k) {
        PriorityQueue<Integer> pq = new PriorityQueue<>((x, y) -> Double.compare(dist(rp[y], q), dist(rp[x], q)));
        for (int i = 0; i < rp.length; i++) { pq.add(i); if (pq.size() > k) pq.poll(); }
        return new ArrayList<>(pq);
    }
    static List<Integer> gridK(double[][] rp, List<List<Integer>> g, int n, double cell, double[] q, int k) {
        int a = cellOf(q[0], LAT0, cell), b = cellOf(q[1], LON0, cell);
        PriorityQueue<Integer> pq = new PriorityQueue<>((x, y) -> Double.compare(dist(rp[y], q), dist(rp[x], q)));
        for (int ring = 0; ring < n; ring++) {
            for (int i = a - ring; i <= a + ring; i++) for (int j = b - ring; j <= b + ring; j++) {
                if (Math.max(Math.abs(i - a), Math.abs(j - b)) != ring || i < 0 || j < 0 || i >= n || j >= n) continue;
                for (int id : g.get(i * n + j)) { pq.add(id); if (pq.size() > k) pq.poll(); }
            }
            if (pq.size() == k && dist(rp[pq.peek()], q) <= ring * cell * M_PER_DEG_LAT) break;   // kth best is closer than any unvisited ring
        }
        return new ArrayList<>(pq);
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
