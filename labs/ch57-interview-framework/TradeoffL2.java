// TradeoffL2.java — run with: java TradeoffL2.java   (JDK 21, no dependencies)
// DEFENDING A CHOICE UNDER QUESTIONING. Interviewers push: "why not X?" A weighted decision matrix makes the reasoning
// explicit — but is the winner robust, or an artefact of the weights you picked? Three decisions from this handbook,
// with criteria weights and 1–5 scores as a candidate might state them (ILLUSTRATIVE judgements, not benchmarks).
// For each: the base-case winner, then 100,000 perturbations (every weight ±40%, every score ±1 with 30% chance):
// how often each option wins, and which single criterion, if its weight doubled, would change the winner.
import java.util.*;

public class TradeoffL2 {
    record Decision(String name, String[] criteria, double[] weights, String[] options, int[][] scores) {}

    public static void main(String[] args) {
        List<Decision> ds = List.of(
            new Decision("Cart store (read-your-writes, 5M active carts, sale spikes)",
                new String[]{"read-your-writes", "write throughput", "ops burden", "team familiarity", "cost"}, new double[]{5, 4, 3, 2, 2},
                new String[]{"PostgreSQL (partitioned)", "Redis + PG snapshot", "Managed KV (DynamoDB-like)", "Cassandra"},
                new int[][]{{5, 3, 4, 5, 4}, {4, 5, 3, 4, 3}, {4, 5, 5, 3, 3}, {3, 5, 2, 2, 3}}),
            new Decision("Event backbone (order events, replay, 20 consumers)",
                new String[]{"replay/retention", "throughput", "ordering per key", "ops burden", "ecosystem"}, new double[]{5, 3, 4, 3, 3},
                new String[]{"Kafka", "RabbitMQ", "SQS/SNS", "Pulsar"},
                new int[][]{{5, 5, 5, 2, 5}, {2, 3, 3, 4, 4}, {1, 4, 2, 5, 3}, {5, 5, 5, 2, 3}}),
            new Decision("Product search (250k SKUs, typo tolerance, facets)",
                new String[]{"relevance features", "latency at 10k qps", "ops burden", "consistency with catalog", "cost"}, new double[]{5, 4, 3, 2, 2},
                new String[]{"OpenSearch", "PostgreSQL full-text", "Managed search SaaS"},
                new int[][]{{5, 4, 2, 3, 3}, {2, 3, 5, 5, 5}, {5, 5, 5, 3, 2}}));
        Random r = new Random(57);
        for (Decision d : ds) {
            int n = d.options().length; double[] base = score(d, d.weights(), d.scores());
            int bw = argmax(base); double second = -1; for (int o = 0; o < n; o++) if (o != bw) second = Math.max(second, base[o]);
            System.out.printf("%s%n", d.name());
            for (int o = 0; o < n; o++) System.out.printf("   %-30s base score %5.1f%s%n", d.options()[o], base[o], o == bw ? "   ← base-case winner" : "");
            int[] wins = new int[n];
            for (int k = 0; k < 100_000; k++) {
                double[] w = d.weights().clone(); for (int i = 0; i < w.length; i++) w[i] *= 0.6 + 0.8 * r.nextDouble();
                int[][] s = new int[n][]; for (int o = 0; o < n; o++) { s[o] = d.scores()[o].clone(); for (int i = 0; i < s[o].length; i++) if (r.nextDouble() < 0.3) s[o][i] = Math.max(1, Math.min(5, s[o][i] + (r.nextBoolean() ? 1 : -1))); }
                wins[argmax(score(d, w, s))]++; }
            System.out.print("   wins under perturbation: "); for (int o = 0; o < n; o++) System.out.printf("%s %.0f%%   ", d.options()[o].split(" ")[0], wins[o] / 1000.0); System.out.println();
            List<String> flips = new ArrayList<>();
            for (int i = 0; i < d.criteria().length; i++) { double[] w = d.weights().clone(); w[i] *= 2; int win = argmax(score(d, w, d.scores())); if (win != bw) flips.add("'" + d.criteria()[i] + "' → " + d.options()[win]); }
            System.out.printf("   margin over runner-up: %s; doubling one weight flips it: %s%n%n", base[bw] - second < 1e-9 ? "TIE" : String.format("%.1f points", base[bw] - second), flips.isEmpty() ? "no" : String.join("; ", flips));
        }
        System.out.println("   → a matrix doesn't make the decision; it makes your reasoning inspectable. The useful output is not the");
        System.out.println("     winner but its robustness: a choice that wins under most reasonable weightings can be defended calmly;");
        System.out.println("     a tie or narrow margin should be presented as a close call, naming what would flip it ('if write throughput");
        System.out.println("     or ops burden mattered more, I'd take the managed KV'). And when the matrix disagrees with the choice you");
        System.out.println("     made (search here), say which criterion it is missing — that is the conversation senior interviews reward.");
    }
    static double[] score(Decision d, double[] w, int[][] s) { double[] out = new double[s.length]; for (int o = 0; o < s.length; o++) for (int i = 0; i < w.length; i++) out[o] += w[i] * s[o][i]; return out; }
    static int argmax(double[] a) { int b = 0; for (int i = 1; i < a.length; i++) if (a[i] > a[b]) b = i; return b; }
}
