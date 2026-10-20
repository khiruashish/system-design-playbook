// TrieTopKL1.java — run with: java -Xmx2g TrieTopKL1.java   (JDK 21, no dependencies)
// THE DATA STRUCTURE BEHIND TYPEAHEAD. The grocery app shows 5 suggestions on every keystroke, ranked by how often people
// searched for them. The suggestion set is 200,000 distinct queries with Zipf-like popularity, built from a synthetic
// grocery vocabulary (fixed seed). We answer 200,000 keystroke prefixes (taken from real-looking typing sessions) three ways:
//   A) scan: filter every query that starts with the prefix, sort by count, take 5
//   B) trie: walk to the prefix node, then search the whole subtree keeping the best 5 in a heap
//   C) trie with the top 5 PRECOMPUTED at every node: walk to the prefix node and read its list
// We measure build time, memory, per-keystroke latency (real JVM timings on the first 2,000 / 5,000 / 20,000 prefixes —
// they vary by machine) and check that all three
// return identical suggestions.
import java.util.*;

public class TrieTopKL1 {
    static final class Node { final Map<Character, Node> kids = new HashMap<>(4); int count = -1; String word; String[] top; }

    public static void main(String[] args) {
        Random r = new Random(42);
        String[] brands = {"amul", "tata", "aashirvaad", "fortune", "britannia", "parle", "mother dairy", "nestle", "haldiram", "mdh",
                "everest", "saffola", "dabur", "patanjali", "maggi", "surf excel", "dettol", "colgate", "lays", "kurkure"};
        String[] items = {"milk", "atta", "rice", "dal", "oil", "ghee", "paneer", "curd", "butter", "bread", "biscuits", "salt", "sugar",
                "tea", "coffee", "noodles", "chips", "soap", "detergent", "toothpaste", "masala", "besan", "poha", "maida", "honey",
                "jam", "ketchup", "eggs", "chicken", "onion", "tomato", "potato", "banana", "mango", "apple", "grapes", "coriander"};
        String[] sizes = {"", " 500g", " 1kg", " 5kg", " 1l", " 500ml", " 200g", " family pack", " combo", " organic"};
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        while (counts.size() < 200_000) {
            String q = switch (r.nextInt(4)) {
                case 0 -> items[r.nextInt(items.length)] + sizes[r.nextInt(sizes.length)];
                case 1 -> brands[r.nextInt(brands.length)] + " " + items[r.nextInt(items.length)] + sizes[r.nextInt(sizes.length)];
                case 2 -> brands[r.nextInt(brands.length)] + " " + items[r.nextInt(items.length)] + " " + (r.nextInt(900) + 100) + "g";
                default -> items[r.nextInt(items.length)] + " " + items[r.nextInt(items.length)] + " " + r.nextInt(50);
            };
            counts.putIfAbsent(q, 0);
        }
        List<String> qs = new ArrayList<>(counts.keySet()); Collections.shuffle(qs, r);
        for (int i = 0; i < qs.size(); i++) counts.put(qs.get(i), (int) Math.max(1, 1_000_000 / Math.pow(i + 1, 1.05)));   // Zipf
        String[] words = qs.toArray(new String[0]); int[] cnt = new int[words.length]; for (int i = 0; i < words.length; i++) cnt[i] = counts.get(words[i]);

        // keystroke workload: people type popular queries more often; every prefix of length 1..len is a request
        List<String> prefixes = new ArrayList<>();
        while (prefixes.size() < 200_000) { String w = words[Math.min(words.length - 1, (int) Math.abs(r.nextGaussian() * 2_000))]; for (int k = 1; k <= Math.min(w.length(), 12) && prefixes.size() < 200_000; k++) prefixes.add(w.substring(0, k)); }

        // build tries
        long t0 = System.nanoTime(); Node trie = new Node(); long nodes = 1;
        for (int i = 0; i < words.length; i++) { Node n = trie; for (char c : words[i].toCharArray()) { Node k = n.kids.get(c); if (k == null) { k = new Node(); n.kids.put(c, k); nodes++; } n = k; } n.count = cnt[i]; n.word = words[i]; }
        double buildTrie = (System.nanoTime() - t0) / 1e6;
        t0 = System.nanoTime(); precompute(trie); double buildTop = (System.nanoTime() - t0) / 1e6;

        System.out.printf("%,d suggestion candidates, %,d trie nodes; %,d keystroke prefixes (avg length %.1f).%n%n", words.length, nodes, prefixes.size(),
                prefixes.stream().mapToInt(String::length).average().orElse(0));
        System.out.printf("   %-52s %12s %14s %14s %14s%n", "approach", "build", "extra memory", "p50 / keystroke", "p99 / keystroke");
        long[] a = time(prefixes, 2_000, p -> scan(words, cnt, p)), b = time(prefixes, 5_000, p -> dfs(trie, p)), c = time(prefixes, 20_000, p -> top(trie, p));
        System.out.printf("   %-52s %12s %14s %11.1f µs %11.1f µs%n", "A) scan all candidates, sort, take 5", "—", "—", a[0] / 1e3, a[1] / 1e3);
        System.out.printf("   %-52s %9.0f ms %14s %11.1f µs %11.1f µs%n", "B) trie + subtree search with a heap", buildTrie, "—", b[0] / 1e3, b[1] / 1e3);
        System.out.printf("   %-52s %9.0f ms %11.0f MB %11.1f µs %11.1f µs%n", "C) trie with top-5 precomputed per node", buildTrie + buildTop, nodes * 5 * 8 / 1e6 + nodes * 16 / 1e6, c[0] / 1e3, c[1] / 1e3);
        int mismatch = 0;
        for (int i = 0; i < 2_000; i++) { String p = prefixes.get(i); if (!scan(words, cnt, p).equals(top(trie, p)) || !dfs(trie, p).equals(top(trie, p))) mismatch++; }
        System.out.printf("   identical top-5 across all three approaches on 2,000 checked prefixes: %s%n", mismatch == 0 ? "yes" : "NO (" + mismatch + " differ)");
        Node n1 = trie.kids.get('m'); int sub = subtree(n1);
        System.out.printf("   why B's p99 is high: the prefix 'm' has %,d candidates under it; C reads 5 pointers regardless.%n", sub);
        System.out.println("\n   → a scan is fine for a few thousand candidates and hopeless for hundreds of thousands. A plain trie makes long");
        System.out.println("     prefixes fast but short ones — the first keystrokes, which everybody types — still search huge subtrees.");
        System.out.println("     Precomputing the top-k at every node moves that work to build time and makes every keystroke a walk of");
        System.out.println("     prefix-length steps plus a read of k items, at the cost of memory and a rebuild when counts change.");
    }

