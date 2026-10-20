// SpacedL4.java — run with: java SpacedL4.java   (JDK 21, no dependencies)
// HOW TO STUDY 415 ⭐ QUESTIONS IN SIX WEEKS. A simple memory model (an assumption, in the spirit of spaced-repetition
// research — VERIFY against your own experience): after a review, the chance of recalling an answer t days later is
// exp(−t / S). S starts at 1 day; each successful recall multiplies S by 2.5; a failed recall resets S to 1 day.
// Budget: about 40 reviews a day. Interview on day 43. Three plans (fixed seed):
//   P1 cram: go through all 415 on each of the last 4 days
//   P2 round-robin: 40 cards a day in a fixed cycle for 42 days (each card every ~10 days)
//   P3 spaced (Leitner-style): each day review the cards that are due (next review when recall would drop to ~80%),
//      introduce ~15 new cards a day until all are started, within the same budget
//   P4 P3 for days 1–40, then all 415 on each of the last 2 days (spaced learning + a final pass)
// Measured: expected share recalled on interview day, cards above 80% recall, expected recall 30 days later (a second
// loop, or the job), and reviews spent.
import java.util.*;

public class SpacedL4 {
    public static void main(String[] args) {
        System.out.println("415 cards, ~40 reviews/day budget, interview on day 43; recall(t) = exp(−t/S), S ×2.5 on success.\n");
        System.out.printf("   %-56s %16s %14s %16s %10s%n", "plan", "expected recall", "cards > 80%", "recall 30 days", "reviews");
        System.out.printf("   %-56s %16s %14s %16s %10s%n", "", "on interview day", "that day", "later (2nd loop)", "");
        for (int p = 1; p <= 4; p++) run(p);
        System.out.println("\n   → under this model, cramming wins the next morning and loses a month later: it fills short-term memory");
        System.out.println("     that fades in days. A fixed cycle is the worst of both — every card comes back after it has been forgotten.");
        System.out.println("     Spaced review builds durable memory but, on a tight budget, reaches the interview with fewer answers fresh.");
        System.out.println("     The combination — spaced review for weeks, then a final pass over everything — comes close to cramming on");
        System.out.println("     the day (57% vs 61%) and keeps the most a month later (17%), at the cost of more reviews. The absolute numbers");
        System.out.println("     come from a deliberately harsh, simple model; the direction is what matters (and is consistent with");
        System.out.println("     spaced-repetition research — VERIFY with your own practice).");
    }
    static void run(int plan) {
        int N = 415, days = 42; Random r = new Random(59); double[] S = new double[N], last = new double[N]; boolean[] started = new boolean[N]; int reviews = 0;
        Arrays.fill(last, Double.NaN);
        for (int day = 1; day <= days; day++) {
            List<Integer> today = new ArrayList<>();
            if (plan == 1 || (plan == 4 && day > days - 2)) { if (plan == 4 || day > days - 4) for (int i = 0; i < N; i++) today.add(i); }
            else if (plan == 2) { for (int k = 0; k < 40; k++) today.add(((day - 1) * 40 + k) % N); }
            else {
                if (plan == 4 && day > days - 2) { } else {
                List<Integer> due = new ArrayList<>(); for (int i = 0; i < N; i++) if (started[i] && Math.exp(-(day - last[i]) / S[i]) < 0.8) due.add(i);
                final int d0 = day; due.sort(Comparator.comparingDouble(i -> Math.exp(-(d0 - last[i]) / S[i])));
                for (int i : due) { if (today.size() >= 40) break; today.add(i); }
                int added = 0; for (int i = 0; i < N && today.size() < 40 && added < 15; i++) if (!started[i]) { today.add(i); started[i] = true; added++; }
                }
            }
            for (int i : today) { reviews++;
                if (Double.isNaN(last[i])) { S[i] = 1; last[i] = day; continue; }                  // first study
                double pr = Math.exp(-(day - last[i]) / S[i]); if (r.nextDouble() < pr) S[i] *= 2.5; else S[i] = 1; last[i] = day; started[i] = true; }
        }
        double sum = 0, later = 0; int strong = 0; int interview = days + 1;
        for (int i = 0; i < N; i++) { double pr = Double.isNaN(last[i]) ? 0 : Math.exp(-(interview - last[i]) / S[i]); sum += pr; if (pr > 0.8) strong++; later += Double.isNaN(last[i]) ? 0 : Math.exp(-(interview + 30 - last[i]) / S[i]); }
        String[] names = {"", "P1) cram: all 415 on each of the last 4 days", "P2) round-robin: 40 a day in a fixed cycle", "P3) spaced: due cards first, 15 new a day", "P4) spaced (days 1–40) + all 415 on the last 2 days"};
        System.out.printf("   %-56s %15.0f%% %14d %15.0f%% %10d%n", names[plan], 100 * sum / N, strong, 100 * later / N, reviews);
    }
}
