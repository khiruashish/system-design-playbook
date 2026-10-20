// PersonalL4.java — run with: java PersonalL4.java   (JDK 21, no dependencies)
// SUGGESTIONS FOR THIS CUSTOMER, IN THIS STORE. Grocery search is repetitive: people buy the same staples every week, and
// only what their dark store stocks can be delivered. 5,000 customers (fixed seed), each with 3–8 personal staples; a
// global list of 6,000 popular queries; 20 dark stores, each missing ~15% of items. From 4 weeks of each customer's
// history we predict week 5: after 1, 4, 8 and 12 characters, is what they want in the top 5?
//   G1 global popularity only                      G2 G1 filtered to items available in the customer's dark store
//   G3 G2 + blend in the customer's own history (score = global share + weight × personal share)
// Also measured: how many suggestions point at items the customer's store can't deliver.
import java.util.*;

public class PersonalL4 {
    public static void main(String[] args) {
        Random r = new Random(42);
        String[] brands = {"amul", "aashirvaad", "fortune", "britannia", "mother dairy", "nestle", "tata", "saffola", "dabur", "haldiram"};
        String[] items = {"milk", "atta", "rice", "dal", "oil", "ghee", "paneer", "curd", "butter", "bread", "biscuits", "tea", "coffee",
                "noodles", "besan", "poha", "honey", "eggs", "masala", "sugar"};
        String[] vars = {"500g", "1kg", "5kg", "1l", "500ml", "200g", "toned", "full cream", "gold", "classic", "organic", "family pack"};
        LinkedHashSet<String> set = new LinkedHashSet<>();
        while (set.size() < 6_000) set.add(brands[r.nextInt(brands.length)] + " " + items[r.nextInt(items.length)] + " " + vars[r.nextInt(vars.length)] + (r.nextBoolean() ? "" : " " + (r.nextInt(9) + 1)));
        List<String> global = new ArrayList<>(set); Map<String, Double> gScore = new HashMap<>();
        for (int i = 0; i < global.size(); i++) gScore.put(global.get(i), 1.0 / Math.pow(i + 1, 1.0));
        SORTED = new ArrayList<>(global); Collections.sort(SORTED);
        double gSum = gScore.values().stream().mapToDouble(Double::doubleValue).sum(); gScore.replaceAll((k, v) -> v / gSum);
        int stores = 20; List<Set<String>> missing = new ArrayList<>();
        for (int s = 0; s < stores; s++) { Set<String> m = new HashSet<>(); for (String q : global) if (r.nextDouble() < 0.15) m.add(q); missing.add(m); }

        int users = 5_000; int[] store = new int[users]; List<List<String>> staples = new ArrayList<>();
        for (int u = 0; u < users; u++) {
            store[u] = r.nextInt(stores); List<String> st = new ArrayList<>(); int n = 3 + r.nextInt(6);
            while (st.size() < n) { String q = global.get(r.nextInt(global.size())); if (!missing.get(store[u]).contains(q) && !st.contains(q)) st.add(q); }
            staples.add(st);
        }
        // history (weeks 1–4) and test (week 5): 6 searches a week, 65% from own staples, 35% global popularity
        List<Map<String, Integer>> hist = new ArrayList<>(); List<List<String>> test = new ArrayList<>();
        double[] cdf = new double[global.size()]; double c = 0; for (int i = 0; i < cdf.length; i++) { c += gScore.get(global.get(i)); cdf[i] = c; }
        for (int u = 0; u < users; u++) {
            Map<String, Integer> h = new HashMap<>(); List<String> t = new ArrayList<>();
            for (int w = 0; w < 5; w++) for (int k = 0; k < 6; k++) {
                String q;
                if (r.nextDouble() < 0.65) q = staples.get(u).get(r.nextInt(staples.get(u).size()));
                else { int i = Arrays.binarySearch(cdf, r.nextDouble() * c); q = global.get(i < 0 ? Math.min(cdf.length - 1, -i - 1) : i); }
                if (w < 4) h.merge(q, 1, Integer::sum); else t.add(q);
            }
            hist.add(h); test.add(t);
        }
        System.out.printf("%,d customers, %,d week-5 searches; %,d global queries; %d dark stores missing ~15%% of items each.%n%n",
                users, test.stream().mapToInt(List::size).sum(), global.size(), stores);
        System.out.printf("   %-52s %9s %9s %9s %9s %14s %16s%n", "method", "in top-5", "", "", "", "keystrokes", "suggestions not");
        System.out.printf("   %-52s %9s %9s %9s %9s %14s %16s%n", "", "@1 char", "@4", "@8", "@12", "to find", "in the store");
        String[] names = {"G1) global popularity", "G2) G1 + only items the customer's store stocks", "G3) G2 + the customer's own history"};
        for (int m = 0; m < 3; m++) {
            int[] at = {1, 4, 8, 12}; long[] hit = new long[4]; long n = 0, keys = 0, sugg = 0, unavailable = 0;
            for (int u = 0; u < users; u++) for (String target : test.get(u)) {
                n++; int found = target.length();
                for (int len = 1; len <= target.length(); len++) {
                    List<String> top = suggest(target.substring(0, len), m, global, gScore, missing.get(store[u]), hist.get(u));
                    if (len <= 12) { sugg += top.size(); for (String s : top) if (missing.get(store[u]).contains(s)) unavailable++; }
                    if (top.contains(target)) { found = len; break; }
                }
                keys += found; for (int a = 0; a < 4; a++) if (found <= at[a]) hit[a]++;
            }
            System.out.printf("   %-52s %8.1f%% %8.1f%% %8.1f%% %8.1f%% %14.2f %15.1f%%%n", names[m], 100.0 * hit[0] / n, 100.0 * hit[1] / n,
                    100.0 * hit[2] / n, 100.0 * hit[3] / n, (double) keys / n, 100.0 * unavailable / sugg);
        }
        System.out.println("\n   → global popularity is a poor predictor for a repeat-purchase business: most searches are for a customer's own");
        System.out.println("     staples, which are rarely the globally most popular variant. Filtering by the dark store removes every");
        System.out.println("     suggestion that would end in 'out of stock' — including a few the customer wanted, which is the right outcome");
        System.out.println("     when the store can't deliver them. Blending the customer's own history");
        System.out.println("     puts their usual items within the first keystroke or two. Serve it as global top-k from the precomputed trie,");
        System.out.println("     re-ranked per request with a small per-user history — personalisation is a re-ranking step, not a new index.");
    }

    static List<String> SORTED;
    static List<String> suggest(String p, int m, List<String> global, Map<String, Double> g, Set<String> missing, Map<String, Integer> hist) {
        Map<String, Double> score = new HashMap<>();
        int lo = Collections.binarySearch(SORTED, p); lo = lo < 0 ? -lo - 1 : lo;               // prefix range in the sorted list
        for (int i = lo; i < SORTED.size() && SORTED.get(i).startsWith(p); i++) { String q = SORTED.get(i); if (m == 0 || !missing.contains(q)) score.put(q, g.get(q)); }
        if (m == 2) {
            int total = hist.values().stream().mapToInt(Integer::intValue).sum();
            for (var e : hist.entrySet()) if (e.getKey().startsWith(p) && !missing.contains(e.getKey()))
                score.merge(e.getKey(), 0.5 * e.getValue() / (double) total, Double::sum);   // personal share, weight 0.5
        }
        return score.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).limit(5).map(Map.Entry::getKey).toList();
    }
}
