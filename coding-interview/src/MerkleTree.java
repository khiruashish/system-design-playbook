import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Problem: two replicas of a key range; find WHICH keys differ while exchanging as few hashes as possible (anti-entropy). */
public class MerkleTree {

    static final class Node {
        final byte[] hash; final Node left, right; final int lo, hi;     // covers buckets [lo, hi)
        Node(byte[] hash, Node left, Node right, int lo, int hi) { this.hash = hash; this.left = left; this.right = right; this.lo = lo; this.hi = hi; }
        boolean leaf() { return left == null; }
    }

    static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Leaves = hash of each key bucket's contents (sorted). Parents = hash(left || right). */
    static Node build(List<TreeMap<String, String>> buckets, int lo, int hi) {
        if (hi - lo == 1) {
            StringBuilder sb = new StringBuilder();
            buckets.get(lo).forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
            return new Node(sha256(sb.toString().getBytes(StandardCharsets.UTF_8)), null, null, lo, hi);
        }
        int mid = (lo + hi) / 2;
        Node l = build(buckets, lo, mid), r = build(buckets, mid, hi);
        return new Node(sha256(l.hash, r.hash), l, r, lo, hi);
    }

    /** Walk both trees top-down; descend only into subtrees whose hashes differ. */
    static void diff(Node a, Node b, List<Integer> out, int[] compared) {
        compared[0]++;
        if (Arrays.equals(a.hash, b.hash)) return;
        if (a.leaf()) { out.add(a.lo); return; }
        diff(a.left, b.left, out, compared);
        diff(a.right, b.right, out, compared);
    }

    static List<TreeMap<String, String>> bucketise(Map<String, String> data, int nBuckets) {
        List<TreeMap<String, String>> b = new ArrayList<>();
        for (int i = 0; i < nBuckets; i++) b.add(new TreeMap<>());
        data.forEach((k, v) -> b.get(Math.floorMod(k.hashCode(), nBuckets)).put(k, v));
        return b;
    }

    public static void main(String[] args) {
        Map<String, String> replicaA = new HashMap<>();
        for (int i = 0; i < 100_000; i++) replicaA.put("key-" + i, "v" + i);
        Map<String, String> replicaB = new HashMap<>(replicaA);
        replicaB.put("key-4242", "STALE");                 // a missed write
        replicaB.remove("key-77777");                      // a missed insert

        int nBuckets = 1024;
        var bucketsA = bucketise(replicaA, nBuckets);
        var bucketsB = bucketise(replicaB, nBuckets);
        Node ta = build(bucketsA, 0, nBuckets), tb = build(bucketsB, 0, nBuckets);

        List<Integer> differing = new ArrayList<>();
        int[] compared = {0};
        diff(ta, tb, differing, compared);
        Set<String> keysToSync = new TreeSet<>();
        for (int bucket : differing) {
            Set<String> keys = new HashSet<>(bucketsA.get(bucket).keySet());
            keys.addAll(bucketsB.get(bucket).keySet());
            for (String k : keys) if (!Objects.equals(bucketsA.get(bucket).get(k), bucketsB.get(bucket).get(k))) keysToSync.add(k);
        }
        System.out.println("100000 keys, 1024 buckets: roots equal? " + Arrays.equals(ta.hash, tb.hash));
        System.out.println("hash comparisons: " + compared[0] + " (vs 1024 leaf hashes or 100000 values)");
        System.out.println("differing buckets: " + differing + " -> keys to repair: " + keysToSync);
        check(keysToSync.equals(new TreeSet<>(List.of("key-4242", "key-77777"))), "exactly the divergent keys");
        check(compared[0] < 50, "logarithmic number of comparisons");
        System.out.println("PASS");
    }

    static void check(boolean ok, String what) { if (!ok) { System.out.println("FAILED: " + what); System.exit(1); } }
}
