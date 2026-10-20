// L4 — Plan which story answers which question across a whole loop. Interviewers compare notes, so a story may be
// used at most twice in the loop and never twice with the same interviewer. Fit: 2 = story tagged for the competency
// at or above the target level, 1 = tagged but below level, 0 = not tagged. Optimal plan by min-cost flow; compared
// with the natural greedy approach (best available story, question by question, in interview order).
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class LoopPlanL4 {
    record Story(String id, String title, Set<String> comps, int level) {}
    record Q(String interview, String comp) {}
    static final List<Q> LOOP = List.of(
        new Q("hiring manager", "ownership"), new Q("hiring manager", "conflict"), new Q("hiring manager", "delivery"),
        new Q("bar raiser", "failure"), new Q("bar raiser", "influence"), new Q("bar raiser", "customer-focus"),
        new Q("peer staff eng", "technical-decision"), new Q("peer staff eng", "mentoring"), new Q("peer staff eng", "cross-team"),
        new Q("director", "strategy"), new Q("director", "prioritisation"), new Q("director", "incident"));
    static final int TARGET = 3;   // a staff (B3) loop

    public static void main(String[] a) throws Exception {
        Map<String, Story> v1 = parse(Files.readString(Path.of("stories.md")));
        Map<String, Story> v2 = new LinkedHashMap<>(v1); v2.putAll(parse(Files.readString(Path.of("stories_v2.md"))));
        for (var e : List.of(Map.entry("story bank v1 (8 stories)", v1), Map.entry("story bank v2 (S3, S8 rewritten, S9 added)", v2))) {
            List<Story> st = new ArrayList<>(e.getValue().values());
            System.out.println("=== " + e.getKey() + ", staff (B3) loop, 4 interviews x 3 questions");
            String[] greedy = greedy(st), best = optimal(st);
            System.out.printf("%-16s %-20s %-10s %-10s%n", "interview", "competency", "greedy", "optimal");
            for (int i = 0; i < LOOP.size(); i++)
                System.out.printf("%-16s %-20s %-10s %-10s%n", LOOP.get(i).interview(), LOOP.get(i).comp(), label(greedy[i], st, i), label(best[i], st, i));
            System.out.printf("%-37s %-10s %-10s%n", "total fit (max 24)", score(greedy, st), score(best, st));
            System.out.printf("%-37s %-10s %-10s%n", "questions with no fitting story", empty(greedy, st), empty(best, st));
            System.out.printf("%-37s %-10s %-10s%n%n", "stories used twice", reused(greedy), reused(best));
        }
        System.out.println("label: story id, then * if it fits at level (2), ~ if tagged but below level (1), ! if not tagged for the question (0), - if nothing left");
    }

    static int fit(Story s, Q q) { return !s.comps().contains(q.comp()) ? 0 : s.level() >= TARGET ? 2 : 1; }
    static String label(String id, List<Story> st, int i) { if (id == null) return "-"; Story s = st.stream().filter(x -> x.id().equals(id)).findFirst().get(); int f = fit(s, LOOP.get(i)); return id + (f == 2 ? "*" : f == 1 ? "~" : "!"); }
    static int score(String[] plan, List<Story> st) { int t = 0; for (int i = 0; i < plan.length; i++) if (plan[i] != null) { String id = plan[i]; t += fit(st.stream().filter(x -> x.id().equals(id)).findFirst().get(), LOOP.get(i)); } return t; }
    static long empty(String[] plan, List<Story> st) { int n = 0; for (int i = 0; i < plan.length; i++) { String id = plan[i]; if (id == null || fit(st.stream().filter(x -> x.id().equals(id)).findFirst().get(), LOOP.get(i)) == 0) n++; } return n; }
    static long reused(String[] plan) { Map<String, Integer> c = new HashMap<>(); for (String p : plan) if (p != null) c.merge(p, 1, Integer::sum); return c.values().stream().filter(v -> v > 1).count(); }

    static String[] greedy(List<Story> st) {
        String[] plan = new String[LOOP.size()]; Map<String, Integer> uses = new HashMap<>();
        for (int i = 0; i < LOOP.size(); i++) {
            Story bestS = null; int bf = 0;
            for (Story s : st) {
                if (uses.getOrDefault(s.id(), 0) >= 2 || usedIn(plan, s.id(), LOOP.get(i).interview())) continue;
                int f = fit(s, LOOP.get(i)); if (f > bf) { bf = f; bestS = s; } }
            if (bestS != null) { plan[i] = bestS.id(); uses.merge(bestS.id(), 1, Integer::sum); }
        }
        return plan;
    }
    static boolean usedIn(String[] plan, String id, String interview) { for (int j = 0; j < plan.length; j++) if (id.equals(plan[j]) && LOOP.get(j).interview().equals(interview)) return true; return false; }

    // min-cost flow: source → story (two edges: 1st use cost 0, 2nd use cost 0.3) → story×interview (cap 1) → question (cost −fit) → sink
    static String[] optimal(List<Story> st) {
        List<String> ivs = LOOP.stream().map(Q::interview).distinct().toList();
        int S = 0, n0 = 1, nSI = n0 + st.size(), nQ = nSI + st.size() * ivs.size(), T = nQ + LOOP.size(), N = T + 1;
        Graph g = new Graph(N);
        for (int k = 0; k < st.size(); k++) { g.add(S, n0 + k, 1, 0); g.add(S, n0 + k, 1, 0.3);
            for (int v = 0; v < ivs.size(); v++) g.add(n0 + k, nSI + k * ivs.size() + v, 1, 0); }
        for (int i = 0; i < LOOP.size(); i++) { int v = ivs.indexOf(LOOP.get(i).interview());
            for (int k = 0; k < st.size(); k++) { int f = fit(st.get(k), LOOP.get(i)); if (f > 0) g.add(nSI + k * ivs.size() + v, nQ + i, 1, -f); }
            g.add(nQ + i, T, 1, 0); }
        g.minCost(S, T);
        String[] plan = new String[LOOP.size()];
        for (int k = 0; k < st.size(); k++) for (int v = 0; v < ivs.size(); v++) for (Graph.E e : g.adj.get(nSI + k * ivs.size() + v))
            if (e.to >= nQ && e.to < T && e.cap == 0 && e.cost < 0) plan[e.to - nQ] = st.get(k).id();
        return plan;
    }
    static class Graph {
        static class E { int to, rev, cap; double cost; E(int t, int r, int c, double co) { to = t; rev = r; cap = c; cost = co; } }
        List<List<E>> adj = new ArrayList<>();
        Graph(int n) { for (int i = 0; i < n; i++) adj.add(new ArrayList<>()); }
        void add(int u, int v, int cap, double cost) { adj.get(u).add(new E(v, adj.get(v).size(), cap, cost)); adj.get(v).add(new E(u, adj.get(u).size() - 1, 0, -cost)); }
        void minCost(int s, int t) {
            while (true) {                                    // Bellman-Ford shortest augmenting path; stop when it no longer lowers cost
                int n = adj.size(); double[] d = new double[n]; Arrays.fill(d, 1e18); d[s] = 0; int[] pv = new int[n], pe = new int[n];
                for (int it = 0; it < n; it++) { boolean ch = false;
                    for (int u = 0; u < n; u++) if (d[u] < 1e17) for (int j = 0; j < adj.get(u).size(); j++) { E e = adj.get(u).get(j);
                        if (e.cap > 0 && d[u] + e.cost < d[e.to] - 1e-9) { d[e.to] = d[u] + e.cost; pv[e.to] = u; pe[e.to] = j; ch = true; } }
                    if (!ch) break; }
                if (d[t] >= -1e-9) return;
                for (int v = t; v != s; v = pv[v]) { E e = adj.get(pv[v]).get(pe[v]); e.cap--; adj.get(v).get(e.rev).cap++; }
            }
        }
    }
    static Map<String, Story> parse(String md) {
        Map<String, Story> out = new LinkedHashMap<>();
        for (String block : md.split("\n## ")) {
            Matcher h = Pattern.compile("^(S\\d+) (.+)").matcher(block.strip()); if (!h.find()) continue;
            Matcher c = Pattern.compile("- competencies: (.+)").matcher(block), l = Pattern.compile("- level: B(\\d)").matcher(block);
            if (!c.find() || !l.find()) continue;
            out.put(h.group(1), new Story(h.group(1), h.group(2), new HashSet<>(Arrays.stream(c.group(1).split(",")).map(String::strip).toList()), Integer.parseInt(l.group(1))));
        }
        return out;
    }
}
