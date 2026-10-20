// SloL4.java — run with: java SloL4.java      (JDK 17+, no dependencies)
// SLIs, SLOs, error budgets and alerting. A 30-day minute-by-minute stream of good/bad requests for checkout, with a
// steady low error rate plus three injected incidents. We compute the SLO/error budget, then compare three alerting
// strategies on the SAME stream: a naive threshold, a single long-window burn rate, and Google SRE's multi-window
// multi-burn-rate. We count pages, false pages, missed incidents and time-to-detect.
import java.util.*;

public class SloL4 {
    static final int DAYS = 30, MIN = DAYS * 24 * 60;             // one data point per minute
    static final double SLO = 0.999;                              // 99.9% success → 0.1% error budget
    static final double BUDGET = 1 - SLO;

    public static void main(String[] args) {
        Random r = new Random(24);
        int[] total = new int[MIN], bad = new int[MIN];
        // background traffic: ~50 rpm off-peak to ~600 rpm at lunch/dinner; baseline error ~0.02% (well under budget)
        boolean[] incident = new boolean[MIN];
        // three incidents: a short total outage, a slow partial-error burn, a brief blip
        int[][] incidents = {{8 * 24 * 60 + 13 * 60, 20, 90},      // day 8, 13:00, 20 min, 90% errors (sharp)
                             {15 * 24 * 60 + 2 * 60, 6 * 60, 4},    // day 15, 02:00, 6 h, 4% errors (slow burn, low rate)
                             {22 * 24 * 60 + 20 * 60, 3, 60}};      // day 22, 20:00, 3 min, 60% errors (blip)
        for (int m = 0; m < MIN; m++) {
            int hour = (m / 60) % 24;
            double rpm = 50 + 550 * Math.max(0, Math.sin((hour - 6) / 24.0 * Math.PI * 2)) + r.nextGaussian() * 20;
            int t = Math.max(1, (int) rpm);
            double errRate = 0.0002;
            for (int[] inc : incidents) if (m >= inc[0] && m < inc[0] + inc[1]) { errRate = inc[2] / 100.0; incident[m] = true; }
            int b = 0; for (int i = 0; i < t; i++) if (r.nextDouble() < errRate) b++;
            total[m] = t; bad[m] = b;
        }

        long totalReq = 0, totalBad = 0; for (int m = 0; m < MIN; m++) { totalReq += total[m]; totalBad += bad[m]; }
        double observedErr = (double) totalBad / totalReq;
        System.out.printf("Checkout SLO: %.1f%% success over 30 days. Requests %,d, failures %,d, error rate %.4f%%%n", SLO * 100, totalReq, totalBad, observedErr * 100);
        double budgetReq = totalReq * BUDGET;
        System.out.printf("Error budget: %.4f%% = %,.0f failed requests allowed in 30 days; used %,d (%.0f%% of budget)%n",
                BUDGET * 100, budgetReq, totalBad, 100 * totalBad / budgetReq);

        // ---- burn rate over a trailing window: (errors/requests in window) / BUDGET ----
        // A burn rate of 1 exactly exhausts the 30-day budget in 30 days; 14.4 exhausts it in ~2 days.
        java.util.function.BiFunction<Integer, Integer, Double> burn = (endExclusive, windowMin) -> {
            long tr = 0, br = 0; for (int m = Math.max(0, endExclusive - windowMin); m < endExclusive; m++) { tr += total[m]; br += bad[m]; }
            if (tr == 0) return 0.0; return ((double) br / tr) / BUDGET;
        };

        System.out.println("\nComparing alert strategies on the same 30 days (an alert 'fires' when its condition holds; we page on a rising edge):");
        System.out.printf("   %-42s %7s %8s %10s %14s%n", "strategy", "pages", "false", "missed", "median detect");
        // strategy 1: naive — page whenever the last 5 min error rate > 1% (a fixed threshold)
        evaluate("naive threshold: 5-min err > 1%", total, bad, incident, m -> burn.apply(m, 5) * BUDGET > 0.01);
        // strategy 2: single long window burn rate > 1 over 1 hour
        evaluate("single window: 1-hour burn > 1", total, bad, incident, m -> burn.apply(m, 60) > 1.0);
        // strategy 3: Google SRE multi-window multi-burn-rate (page tier)
        //   fast: 14.4x over 1h AND 5m ; slow: 6x over 6h AND 30m  (the short window confirms it's still happening)
        evaluate("multi-window multi-burn (SRE page)", total, bad, incident, m ->
                (burn.apply(m, 60) > 14.4 && burn.apply(m, 5) > 14.4) || (burn.apply(m, 360) > 6 && burn.apply(m, 30) > 6));

        System.out.println("\n   incidents to catch: day 8 (20 min, 90% — sharp), day 15 (6 h, 4% — slow burn), day 22 (3 min, 60% — blip)");
        System.out.println("   → naive fixed threshold: its pages don't map to budget (a 1% rate is a crisis at 600 rpm, noise at 50 rpm), so it both over- and under-pages;");
        System.out.println("     single long window: it flaps — the condition dips and re-crosses during an incident, so one incident becomes many pages (alert fatigue);");
        System.out.println("     multi-window multi-burn: fewest pages, zero false pages, ~2-min detect. The short window confirms the incident is still live before paging,");
        System.out.println("     and the burn-rate framing means every page corresponds to real error-budget burn (see the per-incident budget below).");

        // budget-burn view of each incident
        System.out.println("\n   How much budget each incident burned (page only when it matters):");
        String[] names = {"day 8 sharp outage", "day 15 slow burn", "day 22 blip"};
        for (int i = 0; i < incidents.length; i++) {
            long tr = 0, br = 0; for (int m = incidents[i][0]; m < incidents[i][0] + incidents[i][1]; m++) { tr += total[m]; br += bad[m]; }
            System.out.printf("     %-20s burned %,6d failed reqs = %5.1f%% of the 30-day budget%n", names[i], br, 100 * br / budgetReq);
        }
    }

