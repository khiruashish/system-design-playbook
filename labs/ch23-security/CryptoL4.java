// CryptoL4.java — run with: java CryptoL4.java      (JDK 17+; needs a Vault/OpenBao transit engine at $BAO, default :8210)
// Encryption at rest done properly: envelope encryption. A per-record data key (DEK) encrypts the field with AES-256-GCM;
// the DEK is wrapped by a key-encryption key (KEK) that never leaves the KMS (OpenBao transit). We store only ciphertext +
// wrapped DEK + KEK version. Then: authenticated-encryption tamper detection, KEK rotation + rewrap, crypto-shredding for
// "delete my data" (DPDP/GDPR), and a cost comparison of calling the KMS per record vs caching/deriving DEKs.
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

public class CryptoL4 {
    static final String BAO = System.getenv().getOrDefault("BAO", "http://127.0.0.1:8210");
    static final String TOK = System.getenv().getOrDefault("TOK", "root");
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final SecureRandom RNG = new SecureRandom();
    static final Base64.Encoder B64 = Base64.getEncoder(); static final Base64.Decoder D64 = Base64.getDecoder();

    // ---- KMS (transit) calls: wrap/unwrap a DEK with the KEK that stays in the KMS ----
    static String kms(String path, String body) throws Exception {
        var r = HTTP.send(HttpRequest.newBuilder(URI.create(BAO + "/v1/" + path)).header("X-Vault-Token", TOK)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        return r.body();
    }
    static String field(String json, String key) { var m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"?([^\",}]+)").matcher(json); return m.find() ? m.group(1) : null; }
    static String wrapDek(byte[] dek) throws Exception { return field(kms("transit/encrypt/orders-dek", "{\"plaintext\":\"" + B64.encodeToString(dek) + "\"}"), "ciphertext"); }
    static byte[] unwrapDek(String wrapped) throws Exception { return D64.decode(field(kms("transit/decrypt/orders-dek", "{\"ciphertext\":\"" + wrapped + "\"}"), "plaintext")); }

    // ---- envelope record ----
    record Sealed(byte[] iv, byte[] ct, String wrappedDek, int kekVersion) {}