    static List<String> scan(String[] w, int[] c, String p) {
        PriorityQueue<Integer> pq = new PriorityQueue<>((x, y) -> c[x] != c[y] ? Integer.compare(c[x], c[y]) : w[y].compareTo(w[x]));
        for (int i = 0; i < w.length; i++) if (w[i].startsWith(p)) { pq.add(i); if (pq.size() > 5) pq.poll(); }
        List<String> out = new ArrayList<>(); while (!pq.isEmpty()) out.add(w[pq.poll()]); Collections.reverse(out); return out;
    }
    static Node walk(Node n, String p) { for (char ch : p.toCharArray()) { n = n.kids.get(ch); if (n == null) return null; } return n; }
    static List<String> dfs(Node root, String p) {
        Node n = walk(root, p); if (n == null) return List.of();
        PriorityQueue<Node> pq = new PriorityQueue<>((x, y) -> x.count != y.count ? Integer.compare(x.count, y.count) : y.word.compareTo(x.word));
        Deque<Node> st = new ArrayDeque<>(); st.push(n);
        while (!st.isEmpty()) { Node x = st.pop(); if (x.count >= 0) { pq.add(x); if (pq.size() > 5) pq.poll(); } for (Node k : x.kids.values()) st.push(k); }
        List<String> out = new ArrayList<>(); while (!pq.isEmpty()) out.add(pq.poll().word); Collections.reverse(out); return out;
    }
    static List<String> top(Node root, String p) { Node n = walk(root, p); return n == null ? List.of() : Arrays.asList(n.top); }
    static List<Node> precompute(Node n) {                           // returns this subtree's top-5 terminal nodes
        List<Node> best = new ArrayList<>(); if (n.count >= 0) best.add(n);
        for (Node k : n.kids.values()) best.addAll(precompute(k));
        best.sort((x, y) -> x.count != y.count ? Integer.compare(y.count, x.count) : x.word.compareTo(y.word));
        if (best.size() > 5) best = new ArrayList<>(best.subList(0, 5));
        n.top = best.stream().map(x -> x.word).toArray(String[]::new);
        return best;
    }
    static int subtree(Node n) { int s = n.count >= 0 ? 1 : 0; for (Node k : n.kids.values()) s += subtree(k); return s; }
    static long[] time(List<String> ps, int n, java.util.function.Function<String, List<String>> f) {
        for (int i = 0; i < 200; i++) f.apply(ps.get(i));                         // warm-up
        long[] t = new long[n];
        for (int i = 0; i < n; i++) { long s = System.nanoTime(); f.apply(ps.get(i)); t[i] = System.nanoTime() - s; }
        Arrays.sort(t); return new long[]{t[n / 2], t[(int) (n * 0.99)]};
    }
}
