// ClarifyL3.java — run with: java ClarifyL3.java   (JDK 21, no dependencies)
// WHY THE FIRST FIVE MINUTES ARE QUESTIONS. Prompt: "Design a notification system." Five things the prompt doesn't
// say, each with plausible answers (every combination is a requirement set an interviewer might have in mind):
//   scale: 1k/s | 50k/s | 1M/s burst        latency: seconds OK | < 500 ms (OTP)       guarantee: best effort | at-least-once
//   channels: push only | SMS+push+email     user prefs/quiet hours: no | yes
// A rule set (from Ch 40 and Parts I–II) maps each requirement set to the architecture it needs: queue type, priority
// lanes, provider failover, dedup store, preference service, rate limiting. We then compare:
//   Q0 skip the questions: design for the "typical" set (50k/s, seconds, at-least-once, all channels, prefs)
//   Q1..Q2 ask the most INFORMATIVE questions first (greatest reduction in uncertainty about the architecture), then
//      assume typical values for the rest
//   Q5 ask all five
// Measured: share of requirement sets for which the resulting design is right (not over-built, not missing a need).
import java.util.*;

public class ClarifyL3 {
    static final String[][] DIMS = {{"1k/s", "50k/s", "1M/s burst"}, {"seconds OK", "<500 ms"}, {"best effort", "at-least-once"}, {"push only", "SMS+push+email"}, {"no prefs", "prefs + quiet hours"}};
    static final String[] NAMES = {"scale", "latency", "guarantee", "channels", "preferences"};
    static final int[] TYPICAL = {1, 0, 1, 1, 1};

    public static void main(String[] args) {
        List<int[]> sets = new ArrayList<>(); enumerate(new int[5], 0, sets);
        System.out.printf("%d plausible requirement sets for \"design a notification system\"; each implies an architecture.%n%n", sets.size());
        // information gain of each question (entropy of the architecture over sets)
        Map<String, Integer> archCount = new HashMap<>(); for (int[] s : sets) archCount.merge(arch(s), 1, Integer::sum);
        double h0 = entropy(archCount.values(), sets.size());
        System.out.printf("   distinct architectures implied: %d (uncertainty %.2f bits)%n", archCount.size(), h0);
        Integer[] order = {0, 1, 2, 3, 4}; double[] gain = new double[5];
        for (int d = 0; d < 5; d++) { double h = 0; for (int v = 0; v < DIMS[d].length; v++) { Map<String, Integer> c = new HashMap<>(); int n = 0; for (int[] s : sets) if (s[d] == v) { c.merge(arch(s), 1, Integer::sum); n++; } h += (double) n / sets.size() * entropy(c.values(), n); } gain[d] = h0 - h; }
        Arrays.sort(order, (a, b) -> Double.compare(gain[b], gain[a]));
        System.out.print("   information gain per question: "); for (int d : order) System.out.printf("%s %.2f bits   ", NAMES[d], gain[d]); System.out.println("\n");
        System.out.printf("   %-52s %22s %14s %14s%n", "strategy", "design right for", "over-built", "missing a need");
        for (int k : new int[]{0, 1, 2, 5}) {
            int right = 0, over = 0, under = 0;
            for (int[] s : sets) { int[] assumed = TYPICAL.clone(); for (int i = 0; i < k; i++) assumed[order[i]] = s[order[i]];
                Set<String> need = parts(s), built = parts(assumed);
                if (need.equals(built)) right++; else { if (!built.containsAll(need)) under++; else over++; } }
            String name = k == 0 ? "Q0) no questions: assume the typical set" : k == 5 ? "Q5) ask all five" : "Q" + k + ") ask the " + k + " most informative question" + (k > 1 ? "s" : "") + " (" + String.join(", ", Arrays.stream(order).limit(k).map(d -> NAMES[d]).toList()) + ")";
            System.out.printf("   %-62s %13d of %d %14d %14d%n", name, right, sets.size(), over, under);
        }
        System.out.println("\n   → an unclarified prompt is 48 different systems here. Designing for a 'typical' reading fits one of them; it");
        System.out.println("     is over-built for some (Kafka and provider failover for push-only at 1k/s) and, worse, MISSING something");
        System.out.println("     essential for 40 (usually the OTP priority lane, because 'seconds are fine' was assumed). Scale splits the");
        System.out.println("     space most, so ask it first; latency is next because its wrong default does damage rather than waste. After");
        System.out.println("     two questions nothing essential is missing, and the rest can be stated as assumptions ('I'll assume at-least-");
        System.out.println("     once and multi-channel — tell me if not'), which costs seconds and fixes the over-building.");
    }
    static void enumerate(int[] cur, int d, List<int[]> out) { if (d == 5) { out.add(cur.clone()); return; } for (int v = 0; v < DIMS[d].length; v++) { cur[d] = v; enumerate(cur, d + 1, out); } }
    static Set<String> parts(int[] s) {
        Set<String> p = new TreeSet<>();
        p.add(s[0] == 0 ? "single DB-backed queue" : s[0] == 1 ? "Kafka, partitioned workers" : "Kafka + autoscaled workers + provider rate limiting");
        if (s[1] == 1) p.add("priority lane for OTP/transactional");
        if (s[2] == 1) p.add("idempotency/dedup store + retries");
        if (s[3] == 1) p.add("per-channel adapters + provider failover");
        if (s[4] == 1) p.add("preference service + scheduler for quiet hours");
        if (s[0] == 2 && s[2] == 1) p.add("outbox + DLQ");
        return p;
    }
    static String arch(int[] s) { return String.join("|", parts(s)); }
    static double entropy(Collection<Integer> counts, int n) { double h = 0; for (int c : counts) { double p = (double) c / n; h -= p * Math.log(p) / Math.log(2); } return h; }
}
