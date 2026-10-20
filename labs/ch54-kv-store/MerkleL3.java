// MerkleL3.java — run with: java -Xmx3g MerkleL3.java   (JDK 21, no dependencies)
// ANTI-ENTROPY: FINDING WHAT TWO REPLICAS DISAGREE ON. Replica A holds 2,000,000 keys; replica B is the same except for
// writes it missed while it was down (fixed seed). To repair, the replicas must find the differing keys. REAL hashing
// (SHA-256) of real key/value data:
//   F  full comparison: B sends every (key, value-hash) pair to A
//   M  Merkle trees over the key space (keys hashed into 2^depth leaf ranges; each inner node = hash of its children):
//      exchange root hashes, descend only into subtrees whose hashes differ, then exchange only the differing leaves
// Swept over the number of differing keys (10 → 200,000) and leaf sizes. After repair, both roots must match.
// Measured: bytes exchanged to find the differences, round trips, and real time to build a tree.
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;

public class MerkleL3 {
    static final int KEYS = 2_000_000;

    public static void main(String[] args) throws Exception {
        String[] keys = new String[KEYS]; for (int i = 0; i < KEYS; i++) keys[i] = String.format("order:%010d", i);
        byte[][] valA = new byte[KEYS][]; for (int i = 0; i < KEYS; i++) valA[i] = sha(("v" + i).getBytes());
        long fullBytes = (long) KEYS * (16 + 32);
        System.out.printf("2,000,000 keys per replica; full comparison ships %.1f MB every time.%n%n", fullBytes / 1048576.0);
        System.out.printf("   %-18s %-22s %16s %12s %16s %12s%n", "differing keys", "method", "bytes exchanged", "round trips", "keys to repair", "roots equal");
        for (int diff : new int[]{10, 1_000, 20_000, 200_000}) {
            Random r = new Random(54 + diff); byte[][] valB = valA.clone(); Set<Integer> changed = new HashSet<>();
            while (changed.size() < diff) { int i = r.nextInt(KEYS); if (changed.add(i)) valB[i] = r.nextInt(5) == 0 ? new byte[32] : sha(("old" + i).getBytes()); }
            System.out.printf("   %,-18d %-22s %16s %12d %,16d %12s%n", diff, "F full pairs", human(fullBytes), 1, diff, "yes");
            for (int depth : new int[]{15, 18}) {
                long t0 = System.nanoTime(); byte[][][] ta = tree(keys, valA, depth); double buildMs = (System.nanoTime() - t0) / 1e6; byte[][][] tb = tree(keys, valB, depth);
                // descend: at each level, compare hashes of children of differing parents (each hash 32 B + 4 B id, both ways)
                long bytes = 32; int trips = 1; List<Integer> frontier = new ArrayList<>(); if (!Arrays.equals(ta[0][0], tb[0][0])) frontier.add(0);
                for (int lvl = 1; lvl <= depth && !frontier.isEmpty(); lvl++) { List<Integer> next = new ArrayList<>();
                    for (int p : frontier) for (int c = 2 * p; c <= 2 * p + 1; c++) { bytes += 36; if (!Arrays.equals(ta[lvl][c], tb[lvl][c])) next.add(c); }
                    frontier = next; trips++; }
                int leafSize = KEYS >> depth; long leafBytes = (long) frontier.size() * leafSize * (16 + 32); bytes += leafBytes; trips++;
                // repair: copy A's values for keys in differing leaves whose hash differs
                Set<Integer> dl = new HashSet<>(frontier); int repaired = 0; byte[][] valB2 = valB.clone();
                for (int i = 0; i < KEYS; i++) if (dl.contains(leafOf(keys[i], depth)) && !Arrays.equals(valA[i], valB2[i])) { valB2[i] = valA[i]; repaired++; }
                boolean equal = Arrays.equals(tree(keys, valB2, depth)[0][0], ta[0][0]);
                System.out.printf("   %-18s %-22s %16s %12d %,16d %12s%s%n", "", "M depth " + depth + " (~" + leafSize + "/leaf)", human(bytes), trips, repaired, equal ? "yes" : "NO",
                        diff == 10 ? String.format("   (tree build %.0f ms)", buildMs) : "");
            }
        }
        System.out.println("\n   → a Merkle tree lets two replicas agree that billions of bytes match by comparing one 32-byte hash, and find");
        System.out.println("     a few differences by walking only the branches that differ: kilobytes instead of 92 MB. The saving shrinks as");
        System.out.println("     divergence grows — when a large share of leaves differ, shipping everything is as cheap and needs one round");
        System.out.println("     trip. Deeper trees mean smaller leaves (less to ship per difference) but more levels and more tree to keep");
        System.out.println("     up to date. This is how Dynamo-style stores (Cassandra repair, Riak AAE) repair replicas in the background.");
    }

    static String human(long b) { return b < 1 << 20 ? String.format("%.1f KB", b / 1024.0) : String.format("%.1f MB", b / 1048576.0); }
    static int leafOf(String k, int depth) { return (k.hashCode() * 0x9E3779B9 >>> (32 - depth)); }
    static byte[][][] tree(String[] keys, byte[][] vals, int depth) throws Exception {
        MessageDigest[] mds = new MessageDigest[1 << depth]; byte[][][] t = new byte[depth + 1][][];
        for (int i = 0; i < keys.length; i++) { int l = leafOf(keys[i], depth); if (mds[l] == null) mds[l] = MessageDigest.getInstance("SHA-256"); mds[l].update(keys[i].getBytes()); mds[l].update(vals[i]); }
        t[depth] = new byte[1 << depth][]; for (int l = 0; l < (1 << depth); l++) t[depth][l] = mds[l] == null ? new byte[32] : mds[l].digest();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (int lvl = depth - 1; lvl >= 0; lvl--) { t[lvl] = new byte[1 << lvl][]; for (int i = 0; i < (1 << lvl); i++) { md.update(t[lvl + 1][2 * i]); md.update(t[lvl + 1][2 * i + 1]); t[lvl][i] = md.digest(); } }
        return t;
    }
    static byte[] sha(byte[] b) throws Exception { return MessageDigest.getInstance("SHA-256").digest(b); }
}
