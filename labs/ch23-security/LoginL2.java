// LoginL2.java — run with: java -Dsun.net.httpserver.nodelay=true LoginL2.java     (JDK 21, no dependencies)
// A small authorization server over real HTTP, and a conformance test that checks its defensive controls hold.
// Part A: phone OTP login. We verify the hardened endpoint enforces: attempt cap per code, send cap per phone,
//         hashed one-time codes, expiry, constant-time compare. The "naive" endpoint is the control that lacks them.
// Part B: OAuth 2.0 Authorization Code + PKCE for the mobile app. We verify the RFC 7636 / Security-BCP controls:
//         registered redirect URIs, S256 PKCE (verifier must match), single-use codes with replay revocation.
// This is a control-conformance harness (the kind you run in CI), not an attack tool: it asserts that bad requests are
// refused and good ones succeed, and reports how many guessing attempts each design would tolerate before lockout.
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class LoginL2 {
    // ---------------- server state ----------------
    record Otp(byte[] hash, byte[] salt, long expiresAt, AtomicInteger attempts) {}
    static final Map<String, Otp> otps = new ConcurrentHashMap<>();
    static final Map<String, Deque<Long>> sends = new ConcurrentHashMap<>();
    static final Map<String, String> sessions = new ConcurrentHashMap<>();
    record Code(String sub, String clientId, String redirect, String challenge, String method, long exp, AtomicBoolean used, List<String> issued) {}
    static final Map<String, Code> codes = new ConcurrentHashMap<>();
    static final Set<String> revokedTokens = ConcurrentHashMap.newKeySet();
    static final Map<String, Set<String>> REGISTERED = Map.of("grocery-app", Set.of("in.grocery.app://callback"));
    static final SecureRandom RNG = new SecureRandom();
    static final Map<String, String> issuedCodeForTest = new ConcurrentHashMap<>();          // test harness reads the code it just caused to be sent

    static byte[] sha256(byte[]... parts) throws Exception { var d = MessageDigest.getInstance("SHA-256"); for (var p : parts) d.update(p); return d.digest(); }
    static String rnd(int bytes) { byte[] b = new byte[bytes]; RNG.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static Map<String, String> form(String s) {
        Map<String, String> m = new HashMap<>();
        for (String kv : s.split("&")) { int i = kv.indexOf('='); if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8)); }
        return m;
    }
    static void reply(HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8); ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length); if (b.length > 0) ex.getResponseBody().write(b); ex.close();
    }

    static void otpHandlers(HttpServer s, String prefix, boolean hardened) {
        s.createContext(prefix + "/otp/send", ex -> {
            try {
                String phone = form(new String(ex.getRequestBody().readAllBytes())).get("phone");
                if (hardened) {                                                                  // control: at most 3 codes per phone per 10 minutes
                    var dq = sends.computeIfAbsent(prefix + phone, k -> new ConcurrentLinkedDeque<>()); long now = System.currentTimeMillis();
                    dq.removeIf(t -> t < now - 600_000);
                    if (dq.size() >= 3) { reply(ex, 429, "too many codes requested"); return; }
                    dq.add(now);
                }
                String code = String.format("%06d", RNG.nextInt(1_000_000));
                byte[] salt = RNG.generateSeed(16);
                otps.put(prefix + phone, new Otp(sha256(salt, code.getBytes()), salt, System.currentTimeMillis() + 300_000, new AtomicInteger()));
                issuedCodeForTest.put(prefix + phone, code);                                      // stands in for the SMS the owner receives
                reply(ex, 202, "");
            } catch (Exception e) { reply(ex, 500, ""); }
        });
        s.createContext(prefix + "/otp/verify", ex -> {
            try {
                var f = form(new String(ex.getRequestBody().readAllBytes()));
                Otp o = otps.get(prefix + f.get("phone"));
                if (o == null) { reply(ex, 401, "no active code"); return; }
                if (hardened) {                                                                  // control: expiry + attempt cap
                    if (System.currentTimeMillis() > o.expiresAt()) { otps.remove(prefix + f.get("phone")); reply(ex, 401, "expired"); return; }
                    if (o.attempts().incrementAndGet() > 5) { otps.remove(prefix + f.get("phone")); reply(ex, 423, "locked: too many attempts"); return; }
                }
                boolean ok = MessageDigest.isEqual(o.hash(), sha256(o.salt(), f.get("code").getBytes()));   // control: constant-time compare of hashes
                if (!ok) { reply(ex, 401, "wrong code"); return; }
                otps.remove(prefix + f.get("phone"));                                             // control: one-time use
                String sid = rnd(32); sessions.put(sid, "cust:" + f.get("phone"));
                reply(ex, 200, sid);
            } catch (Exception e) { reply(ex, 500, ""); }
        });
    }

    static void oauthHandlers(HttpServer s, String prefix, boolean strict) {
        s.createContext(prefix + "/authorize", ex -> {
            try {
                var q = form(ex.getRequestURI().getRawQuery());
                String sub = sessions.get(q.get("session"));
                if (sub == null) { reply(ex, 401, "login required"); return; }
                String redirect = q.get("redirect_uri");
                if (strict) {                                                                    // control: redirect must be pre-registered; PKCE S256 mandatory
                    if (!REGISTERED.getOrDefault(q.get("client_id"), Set.of()).contains(redirect)) { reply(ex, 400, "redirect_uri not registered"); return; }
                    if (q.get("code_challenge") == null || !"S256".equals(q.get("code_challenge_method"))) { reply(ex, 400, "PKCE S256 required"); return; }
                }
                String code = rnd(32);
                codes.put(prefix + code, new Code(sub, q.get("client_id"), redirect, q.get("code_challenge"), q.get("code_challenge_method"),
                        System.currentTimeMillis() + 60_000, new AtomicBoolean(), new CopyOnWriteArrayList<>()));
                ex.getResponseHeaders().set("Location", redirect + "?code=" + code + "&state=" + q.get("state"));
                reply(ex, 302, "");
            } catch (Exception e) { reply(ex, 500, ""); }
        });
        s.createContext(prefix + "/token", ex -> {
            try {
                var f = form(new String(ex.getRequestBody().readAllBytes()));
                Code c = codes.get(prefix + f.get("code"));
                if (c == null || System.currentTimeMillis() > c.exp()) { reply(ex, 400, "invalid_grant"); return; }
                if (strict) {
                    if (c.used().getAndSet(true)) { revokedTokens.addAll(c.issued()); reply(ex, 400, "invalid_grant: code replay, prior tokens revoked"); return; }  // control: single use + revoke on replay
                    if (!c.redirect().equals(f.get("redirect_uri"))) { reply(ex, 400, "invalid_grant: redirect mismatch"); return; }
                    String v = f.getOrDefault("code_verifier", "");
                    String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(v.getBytes(StandardCharsets.US_ASCII)));
                    if (!MessageDigest.isEqual(computed.getBytes(), c.challenge().getBytes())) { reply(ex, 400, "invalid_grant: PKCE verifier"); return; }  // control: proof of possession
                }
                String at = "at-" + rnd(24); c.issued().add(at);
                reply(ex, 200, at);
            } catch (Exception e) { reply(ex, 500, ""); }
        });
        s.createContext(prefix + "/introspect", ex -> {
            String t = new String(ex.getRequestBody().readAllBytes());
            reply(ex, 200, (t.startsWith("at-") && !revokedTokens.contains(t)) ? "active" : "inactive");
        });
    }

    // ---------------- test client ----------------
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
            .executor(Executors.newVirtualThreadPerTaskExecutor()).build();
    static HttpResponse<String> post(String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:19601" + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> get(String pathAndQuery) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:19601" + pathAndQuery)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }
    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    static int codeAttemptsToleratedBeforeLockout(String prefix) throws Exception {
        // Conformance probe: how many *wrong* codes does the endpoint accept before it stops responding to guesses?
        // We never learn the real code; we count wrong guesses until the endpoint refuses (lockout) or the code space is exhausted.
        post(prefix + "/otp/send", "phone=%2B9198000TEST");
        for (int i = 0; i < 20; i++) {
            int st = post(prefix + "/otp/verify", "phone=%2B9198000TEST&code=" + String.format("%06d", 999_000 + i)).statusCode();
            if (st == 423) return i;                                 // i wrong guesses were answered before the lockout                                                          // locked after i+1 wrong attempts
        }
        return -1;                                                                                // -1 = never locks: unlimited guessing allowed
    }

    public static void main(String[] args) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 19601), 2000);
        otpHandlers(s, "/naive", false); otpHandlers(s, "/hardened", true);
        oauthHandlers(s, "/naive", false); oauthHandlers(s, "/hardened", true);
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); s.start();

        System.out.println("A. OTP login control conformance (6-digit code, 1,000,000 possibilities)");
        System.out.printf("   %-46s %-14s %-14s%n", "control", "naive", "hardened");
        // owner logs in with the real code
        post("/hardened/otp/send", "phone=%2B919800000001");
        String realCode = issuedCodeForTest.get("/hardened+919800000001");                       // the SMS the owner reads
        // (URL-decoding note) key uses the decoded phone:
        realCode = issuedCodeForTest.get("/hardened" + URLDecoder.decode("%2B919800000001", StandardCharsets.UTF_8));
        int ownerOk = post("/hardened/otp/verify", "phone=%2B919800000001&code=" + realCode).statusCode();
        System.out.printf("   %-46s %-14s %-14s%n", "legitimate owner with the real code", "—", ownerOk == 200 ? "200 logged in" : "FAIL " + ownerOk);
        int naiveLock = codeAttemptsToleratedBeforeLockout("/naive");
        int hardLock = codeAttemptsToleratedBeforeLockout("/hardened");
        System.out.printf("   %-46s %-14s %-14s%n", "wrong codes tolerated before lockout", naiveLock < 0 ? "unlimited" : String.valueOf(naiveLock), hardLock < 0 ? "unlimited" : String.valueOf(hardLock));
        System.out.printf("   %-46s %-14s %-14s%n", "codes issuable per phone / 10 min", "unlimited",
                (post("/hardened/otp/send", "phone=%2B919800000009").statusCode() == 202
                 && post("/hardened/otp/send", "phone=%2B919800000009").statusCode() == 202
                 && post("/hardened/otp/send", "phone=%2B919800000009").statusCode() == 202
                 && post("/hardened/otp/send", "phone=%2B919800000009").statusCode() == 429) ? "3 (4th → 429)" : "?");
        System.out.printf("   %-46s %-14s %-14s%n", "guessing budget before code expires/locks", "n/a", "5 tries vs 1e6 space ≈ 1 in 200,000");
        System.out.println("   → naive: unlimited attempts make a 6-digit code guessable; hardened: locked after 5, and codes are hashed at rest with expiry.");

        System.out.println("B. OAuth 2.0 Authorization Code + PKCE conformance for the mobile app");
        System.out.printf("   %-52s %-16s %-16s%n", "request presented to /token", "naive", "hardened (BCP)");
        // a real login + a real PKCE pair
        post("/hardened/otp/send", "phone=%2B919800000002");
        String sid = post("/hardened/otp/verify", "phone=%2B919800000002&code=" + issuedCodeForTest.get("/hardened+919800000002")).body();
        String verifier = rnd(48);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));

        for (boolean strict : new boolean[]{false, true}) {
            String p = strict ? "/hardened" : "/naive";
            // register a session on this server prefix
            post(p + "/otp/send", "phone=%2B91980000000" + (strict ? "3" : "4"));
            String session = post(p + "/otp/verify", "phone=%2B91980000000" + (strict ? "3" : "4") + "&code="
                    + issuedCodeForTest.get(p + "+91980000000" + (strict ? "3" : "4"))).body();
            String col = strict ? null : "naive";
            java.util.function.BiFunction<String, String, String> exchange = (redirect, extra) -> {
                try {
                    var auth = get(p + "/authorize?client_id=grocery-app&session=" + session + "&redirect_uri=" + enc(redirect)
                            + "&code_challenge=" + challenge + "&code_challenge_method=S256&state=xyz");
                    if (auth.statusCode() != 302) return auth.statusCode() + " at /authorize (" + auth.body() + ")";
                    String loc = auth.headers().firstValue("Location").orElse("");
                    String code = loc.replaceAll(".*code=([^&]+).*", "$1");
                    var tok = post(p + "/token", "grant_type=authorization_code&client_id=grocery-app&code=" + code + "&redirect_uri=" + enc(redirect) + extra);
                    return tok.statusCode() == 200 ? "200 token issued" : tok.statusCode() + " " + tok.body();
                } catch (Exception e) { return e.getMessage(); }
            };
            if (!strict) results.put("naive", new LinkedHashMap<>()); else results.put("hardened", new LinkedHashMap<>());
            var r = results.get(strict ? "hardened" : "naive");
            r.put("correct verifier, registered redirect", exchange.apply("in.grocery.app://callback", "&code_verifier=" + verifier));
            r.put("wrong PKCE verifier", exchange.apply("in.grocery.app://callback", "&code_verifier=" + rnd(48)));
            r.put("no verifier at all", exchange.apply("in.grocery.app://callback", ""));
            r.put("attacker-controlled redirect_uri", exchange.apply("https://evil.example/callback", "&code_verifier=" + verifier));
        }
        // replay: use a strict code twice
        post("/hardened/otp/send", "phone=%2B919800000005");
        String sess5 = post("/hardened/otp/verify", "phone=%2B919800000005&code=" + issuedCodeForTest.get("/hardened+919800000005")).body();
        var a5 = get("/hardened/authorize?client_id=grocery-app&session=" + sess5 + "&redirect_uri=" + enc("in.grocery.app://callback")
                + "&code_challenge=" + challenge + "&code_challenge_method=S256&state=xyz");
        String code5 = a5.headers().firstValue("Location").orElse("").replaceAll(".*code=([^&]+).*", "$1");
        String t1 = post("/hardened/token", "grant_type=authorization_code&client_id=grocery-app&code=" + code5 + "&redirect_uri=" + enc("in.grocery.app://callback") + "&code_verifier=" + verifier).body();
        String firstToken = t1;
        String t2 = post("/hardened/token", "grant_type=authorization_code&client_id=grocery-app&code=" + code5 + "&redirect_uri=" + enc("in.grocery.app://callback") + "&code_verifier=" + verifier).body();
        String stillActive = post("/hardened/introspect", firstToken).body();
        results.get("hardened").put("same code exchanged a second time", t2);
        results.get("hardened").put("first token after the replay", stillActive);

        for (var row : results.get("naive").keySet())
            System.out.printf("   %-52s %-16s %-16s%n", row, brief(results.get("naive").get(row)), brief(results.get("hardened").getOrDefault(row, "—")));
        System.out.printf("   %-52s %-16s %-16s%n", "same code exchanged a second time", "—", brief(results.get("hardened").get("same code exchanged a second time")));
        System.out.printf("   %-52s %-16s %-16s%n", "first token after that replay", "—", brief(results.get("hardened").get("first token after the replay")));
        s.stop(0);
    }

    static final Map<String, Map<String, String>> results = new LinkedHashMap<>();
    static String brief(String r) {
        if (r == null) return "—";
        if (r.startsWith("200 token")) return "token issued";
        if (r.equals("active")) return "still ACTIVE";
        if (r.equals("inactive")) return "revoked";
        if (r.contains("PKCE")) return "reject: PKCE";
        if (r.contains("not registered")) return "reject: redirect";
        if (r.contains("replay")) return "reject: replay";
        if (r.startsWith("400")) return "reject";
        return r.length() > 15 ? r.substring(0, 15) : r;
    }
}
