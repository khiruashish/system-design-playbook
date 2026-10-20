// L1 — The always-writable cart (Dynamo-style). Three replicas accept cart writes during a partition; afterwards the
// versions are merged. Compare: last-writer-wins (LWW), Dynamo's "union of siblings" (adds survive, deletes can
// resurrect), and an OR-Set cart (adds and removes both survive). Counts lost adds and resurrected removes.
import java.util.*;

public class CartMergeL1 {
    record Op(int replica, long ts, boolean add, String item, String tag) {}

    public static void main(String[] a) {
        int sessions = 20_000; Random r = new Random(101);
        long[] lostAdd = new long[3], resurrect = new long[3], adds = new long[1], removes = new long[1], correctCarts = new long[3];
        // one shopper, two devices, requests routed to any of 3 replicas while they are partitioned from each other
        String[] names = {"LWW (whole cart, newest timestamp)", "Dynamo union of siblings", "OR-Set (add-tags, remove-tags)"};
        for (int s = 0; s < sessions; s++) {
            // a shopper (phone + laptop) edits the cart while replicas are partitioned; clocks skew by up to ±150 ms
            List<Op> ops = new ArrayList<>(); long t = 0; Set<String> truth = new LinkedHashSet<>();   // truth = what the shopper intended
            int n = 4 + r.nextInt(8);
            for (int i = 0; i < n; i++) {
                t += 50 + r.nextInt(2000);
                int rep = r.nextInt(3); long skewed = t + (r.nextInt(301) - 150);
                String item = "sku" + r.nextInt(6);
                if (truth.contains(item) && r.nextDouble() < 0.5) {                  // remove something already in the cart
                    truth.remove(item); removes[0]++; ops.add(new Op(rep, skewed, false, item, null));
                } else if (!truth.contains(item)) {                                  // add something new
                    truth.add(item); adds[0]++; ops.add(new Op(rep, skewed, true, item, "t" + i));
                }
            }
            // each replica only sees the ops sent to it (partition), then anti-entropy merges the three replica states
            Set<String> lww = lww(ops), union = union(ops), orset = orset(ops);
            List<Set<String>> results = List.of(lww, union, orset);
            for (int k = 0; k < 3; k++) {
                Set<String> got = results.get(k);
                for (String x : truth) if (!got.contains(x)) lostAdd[k]++;
                for (String x : got) if (!truth.contains(x)) resurrect[k]++;
                if (got.equals(truth)) correctCarts[k]++;
            }
        }
        System.out.printf("%,d partitioned cart sessions, %,d adds and %,d removes spread over 3 replicas (clock skew ±150 ms)%n%n", sessions, adds[0], removes[0]);
        System.out.printf("%-38s %14s %20s %16s%n", "merge strategy", "lost adds", "resurrected removes", "cart exactly right");
        for (int k = 0; k < 3; k++)
            System.out.printf("%-38s %,14d %,20d %15.1f%%%n", names[k], lostAdd[k], resurrect[k], 100.0 * correctCarts[k] / sessions);
    }

    // per-replica state is the ops it received, applied in its own (skewed) timestamp order
    static Set<String> apply(List<Op> ops) { Set<String> c = new LinkedHashSet<>(); ops.stream().sorted(Comparator.comparingLong(Op::ts)).forEach(o -> { if (o.add()) c.add(o.item()); else c.remove(o.item()); }); return c; }
    static List<Op> at(List<Op> ops, int rep) { return ops.stream().filter(o -> o.replica() == rep).toList(); }
    // LWW: keep the whole cart of the replica whose last write has the newest timestamp
    static Set<String> lww(List<Op> ops) {
        int best = -1; long bt = Long.MIN_VALUE;
        for (int rep = 0; rep < 3; rep++) for (Op o : at(ops, rep)) if (o.ts() > bt) { bt = o.ts(); best = rep; }
        return best < 0 ? Set.of() : apply(at(ops, best));
    }
    // Dynamo: concurrent versions come back as siblings; the cart merges them by union (so an item removed on one
    // replica but still present on another comes back — the paper's "deleted items can resurface")
    static Set<String> union(List<Op> ops) { Set<String> u = new LinkedHashSet<>(); for (int rep = 0; rep < 3; rep++) u.addAll(apply(at(ops, rep))); return u; }
    // OR-Set: each add carries a unique tag; a remove deletes the tags it has SEEN. Here a remove on a replica sees the
    // add tags that the shopper's client had observed (the client sends them with the remove request).
    static Set<String> orset(List<Op> ops) {
        Map<String, Set<String>> addTags = new HashMap<>(), removed = new HashMap<>(); Map<String, Set<String>> seenByClient = new HashMap<>();
        for (Op o : ops) {   // in the order the client issued them
            if (o.add()) { addTags.computeIfAbsent(o.item(), k -> new HashSet<>()).add(o.tag()); seenByClient.computeIfAbsent(o.item(), k -> new HashSet<>()).add(o.tag()); }
            else { removed.computeIfAbsent(o.item(), k -> new HashSet<>()).addAll(seenByClient.getOrDefault(o.item(), Set.of())); seenByClient.remove(o.item()); }
        }
        Set<String> c = new LinkedHashSet<>();
        addTags.forEach((item, tags) -> { Set<String> live = new HashSet<>(tags); live.removeAll(removed.getOrDefault(item, Set.of())); if (!live.isEmpty()) c.add(item); });
        return c;
    }
}
