// PropertyL1.java — run with: java PropertyL1.java   (JDK 21, no dependencies)
// PROPERTY-BASED TESTING from scratch: instead of a few hand-picked examples, we state a PROPERTY that must hold for ALL
// inputs and let the framework throw hundreds of random inputs at it — then SHRINK any failure to the smallest counter-
// example. The system under test is a money splitter: split an order total (in paise) evenly across N people. The
// property every correct splitter must satisfy: THE PARTS SUM BACK TO THE TOTAL (you can't create or destroy money).
// We test a naive splitter (integer division — loses the remainder) and a correct one (distributes the remainder).
import java.util.*;
import java.util.function.*;

public class PropertyL1 {

    // ---- systems under test ----
    // NAIVE: total/n each. If total doesn't divide evenly, the remainder paise vanish. (A real bug seen in billing code.)
    static long[] splitNaive(long total, int n) {
        long each = total / n;
        long[] parts = new long[n];
        Arrays.fill(parts, each);
        return parts;
    }
    // CORRECT: give the first (total % n) people one extra paisa, so the parts sum exactly to total.
    static long[] splitCorrect(long total, int n) {
        long each = total / n, rem = total % n;
        long[] parts = new long[n];
        for (int i = 0; i < n; i++) parts[i] = each + (i < rem ? 1 : 0);
        return parts;
    }

    // ---- a tiny property-based tester with shrinking ----
    record Case(long total, int n) {}

    // property: sum(parts) == total, for all total>=0, n in [1..20]
    static boolean property(BiFunction<Long,Integer,long[]> split, Case c) {
        long[] parts = split.apply(c.total, c.n);
        long sum = 0; for (long p : parts) sum += p;
        return sum == c.total;
    }

    // shrink a failing case toward the smallest still-failing case (fewer people, smaller total)
    static Case shrink(BiFunction<Long,Integer,long[]> split, Case fail) {
        Case best = fail;
        boolean improved = true;
        while (improved) {
            improved = false;
            // try shrinking total toward 0, and n toward 1, greedily keeping any smaller case that STILL fails
            List<Case> candidates = new ArrayList<>();
            if (best.total > 0) { candidates.add(new Case(best.total / 2, best.n)); candidates.add(new Case(best.total - 1, best.n)); }
            if (best.n > 1)     { candidates.add(new Case(best.total, best.n - 1)); }
            for (Case cand : candidates) {
                if (cand.total >= 0 && cand.n >= 1 && !property(split, cand)) { best = cand; improved = true; break; }
            }
        }
        return best;
    }

    static void check(String name, BiFunction<Long,Integer,long[]> split, long seed) {
        Random r = new Random(seed);
        int RUNS = 1000;
        Case firstFail = null;
        for (int i = 0; i < RUNS; i++) {
            long total = (long) (r.nextDouble() * 1_000_000);   // 0..10,000.00 rupees in paise
            int n = 1 + r.nextInt(20);
            Case c = new Case(total, n);
            if (!property(split, c)) { firstFail = c; break; }
        }
        if (firstFail == null) {
            System.out.printf("   %-14s: PASSED %d random cases — property 'parts sum to total' holds.%n", name, RUNS);
        } else {
            Case small = shrink(split, firstFail);
            long[] parts = split.apply(small.total, small.n);
            long sum = 0; for (long p : parts) sum += p;
            System.out.printf("   %-14s: FAILED. Random counterexample total=%d n=%d; shrunk to the minimal failing case:%n",
                    name, firstFail.total, firstFail.n);
            System.out.printf("                   total=%d split %d ways = %s  → sum=%d, LOST %d paise.%n",
                    small.total, small.n, Arrays.toString(parts), sum, small.total - sum);
        }
    }

    public static void main(String[] args) {
        long seed = 42;
        System.out.println("Property-based testing: state one property ('the split parts sum to the total') and throw 1000 random");
        System.out.println("inputs at each splitter. A failure is SHRUNK to the smallest input that still breaks it.\n");
        check("splitNaive", PropertyL1::splitNaive, seed);
        check("splitCorrect", PropertyL1::splitCorrect, seed);
        System.out.println("\n   → the naive splitter passes hand-picked even cases (100 / 4 = 25 each) but a random search finds an ODD");
        System.out.println("     case in milliseconds; shrinking reports the *minimal* reproducer (e.g. 1 paisa split 2 ways) instead of");
        System.out.println("     the messy random one. That minimal case is what you paste into a regression unit test. Property tests");
        System.out.println("     find the edge cases you didn't think to write — exactly the ones that bite in production.");
    }
}
