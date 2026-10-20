// KafkaClientsL2.java — run with: java -cp kafka-clients-3.9.0.jar:slf4j-api-1.7.36.jar KafkaClientsL2.java
// Uses the REAL Kafka client library (no broker needed): the default partitioner's murmur2 hashing and the real
// consumer-group assignors, to answer design questions before you create a topic.
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor.*;
import org.apache.kafka.common.*;
import org.apache.kafka.common.utils.Utils;

public class KafkaClientsL2 {

    /** Exactly what the producer does for a record WITH a key: murmur2(keyBytes) → positive → mod partitions. */
    static int partitionFor(String key, int partitions) {
        return Utils.toPositive(Utils.murmur2(key.getBytes(StandardCharsets.UTF_8))) % partitions;
    }

    static Cluster cluster(String topic, int partitions) {
        Node n = new Node(0, "localhost", 9092);
        List<PartitionInfo> ps = IntStream.range(0, partitions).mapToObj(p -> new PartitionInfo(topic, p, n, new Node[]{n}, new Node[]{n})).toList();
        return new Cluster("c", List.of(n), ps, Set.of(), Set.of());
    }

    static Map<String, List<TopicPartition>> assign(ConsumerPartitionAssignor a, Cluster c, List<String> members,
                                                    Map<String, List<TopicPartition>> owned, int generation) {
        Map<String, Subscription> subs = new TreeMap<>();
        for (String m : members) subs.put(m, new Subscription(List.of("order.order.placed.v1"),
                a instanceof CooperativeStickyAssignor cs ? cs.subscriptionUserData(Set.of("order.order.placed.v1")) : null,
                owned.getOrDefault(m, List.of()), generation, Optional.empty()));
        GroupAssignment ga = a.assign(c, new GroupSubscription(subs));
        Map<String, List<TopicPartition>> out = new TreeMap<>();
        ga.groupAssignment().forEach((m, as) -> out.put(m, as.partitions().stream().sorted(Comparator.comparingInt(TopicPartition::partition)).toList()));
        return out;
    }

    static String show(Map<String, List<TopicPartition>> a) {
        return a.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().stream().map(tp -> "" + tp.partition()).collect(Collectors.joining(",", "[", "]"))).collect(Collectors.joining(" "));
    }

    public static void main(String[] args) {
        String topic = "order.order.placed.v1";

        System.out.println("A. Keyed records: every event of one order lands in one partition (per-key order)");
        for (String k : List.of("ord-1001", "ord-1002", "ord-1003")) System.out.printf("   key %s → partition %d of 12%n", k, partitionFor(k, 12));

        System.out.println("B. Adding partitions later moves keys: order during the change is no longer guaranteed");
        int moved = 0, n = 100_000;
        for (int i = 0; i < n; i++) if (partitionFor("ord-" + i, 12) != partitionFor("ord-" + i, 24)) moved++;
        System.out.printf("   12 → 24 partitions: %.1f %% of keys map to a different partition%n", 100.0 * moved / n);

        System.out.println("C. Key choice and skew: key by store_id, one mega store has 5 % of orders, 24 partitions");
        Random r = new Random(5); int[] load = new int[24];
        for (int i = 0; i < 200_000; i++) load[partitionFor(r.nextDouble() < 0.05 ? "store-MEGA" : "store-" + r.nextInt(500), 24)]++;
        System.out.printf("   busiest partition %.2fx the average → one consumer in the group is the bottleneck%n", Arrays.stream(load).max().getAsInt() / (200_000 / 24.0));

        System.out.println("D. Consumer group: 12 partitions, 3 consumers, then a 4th joins (the real assignors)");
        Cluster c12 = cluster(topic, 12);
        for (ConsumerPartitionAssignor a : List.of(new RangeAssignor(), new RoundRobinAssignor(), new CooperativeStickyAssignor())) {
            var before = assign(a, c12, List.of("c1", "c2", "c3"), Map.of(), 1);
            var after = assign(a, c12, List.of("c1", "c2", "c3", "c4"), before, 2);
            Map<Integer, String> ownerBefore = new HashMap<>(), ownerAfter = new HashMap<>();
            before.forEach((m, l) -> l.forEach(tp -> ownerBefore.put(tp.partition(), m)));
            after.forEach((m, l) -> l.forEach(tp -> ownerAfter.put(tp.partition(), m)));
            long changed = IntStream.range(0, 12).filter(p -> !Objects.equals(ownerBefore.get(p), ownerAfter.get(p))).count();
            System.out.printf("   %-26s before %s%n", a.name(), show(before));
            System.out.printf("   %-26s after  %s   partitions that changed owner: %d%n", "", show(after), changed);
            if (a instanceof CooperativeStickyAssignor) {                         // the follow-up rebalance hands over revoked partitions
                var round2 = assign(a, c12, List.of("c1", "c2", "c3", "c4"), after, 3);
                System.out.printf("   %-26s round2 %s%n", "", show(round2));
            }
        }
        System.out.println("   (eager assignors: every consumer stops and gives up ALL partitions during the rebalance;");
        System.out.println("    cooperative-sticky: consumers keep what they own, and moved partitions are handed over in a second round)");
    }
}