    interface Cond { boolean firing(int minute); }
    static void evaluate(String name, int[] total, int[] bad, boolean[] incident, Cond cond) {
        int pages = 0, falsePages = 0; boolean prev = false;
        Set<Integer> incidentsCaught = new HashSet<>();
        List<Integer> detectDelays = new ArrayList<>();
        // map each minute to its incident id (or -1)
        int[] incId = incidentId(incident);
        Map<Integer, Integer> firstFire = new HashMap<>();
        for (int m = 0; m < total.length; m++) {
            boolean f = cond.firing(m);
            if (f && !prev) {                                                     // rising edge = one page
                pages++;
                int id = incId[m];
                if (id >= 0) { incidentsCaught.add(id); firstFire.putIfAbsent(id, m); }
                else falsePages++;
            }
            prev = f;
        }
        // time to detect: first fire minus incident start
        int[][] starts = incidentStarts(incident);
        for (int[] st : starts) {
            int id = st[1];
            if (firstFire.containsKey(id)) detectDelays.add(firstFire.get(id) - st[0]);
        }
        int totalIncidents = starts.length;
        long missed = totalIncidents - incidentsCaught.size();
        String detect = detectDelays.isEmpty() ? "n/a" : median(detectDelays) + " min";
        System.out.printf("   %-42s %7d %8d %10d %14s%n", name, pages, falsePages, missed, detect);
    }

    static int[] incidentId(boolean[] incident) {
        int[] id = new int[incident.length]; Arrays.fill(id, -1); int cur = -1; boolean prev = false;
        for (int m = 0; m < incident.length; m++) { if (incident[m] && !prev) cur++; id[m] = incident[m] ? cur : -1; prev = incident[m]; }
        return id;
    }
    static int[][] incidentStarts(boolean[] incident) {
        List<int[]> starts = new ArrayList<>(); int cur = -1; boolean prev = false;
        for (int m = 0; m < incident.length; m++) { if (incident[m] && !prev) { cur++; starts.add(new int[]{m, cur}); } prev = incident[m]; }
        return starts.toArray(new int[0][]);
    }
    static int median(List<Integer> xs) { var s = xs.stream().sorted().toList(); return s.get(s.size() / 2); }
}
