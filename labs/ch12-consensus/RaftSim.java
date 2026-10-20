// RaftSim.java — run with: java RaftSim.java
// A deterministic Raft simulator (elections, log replication, commit rule, ReadIndex reads) with message delays,
// drops, network partitions and crash/restarts. Safety invariants are checked on every step.
// Two bug switches show WHY two rules exist; a read switch shows why leader-local reads are not linearizable.
import java.util.*;

public class RaftSim {

    // ---------- configuration for one run ----------
    record Config(long seed, boolean bugNoUpToDateCheck, boolean bugCommitOldTerm, String reads, boolean sticky, double clockRate) {
        Config(long seed, boolean noUpToDate, boolean oldTerm) { this(seed, noUpToDate, oldTerm, "readindex", false, 1.0); }
    }

    record Entry(int term, long cmd) { }                              // cmd 0 = leader no-op
    sealed interface Msg permits Vote, VoteReply, Append, AppendReply { }
    record Vote(int term, int from, int lastIndex, int lastTerm) implements Msg { }
    record VoteReply(int term, int from, boolean granted) implements Msg { }
    record Append(int term, int from, int prevIndex, int prevTerm, List<Entry> entries, int leaderCommit, long readRound) implements Msg { }
    record AppendReply(int term, int from, boolean ok, int match, long readRound) implements Msg { }
    
    enum Role { FOLLOWER, CANDIDATE, LEADER }

    static final int N = 5, MAJ = N / 2 + 1;

    final Config cfg;
    final Random rnd;
    long now = 0;
    final PriorityQueue<long[]> timeline = new PriorityQueue<>(Comparator.comparingLong((long[] e) -> e[0]).thenComparingLong(e -> e[1]));
    final Map<Long, Runnable> actions = new HashMap<>();
    long seq = 0;
    final Node[] nodes = new Node[N];
    int[] partition = new int[N];                                    // nodes talk only within the same group
    boolean scripted = false;                                        // Figure-8 script: no loss, 1 ms delay, manual elections, no no-op

    // ---------- global observers (the "oracle" that checks safety) ----------
    final Map<Integer, Integer> leaderOfTerm = new HashMap<>();
    final Map<Integer, Entry> appliedAt = new HashMap<>();           // index → entry applied by ANYONE
    final Map<Integer, Entry> committed = new HashMap<>();           // index → entry some leader declared committed
    final List<String> violations = new ArrayList<>();
    long maxAckedWrite = 0;                                          // highest cmd acknowledged to a client
    long reads = 0, staleReads = 0, writesAcked = 0, elections = 0;

    RaftSim(Config cfg) {
        this.cfg = cfg; this.rnd = new Random(cfg.seed);
        for (int i = 0; i < N; i++) nodes[i] = new Node(i);
    }

    void at(long t, Runnable r) { long id = seq++; actions.put(id, r); timeline.add(new long[]{t, id}); }

    void send(int from, int to, Msg m) {
        if (!scripted && rnd.nextDouble() < 0.05) return;                            // 5 % loss
        long delay = scripted ? 1 : 1 + rnd.nextInt(15);
        at(now + delay, () -> {
            if (partition[from] != partition[to]) return;                            // cut by a partition
            if (!nodes[to].up) return;
            nodes[to].receive(m);
        });
    }

    // ---------- one Raft node ----------
    final class Node {
        final int id;
        boolean up = true;
        // persistent state (survives crash)
        int term = 0; Integer votedFor = null; final List<Entry> log = new ArrayList<>(List.of(new Entry(0, 0)));  // index 0 = sentinel
        // volatile state
        Role role = Role.FOLLOWER; int commit = 0, applied = 0; long electionDeadline; int votes;
        int[] next = new int[N], match = new int[N];
        long lastAppliedCmd = 0;
        // ReadIndex bookkeeping
        long readRound = 0; final Map<Long, Set<Integer>> readAcks = new HashMap<>(); final Map<Long, Runnable> pendingReads = new HashMap<>();
        final Map<Long, Integer> readIndexOf = new HashMap<>();
        // lease reads: heartbeat rounds acked by a majority extend a lease measured on THIS node's (possibly drifting) clock
        final Map<Long, Long> hbSentAt = new HashMap<>(); final Map<Long, Set<Integer>> hbAcks = new HashMap<>();
        long leaseUntilLocal = -1, lastHeardFromLeader = -1_000;
        long local(long t) { return (long) (t * cfg.clockRate); }

