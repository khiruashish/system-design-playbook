// PiiScanL2.java — run with: java PiiScanL2.java   (JDK 21, no dependencies)
// FINDING PERSONAL DATA WHERE IT SHOULDN'T BE — in application logs. You can't protect, minimise or erase personal data you
// don't know you have, and logs are where it leaks (a request body logged at DEBUG, an exception message with a phone
// number). We generate 20,000 realistic log lines, plant known PII in some of them (ALL SYNTHETIC: generated checksum-valid
// Aadhaar-format numbers, public card-network TEST numbers, made-up phones/emails/PANs), and compare two scanners:
//   NAIVE     — format regexes only (12 digits = Aadhaar, 13–19 digits = card, ...)
//   VALIDATED — the same regexes plus the identifier's own rules: Verhoeff checksum for Aadhaar-format numbers, Luhn checksum
//               + issuer prefix for cards, entity-type letter for PAN
// Logs are full of look-alikes — 12-digit order ids, 13-digit epoch-millisecond timestamps — so precision matters: a scanner
// that cries wolf on every timestamp gets switched off. Then we REDACT and re-scan to prove nothing real survives.
import java.util.*;
import java.util.regex.*;

public class PiiScanL2 {
    // ---------------- Verhoeff (used by Aadhaar) ----------------
    static final int[][] D = {{0,1,2,3,4,5,6,7,8,9},{1,2,3,4,0,6,7,8,9,5},{2,3,4,0,1,7,8,9,5,6},{3,4,0,1,2,8,9,5,6,7},{4,0,1,2,3,9,5,6,7,8},
            {5,9,8,7,6,0,4,3,2,1},{6,5,9,8,7,1,0,4,3,2},{7,6,5,9,8,2,1,0,4,3},{8,7,6,5,9,3,2,1,0,4},{9,8,7,6,5,4,3,2,1,0}};
    static final int[][] P = {{0,1,2,3,4,5,6,7,8,9},{1,5,7,6,2,8,3,0,9,4},{5,8,0,3,7,9,6,1,4,2},{8,9,1,6,0,4,3,5,2,7},{9,4,5,3,1,2,6,8,7,0},
            {4,2,8,6,5,7,3,9,0,1},{2,7,9,3,8,0,6,4,1,5},{7,0,4,6,9,1,3,2,5,8}};
    static final int[] INV = {0,4,3,2,1,5,6,7,8,9};
    static boolean verhoeff(String n) { int c = 0; for (int i = 0; i < n.length(); i++) c = D[c][P[i % 8][n.charAt(n.length() - 1 - i) - '0']]; return c == 0; }
    static String withVerhoeff(String n) { int c = 0; for (int i = 0; i < n.length(); i++) c = D[c][P[(i + 1) % 8][n.charAt(n.length() - 1 - i) - '0']]; return n + INV[c]; }
    // ---------------- Luhn (payment cards) ----------------
    static boolean luhn(String n) { int s = 0; boolean alt = false; for (int i = n.length() - 1; i >= 0; i--) { int d = n.charAt(i) - '0'; if (alt) { d *= 2; if (d > 9) d -= 9; } s += d; alt = !alt; } return s % 10 == 0; }

    // ---------------- detectors ----------------
    static final Pattern AADHAAR = Pattern.compile("(?<![\\d-])[2-9]\\d{3}[ -]?\\d{4}[ -]?\\d{4}(?![\\d-])");
    static final Pattern CARD    = Pattern.compile("(?<!\\d)\\d{13,19}(?!\\d)");
    static final Pattern PAN     = Pattern.compile("\\b[A-Z]{5}\\d{4}[A-Z]\\b");
    static final Pattern PHONE   = Pattern.compile("(?<!\\d)(?:\\+91[ -]?)?[6-9]\\d{4}[ -]?\\d{5}(?!\\d)");
    static final Pattern EMAIL   = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    static final String PAN_ENTITY = "PCHFATBLJG";