    static Sealed seal(String plaintext) throws Exception {
        byte[] dek = new byte[32]; RNG.nextBytes(dek);                                            // fresh per record
        byte[] iv = new byte[12]; RNG.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        String wrapped = wrapDek(dek);
        Arrays.fill(dek, (byte) 0);                                                               // scrub the plaintext DEK from memory
        return new Sealed(iv, ct, wrapped, Integer.parseInt(wrapped.split(":")[1].substring(1)));
    }
    static String open(Sealed s) throws Exception {
        byte[] dek = unwrapDek(s.wrappedDek());
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(128, s.iv()));
        try { return new String(c.doFinal(s.ct()), StandardCharsets.UTF_8); } finally { Arrays.fill(dek, (byte) 0); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("A. Envelope encryption of a customer's address (AES-256-GCM per record, DEK wrapped by the KMS KEK)");
        String pii = "Flat 4B, 12 MG Road, Bengaluru 560001; phone +91 98000 00001";
        Sealed rec = seal(pii);
        System.out.printf("   plaintext (%d bytes) → ciphertext %d bytes, iv %d bytes, wrapped DEK %d bytes, KEK version %d%n",
                pii.length(), rec.ct().length, rec.iv().length, rec.wrappedDek().length(), rec.kekVersion());
        System.out.printf("   what the database row stores: %s...  (no plaintext, no key material)%n", B64.encodeToString(rec.ct()).substring(0, 32));
        System.out.printf("   decrypt round-trip matches original: %s%n", open(rec).equals(pii));

        System.out.println("B. Authenticated encryption detects tampering (GCM tag)");
        byte[] flipped = rec.ct().clone(); flipped[5] ^= 0x01;                                    // flip one bit of ciphertext
        try { open(new Sealed(rec.iv(), flipped, rec.wrappedDek(), rec.kekVersion())); System.out.println("   FAIL: tampered ciphertext decrypted"); }
        catch (Exception e) { System.out.printf("   one flipped bit → %s (record rejected, not silently corrupted)%n", e.getClass().getSimpleName()); }

        System.out.println("C. KEK rotation without re-encrypting the data");
        kms("transit/keys/orders-dek/rotate", "{}");                                              // new KEK version in the KMS
        String v = field(kms("transit/keys/orders-dek", ""), "latest_version");
        System.out.printf("   KEK rotated to version %s in the KMS. Existing rows keep working (KMS keeps old versions to unwrap):%n", v);
        System.out.printf("     old row still decrypts: %s%n", open(rec).equals(pii));
        // rewrap ONLY the small wrapped DEK — the large ciphertext is untouched
        String rewrapped = field(kms("transit/rewrap/orders-dek", "{\"ciphertext\":\"" + rec.wrappedDek() + "\"}"), "ciphertext");
        Sealed rec2 = new Sealed(rec.iv(), rec.ct(), rewrapped, Integer.parseInt(rewrapped.split(":")[1].substring(1)));
        System.out.printf("     rewrapped DEK now at KEK version %d; ciphertext unchanged (%s); still decrypts: %s%n",
                rec2.kekVersion(), Arrays.equals(rec.ct(), rec2.ct()), open(rec2).equals(pii));
        System.out.println("   → rotation rewraps kilobytes of DEKs, never terabytes of data.");

        System.out.println("D. Crypto-shredding: honour \"delete all my data\" by destroying the key, not chasing every copy");
        // Per-customer KEK: destroying it makes every record encrypted under it permanently unrecoverable (backups included).
        kms("sys/mounts/transit-cust-42", "{\"type\":\"transit\"}");
        kms("transit-cust-42/keys/pii", "{\"type\":\"aes256-gcm96\"}");
        byte[] dek = new byte[32]; RNG.nextBytes(dek);
        String wrapped = field(kms("transit-cust-42/encrypt/pii", "{\"plaintext\":\"" + B64.encodeToString(dek) + "\"}"), "ciphertext");
        boolean before = field(kms("transit-cust-42/decrypt/pii", "{\"ciphertext\":\"" + wrapped + "\"}"), "plaintext") != null;
        kms("transit-cust-42/keys/pii/config", "{\"deletion_allowed\":true}");
        kms("sys/mounts/transit-cust-42/tune", "{}");
        kms("transit-cust-42/keys/pii", "{\"type\":\"aes256-gcm96\"}");                            // no-op guard
        String del = HTTP.send(HttpRequest.newBuilder(URI.create(BAO + "/v1/transit-cust-42/keys/pii")).header("X-Vault-Token", TOK).DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode() + "";
        String after = field(kms("transit-cust-42/decrypt/pii", "{\"ciphertext\":\"" + wrapped + "\"}"), "errors");
        System.out.printf("   before deletion: DEK unwrappable = %s%n", before);
        System.out.printf("   after destroying the customer's KEK (HTTP %s): unwrap fails → %s%n", del,
                after != null ? "data is unrecoverable everywhere it was copied" : "(still works — check deletion_allowed)");

        System.out.println("E. Cost: KMS round-trip per record vs a cached/derived DEK (1,000 fields)");
        long t0 = System.nanoTime();
        for (int i = 0; i < 1_000; i++) seal("field-" + i);                                       // a KMS call per record
        double perRecordMs = (System.nanoTime() - t0) / 1e6 / 1_000;
        // amortised: one DEK wrapped once, reused for many records (each still gets its own IV)
        byte[] shared = new byte[32]; RNG.nextBytes(shared); String sharedWrapped = wrapDek(shared);
        t0 = System.nanoTime();
        for (int i = 0; i < 1_000; i++) {
            byte[] iv = new byte[12]; RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(shared, "AES"), new GCMParameterSpec(128, iv));
            c.doFinal(("field-" + i).getBytes());
        }
        double amortisedMs = (System.nanoTime() - t0) / 1e6 / 1_000;
        System.out.printf("   KMS call per record : %6.3f ms/field  (a network hop each time)%n", perRecordMs);
        System.out.printf("   one wrapped DEK reused: %6.3f ms/field  (%.0fx cheaper; rewrap the one DEK on rotation)%n", amortisedMs, perRecordMs / amortisedMs);
        System.out.println("   → cache/derive DEKs per tenant or batch; never cache the KEK, and give each record its own IV.");
    }
}
