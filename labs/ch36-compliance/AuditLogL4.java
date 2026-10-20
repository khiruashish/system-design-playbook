// AuditLogL4.java — run with: java AuditLogL4.java   (JDK 21, no dependencies)
// A TAMPER-EVIDENT AUDIT LOG. Regulators, auditors and incident responders need to trust "who did what to which record
// when" — refunds issued, PII viewed, roles granted. An audit table in the same database the admins control proves nothing:
// anyone with write access can edit it. Two layers of evidence:
//   1. HASH CHAIN — each entry stores SHA-256(previous hash || entry). Editing, deleting or reordering any entry breaks
//      every hash after it.
//   2. ANCHORED CHECKPOINTS — every 10,000 entries, (sequence number, chain hash) is written to an EXTERNAL write-once store
//      (object storage with Object Lock/WORM, another team's account, or a public timestamping service). Someone who
//      controls the log's database can't change what was already anchored elsewhere.
// We append 100,000 events, then apply the manipulations an insider with database access could make, and verify.
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public class AuditLogL4 {
    record Entry(long seq, String at, String actor, String action, String target, String detail, String prevHash, String hash) {}
    static final int ANCHOR_EVERY = 10_000, TOTAL = 103_500;             // 3,500 entries written since the last anchor

    static String sha(String s) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder(); for (byte x : h) b.append(String.format("%02x", x)); return b.toString();
    }
    static String body(long seq, String at, String actor, String action, String target, String detail) {
        return seq + "|" + at + "|" + actor + "|" + action + "|" + target + "|" + detail;
    }
    static Entry make(long seq, String prev, String at, String actor, String action, String target, String detail) throws Exception {
        return new Entry(seq, at, actor, action, target, detail, prev, sha(prev + "|" + body(seq, at, actor, action, target, detail)));
    }

    // verify: recompute the chain; compare with external anchors. Returns a list of problems found.
    static List<String> verify(List<Entry> log, Map<Long, String> anchors, boolean useAnchors) throws Exception {
        List<String> problems = new ArrayList<>();
        String prev = "GENESIS"; long expectSeq = 1;
        for (Entry e : log) {
            if (e.seq() != expectSeq) { problems.add("sequence gap/reorder at position " + expectSeq + " (found seq " + e.seq() + ")"); break; }
            if (!e.prevHash().equals(prev)) { problems.add("broken link at seq " + e.seq()); break; }
            String h = sha(prev + "|" + body(e.seq(), e.at(), e.actor(), e.action(), e.target(), e.detail()));
            if (!h.equals(e.hash())) { problems.add("entry " + e.seq() + " content does not match its hash"); break; }
            prev = e.hash(); expectSeq++;
        }
        if (useAnchors) {
            Map<Long, String> bySeq = new HashMap<>(); for (Entry e : log) bySeq.put(e.seq(), e.hash());
            for (var a : new TreeMap<>(anchors).entrySet()) {
                String h = bySeq.get(a.getKey());
                if (h == null) { problems.add("anchored entry " + a.getKey() + " is missing (log truncated or rewritten)"); break; }
                if (!h.equals(a.getValue())) { problems.add("chain hash at anchored seq " + a.getKey() + " differs from the external anchor"); break; }
            }
        }
        return problems;
    }

    public static void main(String[] args) throws Exception {
        String[] actors = {"agent.ravi", "agent.meena", "ops.kiran", "admin.suresh", "svc.refunds"};
        String[] actions = {"VIEW_PII", "ISSUE_REFUND", "EDIT_ADDRESS", "GRANT_ROLE", "EXPORT_ORDERS"};
        Random r = new Random(9);
        List<Entry> log = new ArrayList<>(); Map<Long, String> anchors = new HashMap<>();
        String prev = "GENESIS"; long t0 = System.nanoTime();
        for (long seq = 1; seq <= TOTAL; seq++) {
            int a = r.nextInt(actions.length);
            String detail = actions[a].equals("ISSUE_REFUND") ? "amountPaise=" + (500 + r.nextInt(50_000)) : actions[a].equals("GRANT_ROLE") ? "role=support-l1" : "-";
            Entry e = make(seq, prev, String.format("2026-09-%02dT%02d:%02d:%02dZ", 1 + (int) (seq / 4000), r.nextInt(24), r.nextInt(60), r.nextInt(60)),
                    actors[r.nextInt(actors.length)], actions[a], "ORD" + (50_000_000 + r.nextInt(900_000)), detail);
            log.add(e); prev = e.hash();
            if (seq % ANCHOR_EVERY == 0) anchors.put(seq, e.hash());               // written to external WORM storage
        }
        double appendMs = (System.nanoTime() - t0) / 1e6;
        long v0 = System.nanoTime(); List<String> clean = verify(log, anchors, true); double verifyMs = (System.nanoTime() - v0) / 1e6;
        System.out.printf("Appended %,d audit events in %.0f ms (%,.0f/s); full verification in %.0f ms. %d external anchors (last at seq 100,000).%n",
                TOTAL, appendMs, TOTAL / (appendMs / 1000), verifyMs, anchors.size());
        System.out.println("Clean log: " + (clean.isEmpty() ? "VERIFIED" : clean) + "\n");

        // an insider with write access to the audit table tries to hide refund fraud at seq 42,137
        int target = 42_000; while (!log.get(target).action().equals("ISSUE_REFUND")) target++;      // first refund after seq 42,000
        System.out.printf("   Insider cover-up of entry %,d (%s by %s, %s):%n%n", log.get(target).seq(), log.get(target).action(), log.get(target).actor(), log.get(target).detail());
        System.out.printf("   %-58s %-22s %s%n", "manipulation", "hash chain alone", "chain + external anchors");

        List<Entry> m1 = new ArrayList<>(log); Entry o = m1.get(target);
        m1.set(target, new Entry(o.seq(), o.at(), "svc.refunds", o.action(), o.target(), o.detail(), o.prevHash(), o.hash()));
        row("1. change the actor on the entry", m1, anchors);

        List<Entry> m2 = new ArrayList<>(log); m2.remove(target);
        row("2. delete the entry", m2, anchors);

        List<Entry> m3 = new ArrayList<>(log); Collections.swap(m3, target, target + 1);
        row("3. swap it with the next entry (reorder)", m3, anchors);

        List<Entry> m4 = rewrite(new ArrayList<>(log.subList(0, target)), log.subList(target + 1, log.size()));
        row("4. delete it AND recompute every later hash", m4, anchors);

        List<Entry> m5 = new ArrayList<>(log.subList(0, 100_000));
        row("5. delete the 3,500 entries written since the last anchor", m5, anchors);

        System.out.println("\n   → the hash chain alone catches the clumsy edits (1–3): any change breaks every later hash. But an insider who controls");
        System.out.println("     the whole table can delete the entry and recompute the rest of the chain (4) — internally consistent, undetectable");
        System.out.println("     from inside. External anchors defeat that: the chain hash at seq 50,000 no longer matches what was written to");
        System.out.println("     write-once storage outside the insider's control. Neither catches (5), removing entries written since the last");
        System.out.println("     anchor: anchor often (or stream every entry to the external store) so that window is seconds, not hours. Keep the");
        System.out.println("     anchor store in a separate account with separate admins, and alert on any verification failure.");
    }

    static List<Entry> rewrite(List<Entry> head, List<Entry> tail) throws Exception {
        String prev = head.get(head.size() - 1).hash(); long seq = head.get(head.size() - 1).seq() + 1;
        for (Entry e : tail) { Entry n = make(seq++, prev, e.at(), e.actor(), e.action(), e.target(), e.detail()); head.add(n); prev = n.hash(); }
        return head;
    }
    static void row(String label, List<Entry> log, Map<Long, String> anchors) throws Exception {
        List<String> a = verify(log, anchors, false), b = verify(log, anchors, true);
        System.out.printf("   %-58s %-22s %s%n", label, a.isEmpty() ? "NOT detected" : "DETECTED", b.isEmpty() ? "NOT detected" : "DETECTED — " + b.get(b.size() - 1));
    }
}
