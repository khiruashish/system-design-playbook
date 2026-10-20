// CandidatesL1.java — run with: javac -d out World.java CandidatesL1.java && java -cp out CandidatesL1   (JDK 21, no deps)
// WHERE HOME-FEED CANDIDATES COME FROM. The home screen shows ~20 products per customer. Ranking can only reorder what
// candidate generation retrieves, so retrieval recall sets the ceiling. Using weeks 1–11 of the shared synthetic world, we
// predict each customer's week-12 basket with four retrievers and measure recall@20 (share of week-12 items that were in
// the 20), and how many distinct products the whole population was shown (catalogue coverage):
//   C1 global best-sellers
//   C2 BUY AGAIN: the customer's own history, scored by frequency and by whether the item is due (its usual gap has passed)
//   C3 ITEM-TO-ITEM: products co-purchased with the customer's recent items (co-occurrence normalised by popularity)
//   C4 BLEND: 12 buy-again + 5 item-to-item + 3 best-sellers, de-duplicated
import java.util.*;

public class CandidatesL1 {
    public static void main(String[] args) {
        World w = new World(43); int train = 11;
        // global counts and item-item co-occurrence from training weeks
        double[] count = new double[w.products]; Map<Long, Integer> co = new HashMap<>();
        for (List<Set<Integer>> ub : w.baskets) for (int wk = 0; wk < train; wk++) {
            Integer[] b = ub.get(wk).toArray(new Integer[0]);
            for (int i : b) count[i]++;
            for (int i : b) for (int j : b) if (i != j) co.merge((long) i * w.products + j, 1, Integer::sum);
        }
        Map<Integer, List<int[]>> neighbours = new HashMap<>();                 // i -> [(j, score*1000)]
        for (var e : co.entrySet()) {
            int i = (int) (e.getKey() / w.products), j = (int) (e.getKey() % w.products); if (e.getValue() < 5) continue;
            double score = e.getValue() / Math.sqrt(count[i] * count[j]);        // cosine: penalises items that are simply popular
            neighbours.computeIfAbsent(i, k -> new ArrayList<>()).add(new int[]{j, (int) (score * 1e6)});
        }
        neighbours.values().forEach(l -> l.sort((a, b) -> b[1] - a[1]));
        Integer[] best = new Integer[w.products]; for (int i = 0; i < w.products; i++) best[i] = i;
        Arrays.sort(best, (a, b) -> Double.compare(count[b], count[a]));

        System.out.printf("%,d customers, %,d products; history weeks 1–%d, predicting week %d (avg basket %.1f items).%n%n", w.users, w.products, train, train + 1,
                w.baskets.stream().mapToInt(ub -> ub.get(train).size()).average().orElse(0));
        System.out.printf("   %-52s %11s %16s %18s%n", "retriever", "recall@20", "new-to-customer", "distinct products");
        System.out.printf("   %-52s %11s %16s %18s%n", "", "", "items found", "shown (coverage)");
        String[] names = {"C1) global best-sellers", "C2) buy again (frequency + due)", "C3) item-to-item co-purchase", "C4) blend 12 + 5 + 3"};
        for (int s = 0; s < 4; s++) {
            long hit = 0, total = 0, newHits = 0; Set<Integer> shown = new HashSet<>();
            for (int u = 0; u < w.users; u++) {
                List<Set<Integer>> ub = w.baskets.get(u);
                Map<Integer, List<Integer>> seenWeeks = new HashMap<>();
                for (int wk = 0; wk < train; wk++) for (int i : ub.get(wk)) seenWeeks.computeIfAbsent(i, k -> new ArrayList<>()).add(wk);
                List<Integer> c1 = new ArrayList<>(); for (int i = 0; i < 20; i++) c1.add(best[i]);
                List<Integer> c2 = buyAgain(seenWeeks, train, 20);
                List<Integer> c3 = itemToItem(ub.get(train - 1), ub.get(train - 2), neighbours, seenWeeks.keySet(), 20);
                List<Integer> rec = switch (s) {
                    case 0 -> c1; case 1 -> c2; case 2 -> c3;
                    default -> { LinkedHashSet<Integer> m = new LinkedHashSet<>(buyAgain(seenWeeks, train, 12)); for (int i : c3) { if (m.size() >= 17) break; m.add(i); } for (int i : c1) { if (m.size() >= 20) break; m.add(i); } yield new ArrayList<>(m); }
                };
                shown.addAll(rec); Set<Integer> recSet = new HashSet<>(rec);
                for (int i : ub.get(train)) { total++; if (recSet.contains(i)) { hit++; if (!seenWeeks.containsKey(i)) newHits++; } }
            }
            System.out.printf("   %-52s %10.1f%% %16s %,18d%n", names[s], 100.0 * hit / total, String.format("%,d", newHits), shown.size());
        }
        System.out.println("\n   → in a repeat-purchase business the customer's own history is by far the strongest retriever — but it only");
        System.out.println("     ever shows what they already buy: it found no new items at all. Best-sellers find new items for everyone, but");
        System.out.println("     the same 20 for everyone. Item-to-item co-purchase finds about as many new items, spread across almost the whole");
        System.out.println("     catalogue — personal discovery. The blend gives up ~6 points of recall to find thousands of new items; that");
        System.out.println("     quota is a product decision (repeat revenue now vs discovery), and the ranker (L2) can only reorder what the");
        System.out.println("     retrievers bring back.");
    }

    static List<Integer> buyAgain(Map<Integer, List<Integer>> seen, int now, int k) {
        List<int[]> scored = new ArrayList<>();
        for (var e : seen.entrySet()) {
            List<Integer> wk = e.getValue(); int n = wk.size(), last = wk.get(n - 1);
            double gap = n > 1 ? (double) (last - wk.get(0)) / (n - 1) : 4;          // usual gap between purchases
            double due = Math.min(1.5, (now - last) / Math.max(1, gap));              // ≥1 → due now
            scored.add(new int[]{e.getKey(), (int) (1000 * n * due)});
        }
        scored.sort((a, b) -> b[1] - a[1]);
        List<Integer> out = new ArrayList<>(); for (int i = 0; i < Math.min(k, scored.size()); i++) out.add(scored.get(i)[0]); return out;
    }
    static List<Integer> itemToItem(Set<Integer> last, Set<Integer> prev, Map<Integer, List<int[]>> nb, Set<Integer> exclude, int k) {
        Map<Integer, Integer> score = new HashMap<>();
        for (Set<Integer> b : List.of(last, prev)) for (int i : b) for (int[] n : nb.getOrDefault(i, List.of()).subList(0, Math.min(10, nb.getOrDefault(i, List.of()).size())))
            if (!exclude.contains(n[0])) score.merge(n[0], n[1], Integer::sum);
        return score.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(k).map(Map.Entry::getKey).toList();
    }
}
