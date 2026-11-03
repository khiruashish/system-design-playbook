package com.sdp.ci.p04bloom;

import java.nio.charset.StandardCharsets;

/** Sizing and hashing from the interview solution: m = -n ln p / (ln 2)^2, k = (m/n) ln 2, Kirsch-Mitzenmacher double hashing. */
public final class BloomMath {
    private BloomMath() {}

    public static long bits(long expectedItems, double falsePositiveRate) {
        return (long) Math.ceil(-expectedItems * Math.log(falsePositiveRate) / (Math.log(2) * Math.log(2)));
    }

    public static int hashes(long bits, long expectedItems) {
        return Math.max(1, (int) Math.round((double) bits / expectedItems * Math.log(2)));
    }

    /** k bit positions from two 64-bit hashes: h1 + i*h2. */
    public static long[] positions(String item, int k, long m) {
        byte[] data = item.getBytes(StandardCharsets.UTF_8);
        long h1 = fnv1a64(data), h2 = mix(h1 ^ 0x9E3779B97F4A7C15L) | 1;
        long[] pos = new long[k];
        for (int i = 0; i < k; i++) pos[i] = Math.floorMod(h1 + i * h2, m);
        return pos;
    }

    private static long fnv1a64(byte[] data) {
        long h = 0xcbf29ce484222325L;
        for (byte b : data) { h ^= (b & 0xff); h *= 0x100000001b3L; }
        return mix(h);
    }

    private static long mix(long z) {                    // SplitMix64 finaliser: spreads bits evenly
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
