import java.util.*;

/** Problem: search-box autocomplete: top-3 most popular queries for a prefix, fast; popularity updates as users search. */
public class AutocompleteTrie {

    static final int K = 3;

    static final class Node {
        final Map<Character, Node> children = new TreeMap<>();
        long count;                                // > 0 if a full query ends here
        List<String> topK = new ArrayList<>();     // CACHED top-K for this prefix: O(prefix length) reads
    }

    private final Node root = new Node();
    private final Map<String, Long> counts = new HashMap<>();

    /** Record a search; update the cached top-K along the path (writes pay, reads stay cheap). */
    void record(String query, long times) {
        String q = query.toLowerCase(Locale.ROOT).strip();
        long c = counts.merge(q, times, Long::sum);
        Node n = root;
        List<Node> path = new ArrayList<>(List.of(root));
        for (char ch : q.toCharArray()) { n = n.children.computeIfAbsent(ch, x -> new Node()); path.add(n); }
        n.count = c;
        for (Node p : path) updateTopK(p, q);
    }

    private void updateTopK(Node n, String q) {
        List<String> list = n.topK;
        if (!list.contains(q)) list.add(q);
        list.sort(Comparator.comparingLong((String s) -> counts.get(s)).reversed().thenComparing(s -> s));
        if (list.size() > K) list.remove(list.size() - 1);
    }

    List<String> suggest(String prefix) {
        Node n = root;
        for (char ch : prefix.toLowerCase(Locale.ROOT).toCharArray()) { n = n.children.get(ch); if (n == null) return List.of(); }
        return List.copyOf(n.topK);
    }

    /** Without the cache: DFS the whole subtree on every keystroke (what the cache avoids). */
    List<String> suggestByDfs(String prefix) {
        Node n = root;
        for (char ch : prefix.toCharArray()) { n = n.children.get(ch); if (n == null) return List.of(); }
        List<String> all = new ArrayList<>();
        dfs(n, new StringBuilder(prefix), all);
        all.sort(Comparator.comparingLong((String s) -> counts.get(s)).reversed().thenComparing(s -> s));
        return all.subList(0, Math.min(K, all.size()));
    }

    private void dfs(Node n, StringBuilder sb, List<String> out) {
        if (n.count > 0) out.add(sb.toString());
        for (var e : n.children.entrySet()) { sb.append(e.getKey()); dfs(e.getValue(), sb, out); sb.setLength(sb.length() - 1); }
    }

    public static void main(String[] args) {
        AutocompleteTrie t = new AutocompleteTrie();
        Map<String, Long> log = new LinkedHashMap<>();
        log.put("java tutorial", 900L); log.put("java streams", 700L); log.put("javascript array", 1_200L);
        log.put("java virtual threads", 400L); log.put("jaguar", 300L); log.put("kafka", 800L);
        log.forEach(t::record);

        for (String p : List.of("j", "jav", "java ", "k", "x")) System.out.printf("suggest(%-7s) = %s%n", '"' + p + '"', t.suggest(p));
        check(t.suggest("java ").equals(List.of("java tutorial", "java streams", "java virtual threads")), "prefix top-3");

        t.record("java virtual threads", 1_000);   // trending: 400 -> 1400
        System.out.println("after 'java virtual threads' trends: suggest(\"jav\") = " + t.suggest("jav"));
        check(t.suggest("jav").get(0).equals("java virtual threads"), "cache updated on write");
        for (String p : List.of("j", "ja", "java", "java s", "k")) check(t.suggest(p).equals(t.suggestByDfs(p)), "cache == DFS for " + p);
        System.out.println("PASS");
    }

    static void check(boolean ok, String what) { if (!ok) { System.out.println("FAILED: " + what); System.exit(1); } }
}
