// ContactLeakL4.java — run with: java ContactLeakL4.java   (JDK 21, no dependencies)
// KEEPING PHONE NUMBERS OUT OF THE CHAT. Customers and riders talk through the platform so that neither sees the other's
// real number (calls go through masked numbers). People still type numbers into chat — to take a tip off-platform, to
// keep contacting a customer after delivery, or in harassment. The platform masks contact details in chat messages.
// 20,000 synthetic messages (fixed seed): ordinary delivery chat — including numbers that MUST NOT be masked (4-digit
// delivery OTPs, order ids, flat numbers, amounts, pincodes) — and 2,000 messages that leak a contact in the ways people
// actually write them: plain, with spaces or dashes, +91 or leading 0, Devanagari digits, digits as words in English or
// Hindi ("nau aath chaar…"), mixed, and UPI ids / emails. Three detectors:
//   D1 a regex for a 10-digit Indian mobile number        D2 normalise (strip separators, map Devanagari digits, drop
//   +91/0) then regex, plus UPI/email patterns            D3 D2 + digits of any script + number words, with context rules
//   that leave courier AWB numbers, OTPs, order ids, amounts and our own support address alone
// The ordinary messages include 300 hard negatives (courier AWB numbers, our support email, number words in normal speech).
import java.util.*;
import java.util.regex.*;

public class ContactLeakL4 {
    record M(String text, boolean leak, String style) {}
    static final String[] EN = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"};
    static final String[] HI = {"shunya", "ek", "do", "teen", "char", "paanch", "chhe", "saat", "aath", "nau"};
    static final char[] DEV = {'०', '१', '२', '३', '४', '५', '६', '७', '८', '९'};

    public static void main(String[] args) {
        Random r = new Random(41); List<M> msgs = new ArrayList<>();
        String[] ordinary = {"I'm at the gate", "Which tower is it?", "Please leave it with the guard", "Coming down in 2 min",
                "Delivery OTP is %04d", "Order ORD-%06d has 3 items", "Flat %d, B wing", "Change for ₹%d please", "Pincode is 5600%02d",
                "Reached, 5th floor", "Lift not working, taking stairs", "Can you bring it to gate %d?", "Paid ₹%d by UPI already",
                "Rider will reach in %d minutes", "Total is ₹%d.00", "It's house number %d, near the temple"};
        String[] hardNegatives = {"Courier AWB 7%09d for the return", "Write to support@grocery.in for the refund", "Bring two packets, one for my neighbour"};
        String[] styles = {"plain", "spaced", "dashed", "+91", "leading0", "devanagari", "kannada digits", "english words", "hindi words",
                "mixed", "upi", "email", "typo words", "split in two"};
        char[] KAN = {'೦', '೧', '೨', '೩', '೪', '೫', '೬', '೭', '೮', '೯'};
        for (int i = 0; i < 18_000; i++) {
            if (i < 300) { String h = hardNegatives[i % 3]; msgs.add(new M(h.contains("%") ? String.format(h, r.nextInt(1_000_000_000)) : h, false, "hard negative")); continue; }
            String t = ordinary[r.nextInt(ordinary.length)];
            msgs.add(new M(t.contains("%") ? String.format(t, r.nextInt(t.contains("OTP") ? 10_000 : t.contains("ORD") ? 1_000_000 : 1_000)) : t, false, "ordinary"));
        }
        for (int i = 0; i < 2_000; i++) {
            String num = (char) ('6' + r.nextInt(4)) + String.format("%09d", r.nextInt(1_000_000_000));
            String style = styles[r.nextInt(styles.length)], shown = switch (style) {
                case "plain" -> num; case "spaced" -> num.substring(0, 5) + " " + num.substring(5);
                case "dashed" -> num.substring(0, 3) + "-" + num.substring(3, 6) + "-" + num.substring(6);
                case "+91" -> "+91 " + num; case "leading0" -> "0" + num;
                case "devanagari" -> num.chars().mapToObj(c -> String.valueOf(DEV[c - '0'])).reduce("", String::concat);
                case "kannada digits" -> num.chars().mapToObj(c -> String.valueOf(KAN[c - '0'])).reduce("", String::concat);
                case "english words" -> words(num, EN); case "hindi words" -> words(num, HI);
                case "typo words" -> words(num, EN).replace("eight", "ate").replace("four", "for").replace("two", "to");
                case "split in two" -> num.substring(0, 5) + " (rest in next msg)";
                case "mixed" -> num.substring(0, 5) + " " + words(num.substring(5), EN);
                case "upi" -> "ramesh" + r.nextInt(100) + "@okaxis"; default -> "ramesh.k" + r.nextInt(100) + "@gmail.com";
            };
            String[] pre = {"call me on ", "my number ", "whatsapp ", "tip here ", "save this ", ""};
            msgs.add(new M(pre[r.nextInt(pre.length)] + shown, true, style));
        }
        Collections.shuffle(msgs, r);
        System.out.printf("%,d messages: %,d ordinary (with OTPs, order ids, flats, amounts, pincodes), %,d leaking a contact.%n%n",
                msgs.size(), msgs.stream().filter(m -> !m.leak()).count(), msgs.stream().filter(M::leak).count());
        System.out.printf("   %-60s %9s %17s%n", "detector", "recall", "false positives");
        Map<String, int[]> byStyle = new TreeMap<>();
        for (int d = 1; d <= 3; d++) {
            int tp = 0, fp = 0, leaks = 0; List<String> fpSamples = new ArrayList<>();
            for (M m : msgs) {
                boolean hit = detect(d, m.text());
                if (m.leak()) { leaks++; if (hit) tp++; if (d == 3 || d == 1) byStyle.computeIfAbsent(m.style(), k -> new int[4])[d == 1 ? 0 : 2] += hit ? 1 : 0; if (d == 1) byStyle.get(m.style())[1]++; }
                else if (hit) { fp++; if (fpSamples.size() < 2) fpSamples.add(m.text()); }
            }
            System.out.printf("   %-60s %8.1f%% %,17d%s%n", new String[]{"", "D1) regex for a 10-digit mobile number", "D2) normalise, then regex + UPI/email",
                    "D3) + any-script digits, number words, context rules"}[d], 100.0 * tp / leaks, fp, fpSamples.isEmpty() ? "" : "   e.g. \"" + fpSamples.get(0) + "\"");
        }
        System.out.println("\n   Recall by how the number was written (D1 → D3):");
        for (var e : byStyle.entrySet()) System.out.printf("        %-14s %5.0f%% → %5.0f%%%n", e.getKey(), 100.0 * e.getValue()[0] / e.getValue()[1], 100.0 * e.getValue()[2] / e.getValue()[1]);
        System.out.println("\n   → a phone-number regex finds only the numbers people write plainly; the rest are written with spaces, words or");
        System.out.println("     another script precisely because people know plain numbers get caught. Normalising first — separators,");
        System.out.println("     Devanagari digits, number words in the languages your users write — is what makes detection work, and");
        System.out.println("     context rules keep it from masking a courier AWB or our own support address. What no per-message rule catches:");
        System.out.println("     misspelt number words and a number split across two messages — detection has to look at the conversation, and");
        System.out.println("     it will never be perfect. Mask rather than block, log for trust & safety, and keep masked calling so that");
        System.out.println("     nobody needs to share a number in the first place.");
    }

