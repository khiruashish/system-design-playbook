// ReplicationL3.java — run with: java ReplicationL3.java
// One Kafka partition, replication factor 3, simulated tick by tick: leader appends, followers fetch every few
// ticks, ISR shrinks when a follower falls behind for replica.lag.time.max, HW = min LEO over the ISR,
// producers use acks=0/1/all with min.insync.replicas, leaders crash, followers get cut off,
// elections are clean (from the ISR) or unclean, and replicas truncate to the new leader's log.
import java.util.*;

public class ReplicationL3 {

    record Cfg(String name, String acks, int minIsr, boolean unclean, boolean idempotent) { }
    record Rec(long id, int attempt) { }

    static final int RF = 3, FETCH_EVERY = 5, LAG_MAX = 30;

    static final class Replica {
        final List<Rec> log = new ArrayList<>();
        boolean up = true, diverged = false; int cutUntil = -1, downUntil = -1, lastCaughtUp = 0;
        int leo() { return log.size(); }
    }

    static Map<String, Long> run(Cfg cfg, long seed) {
        Random rnd = new Random(seed);
        Replica[] r = new Replica[RF];
        for (int i = 0; i < RF; i++) r[i] = new Replica();
        int leader = 0; Set<Integer> isr = new TreeSet<>(Set.of(0, 1, 2)); int hw = 0;
        Map<Long, Integer> pending = new HashMap<>();                  // id → offset waiting for HW (acks=all)
        Set<Long> acked = new HashSet<>();
        Deque<Long> retry = new ArrayDeque<>();
        long nextId = 0, rejected = 0, offlineTicks = 0, retries = 0;
        Set<Long> leaderIds = new HashSet<>();                         // idempotent producer: leader dedups (pid, seq) from its log

        for (int t = 0; t < 200_000; t++) {
            // ---- faults ----
            for (int i = 0; i < RF; i++) {
                Replica x = r[i];
                if (!x.up && t >= x.downUntil) { x.up = true; }
                if (x.up && rnd.nextDouble() < 0.0003) {
                    x.up = false; x.downUntil = t + 200 + rnd.nextInt(400);
                }
                if (x.up && rnd.nextDouble() < 0.001) x.cutUntil = t + 100 + rnd.nextInt(400);   // network cut from the leader
            }
            // ---- leader failure → election ----
            if (leader < 0 || !r[leader].up) {
                int old = leader;
                Integer next = isr.stream().filter(i -> i != old && r[i].up).findFirst().orElse(null);
                if (next == null && old >= 0 && r[old].up && isr.contains(old)) next = old;      // the old leader came back intact
                if (next == null && cfg.unclean())                                                 // unclean: best surviving replica
                    for (int i = 0; i < RF; i++) if (r[i].up && (next == null || r[i].leo() > r[next].leo())) next = i;
                if (next == null) { offlineTicks++; leader = -1; pending.clear(); }
                else if (next != leader) {
                    leader = next;
                    for (int i = 0; i < RF; i++) if (i != leader) r[i].diverged = true;   // must truncate to the new leader's log
                    leaderIds.clear(); for (Rec x : r[leader].log) leaderIds.add(x.id());
                    if (!isr.contains(leader)) isr = new TreeSet<>(Set.of(leader));             // unclean: ISR restarts at the new leader
                    isr.removeIf(i -> !r[i].up);
                    // in-flight acks=all requests to the dead leader time out → the producer retries them
                    retry.addAll(pending.keySet()); retries += pending.size(); pending.clear();
                    hw = Math.min(hw, r[leader].leo());
                }
            }
            // ---- produce one record per tick (+ retries) ----
            if (leader >= 0) {
                Replica L = r[leader];
                List<Long> batch = new ArrayList<>(retry); retry.clear();
                batch.add(nextId++);
                for (long id : batch) {
                    if (cfg.acks().equals("all") && isr.size() < cfg.minIsr()) { rejected++; continue; }   // NotEnoughReplicas
                    if (cfg.idempotent() && leaderIds.contains(id)) {                             // duplicate retry: not appended
                        if (cfg.acks().equals("all")) pending.put(id, L.log.size()); else acked.add(id);
                        continue;
                    }
                    L.log.add(new Rec(id, 0)); leaderIds.add(id);
                    switch (cfg.acks()) {
                        case "0", "1" -> acked.add(id);
                        default -> pending.put(id, L.log.size());
                    }
                }
            } else {                                                   // offline
                long id = nextId++;
                if (cfg.acks().equals("0")) acked.add(id);                // fire-and-forget: the producer never finds out
            }
            // ---- followers fetch ----
            if (leader >= 0 && t % FETCH_EVERY == 0) {
                Replica L = r[leader];
                for (int i = 0; i < RF; i++) {
                    if (i == leader || !r[i].up || t < r[i].cutUntil) continue;
                    Replica f = r[i];
                    // truncate a divergent suffix (leader-epoch based truncation), then append what's missing
                    if (f.diverged) {
                        int common = 0;
                        while (common < f.leo() && common < L.leo() && f.log.get(common).equals(L.log.get(common))) common++;
                        while (f.leo() > common) f.log.remove(f.leo() - 1);
                        f.diverged = false;
                    }
                    f.log.addAll(L.log.subList(f.leo(), L.leo()));
                    f.lastCaughtUp = t;
                    if (!isr.contains(i)) isr.add(i);                  // caught up → rejoins the ISR
                }
                r[leader].lastCaughtUp = t;
            }
            // ---- ISR shrink and high watermark ----
            if (leader >= 0) {
                int tt = t, ld = leader;
                isr.removeIf(i -> i != ld && (!r[i].up || tt - r[i].lastCaughtUp > LAG_MAX));
                int newHw = isr.stream().mapToInt(i -> r[i].leo()).min().orElse(0);
                hw = Math.max(hw, newHw);
                int h = hw;
                pending.entrySet().removeIf(e -> { if (e.getValue() <= h) { acked.add(e.getKey()); return true; } return false; });
            }
        }
        // ---- verdict: what does the final leader's log contain? ----
        Map<Long, Integer> counts = new HashMap<>();
        if (leader >= 0) for (Rec x : r[leader].log) counts.merge(x.id(), 1, Integer::sum);
        else for (Replica x : r) for (Rec y : x.log) counts.putIfAbsent(y.id(), 1);          // offline: anything still on disk
        long lost = acked.stream().filter(id -> !counts.containsKey(id)).count();
        long dups = counts.values().stream().filter(c -> c > 1).count();
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("acked", (long) acked.size()); out.put("acked-but-lost", lost); out.put("duplicates", dups);
        out.put("rejected (NotEnoughReplicas)", rejected); out.put("offline ticks", offlineTicks); out.put("retries", retries);
        return out;
    }