        Node(int id) { this.id = id; resetElectionTimer(); }

        int lastIndex() { return log.size() - 1; }
        int lastTerm() { return log.get(lastIndex()).term(); }

        void resetElectionTimer() {
            if (scripted) return;
            long deadline = now + 150 + rnd.nextInt(150);                            // randomized 150–300 ms
            electionDeadline = deadline;
            at(deadline, () -> { if (up && role != Role.LEADER && electionDeadline == deadline) startElection(); });
        }

        void stepDown(int newTerm) {
            if (newTerm > term) { term = newTerm; votedFor = null; }
            if (role != Role.FOLLOWER) { role = Role.FOLLOWER; pendingReads.clear(); readAcks.clear(); hbAcks.clear(); leaseUntilLocal = -1; }
        }

        void startElection() {
            role = Role.CANDIDATE; term++; votedFor = id; votes = 1; elections++;
            resetElectionTimer();
            for (int p = 0; p < N; p++) if (p != id) send(id, p, new Vote(term, id, lastIndex(), lastTerm()));
        }

        void becomeLeader() {
            role = Role.LEADER;
            Integer prev = leaderOfTerm.putIfAbsent(term, id);
            if (prev != null && prev != id) violations.add("ELECTION SAFETY: two leaders in term " + term);
            // Leader completeness: every entry already committed must be in the new leader's log
            for (var e : committed.entrySet())
                if (e.getKey() > lastIndex() || !log.get(e.getKey()).equals(e.getValue()))
                    violations.add("LEADER COMPLETENESS: leader " + id + " term " + term + " lacks committed index " + e.getKey());
            Arrays.fill(next, lastIndex() + 1); Arrays.fill(match, 0); match[id] = lastIndex();
            if (!scripted) { log.add(new Entry(term, 0)); match[id] = lastIndex(); }   // no-op of the new term
            heartbeat(0);
        }

        void heartbeat(long round) {
            if (role != Role.LEADER || !up) return;
            for (int p = 0; p < N; p++) if (p != id) {
                int prevIdx = Math.min(next[p] - 1, lastIndex());
                List<Entry> ents = new ArrayList<>(log.subList(prevIdx + 1, Math.min(log.size(), prevIdx + 1 + 50)));
                send(id, p, new Append(term, id, prevIdx, log.get(prevIdx).term(), ents, commit, round));
            }
        }