    static String words(String num, String[] w) { StringBuilder sb = new StringBuilder(); for (char c : num.toCharArray()) sb.append(w[c - '0']).append(' '); return sb.toString().trim(); }

    static final Pattern MOBILE = Pattern.compile("(?<!\\d)[6-9]\\d{9}(?!\\d)");
    static final Pattern UPI_EMAIL = Pattern.compile("[A-Za-z0-9._-]{2,}@[A-Za-z]{2,}(\\.[A-Za-z]{2,})*");

    static boolean detect(int d, String text) {
        if (d == 1) return MOBILE.matcher(text).find();
        String t = text.toLowerCase();
        Matcher em = UPI_EMAIL.matcher(t);
        while (em.find()) if (d == 2 || !em.group().endsWith("@grocery.in")) return true;           // D3: our own addresses are fine
        StringBuilder sb = new StringBuilder();
        for (char c : t.toCharArray()) {
            if (d == 2) { int k = new String(DEV).indexOf(c); sb.append(k >= 0 ? (char) ('0' + k) : c); }
            else sb.append(Character.isDigit(c) ? (char) ('0' + Character.getNumericValue(c)) : c);   // any script's digits
        }
        t = sb.toString();
        if (d == 3) {                                                                  // number words → digits
            for (int i = 0; i < 10; i++) { t = t.replaceAll("\\b" + EN[i] + "\\b", String.valueOf(i)); t = t.replaceAll("\\b" + HI[i] + "\\b", String.valueOf(i)); }
        }
        t = t.replaceAll("(?<=\\d)[\\s.-]+(?=\\d)", "");                                // join digits split by separators
        t = t.replaceAll("\\+?91(?=[6-9]\\d{9}(?!\\d))", "").replaceAll("(?<!\\d)0(?=[6-9]\\d{9}(?!\\d))", "");
        if (d == 3 && t.matches(".*(otp|ord-|₹|pincode|awb).*") && !MOBILE.matcher(t.replaceAll("(ord-|awb )\\d+", "")).find()) return false;
        return MOBILE.matcher(t).find();
    }
}
