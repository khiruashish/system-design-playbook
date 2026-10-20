// World.java — the synthetic grocery world shared by the Chapter 43 labs (fixed seed).
// 3,000 products in 40 categories with Zipf popularity; each product has 2–3 complements (bread → butter, pasta → sauce).
// 20,000 customers, each with 5–15 staples bought on a personal cadence (weekly, fortnightly, monthly). Twelve weeks of
// orders: staples that are due, complements of what's in the basket (30%), and 1–3 popular impulse items.
import java.util.*;

final class World {
    final int products = 3_000, users = 20_000, weeks = 12;
    final int[] category = new int[products]; final double[] pop = new double[products]; final int[][] complements = new int[products][];
    final List<List<Set<Integer>>> baskets = new ArrayList<>();        // [user][week] -> items
    final double[] popCdf = new double[products]; double popSum;

    World(long seed) {
        Random r = new Random(seed);
        Integer[] order = new Integer[products]; for (int i = 0; i < products; i++) order[i] = i;
        Collections.shuffle(Arrays.asList(order), r);
        for (int i = 0; i < products; i++) { category[i] = r.nextInt(40); pop[order[i]] = 1.0 / Math.pow(i + 1, 0.9); }
        for (int i = 0; i < products; i++) { popSum += pop[i]; popCdf[i] = popSum; }
        for (int i = 0; i < products; i++) {
            int n = 2 + r.nextInt(2); complements[i] = new int[n];
            for (int k = 0; k < n; k++) complements[i][k] = drawPopular(r);          // complements skew popular too
        }
        for (int u = 0; u < users; u++) {
            int ns = 5 + r.nextInt(11); int[] staples = new int[ns]; int[] cadence = new int[ns]; int[] phase = new int[ns];
            for (int k = 0; k < ns; k++) { staples[k] = r.nextDouble() < 0.5 ? drawPopular(r) : r.nextInt(products); cadence[k] = new int[]{1, 1, 2, 4}[r.nextInt(4)]; phase[k] = r.nextInt(cadence[k]); }
            List<Set<Integer>> ub = new ArrayList<>();
            for (int w = 0; w < weeks; w++) {
                Set<Integer> b = new LinkedHashSet<>();
                for (int k = 0; k < ns; k++) if ((w + phase[k]) % cadence[k] == 0 && r.nextDouble() < 0.85) b.add(staples[k]);
                for (int item : new ArrayList<>(b)) for (int c : complements[item]) if (r.nextDouble() < 0.30) b.add(c);
                int impulse = 1 + r.nextInt(3); for (int k = 0; k < impulse; k++) b.add(drawPopular(r));
                ub.add(b);
            }
            baskets.add(ub);
        }
    }
    int drawPopular(Random r) { int i = Arrays.binarySearch(popCdf, r.nextDouble() * popSum); return i < 0 ? Math.min(products - 1, -i - 1) : i; }
}