        void receive(Msg m) {
            switch (m) {
                case Vote v -> {
                    if (cfg.sticky && role == Role.FOLLOWER && now - lastHeardFromLeader < 150) {   // heard a leader recently:
                        send(id, v.from(), new VoteReply(term, id, false)); return;                  // ignore, don't bump term
                    }
                    if (v.term() > term) stepDown(v.term());
                    boolean upToDate = v.lastTerm() > lastTerm() || (v.lastTerm() == lastTerm() && v.lastIndex() >= lastIndex());
                    if (cfg.bugNoUpToDateCheck) upToDate = true;                   // BUG: vote for anyone
                    boolean grant = v.term() == term && (votedFor == null || votedFor == v.from()) && upToDate;
                    if (grant) { votedFor = v.from(); resetElectionTimer(); }
                    send(id, v.from(), new VoteReply(term, id, grant));
                }
                case VoteReply r -> {
                    if (r.term() > term) { stepDown(r.term()); return; }
                    if (role == Role.CANDIDATE && r.term() == term && r.granted() && ++votes >= MAJ) becomeLeader();
                }
                case Append a -> {
                    if (a.term() > term || (a.term() == term && role != Role.FOLLOWER)) stepDown(a.term());
                    if (a.term() < term) { send(id, a.from(), new AppendReply(term, id, false, 0, a.readRound())); return; }
                    resetElectionTimer();
                    lastHeardFromLeader = now;
                    if (a.prevIndex() > lastIndex() || log.get(a.prevIndex()).term() != a.prevTerm()) {
                        send(id, a.from(), new AppendReply(term, id, false, Math.min(a.prevIndex() - 1, lastIndex()), a.readRound())); return;
                    }
                    int i = a.prevIndex();
                    for (Entry e : a.entries()) {
                        i++;
                        if (i <= lastIndex() && log.get(i).term() != e.term()) while (lastIndex() >= i) log.remove(lastIndex());   // truncate conflict
                        if (i > lastIndex()) log.add(e);
                    }
                    int lastNew = a.prevIndex() + a.entries().size();
                    if (a.leaderCommit() > commit) commit = Math.min(a.leaderCommit(), lastNew);
                    apply();
                    send(id, a.from(), new AppendReply(term, id, true, lastNew, a.readRound()));
                }
                case AppendReply r -> {
                    if (r.term() > term) { stepDown(r.term()); return; }
                    if (role != Role.LEADER || r.term() != term) return;
                    if (r.ok()) { match[r.from()] = Math.max(match[r.from()], r.match()); next[r.from()] = match[r.from()] + 1; advanceCommit(); }
                    else next[r.from()] = Math.max(1, Math.min(next[r.from()] - 1, r.match() + 1));
                    if (r.readRound() < 0) {                                        // lease heartbeat round
                        var acks = hbAcks.get(r.readRound());
                        if (acks != null && acks.add(r.from()) && acks.size() >= MAJ) {
                            leaseUntilLocal = Math.max(leaseUntilLocal, local(hbSentAt.get(r.readRound())) + 135);   // < min election timeout
                            hbAcks.remove(r.readRound());
                        }
                    }
                    if (r.readRound() > 0) {                                        // ReadIndex: count acks for this round
                        var acks = readAcks.get(r.readRound());
                        if (acks != null) { acks.add(r.from()); tryServeRead(r.readRound()); }
                    }
                }
            }
        }

        void advanceCommit() {
            for (int n = lastIndex(); n > commit; n--) {
                int count = 0; for (int p = 0; p < N; p++) if (match[p] >= n) count++;
                boolean currentTerm = log.get(n).term() == term;
                if (cfg.bugCommitOldTerm) currentTerm = true;                     // BUG: commit old-term entries by counting
                if (count >= MAJ && currentTerm) {
                    int from = commit + 1;
                    commit = n;
                    for (int k = from; k <= n; k++) {
                        Entry prev = committed.putIfAbsent(k, log.get(k));
                        if (prev != null && !prev.equals(log.get(k))) violations.add("COMMITTED ENTRY CHANGED at index " + k);
                    }
                    apply();
                    break;
                }
            }
        }

        void apply() {
            while (applied < commit) {
                applied++;
                Entry e = log.get(applied);
                Entry prev = appliedAt.putIfAbsent(applied, e);
                if (prev != null && !prev.equals(e)) violations.add("STATE MACHINE SAFETY: index " + applied + " applied as " + prev + " and " + e);
                if (e.cmd() != 0) {
                    lastAppliedCmd = e.cmd();
                    if (role == Role.LEADER && e.term() == term) { writesAcked++; maxAckedWrite = Math.max(maxAckedWrite, e.cmd()); }
                }
            }
            for (var it = pendingReads.entrySet().iterator(); it.hasNext(); ) {
                var pr = it.next();
                if (confirmed.contains(pr.getKey()) && applied >= readIndexOf.get(pr.getKey())) { pr.getValue().run(); it.remove(); }
            }
        }

        void clientWrite(long cmd) { if (role == Role.LEADER && up) { log.add(new Entry(term, cmd)); match[id] = lastIndex(); } }

