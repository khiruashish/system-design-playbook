// OrderingL1.java — run with: java OrderingL1.java   (JDK 21, no dependencies)
// ORDER, DUPLICATES AND GAPS IN A CHAT. 2,000 simulated customer↔rider conversations (10 minutes, ~40 messages each) over
// mobile networks: requests are lost (5%), acks are lost after the server stored the message (3% — the client retries
// the same message), latency has a heavy tail (so messages overtake each other), pushes to the other phone are dropped
// (5%), and the rider's cheap phone clock is off by up to ±2 minutes. Each message is a reply to the latest message its
// sender had seen. Three server designs:
//   A) store every request; clients sort by the SENDER'S timestamp
//   B) dedupe by client message id; sort by SERVER receive time
//   C) dedupe by client message id; server enforces each sender's own order (client sequence numbers) and assigns a
//      per-conversation SEQUENCE; receivers detect gaps in the sequence and fetch what's missing
// We check each participant's final view: duplicates, messages missing, replies shown before the message they answer,
// and a sender's own messages shown out of the order they were written.
import java.util.*;

public class OrderingL1 {
    record Msg(int conv, int sender, int clientSeq, long id, long clientTs, long parent) {}
    record Ev(long t, int type, Msg m, int target) {}                     // type 0 = arrives at server, 1 = arrives at phone

    public static void main(String[] args) {
        System.out.println("2,000 conversations × ~40 messages; 5% request loss, 3% ack loss (retry), heavy-tailed latency,");
        System.out.println("5% of pushes dropped, rider clock skew up to ±120 s.\n");
        System.out.printf("   %-58s %11s %9s %17s %17s%n", "design", "duplicates", "missing", "reply before its", "sender's own msgs");
        System.out.printf("   %-58s %11s %9s %17s %17s%n", "", "shown", "", "message", "out of order");
        String[] names = {"A) no dedupe, sort by sender's clock", "B) dedupe by message id, sort by server receive time",
                "C) dedupe + per-sender order + conversation seq + gap fetch"};
        for (int d = 0; d < 3; d++) run(d, names[d]);
        System.out.println("\n   → phones' clocks can't order a conversation: the rider's replies landed before the questions they answered.");
        System.out.println("     Server time fixes causality between people (a reply is sent after its question was stored) but not a");
        System.out.println("     sender's own burst, which the network reorders, and nothing notices a dropped push. The fix is the one");
        System.out.println("     every chat system converges on: a client-generated id for idempotent retries, the sender's own sequence");
        System.out.println("     number so the server keeps each person's order, and a server-assigned per-conversation sequence so every");
        System.out.println("     phone can see a gap and fetch it. Push delivery is a hint; the sequence is the truth.");
    }

