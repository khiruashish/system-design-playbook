// ErasureL1.java — run with: java ErasureL1.java   (JDK 21, no dependencies)
// THE RIGHT TO ERASURE vs WHERE DATA ACTUALLY LIVES. A customer asks the platform to delete their personal data (DPDP Act
// 2023 / GDPR Art. 17). Their data is not in one table: it's in the orders DB, a CQRS read model, the support search index,
// a cache, last night's backup, and the analytics warehouse. Three strategies:
//   A) delete from the primary database only             (what most first implementations do)
//   B) delete from every store we remembered              (but backups are immutable and the warehouse is someone else's)
//   C) CRYPTO-SHREDDING: personal fields are encrypted with a per-customer key (AES-256-GCM) at write time, in EVERY copy;
//      erasure destroys the key. Records the law requires us to KEEP (GST invoices, ~6 years) are encrypted under a separate
//      per-customer "legal-retention" key with only the minimum fields, and are kept.
// After each strategy we scan every store for the customer's personal data in readable form.
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

public class ErasureL1 {
    static final SecureRandom RNG = new SecureRandom();
    // ---- a toy KMS: key id -> key. Destroying a key is irreversible. ----
    static final Map<String, SecretKey> KMS = new HashMap<>();
    static SecretKey key(String id) {
        return KMS.computeIfAbsent(id, k -> { try { KeyGenerator g = KeyGenerator.getInstance("AES"); g.init(256); return g.generateKey(); }
                                                catch (Exception e) { throw new RuntimeException(e); } });
    }
    static String enc(String keyId, String plain) throws Exception {
        byte[] iv = new byte[12]; RNG.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key(keyId), new GCMParameterSpec(128, iv));
        return "enc:" + keyId + ":" + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }
    static String dec(String v) {
        if (!v.startsWith("enc:")) return v;
        String[] p = v.split(":"); SecretKey k = KMS.get(p[1]);
        if (k == null) return null;                                                  // key destroyed → unrecoverable
        try { Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, Base64.getDecoder().decode(p[2])));
              return new String(c.doFinal(Base64.getDecoder().decode(p[3])), StandardCharsets.UTF_8); }
        catch (Exception e) { return null; }
    }

    // ---- the six places a customer's data lives ----
    static final List<String> STORES = List.of("orders DB", "read model", "support search", "cache", "nightly backup", "warehouse");
    static Map<String, List<Map<String, String>>> world;

    static void populate(boolean encrypt, String... customers) throws Exception {
        world = new LinkedHashMap<>(); STORES.forEach(s -> world.put(s, new ArrayList<>()));
        for (String cust : customers) {
            String name = cust.equals("C-1001") ? "Priya Sharma" : "Arjun Mehta", phone = cust.equals("C-1001") ? "+91 98450 11223" : "+91 99000 44556";
            String addr = cust.equals("C-1001") ? "12 MG Road, Pune 411001" : "7 Park St, Kolkata 700016";
            String pk = "pii-" + cust, lk = "legal-" + cust;
            for (String store : STORES) {
                Map<String, String> r = new LinkedHashMap<>();
                r.put("customer", cust); r.put("store", store);
                r.put("name", encrypt ? enc(pk, name) : name);
                r.put("phone", encrypt ? enc(pk, phone) : phone);
                r.put("address", encrypt ? enc(pk, addr) : addr);
                r.put("basket", encrypt ? enc(pk, "milk, eggs, baby formula, pregnancy test") : "milk, eggs, baby formula, pregnancy test"); // sensitive inference
                world.get(store).add(r);
            }
            // the GST invoice: legally required to be retained — minimum fields, separate legal-retention key
            Map<String, String> inv = new LinkedHashMap<>();
            inv.put("customer", cust); inv.put("store", "GST invoice archive"); inv.put("invoiceNo", "INV-" + cust.substring(2));
            inv.put("name", encrypt ? enc(lk, name) : name); inv.put("address", encrypt ? enc(lk, addr) : addr); inv.put("taxablePaise", "129900");
            world.computeIfAbsent("GST invoice archive", k -> new ArrayList<>()).add(inv);
        }
    }

    // scan: which stores still expose the customer's personal data in readable form?
    static List<String> exposure(String cust) {
        List<String> exposed = new ArrayList<>();
        for (var e : world.entrySet())
            for (Map<String, String> r : e.getValue())
                if (cust.equals(r.get("customer"))) {
                    String nm = r.containsKey("name") ? dec(r.get("name")) : null;
                    if (nm != null) exposed.add(e.getKey() + (r.containsKey("basket") && dec(r.get("basket")) != null ? " (incl. basket)" : ""));
                }
        return exposed;
    }
    static void deleteFrom(String cust, List<String> stores) { for (String s : stores) world.get(s).removeIf(r -> cust.equals(r.get("customer"))); }

    public static void main(String[] args) throws Exception {
        String cust = "C-1001", other = "C-2002";
        System.out.println("Customer C-1001 asks us to erase their personal data. It lives in 6 stores (+ a GST invoice we must retain).\n");

        populate(false, cust, other);
        deleteFrom(cust, List.of("orders DB"));
        report("A) delete from the orders DB only", cust, other);

        populate(false, cust, other);
        deleteFrom(cust, List.of("orders DB", "read model", "support search", "cache"));  // backup is immutable; warehouse owned by another team
        report("B) delete from every store we remembered", cust, other);

        populate(true, cust, other);
        KMS.remove("pii-" + cust);                              // crypto-shred: destroy the personal-data key (KMS schedule-deletion)
        deleteFrom(cust, List.of("orders DB", "read model", "support search", "cache")); // tidy up live stores; not required for erasure
        report("C) crypto-shredding (per-customer key destroyed)", cust, other);

        // what the erasure log records — proof of erasure WITHOUT the personal data
        System.out.println("   Erasure log entry (kept as evidence):");
        System.out.println("        {\"request\":\"ER-2026-0931\",\"subject\":\"C-1001\",\"keyDestroyed\":\"pii-C-1001\",\"retainedUnderLaw\":[\"GST invoice INV-1001 (legal-C-1001)\"],");
        System.out.println("         \"completedAt\":\"2026-09-29T07:12:03Z\",\"stores\":6}");

        System.out.println("\n   → deleting from the primary leaves the customer readable in 5 other places — including the backup, which also keeps");
        System.out.println("     a sensitive INFERENCE (the basket) that was never a 'field' anyone thought to erase. Chasing every copy misses the");
        System.out.println("     immutable backup and the warehouse another team owns. Crypto-shredding makes erasure a single, provable action:");
        System.out.println("     every copy — backups included — was written as ciphertext under the customer's key, so destroying the key erases");
        System.out.println("     them all at once, while other customers are untouched. The GST invoice survives because the law requires it,");
        System.out.println("     under a separate purpose key with only the fields the law needs. Crypto-shredding only works if personal data is");
        System.out.println("     NEVER written in plaintext anywhere (logs, exports, caches) — enforce that at write time.");
    }

    static void report(String label, String cust, String other) {
        List<String> ex = new ArrayList<>(exposure(cust));
        boolean invoice = ex.remove("GST invoice archive");
        System.out.printf("   %-50s NOT erased (still readable): %d %s%n", label, ex.size(), ex);
        System.out.printf("   %-50s retained under legal obligation: %s%n", "", invoice ? "GST invoice (name + address only)" : "none");
        System.out.printf("   %-50s other customer C-2002 still intact: %s%n%n", "", exposure(other).size() == 7 ? "yes (7/7)" : "NO (" + exposure(other).size() + "/7)");
    }
}
