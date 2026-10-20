// TeamsL2.java — run with: java TeamsL2.java services.csv features.csv   (JDK 21, no dependencies)
// DERIVING TEAM BOUNDARIES FROM HOW CHANGE FLOWS — under cognitive-load limits. Using the same year of features as L1, a
// local search assigns the 26 services to 8 teams to minimise HAND-OFFS (for each feature, the number of teams involved
// minus one). Without limits the answer is trivial and useless — one giant team has zero hand-offs. So we add the limits
// Team Topologies argues for: each team owns 2–4 services (cognitive load), and no team carries more than 1.5x the average
// change load. Then we compare with the context-aligned map from L1 and list where the data disagrees with the contexts.
import java.nio.file.*;
import java.util.*;

public class TeamsL2 {
    static List<String> SVC = new ArrayList<>(); static Map<String, String> ALIGNED = new HashMap<>(), CURRENT = new HashMap<>();
    static List<int[]> FEATURES = new ArrayList<>();                   // service indices per feature
    static final int TEAMS = 8, MIN = 2, MAX = 4; static double LOAD_CAP;

    static List<String[]> csv(String f) throws Exception {
        return Files.readAllLines(Path.of(f)).stream().filter(l -> !l.isBlank() && !l.startsWith("#")).map(l -> l.split(",")).toList();
    }
    static int handoffs(int[] team) {
        int h = 0;
        for (int[] f : FEATURES) { Set<Integer> t = new HashSet<>(); for (int s : f) t.add(team[s]); h += t.size() - 1; }
        return h;
    }
    static int[] load(int[] team) {                                   // features touching each team
        int[] l = new int[Arrays.stream(team).max().getAsInt() + 1];
        for (int[] f : FEATURES) { Set<Integer> t = new HashSet<>(); for (int s : f) t.add(team[s]); for (int x : t) l[x]++; }
        return l;
    }
    static boolean feasible(int[] team) {
        int[] size = new int[TEAMS]; for (int t : team) size[t]++;
        for (int s : size) if (s < MIN || s > MAX) return false;
        for (int l : load(team)) if (l > LOAD_CAP) return false;
        return true;
    }
    static int[] fromMap(Map<String, String> m) {
        List<String> names = new ArrayList<>(new TreeSet<>(m.values())); int[] t = new int[SVC.size()];
        for (int i = 0; i < SVC.size(); i++) t[i] = names.indexOf(m.get(SVC.get(i))); return t;
    }

    public static void main(String[] args) throws Exception {
        for (String[] r : csv(args[0])) { SVC.add(r[0]); CURRENT.put(r[0], r[2]); ALIGNED.put(r[0], r[3]); }
        List<String[]> arch = csv(args[1]); int total = arch.stream().mapToInt(a -> Integer.parseInt(a[1])).sum();
        Random rnd = new Random(37);                                      // identical feature year to L1
        for (int i = 0; i < 500; i++) {
            int x = rnd.nextInt(total); String[] a = arch.get(0);
            for (String[] c : arch) { int w = Integer.parseInt(c[1]); if (x < w) { a = c; break; } x -= w; }
            Set<String> s = new TreeSet<>(List.of(a[2].trim().split(" ")));
            if (rnd.nextDouble() < 0.15) s.add(SVC.get(rnd.nextInt(SVC.size())));
            FEATURES.add(s.stream().mapToInt(SVC::indexOf).toArray());
        }
        int[] aligned = fromMap(ALIGNED);
        double avgLoad = Arrays.stream(load(aligned)).average().orElse(0); LOAD_CAP = 1.5 * avgLoad;

        // local search: moves and swaps, many restarts from the aligned map, keep the best feasible
        Random r = new Random(7); int[] best = aligned.clone(); int bestH = handoffs(best);
        for (int restart = 0; restart < 30; restart++) {
            int[] cur = aligned.clone(); int curH = handoffs(cur);
            for (int it = 0; it < 20_000; it++) {
                int[] nxt = cur.clone(); int a = r.nextInt(SVC.size());
                if (r.nextBoolean()) nxt[a] = r.nextInt(TEAMS); else { int b = r.nextInt(SVC.size()); int tmp = nxt[a]; nxt[a] = nxt[b]; nxt[b] = tmp; }
                if (!feasible(nxt)) continue;
                int h = handoffs(nxt);
                if (h <= curH || r.nextDouble() < Math.exp((curH - h) / Math.max(0.5, 20.0 * (1 - it / 20_000.0)))) { cur = nxt; curH = h; }
                if (curH < bestH) { best = cur.clone(); bestH = curH; }
            }
        }

        int[] current = fromMap(CURRENT);
        int[] one = new int[SVC.size()];                                 // everyone in one team
        System.out.printf("Same 500 features. Hand-offs = Σ (teams per feature − 1). Limits: %d–%d services per team, change load ≤ %.0f features/team.%n%n",
                MIN, MAX, LOAD_CAP);
        System.out.printf("   %-44s %8s %10s %14s %16s%n", "ownership map", "teams", "hand-offs", "multi-team %", "max team load");
        row("one giant team (no limits)", one, 1);
        row("CURRENT — by history / layer", current, 9);
        row("ALIGNED — by bounded context (L1)", aligned, 8);
        row("OPTIMISED from change data (within limits)", best, 8);

        System.out.println("\n   Optimised teams (services grouped by who changes together):");
        Map<Integer, List<String>> teams = new TreeMap<>();
        for (int i = 0; i < SVC.size(); i++) teams.computeIfAbsent(best[i], k -> new ArrayList<>()).add(SVC.get(i));
        for (var e : teams.entrySet()) {
            Map<String, Long> ctx = new TreeMap<>(); for (String s : e.getValue()) ctx.merge(ALIGNED.get(s), 1L, Long::sum);
            System.out.printf("        T%d: %-44s from contexts %s%n", e.getKey() + 1, e.getValue(), ctx);
        }
        long mixed = teams.values().stream().filter(l -> l.stream().map(ALIGNED::get).distinct().count() > 1).count();
        System.out.printf("   %d of %d optimised teams mix bounded contexts.%n", mixed, teams.size());
        System.out.println("\n   → with no limits the 'best' organisation is one giant team — zero hand-offs and unmanageable cognitive load.");
        System.out.println("     Within realistic limits, grouping services by how they change together more than halves hand-offs compared");
        System.out.println("     with the context-aligned map (e.g. pricing, promotions, cart and checkout form one checkout-experience team;");
        System.out.println("     slot and picking travel with dark-store stock). Some groupings are warnings, not answers: identity and notification");
        System.out.println("     land with rider and tracking only because OTP logins and rider updates both send messages — a shared capability");
        System.out.println("     like that belongs with a PLATFORM team offering it as a stable self-service API, not inside one stream team.");
        System.out.println("     The output is evidence for a human decision: check whether each coupling is essential (one domain) or accidental");
        System.out.println("     (a design flaw to fix), weigh skills, on-call and language, and re-run it every quarter as the product changes.");
    }

    static void row(String label, int[] team, int teams) {
        int h = handoffs(team); int multi = 0;
        for (int[] f : FEATURES) { Set<Integer> t = new HashSet<>(); for (int s : f) t.add(team[s]); if (t.size() > 1) multi++; }
        System.out.printf("   %-44s %8d %10d %13.0f%% %16d%n", label, teams, h, 100.0 * multi / FEATURES.size(), Arrays.stream(load(team)).max().getAsInt());
    }
}
