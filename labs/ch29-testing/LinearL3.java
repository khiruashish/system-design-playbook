// LinearL3.java — run with: java LinearL3.java   (JDK 21, no dependencies)
// LINEARIZABILITY CHECKING — the technique behind Jepsen/Knossos. You cannot unit-test a distributed register by asking
// "is it correct?"; you RECORD a concurrent HISTORY of operations (each with the real-time window [invoke, return] and
// its result) and then ask a checker: does there exist a single sequential order of these operations that (a) respects
// real time — if op A returned before op B was invoked, A precedes B — and (b) is a legal register execution (every read
// returns the most recent write)? If such an order exists the history is LINEARIZABLE; if not, the system violated the
// guarantee. We run the checker on three recorded histories from a replicated register (one correct, two buggy).
import java.util.*;

public class LinearL3 {

    // an operation in a recorded history. WRITE(value) or READ()->value. Times are logical ticks.
    record Op(String client, boolean isWrite, int value, int invoke, int ret) {
        public String toString() {
            return String.format("%s %s%s [%d..%d]", client, isWrite ? "write(" + value + ")" : "read()->", isWrite ? "" : ""+value, invoke, ret);
        }
    }

    // ---- the checker: real-time-aware brute-force search for a valid linearization (Wing & Gong style) ----
    // We repeatedly pick a "minimal" op (one whose invoke is <= the earliest return among remaining ops, so choosing it
    // cannot violate real-time order), apply it to the model, recurse, and backtrack. A read is legal iff it returns the
    // model's current value. If we consume all ops, we found a linearization.
    static List<Op> linearizationFound;
    static boolean linearizable(List<Op> remaining, int regValue, List<Op> acc) {
        if (remaining.isEmpty()) { linearizationFound = new ArrayList<>(acc); return true; }
        int minRet = Integer.MAX_VALUE;
        for (Op o : remaining) minRet = Math.min(minRet, o.ret());
        for (Op o : remaining) {
            if (o.invoke() > minRet) continue;                       // choosing o would break real-time order → skip
            int newVal = regValue;
            if (o.isWrite()) newVal = o.value();
            else if (o.value() != regValue) continue;                // read result inconsistent with model → not this pick
            List<Op> rest = new ArrayList<>(remaining); rest.remove(o);
            acc.add(o);
            if (linearizable(rest, newVal, acc)) return true;
            acc.remove(acc.size() - 1);                              // backtrack
        }
        return false;
    }

    static void check(String name, int initial, List<Op> history) {
        System.out.println("   " + name);
        for (Op o : history) System.out.println("        " + o);
        linearizationFound = null;
        boolean ok = linearizable(new ArrayList<>(history), initial, new ArrayList<>());
        if (ok) {
            StringBuilder sb = new StringBuilder();
            for (Op o : linearizationFound) sb.append(o.isWrite() ? "W" + o.value() : "R" + o.value()).append(" ");
            System.out.println("     → LINEARIZABLE. A valid real-time order exists: " + sb.toString().trim());
        } else {
            System.out.println("     → NOT LINEARIZABLE. No sequential order respects both real time and register semantics — the register lied.");
        }
        System.out.println();
    }

    public static void main(String[] args) {
        System.out.println("Linearizability checking: given a recorded history of a distributed register (initial value 0), does a valid");
        System.out.println("sequential order exist? The checker respects real-time order AND register semantics (a read = the last write).\n");

        // History 1: correct register. C2's read overlaps C1's write, so returning 1 is fine (write linearizes first).
        check("History 1 — correct register (quorum reads/writes):", 0, List.of(
            new Op("C1", true, 1, 0, 3),      // write(1)
            new Op("C2", false, 1, 2, 5),     // read()->1  (overlaps the write; legal)
            new Op("C1", true, 2, 6, 8),      // write(2)
            new Op("C3", false, 2, 9, 11)     // read()->2
        ));

        // History 2: async-replica STALE READ. write(1) fully completes and a read sees 1; then a LATER read hits a
        // lagging replica and returns 0 — the value has travelled backwards in time. No linearization can order this.
        check("History 2 — async replica served a STALE read:", 0, List.of(
            new Op("C1", true, 1, 0, 2),      // write(1) completes at t2
            new Op("C2", false, 1, 3, 5),     // read()->1  at t3..t5
            new Op("C3", false, 0, 6, 8)      // read()->0  at t6..t8  ← impossible: 1 was already observed and committed
        ));

        // History 3: LOST UPDATE. Two writes both ack (last-writer-wins bug drops one), then a read returns a value that
        // is consistent with neither final write ordering given the real-time constraints.
        check("History 3 — lost update (write acked but overwritten wrongly):", 0, List.of(
            new Op("C1", true, 1, 0, 2),      // write(1) completes at t2
            new Op("C2", true, 2, 3, 5),      // write(2) completes at t5 (strictly after write(1))
            new Op("C3", false, 1, 6, 8)      // read()->1  at t6..t8  ← must see 2 (the last completed write), not 1
        ));

        System.out.println("   → the checker is your oracle: it turns 'the database says it's strongly consistent' into a mechanical proof");
        System.out.println("     or a concrete counterexample. History 1 is accepted; the stale read and the lost update are both rejected");
        System.out.println("     with certainty. This is exactly how Jepsen finds consistency violations that survived every unit test —");
        System.out.println("     you generate randomized concurrent histories under fault injection (partitions, pauses) and check each one.");
    }
}
