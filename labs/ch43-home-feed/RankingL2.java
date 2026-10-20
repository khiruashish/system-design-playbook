// RankingL2.java — run with: java RankingL2.java   (JDK 21, no dependencies)
// LEARNING TO RANK FROM YOUR OWN LOGS — AND THE POSITION-BIAS TRAP. Each home-feed request has 40 candidates (from L1's
// retrievers) with 4 features: buy-again score, co-purchase score, popularity and discount. A hidden "true" model decides
// how relevant each item is; a customer clicks an item with probability  examine(position) × relevance, where
// examine = 1 / (1 + 0.35 × position): the top slot is looked at far more than slot 10 (position bias).
// The logs come from the OLD ranker, which sorted mostly by popularity. From 200,000 logged requests we train logistic
// regression rankers (plain SGD, fixed seed) and evaluate them on 20,000 new requests against the TRUE relevance:
//   R0 the old popularity ranker (the logging policy)
//   R1 logistic regression on the 4 features, clicks as labels                     — naive
//   R2 R1 + position as a training feature, set to 0 when serving                  — debiasing by modelling position
//   R3 R1 with inverse-propensity weights (each example weighted by 1/examine)     — debiasing by reweighting
//   R* the true model (upper bound)
import java.util.*;

public class RankingL2 {
    static final double[] TRUE_W = {2.2, 1.4, 0.3, 0.8}; static final double TRUE_B = -3.0;
    static double examine(int pos) { return 1.0 / (1 + 0.35 * pos); }
    static double sigmoid(double z) { return 1 / (1 + Math.exp(-z)); }

    public static void main(String[] args) {
        Random r = new Random(43); int cands = 40, shown = 20;
        // --- logging: old ranker sorts by popularity + noise, shows top 20, clicks follow position bias ---
        List<double[]> X = new ArrayList<>(); List<Integer> Y = new ArrayList<>(); List<Integer> P = new ArrayList<>();
        for (int q = 0; q < 200_000; q++) {
            double[][] f = features(r, cands); Integer[] order = order(f, v -> v[2] + 0.3 * r.nextGaussian());
            for (int pos = 0; pos < shown; pos++) { double[] x = f[order[pos]]; double rel = relevance(x); X.add(x); P.add(pos); Y.add(r.nextDouble() < examine(pos) * rel ? 1 : 0); }
        }
        System.out.printf("Logged by the old popularity ranker: %,d impressions, %,d clicks (CTR %.2f%%).%n%n", X.size(), Y.stream().mapToInt(Integer::intValue).sum(),
                100.0 * Y.stream().mapToInt(Integer::intValue).sum() / X.size());
        double[] w1 = train(X, Y, P, false, false), w2 = train(X, Y, P, true, false), w3 = train(X, Y, P, false, true);
        System.out.printf("   %-52s %11s %11s %11s %11s %11s%n", "model", "w buy-again", "w co-purch", "w popular", "w discount", "w position");
        System.out.printf("   %-52s %11.2f %11.2f %11.2f %11.2f %11s%n", "true model (hidden)", TRUE_W[0], TRUE_W[1], TRUE_W[2], TRUE_W[3], "—");
        System.out.printf("   %-52s %11.2f %11.2f %11.2f %11.2f %11s%n", "R1 naive", w1[0], w1[1], w1[2], w1[3], "—");
        System.out.printf("   %-52s %11.2f %11.2f %11.2f %11.2f %11.2f%n", "R2 with position feature", w2[0], w2[1], w2[2], w2[3], w2[5]);
        System.out.printf("   %-52s %11.2f %11.2f %11.2f %11.2f %11s%n", "R3 inverse-propensity weighted", w3[0], w3[1], w3[2], w3[3], "—");

        // --- evaluation on fresh requests against true relevance ---
        String[] names = {"R0) old popularity ranker", "R1) naive logistic regression", "R2) + position feature (0 at serving)", "R3) inverse-propensity weighted", "R*) true model (upper bound)"};
        System.out.printf("%n   %-52s %10s %18s%n", "ranker", "NDCG@10", "expected clicks/req");
        Random e = new Random(99);
        List<double[][]> test = new ArrayList<>(); for (int q = 0; q < 20_000; q++) test.add(features(e, cands));
        for (int m = 0; m < 5; m++) {
            double ndcg = 0, clicks = 0; final int mm = m;
            for (double[][] f : test) {
                Integer[] order = order(f, v -> switch (mm) { case 0 -> v[2]; case 1 -> dot(w1, v, 0); case 2 -> dot(w2, v, 0); case 3 -> dot(w3, v, 0); default -> relevance(v); });
                double dcg = 0, idcg = 0; double[] rels = new double[f.length];
                for (int i = 0; i < f.length; i++) rels[i] = relevance(f[i]);
                double[] sorted = rels.clone(); Arrays.sort(sorted);
                for (int k = 0; k < 10; k++) { dcg += rels[order[k]] / (Math.log(k + 2) / Math.log(2)); idcg += sorted[f.length - 1 - k] / (Math.log(k + 2) / Math.log(2)); }
                for (int k = 0; k < shown; k++) clicks += examine(k) * rels[order[k]];
                ndcg += dcg / idcg;
            }
            System.out.printf("   %-52s %10.3f %18.3f%n", names[m], ndcg / test.size(), clicks / test.size());
        }
        System.out.println("\n   → the logs record what the OLD ranker chose to show at the top, so popular items look better than they are:");
        System.out.println("     the naive model inflates popularity and learns the logging policy's bias back. Modelling position explicitly");
        System.out.println("     (or reweighting by how likely each slot was to be seen) recovers weights close to the truth and a better");
        System.out.println("     ranking. Every learned ranker is trained on data its predecessor shaped — measure and correct for that, and");
        System.out.println("     keep some randomised traffic (L4) so the logs contain evidence about items the old ranker never showed.");
    }

