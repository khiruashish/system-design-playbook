// RubricL4.java — run with: java RubricL4.java   (JDK 21, no dependencies)
// HOW A 45-MINUTE DESIGN INTERVIEW IS SCORED. A rubric of 8 signals (the ones this chapter's framework is built to
// produce), each needing specific evidence, weighted by level, plus time discipline. Three ILLUSTRATIVE transcripts of
// "design a grocery checkout" (written for this lab — timelines of what the candidate did, minute by minute) are
// scored for a senior (B3) and a staff (B4) bar. The point is to see which behaviours move the score.
import java.util.*;

public class RubricL4 {
    record Event(int minute, String signal, int quality) {}                                        // quality 1 weak – 3 strong
    static final String[] SIGNALS = {"requirements", "estimation", "api+data model", "high-level design", "deep dive", "trade-offs", "failure handling", "operability/cost"};
    static final double[] W_SENIOR = {1.0, 0.8, 1.0, 1.2, 1.5, 1.2, 1.2, 0.6}, W_STAFF = {1.2, 0.8, 0.8, 1.0, 1.4, 1.6, 1.5, 1.2};

    public static void main(String[] args) {
        Map<String, List<Event>> t = new LinkedHashMap<>();
        t.put("A — draws boxes fast", List.of(ev(1, "high-level design", 2), ev(12, "api+data model", 1), ev(18, "deep dive", 1), ev(35, "high-level design", 2), ev(43, "trade-offs", 1)));
        t.put("B — solid senior", List.of(ev(1, "requirements", 3), ev(5, "estimation", 2), ev(8, "api+data model", 2), ev(12, "high-level design", 3), ev(18, "deep dive", 3),
                ev(30, "failure handling", 2), ev(36, "trade-offs", 2), ev(42, "operability/cost", 1)));
        t.put("C — staff signals", List.of(ev(1, "requirements", 3), ev(4, "estimation", 3), ev(7, "api+data model", 2), ev(10, "high-level design", 3), ev(14, "deep dive", 3),
                ev(24, "failure handling", 3), ev(30, "trade-offs", 3), ev(36, "operability/cost", 3), ev(40, "trade-offs", 3)));
        System.out.printf("   %-24s %-10s %-10s  %-40s %s%n", "transcript (illustrative)", "senior", "staff", "missing or weak signals", "time discipline");
        System.out.printf("   %-24s %-10s %-10s%n", "", "(bar 70%)", "(bar 85%)");
        for (var e : t.entrySet()) {
            int[] best = new int[SIGNALS.length]; for (Event x : e.getValue()) { int i = Arrays.asList(SIGNALS).indexOf(x.signal()); best[i] = Math.max(best[i], x.quality()); }
            double s = score(best, W_SENIOR), st = score(best, W_STAFF);
            List<String> weak = new ArrayList<>(); for (int i = 0; i < SIGNALS.length; i++) if (best[i] < 2) weak.add(SIGNALS[i] + (best[i] == 0 ? " (absent)" : " (weak)"));
            int firstDesign = e.getValue().stream().filter(x -> x.signal().equals("high-level design")).mapToInt(Event::minute).min().orElse(99);
            int deep = e.getValue().stream().filter(x -> x.signal().equals("deep dive")).mapToInt(Event::minute).min().orElse(99);
            String time = firstDesign < 5 && best[0] == 0 ? "designed before asking" : deep > 25 ? "deep dive started late" : "on plan";
            System.out.printf("   %-24s %4.0f%% %-5s %4.0f%% %-5s  %-40s %s%n", e.getKey(), s, s >= 70 ? "hire" : "no", st, st >= 85 ? "hire" : "no", weak.isEmpty() ? "—" : String.join(", ", weak.subList(0, Math.min(3, weak.size()))) + (weak.size() > 3 ? " +" + (weak.size() - 3) : ""), time);
        }
        System.out.println("\n   bars (illustrative): senior ≥ 70%, staff ≥ 85%. The two rubrics also weigh the same evidence differently:");
        System.out.println("   staff puts more weight on trade-offs, failure handling and operability, less on API detail.");
        System.out.println("\n   → the fast box-drawer loses on everything the boxes don't show: no requirements, no numbers, no failure");
        System.out.println("     story. The solid senior clears the senior bar and not the staff one — the gap is exactly trade-offs argued");
        System.out.println("     under pressure, failure handling as a first-class part of the design, and cost/operability. Nothing in the");
        System.out.println("     staff transcript is exotic; it is the same framework, with every phase done deliberately and on time.");
    }
    static Event ev(int m, String s, int q) { return new Event(m, s, q); }
    static double score(int[] best, double[] w) { double s = 0, max = 0; for (int i = 0; i < best.length; i++) { s += w[i] * best[i]; max += w[i] * 3; } return 100 * s / max; }
}
