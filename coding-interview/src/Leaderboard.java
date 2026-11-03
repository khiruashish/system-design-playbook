import java.util.*;

/** Problem: game leaderboard: update scores, get a player's rank, top-N, and players around me (Redis ZSET semantics). */
public class Leaderboard {

    record Entry(String player, long score) { }

    /** Order: higher score first; ties -> earlier player name (deterministic). */
    private static final Comparator<Entry> ORDER = Comparator.comparingLong(Entry::score).reversed().thenComparing(Entry::player);

    private final TreeSet<Entry> ranking = new TreeSet<>(ORDER);
    private final Map<String, Long> scores = new HashMap<>();
    // Fenwick tree over score buckets for O(log S) rank queries without walking the TreeSet
    private static final int MAX_SCORE = 1_000_000;
    private final long[] fenwick = new long[MAX_SCORE + 2];

    void addScore(String player, long delta) {                                  // ZINCRBY
        Long old = scores.get(player);
        long now = (old == null ? 0 : old) + delta;
        if (now < 0 || now > MAX_SCORE) throw new IllegalArgumentException("score out of range");
        if (old != null) { ranking.remove(new Entry(player, old)); fenwickAdd(old, -1); }
        ranking.add(new Entry(player, now));
        fenwickAdd(now, 1);
        scores.put(player, now);
    }

    /** 1-based rank = 1 + players with a higher score + players with the same score and a smaller name. */
    int rank(String player) {                                                   // ZREVRANK + 1
        long s = scores.get(player);
        long higher = countAtMost(MAX_SCORE) - countAtMost(s);
        long tiesBefore = ranking.subSet(new Entry("", s), true, new Entry(player, s), false).size();
        return (int) (higher + tiesBefore + 1);
    }

    List<Entry> top(int n) { return ranking.stream().limit(n).toList(); }       // ZREVRANGE 0 n-1

    List<Entry> around(String player, int radius) {
        Entry me = new Entry(player, scores.get(player));
        List<Entry> above = new ArrayList<>(ranking.headSet(me, false).descendingSet().stream().limit(radius).toList());
        Collections.reverse(above);
        List<Entry> out = new ArrayList<>(above);
        out.add(me);
        out.addAll(ranking.tailSet(me, false).stream().limit(radius).toList());
        return out;
    }

    private void fenwickAdd(long score, int d) { for (int i = (int) score + 1; i < fenwick.length; i += i & -i) fenwick[i] += d; }
    private long countAtMost(long score) { long c = 0; for (int i = (int) score + 1; i > 0; i -= i & -i) c += fenwick[i]; return c; }

    public static void main(String[] args) {
        Leaderboard lb = new Leaderboard();
        Random r = new Random(3);
        for (int i = 0; i < 10_000; i++) lb.addScore("p" + i, r.nextInt(100_000));
        lb.addScore("ana", 99_990);
        lb.addScore("bob", 50_000);
        lb.addScore("bob", 49_995);        // bob climbs to 99,995
        lb.addScore("cara", 50_000);       // a mid-table player

        System.out.println("top 3       = " + lb.top(3));
        System.out.println("rank(bob)   = " + lb.rank("bob") + ", rank(ana) = " + lb.rank("ana"));
        System.out.println("rank(cara)  = " + lb.rank("cara"));
        System.out.println("around(cara)= " + lb.around("cara", 2));

        List<Entry> all = lb.ranking.stream().toList();
        check(all.get(lb.rank("ana") - 1).player().equals("ana"), "Fenwick rank matches the ordered set");
        check(all.get(lb.rank("bob") - 1).player().equals("bob") && lb.rank("bob") < lb.rank("ana"), "update reorders");
        check(lb.around("cara", 2).size() == 5 && lb.around("cara", 2).get(2).player().equals("cara"), "neighbourhood");
        check(all.get(lb.rank("cara") - 1).player().equals("cara"), "rank of a mid-table player");
        System.out.println("PASS");
    }

    static void check(boolean ok, String what) { if (!ok) { System.out.println("FAILED: " + what); System.exit(1); } }
}
