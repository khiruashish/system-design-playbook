// MarketDataL4.java — run with: java MarketDataL4.java   (JDK 21, no dependencies)
// PUBLISHING THE BOOK. The engine emits 500,000 incremental updates ("price level P now has size S", 20 bytes each) for
// one produce contract over ~5 minutes (fixed seed). 200 subscribers (buyer apps, seller dashboards, risk) receive them
// over a multicast-style feed with loss bursts: ~50 bursts per subscriber of 1–20 messages (~0.1% of messages lost). Each
// subscriber keeps its own copy of the book. Three feed designs:
//   M1 deltas only, no sequence numbers — apply whatever arrives
//   M2 deltas with sequence numbers; on a gap, mark the book stale and fetch the missing messages from a retransmit
//      service (TCP, ~20 ms), then continue
//   M3 deltas with sequence numbers + a full snapshot of the book every second; on a gap, drop the book and wait for
//      the next snapshot
// Measured: subscribers whose book is wrong at the end, share of subscriber-time spent with a wrong book, share spent
// knowingly stale (gap detected, recovering), and bytes sent per subscriber.
import java.util.*;

public class MarketDataL4 {
    public static void main(String[] args) {
        int U = 500_000, SUBS = 200, LEVELS = 400; Random r = new Random(55);
        int[] lvl = new int[U], size = new int[U]; double[] t = new double[U];
        for (int i = 0; i < U; i++) { t[i] = i * 0.0006; lvl[i] = (int) Math.min(LEVELS - 1, Math.abs(r.nextGaussian() * 40)); size[i] = r.nextDouble() < 0.15 ? 0 : 1 + r.nextInt(500); }
        System.out.println("500,000 book updates in ~5 min to 200 subscribers; ~0.1% loss per subscriber in bursts of 1–20.\n");
        System.out.printf("   %-58s %14s %14s %14s %14s%n", "feed design", "books wrong", "time with a", "time knowingly", "MB sent per");
        System.out.printf("   %-58s %14s %14s %14s %14s%n", "", "at the end", "WRONG book", "stale", "subscriber");
        String[] names = {"M1) deltas only, no sequence numbers", "M2) sequenced deltas + retransmit on gap", "M3) sequenced deltas + 1 s snapshots, resync on gap"};
        for (int m = 0; m < 3; m++) {
            Random loss = new Random(99); int wrongEnd = 0; double wrongTime = 0, staleTime = 0; double snapshotBytes = 0;
            int[] truth = new int[LEVELS];
            // precompute truth trajectory checkpoints lazily per subscriber (cheap: replay)
            for (int s = 0; s < SUBS; s++) {
                int[] book = new int[LEVELS]; Arrays.fill(truth, 0); int diff = 0; boolean stale = false; double staleSince = 0, wrongSince = -1; double recoverAt = -1; int burst = 0; double nextSnap = 1.0;
                for (int i = 0; i < U; i++) {
                    // snapshot boundary (M3)
                    if (m == 2 && t[i] >= nextSnap) { if (stale) { System.arraycopy(truth, 0, book, 0, LEVELS); diff = 0; stale = false; staleTime += t[i] - staleSince; } nextSnap += 1.0; if (s == 0) snapshotBytes += LEVELS * 12; }
                    if (m == 1 && stale && t[i] >= recoverAt) { System.arraycopy(truth, 0, book, 0, LEVELS); diff = 0; stale = false; staleTime += t[i] - staleSince; }
                    int L = lvl[i]; boolean wasDiff = book[L] != truth[L]; truth[L] = size[i];
                    boolean lost = burst > 0 || loss.nextDouble() < 0.0001; if (burst > 0) burst--; else if (lost) burst = loss.nextInt(20);
                    if (!lost && !stale) book[L] = size[i];
                    if (lost && m >= 1 && !stale) { stale = true; staleSince = t[i]; recoverAt = t[i] + 0.020; }
                    boolean isDiff = book[L] != truth[L]; if (wasDiff != isDiff) diff += isDiff ? 1 : -1;
                    boolean wrongNow = diff > 0 && !stale;                                           // wrong AND the subscriber doesn't know it
                    if (wrongNow && wrongSince < 0) wrongSince = t[i]; if (!wrongNow && wrongSince >= 0) { wrongTime += t[i] - wrongSince; wrongSince = -1; }
                }
                double end = t[U - 1];
                if (wrongSince >= 0) wrongTime += end - wrongSince; if (stale) staleTime += end - staleSince;
                if (diff > 0 && !stale) wrongEnd++;
            }
            double total = SUBS * t[U - 1]; double mb = (U * 20.0 + (m == 2 ? snapshotBytes : 0) + (m == 1 ? 0.0005 * U * 10 * 20 : 0)) / 1048576;
            System.out.printf("   %-58s %14d %13.2f%% %13.3f%% %12.1f MB%n", names[m], wrongEnd, 100 * wrongTime / total, 100 * staleTime / total, mb);
        }
        System.out.println("\n   → an incremental feed without sequence numbers can't know it missed something: one lost update leaves a price");
        System.out.println("     level wrong until that level happens to change again, and nobody notices. Sequence numbers turn silent");
        System.out.println("     corruption into a detected gap. Retransmission repairs it in milliseconds; snapshots repair it by the next");
        System.out.println("     snapshot (up to a second of 'stale — don't trade on this') at the cost of bandwidth. Real feeds combine");
        System.out.println("     both: sequenced multicast deltas, a retransmit service for small gaps, and snapshots for late joiners and");
        System.out.println("     big gaps.");
    }
}
