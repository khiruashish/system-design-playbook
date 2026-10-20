// QueueVsLogL1.java — run with: java QueueVsLogL1.java
// The two messaging models side by side: a queue (messages removed when acked) and a log (messages kept, readers keep offsets).
import java.util.*;

public class QueueVsLogL1 {

    /** Queue: competing consumers; an acked message is gone. */
    static final class Queue {
        private final Deque<String> ready = new ArrayDeque<>();
        void publish(String m) { ready.add(m); }
        String receive() { return ready.poll(); }                 // (a real broker keeps it "unacked" until ack/timeout)
        int depth() { return ready.size(); }
    }

    /** Log: append-only; each consumer GROUP has its own offset; retention decides how long records stay. */
    static final class Log {
        private final List<String> records = new ArrayList<>();
        private final Map<String, Integer> offsets = new TreeMap<>();
        private int startOffset = 0;                              // advances when retention deletes old records
        void append(String m) { records.add(m); }
        List<String> poll(String group, int max) {
            int from = Math.max(offsets.getOrDefault(group, 0), startOffset);
            List<String> out = new ArrayList<>(records.subList(from, Math.min(records.size(), from + max)));
            offsets.put(group, from + out.size());                // commit
            return out;
        }
        void seek(String group, int offset) { offsets.put(group, offset); }
        void retainLast(int n) { startOffset = Math.max(startOffset, records.size() - n); }
    }

    public static void main(String[] args) {
        System.out.println("A. Queue: 6 order events, two picking workers compete");
        Queue q = new Queue();
        for (int i = 1; i <= 6; i++) q.publish("ord-" + i);
        List<String> w1 = new ArrayList<>(), w2 = new ArrayList<>();
        for (int i = 0; i < 6; i++) (i % 2 == 0 ? w1 : w2).add(q.receive());
        System.out.println("   worker-1 " + w1 + "  worker-2 " + w2 + "  left in queue: " + q.depth());
        System.out.println("   a notification service subscribing now gets: " + q.receive() + "  (gone: a queue is not a history)");

        System.out.println("B. Log: the same 6 events, several consumer groups");
        Log log = new Log();
        for (int i = 1; i <= 6; i++) log.append("ord-" + i);
        System.out.println("   picking       reads " + log.poll("picking", 10));
        System.out.println("   notifications reads " + log.poll("notifications", 3) + " then " + log.poll("notifications", 10) + "  (own pace)");
        log.append("ord-7");
        System.out.println("   picking       reads " + log.poll("picking", 10) + "  (continues from its offset)");
        log.seek("analytics", 0);
        System.out.println("   analytics, added a month later, replays from offset 0: " + log.poll("analytics", 10));
        log.retainLast(3);
        log.seek("audit", 0);
        System.out.println("   after retention keeps only the last 3, a new 'audit' group from 0 gets: " + log.poll("audit", 10));
    }
}
