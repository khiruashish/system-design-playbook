// LinearizabilityL2.java — run with: java LinearizabilityL2.java
// 3 replicas hold one register (e.g. an order's status version). A writer and 3 readers run concurrently over a
// network with random delays. Every history is checked for linearizability with a Wing & Gong style search.
import java.util.*;

public class LinearizabilityL2 {

    // ---------- history + checker ----------
    record Op(int client, boolean write, int value, long invoke, long response) { }

    /** Is there a total order that respects real time (a.response < b.invoke ⇒ a before b) and register semantics? */
    static boolean linearizable(List<Op> ops) {
        return search(ops, 0, 0, new HashSet<>());
    }

    private static boolean search(List<Op> ops, int doneMask, int state, Set<Long> seen) {
        if (doneMask == (1 << ops.size()) - 1) return true;
        if (!seen.add(((long) doneMask << 32) | state)) return false;
        long earliestResponse = Long.MAX_VALUE;                              // an op can go next only if it was
        for (int i = 0; i < ops.size(); i++)                                 // invoked before every pending op finished
            if ((doneMask & (1 << i)) == 0) earliestResponse = Math.min(earliestResponse, ops.get(i).response());
        for (int i = 0; i < ops.size(); i++) {
            if ((doneMask & (1 << i)) != 0) continue;
            Op o = ops.get(i);
            if (o.invoke() > earliestResponse) continue;
            if (o.write()) { if (search(ops, doneMask | (1 << i), o.value(), seen)) return true; }
            else if (o.value() == state && search(ops, doneMask | (1 << i), state, seen)) return true;
        }
        return false;
    }

    // ---------- a tiny discrete-event simulation of 3 replicas ----------
    enum Mode { LEADER_WRITES_ASYNC_FOLLOWER_READS, QUORUM_W2_R2, QUORUM_WITH_READ_WRITEBACK }

    static final class Sim {
        final Random rnd; final Mode mode;
        final PriorityQueue<Object[]> q = new PriorityQueue<>(Comparator.comparingLong((Object[] e) -> (long) e[0]));
        long now = 0, tie = 0;
        final long[] ts = new long[3]; final int[] val = new int[3];
        final List<Op> history = new ArrayList<>();

        Sim(long seed, Mode mode) { rnd = new Random(seed); this.mode = mode; }
        void at(long t, Runnable r) { q.add(new Object[]{t, r}); }
        long delay() { return 1 + rnd.nextInt(30); }

        /** Replica r stores (t, v) if newer. */
        void store(int r, long t, int v) { if (t > ts[r]) { ts[r] = t; val[r] = v; } }

        void write(int client, long t, int v, Runnable next) {
            long start = now;
            if (mode == Mode.LEADER_WRITES_ASYNC_FOLLOWER_READS) {
                at(now + delay(), () -> {
                    store(0, t, v);
                    for (int r = 1; r < 3; r++) { int rr = r; at(now + delay() + rnd.nextInt(40), () -> store(rr, t, v)); }   // async
                    at(now + delay(), () -> { history.add(new Op(client, true, v, start, now)); next.run(); });
                });
                return;
            }
            int[] acks = {0};
            for (int r = 0; r < 3; r++) { int rr = r; at(now + delay(), () -> { store(rr, t, v);
                at(now + delay(), () -> { if (++acks[0] == 2) { history.add(new Op(client, true, v, start, now)); next.run(); } }); }); }
        }

        void read(int client, Runnable next) {
            long start = now;
            if (mode == Mode.LEADER_WRITES_ASYNC_FOLLOWER_READS) {
                int r = rnd.nextInt(3);                                              // any replica, R = 1
                at(now + delay(), () -> { int v = val[r]; at(now + delay(), () -> { history.add(new Op(client, false, v, start, now)); next.run(); }); });
                return;
            }
            long[] bestTs = {-1}; int[] bestVal = {0}; int[] replies = {0};
            for (int r = 0; r < 3; r++) { int rr = r; at(now + delay(), () -> { long t = ts[rr]; int v = val[rr];
                at(now + delay(), () -> {
                    if (t > bestTs[0]) { bestTs[0] = t; bestVal[0] = v; }
                    if (++replies[0] != 2) return;
                    if (mode == Mode.QUORUM_W2_R2) { history.add(new Op(client, false, bestVal[0], start, now)); next.run(); return; }
                    // ABD: write the value we are about to return back to a quorum first
                    long wt = bestTs[0]; int wv = bestVal[0]; int[] wacks = {0};
                    for (int x = 0; x < 3; x++) { int xx = x; at(now + delay(), () -> { store(xx, wt, wv);
                        at(now + delay(), () -> { if (++wacks[0] == 2) { history.add(new Op(client, false, wv, start, now)); next.run(); } }); }); }
                }); }); }
        }

        List<Op> run() {
            // writer: 3 sequential writes of 1, 2, 3; readers: 3 sequential reads each
            int[] w = {0};
            Runnable[] writer = new Runnable[1];
            writer[0] = () -> { if (w[0] < 3) { int v = ++w[0]; at(now + rnd.nextInt(20), () -> write(0, v, v, writer[0])); } };
            at(0, writer[0]);
            for (int c = 1; c <= 3; c++) {
                int cc = c; int[] n = {0}; Runnable[] reader = new Runnable[1];
                reader[0] = () -> { if (n[0]++ < 3) at(now + rnd.nextInt(30), () -> read(cc, reader[0])); };
                at(rnd.nextInt(20), reader[0]);
            }
            while (!q.isEmpty()) { Object[] e = q.poll(); now = (long) e[0]; ((Runnable) e[1]).run(); }
            return history;
        }
    }

    public static void main(String[] args) {
        int trials = 20_000;
        System.out.println("3 replicas, 1 writer (writes 1,2,3), 3 readers (3 reads each), random delays 1–30 ms; " + trials + " histories per mode");
        for (Mode m : Mode.values()) {
            int bad = 0; List<Op> example = null;
            for (int s = 0; s < trials; s++) {
                List<Op> h = new Sim(s, m).run();
                if (!linearizable(h)) { bad++; if (example == null) example = h; }
            }
            System.out.printf("   %-36s non-linearizable histories: %6d (%5.2f%%)%n", m, bad, 100.0 * bad / trials);
            if (example != null && m == Mode.QUORUM_W2_R2) {
                System.out.println("      example (times in ms):");
                example.stream().sorted(Comparator.comparingLong(Op::invoke)).forEach(o ->
                    System.out.printf("        client %d %-5s %d  [%3d → %3d]%n", o.client(), o.write() ? "write" : "read", o.value(), o.invoke(), o.response()));
            }
        }
    }
}
