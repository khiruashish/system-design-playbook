import java.util.*;

/** Problem: Dynamo-style replication with N replicas, write quorum W and read quorum R; show why R + W > N gives fresh reads. */
public class QuorumReplication {

    record Versioned(String value, long version) { }

    static final class Replica {
        final String name; boolean up = true;
        final Map<String, Versioned> data = new HashMap<>();
        Replica(String name) { this.name = name; }
    }

    static final class Cluster {
        final List<Replica> replicas; final int w, r;
        long clock;                                             // production: per-key version / vector clock / HLC
        Cluster(int n, int w, int r) {
            this.replicas = new ArrayList<>(); for (int i = 0; i < n; i++) replicas.add(new Replica("r" + i));
            this.w = w; this.r = r;
        }

        /** Write to replicas in order until W acks (a coordinator would send to all and wait for W). */
        boolean write(String key, String value, List<Integer> reachableOrder) {
            Versioned v = new Versioned(value, ++clock);
            int acks = 0;
            for (int i : reachableOrder) { Replica rep = replicas.get(i); if (!rep.up) continue; rep.data.put(key, v); if (++acks == w) return true; }
            return false;                                       // not enough replicas: fail the write (sloppy quorum would hand off)
        }

        /** Read R replicas, return the highest version, and READ-REPAIR stale ones. */
        Optional<String> read(String key, List<Integer> askOrder, List<String> repairs) {
            List<Replica> asked = new ArrayList<>();
            for (int i : askOrder) { Replica rep = replicas.get(i); if (rep.up) asked.add(rep); if (asked.size() == r) break; }
            if (asked.size() < r) throw new IllegalStateException("read quorum not met");
            Versioned newest = null;
            for (Replica rep : asked) { Versioned v = rep.data.get(key); if (v != null && (newest == null || v.version() > newest.version())) newest = v; }
            if (newest == null) return Optional.empty();
            for (Replica rep : asked) {
                Versioned v = rep.data.get(key);
                if (v == null || v.version() < newest.version()) { rep.data.put(key, newest); repairs.add(rep.name); }
            }
            return Optional.of(newest.value());
        }
    }

    public static void main(String[] args) {
        List<String> repairs = new ArrayList<>();

        // N=3, W=2, R=2: R + W = 4 > 3, so every read set overlaps every write set in >= 1 replica.
        Cluster strong = new Cluster(3, 2, 2);
        strong.write("cart", "v1:[apple]", List.of(0, 1, 2));          // lands on r0, r1
        strong.write("cart", "v2:[apple,milk]", List.of(1, 2, 0));     // lands on r1, r2 (r0 still has v1)
        String read = strong.read("cart", List.of(0, 2, 1), repairs).orElseThrow();   // asks r0 (stale) + r2 (fresh)
        System.out.println("N=3 W=2 R=2 -> read " + read + ", read-repaired " + repairs);
        check(read.startsWith("v2") && repairs.equals(List.of("r0")), "overlap guarantees the newest version");

        // N=3, W=1, R=1: R + W = 2 <= 3, reads can miss the latest write.
        Cluster fast = new Cluster(3, 1, 1);
        fast.write("cart", "v1", List.of(0, 1, 2));
        fast.write("cart", "v2", List.of(1, 0, 2));
        String stale = fast.read("cart", List.of(0), new ArrayList<>()).orElseThrow();
        System.out.println("N=3 W=1 R=1 -> read " + stale + "  <-- stale: read set {r0} and write set {r1} don't overlap");
        check(stale.equals("v1"), "no overlap, stale read possible");

        // Availability: one replica down, W=2/R=2 still works; two down, writes fail.
        strong.replicas.get(2).up = false;
        System.out.println("1 of 3 down: write ok? " + strong.write("cart", "v3", List.of(0, 1, 2)) + ", read = " + strong.read("cart", List.of(0, 1, 2), new ArrayList<>()).orElseThrow());
        strong.replicas.get(1).up = false;
        System.out.println("2 of 3 down: write ok? " + strong.write("cart", "v4", List.of(0, 1, 2)));
        check(!strong.write("cart", "v5", List.of(0, 1, 2)), "quorum unavailable");
        System.out.println("PASS");
    }

    static void check(boolean ok, String what) { if (!ok) { System.out.println("FAILED: " + what); System.exit(1); } }
}