    static boolean valid(String type, String m) {
        String digits = m.replaceAll("[ -]", "");
        return switch (type) {
            case "aadhaar" -> verhoeff(digits);
            case "card" -> luhn(digits) && "3456".indexOf(digits.charAt(0)) >= 0;
            case "pan" -> PAN_ENTITY.indexOf(m.charAt(3)) >= 0;
            default -> true;                                         // phones/emails: format is the check
        };
    }
    static final Map<String, Pattern> DET = new LinkedHashMap<>();
    static { DET.put("aadhaar", AADHAAR); DET.put("card", CARD); DET.put("pan", PAN); DET.put("phone", PHONE); DET.put("email", EMAIL); }

    record Line(String text, Map<String, Set<String>> truth) {}

    // key-aware: in structured (key=value) logs, values of known non-personal fields are never PII candidates
    static final Set<String> NON_PII_KEYS = Set.of("ts", "trace", "order", "latencyMs", "sku", "coupon", "svc", "level");
    static boolean inNonPiiField(String text, int start) {
        int sp = text.lastIndexOf(' ', start - 1), eq = text.indexOf('=', sp + 1);
        return eq > 0 && eq < start && NON_PII_KEYS.contains(text.substring(sp + 1, eq));
    }
    static boolean accept(int tier, String type, String text, Matcher m) {
        if (tier >= 1 && !valid(type, m.group())) return false;
        if (tier >= 2 && inNonPiiField(text, m.start())) return false;
        return true;
    }

    public static void main(String[] args) {
        Random r = new Random(17);
        String[] testCards = {"4111111111111111", "5555555555554444", "4012888888881881", "378282246310005", "6011111111111117"};
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            Map<String, Set<String>> truth = new HashMap<>();
            long ts = 1_780_000_000_000L + r.nextInt(1_000_000_000);                          // 13-digit epoch millis
            String orderId = String.valueOf(200_000_000_000L + (long) (r.nextDouble() * 700_000_000_000L));  // 12-digit order id
            String base = String.format("ts=%d level=INFO svc=checkout trace=%016x order=%s latencyMs=%d", ts, r.nextLong(), orderId, 20 + r.nextInt(400));
            StringBuilder sb = new StringBuilder(base);
            if (r.nextDouble() < 0.02) { String a = withVerhoeff(String.valueOf(2 + r.nextInt(8)) + String.format("%010d", (long) (r.nextDouble() * 1e10)));
                a = a.substring(0, 4) + " " + a.substring(4, 8) + " " + a.substring(8); sb.append(" kycDoc=").append(a); truth.computeIfAbsent("aadhaar", k -> new HashSet<>()).add(a); }
            if (r.nextDouble() < 0.01) { String c = testCards[r.nextInt(testCards.length)]; sb.append(" payload={\"card\":\"").append(c).append("\"}"); truth.computeIfAbsent("card", k -> new HashSet<>()).add(c); }
            if (r.nextDouble() < 0.01) { String p = "" + (char) ('A' + r.nextInt(26)) + (char) ('A' + r.nextInt(26)) + (char) ('A' + r.nextInt(26)) + "P" + (char) ('A' + r.nextInt(26)) + String.format("%04d", r.nextInt(10000)) + (char) ('A' + r.nextInt(26));
                sb.append(" pan=").append(p); truth.computeIfAbsent("pan", k -> new HashSet<>()).add(p); }
            if (r.nextDouble() < 0.03) { String ph = "+91 " + (6 + r.nextInt(4)) + String.format("%04d %05d", r.nextInt(10000), r.nextInt(100000)); sb.append(" msg=\"OTP sent to ").append(ph).append("\""); truth.computeIfAbsent("phone", k -> new HashSet<>()).add(ph); }
            if (r.nextDouble() < 0.02) { String em = "user" + r.nextInt(99999) + "@example.in"; sb.append(" email=").append(em); truth.computeIfAbsent("email", k -> new HashSet<>()).add(em); }
            if (r.nextDouble() < 0.05) sb.append(" sku=SKU").append(100000 + r.nextInt(900000)).append(" coupon=FESTAB").append(1000 + r.nextInt(9000)).append("Z");  // look-alike noise
            lines.add(new Line(sb.toString(), truth));
        }

