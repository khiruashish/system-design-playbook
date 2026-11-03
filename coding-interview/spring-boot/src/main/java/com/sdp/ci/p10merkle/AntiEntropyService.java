package com.sdp.ci.p10merkle;

import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Compare two replicas top-down: equal subtree hashes are skipped entirely, so only the differing
 * buckets are read and transferred. In a real cluster each node keeps its own tree and only the
 * hashes cross the network; here both replicas live in one Redis for a self-contained demo.
 */
@Service
public class AntiEntropyService {
    public record Report(int hashComparisons, List<Integer> differingBuckets, int keysTransferred, int keysRepaired, long totalKeys) {}

    private final ReplicaStore store;

    public AntiEntropyService(ReplicaStore store) { this.store = store; }

    public Report sync(String a, String b) {
        MerkleTree ta = MerkleTree.build(store, a), tb = MerkleTree.build(store, b);
        int[] comparisons = {0};
        List<Integer> differing = new ArrayList<>();
        diff(ta, tb, 1, comparisons, differing);

        int transferred = 0, repaired = 0;
        for (int bucket : differing) {
            Map<String, String> ma = store.bucket(a, bucket), mb = store.bucket(b, bucket);
            transferred += ma.size() + mb.size();
            Set<String> keys = new TreeSet<>(ma.keySet());
            keys.addAll(mb.keySet());
            for (String key : keys) {
                String va = ma.get(key), vb = mb.get(key);
                if (Objects.equals(va, vb)) continue;
                String winner = newer(va, vb);                 // last-writer-wins by version
                String[] parts = winner.split("\\|", 2);
                if (!winner.equals(va)) { store.put(a, key, Long.parseLong(parts[0]), parts[1]); repaired++; }
                if (!winner.equals(vb)) { store.put(b, key, Long.parseLong(parts[0]), parts[1]); repaired++; }
            }
        }
        return new Report(comparisons[0], differing, transferred, repaired, Math.max(store.size(a), store.size(b)));
    }

    public String rootHash(String replica) { return MerkleTree.build(store, replica).rootHex(); }

    private void diff(MerkleTree ta, MerkleTree tb, int node, int[] comparisons, List<Integer> out) {
        comparisons[0]++;
        if (Arrays.equals(ta.node(node), tb.node(node))) return;      // whole subtree identical: skip it
        if (node >= ta.leaves()) { out.add(node - ta.leaves()); return; }
        diff(ta, tb, 2 * node, comparisons, out);
        diff(ta, tb, 2 * node + 1, comparisons, out);
    }

    private static String newer(String x, String y) {
        if (x == null) return y;
        if (y == null) return x;
        return ReplicaStore.version(x) >= ReplicaStore.version(y) ? x : y;
    }
}
