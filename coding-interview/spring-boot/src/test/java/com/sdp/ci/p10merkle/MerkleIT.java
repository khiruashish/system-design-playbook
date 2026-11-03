package com.sdp.ci.p10merkle;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdp.ci.support.RedisIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MerkleIT extends RedisIntegrationTest {
    @Autowired ReplicaStore store;
    @Autowired AntiEntropyService antiEntropy;

    @Test
    void merkleDiffRepairsOnlyDivergentBuckets() {
        var t = transcript("MerkleTreeSpringBoot");
        for (int i = 0; i < 1_000; i++) {
            store.put("a", "user:" + i, 1, "v1");
            store.put("b", "user:" + i, 1, "v1");
        }
        store.put("a", "user:17", 2, "v2");           // writes replica b missed while it was partitioned
        store.put("a", "user:404", 2, "v2");
        store.put("a", "user:777", 2, "v2");
        store.put("b", "user:1000", 1, "new");        // and one write only b received
        t.note("2 replicas x 1,000 keys in " + ReplicaStore.BUCKETS + " buckets; 3 updates missed by b, 1 new key missed by a");

        var rootA = t.get("/api/v1/replicas/a/merkle-root");
        var rootB = t.get("/api/v1/replicas/b/merkle-root");
        assertThat(rootA.body()).isNotEqualTo(rootB.body().replace("\"b\"", "\"a\""));

        var report = t.post("/api/v1/replicas/anti-entropy?a=a&b=b", null);
        assertThat(report.body()).contains("\"keysRepaired\":4");
        var r = antiEntropy.sync("a", "b");
        t.note("second sync: " + r.hashComparisons() + " hash comparison, " + r.differingBuckets().size() + " differing buckets, "
                + r.keysTransferred() + " keys transferred (replicas converged)");
        assertThat(r.hashComparisons()).isEqualTo(1);
        assertThat(antiEntropy.rootHash("a")).isEqualTo(antiEntropy.rootHash("b"));
        t.pass();
    }
}
