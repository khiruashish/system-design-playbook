import java.util.*;

/** Problem: design the core of a URL shortener: id generation, base62 codes, custom aliases, redirect lookup. */
public class UrlShortener {

    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    static String encode(long id) {
        if (id == 0) return "0";
        StringBuilder sb = new StringBuilder();
        while (id > 0) { sb.append(ALPHABET.charAt((int) (id % 62))); id /= 62; }
        return sb.reverse().toString();
    }

    static long decode(String code) {
        long id = 0;
        for (char c : code.toCharArray()) {
            int digit = ALPHABET.indexOf(c);
            if (digit < 0) throw new IllegalArgumentException("invalid code " + code);
            id = Math.multiplyExact(id, 62) + digit;          // overflow-safe for hostile input
        }
        return id;
    }

    static final class Shortener {
        private long nextId;                                   // production: DB sequence / range allocator per node
        private final Map<String, String> codeToUrl = new HashMap<>();
        private final Map<String, String> urlToCode = new HashMap<>();   // optional: same long URL -> same code

        Shortener(long startId) { this.nextId = startId; }

        synchronized String shorten(String url) {
            validate(url);
            return urlToCode.computeIfAbsent(url, u -> { String code = encode(nextId++); codeToUrl.put(code, u); return code; });
        }

        synchronized String shortenWithAlias(String url, String alias) {
            validate(url);
            if (!alias.matches("[A-Za-z0-9_-]{3,30}")) throw new IllegalArgumentException("alias must be 3-30 chars [A-Za-z0-9_-]");
            if (codeToUrl.putIfAbsent(alias, url) != null) throw new IllegalStateException("alias taken: " + alias);
            return alias;
        }

        /** 301 (permanent, cacheable by browsers) vs 302 (temporary, every click reaches us for analytics). */
        Optional<String> resolve(String code) { return Optional.ofNullable(codeToUrl.get(code)); }

        private static void validate(String url) {
            if (!url.startsWith("https://") && !url.startsWith("http://")) throw new IllegalArgumentException("not an http(s) URL");
        }
    }

    public static void main(String[] args) {
        System.out.println("encode(125)        = " + encode(125) + "   (125 = 2*62 + 1)");
        System.out.println("encode(3.5 trillion) = " + encode(3_521_614_606_207L) + " (7 chars cover 62^7 = 3.5e12 URLs)");
        for (long id : new long[]{0, 61, 62, 999_999_999_999L}) check(decode(encode(id)) == id, "round trip " + id);

        Shortener s = new Shortener(100_000_000L);            // start high so codes are not 1-2 chars and guessable-sequential
        String a = s.shorten("https://example.com/articles/2025/system-design?ref=newsletter");
        String b = s.shorten("https://example.com/other");
        String again = s.shorten("https://example.com/articles/2025/system-design?ref=newsletter");
        String alias = s.shortenWithAlias("https://example.com/sale", "big-sale");
        System.out.println("short codes        = " + a + ", " + b + " (same URL again -> " + again + "), alias " + alias);
        System.out.println("resolve(" + a + ")       = " + s.resolve(a).orElse("404"));
        System.out.println("resolve(nope)      = " + s.resolve("nope").orElse("404"));
        try { s.shortenWithAlias("https://x.com", "big-sale"); } catch (IllegalStateException e) { System.out.println(e.getMessage()); }

        check(a.equals(again) && !a.equals(b), "dedupe + unique codes");
        check(s.resolve("big-sale").orElseThrow().endsWith("/sale"), "alias resolves");
        try { decode("zzzzzzzzzzzzzzzzzzzz"); check(false, "overflow"); } catch (ArithmeticException e) { System.out.println("decode of a 20-char code -> overflow detected"); }
        System.out.println("PASS");
    }

    static void check(boolean ok, String what) { if (!ok) { System.out.println("FAILED: " + what); System.exit(1); } }
}