        void clientRead() {
            if (role != Role.LEADER || !up) return;
            long mustSee = maxAckedWrite;                                          // real-time: acked before the read began
            Runnable answer = () -> { reads++; if (lastAppliedCmd < mustSee) staleReads++; };
            if (cfg.reads().equals("local")) { answer.run(); return; }            // leader answers from local state
            if (cfg.reads().equals("lease")) {                                     // answer locally only inside the lease
                if (log.get(commit).term() == term && local(now) < leaseUntilLocal) answer.run();
                return;
            }
            // ReadIndex: need a committed entry of our term (the no-op), then a heartbeat round acked by a majority
            if (log.get(commit).term() != term) return;                           // not ready yet: client retries
            long round = ++readRound + (long) id * 1_000_000_000L + (long) term * 1_000_000L;
            readIndexOf.put(round, commit);
            readAcks.put(round, new HashSet<>(Set.of(id)));
            pendingReads.put(round, answer);
            heartbeat(round);
        }
        final Set<Long> confirmed = new HashSet<>();                              // rounds acked by a majority

        void tryServeRead(long round) {
            var acks = readAcks.get(round);
            if (acks == null || acks.size() < MAJ) return;
            readAcks.remove(round);
            confirmed.add(round);
            // leadership confirmed at a time after the read started; serve once applied >= readIndex
            if (applied >= readIndexOf.get(round)) { var r = pendingReads.remove(round); if (r != null) r.run(); }
        }

        void leaseHeartbeat() {
            if (!cfg.reads().equals("lease")) { heartbeat(0); return; }
            long round = -(++readRound + (long) id * 1_000_000_000L + (long) term * 1_000_000L);
            hbSentAt.put(round, now); hbAcks.put(round, new HashSet<>(Set.of(id)));
            heartbeat(round);
        }

        void crash() { up = false; role = Role.FOLLOWER; pendingReads.clear(); readAcks.clear(); hbAcks.clear(); leaseUntilLocal = -1; }
        void restart() { up = true; commit = 0; applied = 0; lastAppliedCmd = 0; resetElectionTimer(); }
    }

    // ---------- Figure 8 of the Raft paper, scripted step by step ----------
    void runUntil(long t) { while (!timeline.isEmpty() && timeline.peek()[0] <= t) { long[] e = timeline.poll(); now = e[0]; actions.remove(e[1]).run(); } now = t; }
    void pump(Node leader, long until) { for (long t = now; t < until; t += 5) { leader.heartbeat(0); runUntil(t + 5); } }

    static String figure8(boolean bug) {
        RaftSim s = new RaftSim(new Config(1, false, bug)); s.scripted = true;
        Node s1 = s.nodes[0], s2 = s.nodes[1], s3 = s.nodes[2], s4 = s.nodes[3], s5 = s.nodes[4];
        List<String> story = new ArrayList<>();
        s1.startElection(); s.runUntil(5); s1.clientWrite(1); s.pump(s1, 40);
        story.add("S1 leader t" + s1.term + ", idx1 committed everywhere");
        s.partition = new int[]{0, 0, 1, 1, 1}; s1.clientWrite(2); s.pump(s1, 70);
        story.add("S1 writes idx2 (t1) but only S2 gets it");
        s1.crash(); s5.startElection(); s.runUntil(75);
        s5.clientWrite(3); s5.crash();
        story.add("S1 crashes; S5 elected t" + s5.term + " (votes S3,S4), writes idx2 (t2) locally, crashes");
        s1.restart(); s.partition = new int[]{0, 0, 0, 1, 1}; s1.startElection(); s.runUntil(80); s1.startElection(); s.runUntil(85);
        s.pump(s1, 120);
        story.add("S1 back, elected t" + s1.term + ", copies idx2 (t1) to S3 → on 3 of 5 nodes; leader commit=" + s1.commit);
        s1.crash(); s5.restart(); s.partition = new int[]{0, 0, 0, 0, 0};
        s5.startElection(); s.runUntil(125); s5.startElection(); s.runUntil(130);
        story.add("S1 crashes; S5 back, elected t" + s5.term + " with votes from S2,S3,S4 (its last term 2 > their 1)");
        if (s5.role == Role.LEADER) { s5.clientWrite(4); s.pump(s5, 170); }
        story.add("S5 replicates: idx2 on S2,S3 is now " + s2.log.get(2) + " / " + s3.log.get(2));
        story.add(s.violations.isEmpty() ? "no safety violation (idx2 (t1) was never declared committed)" : "VIOLATION: " + s.violations.get(0));
        return String.join("\n      ", story);
    }

