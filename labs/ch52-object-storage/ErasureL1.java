// ErasureL1.java — run with: java ErasureL1.java   (JDK 21, no dependencies)
// HOW OBJECT STORES KEEP DATA WITHOUT KEEPING THREE COPIES. Part 1 is a REAL Reed–Solomon codec over GF(2^8)
// (systematic, Cauchy parity matrix): 96 MB of random bytes (product images, invoices) is split into 16 objects, each
// encoded as k data shards + m parity shards; we destroy m shards per object — including data shards — rebuild the
// object from any k survivors and check its SHA-256. Part 2 computes durability with a Markov model of disk failures
// (AFR 2%, repair times as stated) for 10 PB of user data, and the storage and repair-traffic cost of each scheme.
import java.security.MessageDigest;
import java.util.*;

public class ErasureL1 {
    static final int[] EXP = new int[512], LOG = new int[256];
    static { int x = 1; for (int i = 0; i < 255; i++) { EXP[i] = x; LOG[x] = i; x <<= 1; if ((x & 0x100) != 0) x ^= 0x11d; } for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255]; }
    static int mul(int a, int b) { return a == 0 || b == 0 ? 0 : EXP[LOG[a] + LOG[b]]; }
    static int inv(int a) { return EXP[255 - LOG[a]]; }

    public static void main(String[] args) throws Exception {
        Random r = new Random(52); int objects = 16, objSize = 6 * 1024 * 1024; byte[][] data = new byte[objects][objSize];
        for (byte[] d : data) r.nextBytes(d);
        System.out.println("PART 1 — real Reed–Solomon over GF(2^8): 16 objects × 6 MB, destroy m shards each, rebuild, verify SHA-256.\n");
        System.out.printf("   %-12s %10s %14s %16s %16s %10s%n", "scheme", "overhead", "shards lost", "encode MB/s", "rebuild MB/s", "verified");
        for (int[] km : new int[][]{{6, 3}, {10, 4}}) {
            int k = km[0], m = km[1]; int[][] enc = matrix(k, m);
            long encNs = 0, decNs = 0; int ok = 0;
            for (byte[] obj : data) {
                int shard = (obj.length + k - 1) / k; byte[][] sh = new byte[k + m][shard];
                for (int i = 0; i < k; i++) System.arraycopy(obj, i * shard, sh[i], 0, Math.min(shard, obj.length - i * shard));
                long t0 = System.nanoTime(); encode(enc, sh, k, m); encNs += System.nanoTime() - t0;
                // destroy m shards: always some data shards
                Set<Integer> lost = new HashSet<>(); lost.add(0);                                   // always lose data shard 0 ...
                while (lost.size() < m) lost.add(r.nextInt(k + m));                                  // ... plus random others
                for (int i : lost) Arrays.fill(sh[i], (byte) 0);                                    // really wipe them
                int[] survivors = new int[k]; int c = 0; for (int i = 0; i < k + m && c < k; i++) if (!lost.contains(i)) survivors[c++] = i;
                t0 = System.nanoTime(); byte[][] rebuilt = decode(enc, sh, survivors, k, shard); decNs += System.nanoTime() - t0;
                byte[] out = new byte[obj.length]; for (int i = 0; i < k; i++) System.arraycopy(rebuilt[i], 0, out, i * shard, Math.min(shard, obj.length - i * shard));
                if (Arrays.equals(sha(out), sha(obj))) ok++;
            }
            double mb = objects * objSize / 1048576.0;
            System.out.printf("   %-12s %9.2fx %11d/%d %16.0f %16.0f %7d/%d%n", "RS(" + k + "," + m + ")", (k + m) / (double) k, m, k + m, mb / (encNs / 1e9), mb / (decNs / 1e9), ok, objects);
        }
        System.out.println("\nPART 2 — durability for 10 PB of user data (Markov model: disk AFR 2%, one repair at a time per group).\n");
        System.out.printf("   %-26s %10s %12s %14s %22s %20s%n", "scheme", "raw bytes", "repair time", "repair reads", "P(any data loss)/year", "MTTDL per group");
        System.out.printf("   %-26s %10s %12s %14s %22s %20s%n", "", "stored", "(assumed)", "per lost byte", "across 10 PB", "");
        Object[][] schemes = {{"2 replicas", 2, 1, 1, 12.0}, {"3 replicas", 3, 2, 1, 12.0}, {"RS(6,3)", 9, 3, 6, 24.0}, {"RS(10,4)", 14, 4, 10, 36.0}};
        for (Object[] s : schemes) {
            int n = (int) s[1], tol = (int) s[2], k = n - tol, reads = (int) s[3]; double repairH = (double) s[4];
            double lambda = 0.02 / (365 * 24), mu = 1 / repairH;                                   // per hour
            double mttdlH = mttdl(n, tol, lambda, mu), years = mttdlH / (365 * 24);
            double groupBytes = 64L << 20;                                                       // 64 MB of user data per group (stripe/chunk)
            double groups = 10e15 / groupBytes; double pLoss = 1 - Math.exp(-groups / years);
            System.out.printf("   %-26s %9.2fx %9.0f h %13dx %22s %17.2e y%n", s[0], n / (double) k, repairH, reads, String.format("%.2e", pLoss), years);
        }
        System.out.println("\n   → any k of the k + m shards rebuild the object bit-for-bit: the SHA-256 checks above are real, including");
        System.out.println("     lost data shards. RS(10,4) survives 4 simultaneous losses at 1.4x storage; three replicas survive 2 at 3x.");
        System.out.println("     The price is repair: rebuilding one lost shard reads k shards (6–10x the bytes), so repairs take longer and");
        System.out.println("     use more network — which is why stores keep hot, small or recent objects replicated and erasure-code the rest.");
        System.out.println("     The durability figures are a model (independent failures, fixed repair times); correlated failures — a rack,");
        System.out.println("     a bad firmware batch, a zone — dominate real losses, so shards are also spread across racks and zones.");
    }

    // expected hours to data loss for n devices tolerating tol failures; birth-death chain, single repairer
    static double mttdl(int n, int tol, double lambda, double mu) {
        int S = tol + 1; double[][] A = new double[S][S + 1];                                   // T_i = 1/out + sum p*T_j
        for (int i = 0; i < S; i++) {
            double up = (n - i) * lambda, down = i > 0 ? mu : 0, out = up + down;
            A[i][i] = 1; if (i + 1 < S) A[i][i + 1] -= up / out; if (i > 0) A[i][i - 1] -= down / out; A[i][S] = 1 / out;
        }
        for (int c = 0; c < S; c++) { int p = c; for (int i = c + 1; i < S; i++) if (Math.abs(A[i][c]) > Math.abs(A[p][c])) p = i; double[] t = A[c]; A[c] = A[p]; A[p] = t;
            for (int i = 0; i < S; i++) if (i != c) { double f = A[i][c] / A[c][c]; for (int j = c; j <= S; j++) A[i][j] -= f * A[c][j]; } }
        return A[0][S] / A[0][0];
    }
    static int[][] matrix(int k, int m) {                                                       // parity rows: Cauchy 1/(x_i + y_j)
        int[][] c = new int[m][k]; for (int i = 0; i < m; i++) for (int j = 0; j < k; j++) c[i][j] = inv((i + k) ^ j); return c;
    }
    static void encode(int[][] c, byte[][] sh, int k, int m) {
        int len = sh[0].length;
        for (int i = 0; i < m; i++) { byte[] p = sh[k + i];
            for (int j = 0; j < k; j++) { int coef = c[i][j]; byte[] d = sh[j]; int lc = LOG[coef];
                for (int b = 0; b < len; b++) { int v = d[b] & 0xff; if (v != 0) p[b] ^= EXP[lc + LOG[v]]; } } }
    }
    static byte[][] decode(int[][] c, byte[][] sh, int[] rows, int k, int len) {
        int[][] M = new int[k][k];                                                              // rows of [I ; C] for the survivors
        for (int i = 0; i < k; i++) { int r = rows[i]; if (r < k) M[i][r] = 1; else M[i] = c[r - k].clone(); }
        int[][] Inv = invert(M, k); byte[][] out = new byte[k][len];
        for (int i = 0; i < k; i++) for (int j = 0; j < k; j++) { int coef = Inv[i][j]; if (coef == 0) continue; byte[] s = sh[rows[j]], o = out[i]; int lc = LOG[coef];
            for (int b = 0; b < len; b++) { int v = s[b] & 0xff; if (v != 0) o[b] ^= EXP[lc + LOG[v]]; } }
        return out;
    }
    static int[][] invert(int[][] a, int n) {
        int[][] m = new int[n][2 * n]; for (int i = 0; i < n; i++) { System.arraycopy(a[i], 0, m[i], 0, n); m[i][n + i] = 1; }
        for (int c = 0; c < n; c++) { int p = c; while (m[p][c] == 0) p++; int[] t = m[c]; m[c] = m[p]; m[p] = t;
            int iv = inv(m[c][c]); for (int j = 0; j < 2 * n; j++) m[c][j] = mul(m[c][j], iv);
            for (int i = 0; i < n; i++) if (i != c && m[i][c] != 0) { int f = m[i][c]; for (int j = 0; j < 2 * n; j++) m[i][j] ^= mul(f, m[c][j]); } }
        int[][] r = new int[n][n]; for (int i = 0; i < n; i++) System.arraycopy(m[i], n, r[i], 0, n); return r;
    }
    static byte[] sha(byte[] b) throws Exception { return MessageDigest.getInstance("SHA-256").digest(b); }
}