        System.out.println("20,000 synthetic log lines with planted (synthetic) PII among order ids, epoch timestamps, trace ids and SKUs.\n");
        String[] tiers = {"naive (format only)", "validated (checksums)", "validated + key-aware"};
        System.out.printf("   %-9s %8s | %-22s %-22s %-22s%n", "detector", "planted", "naive: hits / precision", "validated", "validated + key-aware");
        for (var d : DET.entrySet()) {
            int planted = 0; int[] hits = new int[3], tp = new int[3];
            for (Line l : lines) {
                Set<String> t = l.truth().getOrDefault(d.getKey(), Set.of()); planted += t.size();
                Matcher m = d.getValue().matcher(l.text());
                while (m.find()) for (int tier = 0; tier < 3; tier++)
                    if (accept(tier, d.getKey(), l.text(), m)) { hits[tier]++; if (t.contains(m.group())) tp[tier]++; }
            }
            StringBuilder row = new StringBuilder(String.format("   %-9s %8d |", d.getKey(), planted));
            for (int tier = 0; tier < 3; tier++)
                row.append(String.format(" %6d / %5.1f%% r%3.0f%%  ", hits[tier], hits[tier] == 0 ? 100.0 : 100.0 * tp[tier] / hits[tier], planted == 0 ? 100.0 : 100.0 * tp[tier] / planted));
            System.out.println(row);
        }
        System.out.println("   (hits / precision, r = recall)");

        // redaction with the validated scanner, then re-scan for any planted value
        int residual = 0, redactedLines = 0, orderIdsDestroyed = 0; String sample = null, sampleRedacted = null;
        for (Line l : lines) {
            String out = l.text();
            for (var d : DET.entrySet()) {
                Matcher m = d.getValue().matcher(out); StringBuilder sb = new StringBuilder();
                String cur = out;
                while (m.find()) m.appendReplacement(sb, accept(2, d.getKey(), cur, m) ? "[" + d.getKey().toUpperCase() + "]" : Matcher.quoteReplacement(m.group()));
                m.appendTail(sb); out = sb.toString();
            }
            for (String key : List.of("order=", "ts=")) { if (l.text().contains(key) && !out.contains(key + l.text().split(key)[1].split(" ")[0])) orderIdsDestroyed++; }
            if (!out.equals(l.text())) { redactedLines++; if (sample == null && l.truth().size() >= 2) { sample = l.text(); sampleRedacted = out; } }
            for (Set<String> vals : l.truth().values()) for (String v : vals) if (out.contains(v)) residual++;
        }
        System.out.printf("%n   Redaction (validated + key-aware): %,d lines changed; planted PII still present: %d; order ids/timestamps destroyed: %d%n",
                redactedLines, residual, orderIdsDestroyed);
        System.out.println("   before: " + sample);
        System.out.println("   after:  " + sampleRedacted);

        System.out.println("\n   → format-only regexes drown in look-alikes: 13-digit epoch timestamps look like card numbers and 12-digit order ids");
        System.out.println("     look like Aadhaar numbers, so the naive scanner's alerts are almost all noise and get ignored. Checksums remove most");
        System.out.println("     of it — Luhn + issuer prefix makes the card detector nearly exact — but a checksum passes random digits ~10% of the");
        System.out.println("     time, so thousands of order ids still 'pass' Verhoeff, and redacting them would destroy the ids engineers need.");
        System.out.println("     Knowing the STRUCTURE of the log (which keys can never hold personal data) closes the gap. That's also the real fix:");
        System.out.println("     structured logging with field allow-lists, so personal data is never written — scanning is the safety net.");
    }
}