    // ---------- the scenario: 20 s of traffic with faults ----------
    RaftSim run() {
        long cmd = 0;
        for (long t = 10; t < 20_000; t += 10) {
            final long c = ++cmd, tt = t;
            at(t, () -> { for (Node n : nodes) n.clientWrite(c); });                // whoever is leader accepts it
            at(t + 5, () -> { for (Node n : nodes) n.clientRead(); });
            if (t % 50 == 0) at(t, () -> { for (Node n : nodes) if (n.role == Role.LEADER) n.leaseHeartbeat(); });
            if (t % 700 == 0) at(t, () -> {                                            // faults
                double r = rnd.nextDouble();
                if (r < 0.4) { for (int i = 0; i < N; i++) partition[i] = rnd.nextInt(2); }  // random split
                else if (r < 0.6) Arrays.fill(partition, 0);                           // heal
                else if (r < 0.8) { Node n = nodes[rnd.nextInt(N)]; if (n.up) n.crash(); }
                else { for (Node n : nodes) if (!n.up) n.restart(); }
            });
        }
        while (!timeline.isEmpty()) {
            long[] e = timeline.poll(); now = e[0];
            if (now > 20_500) break;                                                   // election timers re-arm forever
            actions.remove(e[1]).run();
            if (!violations.isEmpty()) break;
        }
        return this;
    }

    public static void main(String[] args) {
        int runs = args.length > 0 ? Integer.parseInt(args[0]) : 300;
        System.out.println("5 nodes, 20 s simulated per run, 5 % message loss, random partitions and crash/restarts, " + runs + " seeds each");
        for (var mode : List.of("correct Raft", "BUG: vote without the up-to-date log check", "BUG: commit old-term entries by counting replicas")) {
            int bad = 0; long elections = 0, committedEntries = 0; String example = null;
            for (int s = 1; s <= runs; s++) {
                var sim = new RaftSim(new Config(s, mode.contains("up-to-date"), mode.contains("old-term"))).run();
                elections += sim.elections; committedEntries += sim.committed.size();
                if (!sim.violations.isEmpty()) { bad++; if (example == null) example = "seed " + s + ": " + sim.violations.get(0); }
            }
            System.out.printf("   %-50s runs with a safety violation: %3d/%d   (elections %,d, committed entries %,d)%n", mode, bad, runs, elections, committedEntries);
            if (example != null) System.out.println("      first: " + example);
        }
        for (boolean bug : new boolean[]{true, false})
            System.out.println("Figure 8 script, " + (bug ? "BUG commit rule" : "correct commit rule") + ":\n      " + figure8(bug));
        System.out.println("Reads served by whoever believes it is leader (correct Raft underneath), " + runs + " seeds each");
        Object[][] readModes = {
            {"leader answers from local state", new Config(0, false, false, "local", false, 1.0)},
            {"ReadIndex (confirm leadership with a quorum round)", new Config(0, false, false, "readindex", false, 1.0)},
            {"lease reads, no leader stickiness", new Config(0, false, false, "lease", false, 1.0)},
            {"lease reads + stickiness, accurate clocks", new Config(0, false, false, "lease", true, 1.0)},
            {"lease reads + stickiness, leader clock 10 % slow", new Config(0, false, false, "lease", true, 0.9)},
            {"lease reads + stickiness, leader clock 50 % slow", new Config(0, false, false, "lease", true, 0.5)},
        };
        for (Object[] rm : readModes) {
            Config base = (Config) rm[1];
            long reads = 0, stale = 0; int bad = 0;
            for (int s = 1; s <= runs; s++) {
                var sim = new RaftSim(new Config(s, false, false, base.reads(), base.sticky(), base.clockRate())).run();
                reads += sim.reads; stale += sim.staleReads; if (!sim.violations.isEmpty()) bad++;
            }
            System.out.printf("   %-50s reads %,9d   stale (missed an acknowledged write) %,6d%n", rm[0], reads, stale);
        }
    }
}