    static double relevance(double[] x) { return sigmoid(TRUE_B + TRUE_W[0] * x[0] + TRUE_W[1] * x[1] + TRUE_W[2] * x[2] + TRUE_W[3] * x[3]); }
    static double[][] features(Random r, int n) {
        double[][] f = new double[n][4];
        for (int i = 0; i < n; i++) { double pop = r.nextDouble(); f[i][0] = r.nextDouble() < 0.3 ? r.nextDouble() : 0; f[i][1] = r.nextDouble() * r.nextDouble();
            f[i][2] = pop; f[i][3] = r.nextDouble() < 0.2 ? r.nextDouble() : 0; f[i][0] += 0.4 * pop * r.nextDouble(); }   // popular items are bought again more
        return f;
    }
    static Integer[] order(double[][] f, java.util.function.ToDoubleFunction<double[]> s) {
        Integer[] o = new Integer[f.length]; double[] sc = new double[f.length];
        for (int i = 0; i < f.length; i++) { o[i] = i; sc[i] = s.applyAsDouble(f[i]); }
        Arrays.sort(o, (a, b) -> Double.compare(sc[b], sc[a])); return o;
    }
    static double dot(double[] w, double[] x, int pos) { return w[4] + w[0] * x[0] + w[1] * x[1] + w[2] * x[2] + w[3] * x[3] + w[5] * pos; }
    // weights: [4 features, bias, position]
    static double[] train(List<double[]> X, List<Integer> Y, List<Integer> P, boolean positionFeature, boolean ips) {
        double[] w = new double[6]; Random r = new Random(1); int n = X.size(); double lr = 0.05;
        Integer[] idx = new Integer[n]; for (int i = 0; i < n; i++) idx[i] = i;
        for (int epoch = 0; epoch < 4; epoch++) {
            Collections.shuffle(Arrays.asList(idx), r);
            for (int k : idx) {
                double[] x = X.get(k); int pos = P.get(k); double z = dot(w, x, positionFeature ? pos : 0);
                double p = sigmoid(z), g = (Y.get(k) - p) * (ips ? (Y.get(k) == 1 ? 1 / examine(pos) : 1) : 1);
                for (int j = 0; j < 4; j++) w[j] += lr * g * x[j];
                w[4] += lr * g; if (positionFeature) w[5] += lr * g * pos;
            }
            lr *= 0.5;
        }
        return w;
    }
}
