package com.sdp.ci.p01shortener;

/** Same algorithm as the interview solution: bijective base62 over non-negative longs. */
public final class Base62 {
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private Base62() {}

    public static String encode(long id) {
        if (id < 0) throw new IllegalArgumentException("id must be >= 0");
        if (id == 0) return "0";
        StringBuilder sb = new StringBuilder();
        while (id > 0) { sb.append(ALPHABET.charAt((int) (id % 62))); id /= 62; }
        return sb.reverse().toString();
    }

    public static long decode(String code) {
        long id = 0;
        for (char c : code.toCharArray()) {
            int d = ALPHABET.indexOf(c);
            if (d < 0) throw new IllegalArgumentException("not base62: " + c);
            id = Math.addExact(Math.multiplyExact(id, 62), d);
        }
        return id;
    }
}