    public static void main(String[] args) {
        List<Cfg> cfgs = List.of(
            new Cfg("acks=0", "0", 1, false, false),
            new Cfg("acks=1", "1", 1, false, false),
            new Cfg("acks=all, min.insync=1", "all", 1, false, false),
            new Cfg("acks=all, min.insync=1, unclean election", "all", 1, true, false),
            new Cfg("acks=all, min.insync=2", "all", 2, false, false),
            new Cfg("acks=all, min.insync=2, idempotent producer", "all", 2, false, true));
        System.out.println("A. RF=3, 200,000 ticks x 10 seeds; brokers crash and restart (0.03 %/tick) and get cut off from the leader (0.1 %/tick)");
        System.out.printf("   %-44s %10s %14s %11s %13s %9s%n", "producer / topic config", "acked", "acked-but-lost", "duplicates", "rejected", "offline");
        for (Cfg c : cfgs) {
            Map<String, Long> sum = new LinkedHashMap<>();
            for (long s = 1; s <= 10; s++) run(c, s).forEach((k, v) -> sum.merge(k, v, Long::sum));
            System.out.printf("   %-44s %,10d %,14d %,11d %,13d %,9d%n", c.name(), sum.get("acked"), sum.get("acked-but-lost"),
                    sum.get("duplicates"), sum.get("rejected (NotEnoughReplicas)"), sum.get("offline ticks"));
        }
        System.out.println("B. The case min.insync.replicas exists for: both followers fall out of the ISR, 1,000 more writes arrive,");
        System.out.println("   then the leader's DISK dies (broker replaced, comes back empty)");
        for (int minIsr : new int[]{1, 2}) for (boolean unclean : new boolean[]{false, true}) {
            long acked = 0, lost; String state;
            List<Long> leaderLog = new ArrayList<>(), follower = new ArrayList<>();
            for (long i = 0; i < 10_000; i++) { leaderLog.add(i); follower.add(i); }        // in sync, ISR = {0,1,2}
            int isrSize = 1;                                                                  // followers cut off → ISR = {leader}
            for (long i = 10_000; i < 11_000; i++) if (isrSize >= minIsr) { leaderLog.add(i); acked++; }   // acks=all
            long ackedTotal = 10_000 + acked;
            leaderLog.clear();                                                                // disk lost; no eligible ISR member left
            lost = ackedTotal - follower.size();                                              // their only copy was on the lost disk
            state = unclean ? "back online: a lagging follower is elected (unclean)" : "OFFLINE: no eligible replica until an operator intervenes";
            System.out.printf("   min.insync=%d unclean=%-5s  writes acked during ISR={leader}: %,5d   acked-but-lost: %,5d   %s%n",
                    minIsr, unclean, acked, lost, state);
        }
    }
}
