// DecisionL1.java — run with: java DecisionL1.java   (JDK 21, no dependencies)
// A WEIGHTED TRADE-OFF DECISION MATRIX with SENSITIVITY ANALYSIS. The grocery platform must pick an architecture style
// for its next phase. Three candidates are scored 1..5 against weighted quality attributes; we compute the ranking, then
// do the step most people skip: SENSITIVITY — vary the weights and see whether the winner is robust or an artifact of one
// number. A decision that flips when a weight nudges is a decision you don't actually understand yet.
import java.util.*;

public class DecisionL1 {
    // quality attributes (criteria) and their weights (must be > 0; need not sum to 1 — we normalize)
    static final String[] CRITERIA = {
        "time-to-market", "scalability", "operability", "cost-at-current-scale", "team-fit", "evolvability"
    };
    static final double[] WEIGHTS = { 3, 2, 2, 2, 3, 2 };     // this team weights speed + team-fit highest right now

    static final String[] OPTIONS = { "Modular Monolith", "Microservices", "Serverless (FaaS)" };
    // scores[option][criterion], 1 (poor) .. 5 (excellent), from an ATAM-style scoring workshop
    static final int[][] SCORES = {
        //  ttm  scale  ops  cost  team  evolve
        {    5,    3,    5,    5,    5,    4 },   // Modular Monolith
        {    2,    5,    2,    2,    2,    5 },   // Microservices
        {    4,    5,    3,    4,    3,    3 },   // Serverless
    };

    static double[] weightedTotals(double[] w) {
        double sum = Arrays.stream(w).sum();
        double[] totals = new double[OPTIONS.length];
        for (int o = 0; o < OPTIONS.length; o++) {
            double t = 0;
            for (int c = 0; c < CRITERIA.length; c++) t += SCORES[o][c] * (w[c] / sum);
            totals[o] = t;                                     // 1..5 scale (weighted average)
        }
        return totals;
    }
    static int winner(double[] totals) {
        int best = 0; for (int i = 1; i < totals.length; i++) if (totals[i] > totals[best]) best = i; return best;
    }

    public static void main(String[] args) {
        System.out.println("Weighted decision matrix — architecture style for the grocery platform's next phase.");
        System.out.print("   Criteria/weights: ");
        for (int c = 0; c < CRITERIA.length; c++) System.out.printf("%s=%.0f  ", CRITERIA[c], WEIGHTS[c]);
        System.out.println("\n");

        // the base ranking
        System.out.printf("   %-20s %s   weighted%n", "option", "raw scores (ttm/scale/ops/cost/team/evolve)");
        double[] base = weightedTotals(WEIGHTS);
        for (int o = 0; o < OPTIONS.length; o++)
            System.out.printf("   %-20s %-40s %6.2f%n", OPTIONS[o], Arrays.toString(SCORES[o]), base[o]);
        int baseWin = winner(base);
        System.out.printf("   → base winner: %s (%.2f)%n%n", OPTIONS[baseWin], base[baseWin]);

        // SENSITIVITY 1: crank scalability toward "scale is everything" and watch for a flip
        for (double sw : new double[]{6, 9}) {
            double[] w2 = WEIGHTS.clone(); w2[1] = sw;
            double[] s2 = weightedTotals(w2); int win2 = winner(s2);
            System.out.printf("   sensitivity A — scalability weight 2→%.0f:%n", sw);
            for (int o = 0; o < OPTIONS.length; o++) System.out.printf("        %-20s %6.2f%n", OPTIONS[o], s2[o]);
            System.out.printf("        → winner: %s%s%n%n", OPTIONS[win2], win2 != baseWin ? "  ← RANKING FLIPPED" : "  (unchanged)");
        }

        // SENSITIVITY 2: one-at-a-time — how much must EACH weight change (alone) to flip the winner, and to what?
        System.out.println("   sensitivity B — how far can each weight move (alone) before the winner changes?");
        for (int c = 0; c < CRITERIA.length; c++) {
            Double flipAt = null; int newWin = -1;
            for (double wv = 0.0; wv <= 12.0; wv += 0.25) {
                double[] w = WEIGHTS.clone(); w[c] = wv;
                int win = winner(weightedTotals(w));
                if (win != baseWin) { flipAt = wv; newWin = win; break; }
            }
            System.out.printf("        %-22s base weight %.0f → %s%n", CRITERIA[c], WEIGHTS[c],
                    flipAt == null ? "never flips within [0,12]" : String.format("flips to %s at weight ≈ %.2f", OPTIONS[newWin], flipAt));
        }

        System.out.println("\n   → the recommendation is the Modular Monolith, and the sensitivity analysis shows it's a ROBUST choice, not a coin-flip:");
        System.out.println("     tripling the scalability weight (2→6) doesn't change it, and of all six criteria only scalability can flip it at all —");
        System.out.println("     and only at an extreme weight (≈8.75, i.e. scalability valued ~3x everything else combined), at which point Serverless,");
        System.out.println("     not Microservices, takes over. That is the useful output: the ADR records not just the winner but that the decision is");
        System.out.println("     stable under normal disagreement about weights, with a concrete, quantified trigger to reopen it (scalability becoming");
        System.out.println("     dominant). A decision that flipped under a tiny weight nudge would be one you don't yet understand.");
    }
}
