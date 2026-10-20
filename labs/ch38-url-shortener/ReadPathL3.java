// ReadPathL3.java — run with: java ReadPathL3.java   (JDK 21, no dependencies)
// THE READ PATH AT SCALE: how big a cache, and what happens when marketing sends 20 million SMS at 7 pm.
// Part 1 replays 10,000,000 clicks over 1,000,000 links with a skewed popularity (Zipf, s = 1.0 — an assumption; measure
// your own) through a real LRU cache (LinkedHashMap in access order) of several sizes and reports the hit ratio, the
// database reads that remain, and the cache's memory at ~200 bytes per entry.
// Part 2 simulates a campaign spike second by second: 20M SMS with one offer link, clicks decaying over 10 minutes on top
// of normal traffic, served by 20 redirect nodes in front of a 6-shard Redis cluster. Every request for the offer code
// hashes to ONE shard. We compare shard load without and with a 1-second in-process near-cache on each node.
import java.util.*;

public class ReadPathL3 {
    public static void main(String[] args) {
        int links = 1_000_000, clicks = 10_000_000; Random rnd = new Random(38);
        double[] cdf = new double[links]; double sum = 0;
        for (int i = 0; i < links; i++) { sum += 1.0 / (i + 1); cdf[i] = sum; }
        int[] perm = new int[links]; for (int i = 0; i < links; i++) perm[i] = i;
        for (int i = links - 1; i > 0; i--) { int j = rnd.nextInt(i + 1); int t = perm[i]; perm[i] = perm[j]; perm[j] = t; }
        int[] stream = new int[clicks];
        for (int i = 0; i < clicks; i++) { int idx = Arrays.binarySearch(cdf, rnd.nextDouble() * sum); stream[i] = perm[idx < 0 ? -idx - 1 : idx]; }
        long distinct = Arrays.stream(stream).distinct().count();

        System.out.printf("Part 1 — %,d clicks over %,d links (Zipf s=1.0); %,d distinct links clicked at least once.%n%n", clicks, links, distinct);
        System.out.printf("   %-18s %12s %11s %20s %18s%n", "LRU cache size", "% of links", "hit ratio", "DB reads remaining", "cache memory");
        for (int size : new int[]{1_000, 10_000, 50_000, 100_000, 250_000}) {
            LinkedHashMap<Integer, Boolean> lru = new LinkedHashMap<>(size * 2, 0.75f, true) {
                protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> e) { return size() > size; } };
            long hits = 0;
            for (int k : stream) { if (lru.get(k) != null) hits++; else lru.put(k, Boolean.TRUE); }
            System.out.printf("   %,18d %11.1f%% %10.1f%% %,20d %15.0f MB%n", size, 100.0 * size / links, 100.0 * hits / clicks, clicks - hits, size * 200 / 1e6);
        }

        // ---------------- Part 2: the campaign spike ----------------
        int nodes = 20, shards = 6, seconds = 600; double baseRps = 3_000;           // normal redirect traffic
        double totalCampaignClicks = 20_000_000 * 0.12;                              // 12% click-through (assumption)
        double tau = 90;                                                              // clicks decay with a 90 s time constant
        int hotShard = 2;
        long[][] shardLoad = new long[2][shards]; long[] peakHot = new long[2]; long[] peakOther = new long[2]; long peakRps = 0;
        for (int s = 0; s < seconds; s++) {
            double campaign = totalCampaignClicks / tau * Math.exp(-s / tau);
            long hot = Math.round(campaign), normal = Math.round(baseRps * (0.9 + 0.2 * rnd.nextDouble()));
            peakRps = Math.max(peakRps, hot + normal);
            for (int mode = 0; mode < 2; mode++) {
                long[] load = new long[shards];
                for (int k = 0; k < shards; k++) load[k] = normal / shards;              // background spread evenly (misses of L1)
                load[hotShard] += mode == 0 ? hot : Math.min(hot, nodes);               // near-cache: ≤ 1 miss per node per second
                for (int k = 0; k < shards; k++) shardLoad[mode][k] += load[k];
                peakHot[mode] = Math.max(peakHot[mode], load[hotShard]);
                long other = 0; for (int k = 0; k < shards; k++) if (k != hotShard) other = Math.max(other, load[k]);
                peakOther[mode] = Math.max(peakOther[mode], other);
            }
        }
        System.out.printf("%nPart 2 — campaign: 20M SMS × 12%% click-through = %,.0f clicks on ONE code, decaying over 10 min; peak %,d redirects/s.%n",
                totalCampaignClicks, peakRps);
        System.out.println("   Redis cluster: 6 shards. A single Redis shard comfortably serves ~100k simple GETs/s (VERIFY on your hardware).\n");
        System.out.printf("   %-44s %22s %20s %16s%n", "", "peak on HOT shard /s", "peak other shard /s", "hot / other");
        String[] label = {"no near-cache (every click → Redis)", "1 s near-cache on each of 20 nodes"};
        for (int m = 0; m < 2; m++)
            System.out.printf("   %-44s %,22d %,20d %15.0fx%n", label[m], peakHot[m], peakOther[m], (double) peakHot[m] / peakOther[m]);

        double saturatingBlast = 20_000_000.0 * 100_000 / peakHot[0];
        System.out.printf("%n   At this blast the hot shard runs at ~%.0f%% of one shard's capacity — fine today. But its load scales with the%n", 100.0 * peakHot[0] / 100_000);
        System.out.printf("   SIZE OF THE BLAST, not the cluster: a ~%,.0fM-SMS festival campaign saturates it, and adding shards changes nothing.%n", saturatingBlast / 1e6);
        System.out.println("\n   → the cache curve flattens fast: 1% of links as cache serves 58% of clicks, 10x more memory only gets to 78%,");
        System.out.println("     and the long tail of cold links always reaches the database — size the database for the tail, the cache for");
        System.out.println("     the head. The campaign is a different problem: sharding spreads KEYS, not load, so every click on the offer");
        System.out.println("     code lands on one shard at ~49x its peers' load. A near-cache of even one second on each node caps it at one");
        System.out.println("     lookup per node per second; for a scheduled campaign, pre-warm the code on every node before the blast.");
    }
}
