package com.sdp.ci.p10merkle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Binary hash tree over the bucket hashes. nodes[1] is the root; nodes[BUCKETS + i] is leaf i (heap layout). */
public final class MerkleTree {
    private final byte[][] nodes;
    private final int leaves;

    private MerkleTree(byte[][] nodes, int leaves) { this.nodes = nodes; this.leaves = leaves; }

    public static MerkleTree build(ReplicaStore store, String replica) {
        int n = ReplicaStore.BUCKETS;
        byte[][] nodes = new byte[2 * n][];
        for (int b = 0; b < n; b++) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : store.bucket(replica, b).entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
            nodes[n + b] = sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        for (int i = n - 1; i >= 1; i--) nodes[i] = sha256(concat(nodes[2 * i], nodes[2 * i + 1]));
        return new MerkleTree(nodes, n);
    }

    public byte[] node(int index) { return nodes[index]; }
    public int leaves() { return leaves; }
    public String rootHex() { return HexFormat.of().formatHex(nodes[1]).substring(0, 16); }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] sha256(byte[] data) {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
