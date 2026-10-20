// TokensL1.java — run with: java TokensL1.java      (JDK 17+, no dependencies)
// JWTs built from the JDK's own crypto: issue RS256/ES256/HS256 tokens, then validate a battery of good and forged tokens
// with a NAIVE validator (trusts the header) and a HARDENED one (allow-listed alg per kid, iss/aud/exp/nbf, denylist).
// Also: key rotation through a JWKS with two keys, refresh-token rotation with reuse detection, and sign/verify cost.
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class TokensL1 {
    static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    static final Base64.Decoder D64 = Base64.getUrlDecoder();
    static String b64(String s) { return B64.encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    static String unb64(String s) { return new String(D64.decode(s), StandardCharsets.UTF_8); }

    /** Tiny flat-JSON reader: "key":"string" | "key":number | "key":true/false. Enough for JWT headers and claims. */
    static Map<String, String> json(String j) {
        Map<String, String> m = new HashMap<>();
        Matcher x = Pattern.compile("\"(\\w+)\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|[-\\w.]+|\\{[^}]*\\})").matcher(j);
        while (x.find()) m.put(x.group(1), x.group(3) != null ? x.group(3) : x.group(2));
        return m;
    }

    // ---------------- signing ----------------
    static byte[] sign(String alg, Key key, byte[] data) throws Exception {
        switch (alg) {
            case "RS256" -> { Signature s = Signature.getInstance("SHA256withRSA"); s.initSign((PrivateKey) key); s.update(data); return s.sign(); }
            case "ES256" -> { Signature s = Signature.getInstance("SHA256withECDSAinP1363Format"); s.initSign((PrivateKey) key); s.update(data); return s.sign(); }
            case "HS256" -> { Mac m = Mac.getInstance("HmacSHA256"); m.init(key instanceof SecretKeySpec k ? k : new SecretKeySpec(key.getEncoded(), "HmacSHA256")); return m.doFinal(data); }
            default -> throw new IllegalArgumentException(alg);
        }
    }
    static boolean verify(String alg, Key key, byte[] data, byte[] sig) throws Exception {
        switch (alg) {
            case "RS256" -> { Signature s = Signature.getInstance("SHA256withRSA"); s.initVerify((PublicKey) key); s.update(data); return s.verify(sig); }
            case "ES256" -> { Signature s = Signature.getInstance("SHA256withECDSAinP1363Format"); s.initVerify((PublicKey) key); s.update(data); return s.verify(sig); }
            case "HS256" -> { return MessageDigest.isEqual(sign("HS256", key, data), sig); }                 // constant-time compare
            default -> { return false; }
        }
    }
    static String jwt(String headerJson, String claimsJson, String alg, Key key) throws Exception {
        String si = b64(headerJson) + "." + b64(claimsJson);
        return si + "." + (key == null ? "" : B64.encodeToString(sign(alg, key, si.getBytes(StandardCharsets.US_ASCII))));
    }
    static String claims(String sub, String aud, long iat, long exp, String role, String jti) {
        return "{\"iss\":\"https://auth.grocery.example\",\"sub\":\"" + sub + "\",\"aud\":\"" + aud + "\",\"iat\":" + iat + ",\"nbf\":" + iat
                + ",\"exp\":" + exp + ",\"role\":\"" + role + "\",\"jti\":\"" + jti + "\"}";
    }

    // ---------------- validators ----------------
    record Result(boolean ok, String why) {}

    /** What many hand-rolled validators do: believe the header, check nothing else. */
    static Result naive(String token, PublicKey configuredKey) {
        try {
            String[] p = token.split("\\.", -1);
            var h = json(unb64(p[0]));
            byte[] si = (p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII);
            String alg = h.get("alg");
            if ("none".equalsIgnoreCase(alg)) return new Result(true, "alg none");
            Key key = configuredKey;
            if (h.containsKey("jwk")) {                                                         // "the token tells us its key"
                var jwk = json(h.get("jwk"));
                key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                        new java.math.BigInteger(1, D64.decode(jwk.get("n"))), new java.math.BigInteger(1, D64.decode(jwk.get("e")))));
            }
            if (alg.equals("HS256")) key = new SecretKeySpec(pem(configuredKey).getBytes(StandardCharsets.US_ASCII), "HmacSHA256");
            return new Result(verify(alg, key, si, D64.decode(p[2])), "signature");
        } catch (Exception e) { return new Result(false, e.getClass().getSimpleName()); }
    }

    record TrustedKey(String alg, Key key, boolean retired) {}

    /** Hardened: kid must be known, alg fixed by our key config (never by the token), all registered claims checked, denylist. */
    static Result hardened(String token, Map<String, TrustedKey> jwks, String expectedAud, Set<String> revokedJti, long now) {
        try {
            String[] p = token.split("\\.", -1);
            if (p.length != 3 || p[2].isEmpty()) return new Result(false, "unsigned");
            var h = json(unb64(p[0]));
            if (h.containsKey("jwk") || h.containsKey("jku") || h.containsKey("x5u")) return new Result(false, "key in header");
            TrustedKey k = jwks.get(h.get("kid"));
            if (k == null || k.retired()) return new Result(false, "unknown/retired kid");
            if (!k.alg().equals(h.get("alg"))) return new Result(false, "alg mismatch");
            if (!verify(k.alg(), k.key(), (p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII), D64.decode(p[2]))) return new Result(false, "bad signature");
            var c = json(unb64(p[1]));
            long skew = 30;
            if (!"https://auth.grocery.example".equals(c.get("iss"))) return new Result(false, "iss");
            if (!expectedAud.equals(c.get("aud"))) return new Result(false, "aud");
            if (Long.parseLong(c.get("exp")) + skew < now) return new Result(false, "expired");
            if (Long.parseLong(c.get("nbf")) - skew > now) return new Result(false, "not yet valid");
            if (Long.parseLong(c.get("exp")) - Long.parseLong(c.get("iat")) > 3600) return new Result(false, "lifetime too long");
            if (revokedJti.contains(c.get("jti"))) return new Result(false, "revoked");
            return new Result(true, "valid");
        } catch (Exception e) { return new Result(false, e.getClass().getSimpleName()); }
    }

    static String pem(PublicKey k) { return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(k.getEncoded()) + "\n-----END PUBLIC KEY-----\n"; }

    public static void main(String[] args) throws Exception {
        long now = Instant.now().getEpochSecond();
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA"); rsa.initialize(2048);
        KeyPair k1 = rsa.generateKeyPair(), k2 = rsa.generateKeyPair(), attacker = rsa.generateKeyPair();
        Map<String, TrustedKey> jwks = new HashMap<>(Map.of("k1", new TrustedKey("RS256", k1.getPublic(), false)));
        Set<String> revoked = new HashSet<>(Set.of("jti-logged-out"));
        String H1 = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"k1\"}";

        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("valid token (payment API audience)", jwt(H1, claims("cust-42", "payment-api", now, now + 900, "customer", "j1"), "RS256", k1.getPrivate()));
        cases.put("alg:none, no signature", jwt("{\"alg\":\"none\",\"kid\":\"k1\"}", claims("cust-42", "payment-api", now, now + 900, "admin", "j2"), "none", null));
        cases.put("HS256 signed with our PUBLIC key as secret", jwt("{\"alg\":\"HS256\",\"kid\":\"k1\"}", claims("cust-42", "payment-api", now, now + 900, "admin", "j3"), "HS256",
                new SecretKeySpec(pem(k1.getPublic()).getBytes(StandardCharsets.US_ASCII), "HmacSHA256")));
        var ap = (RSAPublicKey) attacker.getPublic();
        cases.put("attacker key embedded in header (jwk)", jwt("{\"alg\":\"RS256\",\"kid\":\"k1\",\"jwk\":{\"kty\":\"RSA\",\"n\":\"" + B64.encodeToString(ap.getModulus().toByteArray())
                + "\",\"e\":\"" + B64.encodeToString(ap.getPublicExponent().toByteArray()) + "\"}}", claims("cust-42", "payment-api", now, now + 900, "admin", "j4"), "RS256", attacker.getPrivate()));
        String good = cases.get("valid token (payment API audience)");
        String[] gp = good.split("\\.");
        cases.put("payload edited (role→admin), old signature", gp[0] + "." + b64(claims("cust-42", "payment-api", now, now + 900, "admin", "j1")) + "." + gp[2]);
        cases.put("expired 2 hours ago", jwt(H1, claims("cust-42", "payment-api", now - 8100, now - 7200, "customer", "j5"), "RS256", k1.getPrivate()));
        cases.put("valid, but audience rider-app", jwt(H1, claims("rider-7", "rider-app", now, now + 900, "rider", "j6"), "RS256", k1.getPrivate()));
        cases.put("valid, but jti revoked at logout", jwt(H1, claims("cust-42", "payment-api", now, now + 900, "customer", "jti-logged-out"), "RS256", k1.getPrivate()));
        cases.put("valid signature, 30-day lifetime", jwt(H1, claims("cust-42", "payment-api", now, now + 30 * 86400, "customer", "j7"), "RS256", k1.getPrivate()));

        System.out.println("A. Validating tokens presented to payment-api");
        System.out.printf("   %-46s %-16s %-26s%n", "token", "naive validator", "hardened validator");
        for (var e : cases.entrySet()) {
            Result n = naive(e.getValue(), k1.getPublic()), h = hardened(e.getValue(), jwks, "payment-api", revoked, now);
            System.out.printf("   %-46s %-16s %-26s%n", e.getKey(), n.ok() ? "ACCEPTED" : "rejected", (h.ok() ? "ACCEPTED" : "rejected") + " (" + h.why() + ")");
        }

        System.out.println("B. Key rotation through the JWKS (kid k1 → k2)");
        String oldTok = jwt(H1, claims("cust-9", "payment-api", now, now + 900, "customer", "r1"), "RS256", k1.getPrivate());
        jwks.put("k2", new TrustedKey("RS256", k2.getPublic(), false));                         // 1. publish k2 first
        String newTok = jwt("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"k2\"}", claims("cust-9", "payment-api", now, now + 900, "customer", "r2"), "RS256", k2.getPrivate());
        System.out.printf("   overlap (JWKS has k1+k2): old-key token %s, new-key token %s%n",
                hardened(oldTok, jwks, "payment-api", revoked, now).ok() ? "valid" : "rejected", hardened(newTok, jwks, "payment-api", revoked, now).ok() ? "valid" : "rejected");
        jwks.put("k1", new TrustedKey("RS256", k1.getPublic(), true));                           // 3. retire k1 after max token lifetime
        System.out.printf("   after k1 retired (> max token lifetime later): old-key token %s, new-key token %s%n",
                hardened(oldTok, jwks, "payment-api", revoked, now).why(), hardened(newTok, jwks, "payment-api", revoked, now).why());

        System.out.println("C. A refresh token is stolen from a phone backup; the attacker and the customer both use it (30-day refresh, 15-min access)");
        for (boolean rotation : List.of(false, true)) {
            Map<String, String> family = new HashMap<>(); Set<String> used = new HashSet<>(); Set<String> revokedFamilies = new HashSet<>();
            String stolen = "rt-1"; family.put(stolen, "fam-A");
            int attackerMinutes = 0; String attackerRt = stolen, customerRt = stolen; boolean customerForcedLogin = false;
            for (int minute = 0; minute < 30 * 24 * 60; minute += 15) {                           // both refresh every 15 minutes
                for (int who = 1; who >= 0; who--) {                                     // the attacker refreshes first
                    String rt = who == 0 ? customerRt : attackerRt;
                    String fam = family.get(rt);
                    boolean ok = fam != null && !revokedFamilies.contains(fam);
                    if (ok && rotation && used.contains(rt)) { revokedFamilies.add(fam); ok = false; }   // reuse detected → kill the whole family
                    if (ok) {
                        String next = rt;
                        if (rotation) { used.add(rt); next = "rt-" + UUID.randomUUID(); family.put(next, fam); }
                        if (who == 0) customerRt = next; else { attackerRt = next; attackerMinutes += 15; }
                    } else if (who == 0) customerForcedLogin = true;
                }
                if (rotation && customerForcedLogin) break;
            }
            System.out.printf("   %-38s attacker kept access for %,6d minutes%s%n", rotation ? "rotation + reuse detection:" : "long-lived reusable refresh token:",
                    attackerMinutes, rotation ? " (one access token; the customer's next refresh exposed the reuse, the family was revoked)" : " (the whole 30 days, unnoticed)");
        }

        System.out.println("D. Cost per operation and token size");
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC"); ec.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair e1 = ec.generateKeyPair();
        SecretKeySpec hs = new SecretKeySpec(new SecureRandom().generateSeed(32), "HmacSHA256");
        for (Object[] a : new Object[][]{{"RS256 (RSA 2048)", "RS256", k1.getPrivate(), k1.getPublic()}, {"ES256 (P-256)", "ES256", e1.getPrivate(), e1.getPublic()}, {"HS256 (shared secret)", "HS256", hs, hs}}) {
            String alg = (String) a[1];
            String tok = jwt("{\"alg\":\"" + alg + "\",\"kid\":\"x\"}", claims("cust-42", "payment-api", now, now + 900, "customer", "p"), alg, (Key) a[2]);
            byte[] si = tok.substring(0, tok.lastIndexOf('.')).getBytes(StandardCharsets.US_ASCII);
            byte[] sig = D64.decode(tok.substring(tok.lastIndexOf('.') + 1));
            for (int i = 0; i < 2_000; i++) { sign(alg, (Key) a[2], si); verify(alg, (Key) a[3], si, sig); }         // warm-up
            long t0 = System.nanoTime(); int n = 3_000;
            for (int i = 0; i < n; i++) sign(alg, (Key) a[2], si);
            double signUs = (System.nanoTime() - t0) / 1e3 / n;
            t0 = System.nanoTime();
            for (int i = 0; i < n; i++) verify(alg, (Key) a[3], si, sig);
            double verifyUs = (System.nanoTime() - t0) / 1e3 / n;
            System.out.printf("   %-22s sign %8.1f µs   verify %7.1f µs   token %3d bytes%n", a[0], signUs, verifyUs, tok.length());
        }
    }
}