    @SuppressWarnings("unchecked")
    static void run(int design, String name) {
        Random rnd = new Random(41);
        long dups = 0, missing = 0, replyFirst = 0, ownInv = 0, total = 0;
        for (int conv = 0; conv < 2000; conv++) {
            long[] skew = {(long) (rnd.nextGaussian() * 2000), (long) ((rnd.nextDouble() * 2 - 1) * 120_000)};
            PriorityQueue<Ev> q = new PriorityQueue<>(Comparator.comparingLong(Ev::t));
            // server state
            Set<Long> stored = new HashSet<>(); List<long[]> serverLog = new ArrayList<>();       // {msgId, serverTs, seq}
            Map<Long, Msg> byId = new HashMap<>(); int[] nextClientSeq = new int[2]; Map<Integer, Msg>[] held = new Map[]{new HashMap<>(), new HashMap<>()};
            long seq = 0;
            // phone state: what each phone has seen (msgId -> sort key), plus the latest seen message id for replies
            List<Map.Entry<Long, Long>>[] view = new List[]{new ArrayList<>(), new ArrayList<>()};
            long[] latestSeen = {-1, -1}; long[] lastSeenSeq = {0, 0};
            // generate sends
            int[] cSeq = new int[2]; long idGen = conv * 1000L;
            List<long[]> sendTimes = new ArrayList<>();
            for (int p = 0; p < 2; p++) for (long t = (long) (rnd.nextDouble() * 20_000); t < 600_000; t += 3_000 + (long) (-Math.log(1 - rnd.nextDouble()) * 27_000)) sendTimes.add(new long[]{t, p});
            sendTimes.sort(Comparator.comparingLong(a -> a[0]));
            int si = 0;
            while (si < sendTimes.size() || !q.isEmpty()) {
                long nextSend = si < sendTimes.size() ? sendTimes.get(si)[0] : Long.MAX_VALUE;
                if (q.isEmpty() || nextSend <= q.peek().t()) {
                    long t = nextSend; int p = (int) sendTimes.get(si)[1]; si++;
                    Msg m = new Msg(conv, p, cSeq[p]++, idGen++, t + skew[p], latestSeen[p]);
                    view[p].add(Map.entry(m.id(), design == 0 ? m.clientTs() : design == 1 ? t : -1L));   // local echo
                    byId.put(m.id(), m); total++;
                    // attempts until one reaches the server; ack loss causes an extra delivered copy
                    long at = t;
                    while (true) {
                        double x = rnd.nextDouble(); long lat = latency(rnd);
                        if (x < 0.05) { at += 3_000; continue; }                                  // request lost → retry
                        q.add(new Ev(at + lat, 0, m, -1));
                        if (x < 0.08) { at += 3_000; q.add(new Ev(at + latency(rnd), 0, m, -1)); }   // ack lost → duplicate send
                        break;
                    }
                    continue;
                }
                Ev e = q.poll();
                if (e.type() == 0) {                                                              // server receives
                    Msg m = e.m();
                    if (design >= 1 && stored.contains(m.id())) continue;
                    if (design == 2) {
                        if (m.clientSeq() != nextClientSeq[m.sender()]) { if (m.clientSeq() > nextClientSeq[m.sender()]) held[m.sender()].put(m.clientSeq(), m); continue; }
                        Msg cur = m;
                        while (cur != null) {
                            stored.add(cur.id()); seq++; serverLog.add(new long[]{cur.id(), e.t(), seq}); nextClientSeq[cur.sender()]++;
                            q.add(new Ev(e.t() + latency(rnd), 1, cur, 1 - cur.sender()));
                            cur = held[cur.sender()].remove(nextClientSeq[cur.sender()]);
                        }
                    } else {
                        stored.add(m.id()); seq++; serverLog.add(new long[]{m.id(), e.t(), seq});
                        q.add(new Ev(e.t() + latency(rnd), 1, m, 1 - m.sender()));
                    }
                } else {                                                                          // push arrives at the other phone
                    if (rnd.nextDouble() < 0.05) continue;                                        // dropped push
                    Msg m = e.m(); int p = e.target();
                    long key = design == 0 ? m.clientTs() : design == 1 ? serverTs(serverLog, m.id()) : seqOf(serverLog, m.id());
                    view[p].add(Map.entry(m.id(), key)); latestSeen[p] = m.id();
                }
            }
            // design C: each phone reconciles by sequence — own messages get their seq; gaps fetched from the server
            for (int p = 0; p < 2; p++) {
                List<Map.Entry<Long, Long>> v = view[p];
                if (design == 2) {
                    Set<Long> have = new HashSet<>(); List<Map.Entry<Long, Long>> nv = new ArrayList<>();
                    for (long[] row : serverLog) { nv.add(Map.entry(row[0], row[2])); }                  // gap fetch fills everything stored
                    view[p] = nv; v = nv;
                }
                v.sort(Comparator.comparingLong(Map.Entry::getValue));
                Map<Long, Integer> pos = new HashMap<>(); Map<Long, Integer> count = new HashMap<>();
                for (int i = 0; i < v.size(); i++) { long id = v.get(i).getKey(); count.merge(id, 1, Integer::sum); pos.putIfAbsent(id, i); }
                for (int c : count.values()) if (c > 1) dups += c - 1;
                for (long id : stored) if (!count.containsKey(id)) missing++;
                int[] lastOwn = {-1, -1};
                for (Map.Entry<Long, Long> en : v) {
                    Msg m = byId.get(en.getKey());
                    if (m.parent() >= 0 && pos.containsKey(m.parent()) && pos.get(m.parent()) > pos.get(m.id())) replyFirst++;
                    if (m.clientSeq() < lastOwn[m.sender()]) ownInv++; else lastOwn[m.sender()] = m.clientSeq();
                }
            }
        }
        System.out.printf("   %-58s %,11d %,9d %,17d %,17d%n", name, dups, missing, replyFirst, ownInv);
    }
    static long latency(Random r) { return 80 + (long) (-Math.log(1 - r.nextDouble()) * 300) + (r.nextDouble() < 0.10 ? 2_000 + r.nextInt(6_000) : 0); }
    static long serverTs(List<long[]> log, long id) { for (long[] r : log) if (r[0] == id) return r[1]; return Long.MAX_VALUE; }
    static long seqOf(List<long[]> log, long id) { for (long[] r : log) if (r[0] == id) return r[2]; return Long.MAX_VALUE; }
}
