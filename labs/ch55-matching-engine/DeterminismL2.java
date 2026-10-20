// DeterminismL2.java — run with: java -Xmx2g DeterminismL2.java   (JDK 21, no dependencies)
// WHY MATCHING ENGINES ARE SINGLE-THREADED BEHIND A SEQUENCER. 4 gateway threads each receive 250,000 orders (limit /
// cancel / market, fixed seed) and pass them to the engine. The same inputs are processed 3 times per design:
//   D1 the gateways call the shared order book directly, under a lock (the "obvious" multi-threaded design)
//   D2 the gateways hand orders to a SEQUENCER that stamps a global sequence number and appends them to a journal
//      file; ONE matcher thread consumes the journal in order. Then: (a) a standby replica replays the same journal,
//      (b) after a simulated crash, recovery replays the journal into a fresh book
// Measured: distinct trade streams across the 3 runs of the same input (by hash), whether a replica / recovery
// reproduces the live engine exactly, and throughput.
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class DeterminismL2 {
    static final int TICK = 5, BASE = 100_000, BAND = 4_000, GATEWAYS = 4, PER = 250_000;
    record Ev(int type, long id, boolean buy, int price, int qty, long cancelId) {}

    public static void main(String[] args) throws Exception {
        Ev[][] input = new Ev[GATEWAYS][PER]; Random r = new Random(55); long nextId = 1;
        for (int g = 0; g < GATEWAYS; g++) { List<Long> live = new ArrayList<>(); double mid = BASE;
            for (int i = 0; i < PER; i++) { mid += r.nextGaussian() * 2; double x = r.nextDouble();
                if (x < 0.30 && !live.isEmpty()) { int k = r.nextInt(live.size()); long cid = live.get(k); live.set(k, live.get(live.size() - 1)); live.remove(live.size() - 1); input[g][i] = new Ev(1, 0, false, 0, 0, cid); }
                else if (x < 0.40) input[g][i] = new Ev(2, nextId++, r.nextBoolean(), 0, 1 + r.nextInt(20), 0);
                else { boolean buy = r.nextBoolean(); int off = (int) Math.abs(r.nextGaussian() * 15) * TICK; int p = (int) (Math.round(mid / TICK) * TICK) + (buy ? -off : off);
                    long id = nextId++; input[g][i] = new Ev(0, id, buy, p, 1 + r.nextInt(50), 0); live.add(id); } } }
        System.out.println("4 gateways × 250,000 orders; each design runs the same input 3 times.\n");
        System.out.printf("   %-52s %22s %24s %14s%n", "design", "distinct trade streams", "replica / recovery", "orders/s");
        System.out.printf("   %-52s %22s %24s %14s%n", "", "(of 3 runs)", "identical to live?", "");
        // D1
        Set<Long> hashes = new HashSet<>(); double rate = 0;
        for (int run = 0; run < 3; run++) { LadderBook book = new LadderBook(); long t0 = System.nanoTime(); ExecutorService ex = Executors.newFixedThreadPool(GATEWAYS); List<Future<?>> fs = new ArrayList<>();
            for (int g = 0; g < GATEWAYS; g++) { final int gg = g; fs.add(ex.submit(() -> { for (Ev e : input[gg]) synchronized (book) { apply(book, e); } })); }
            for (Future<?> f : fs) f.get(); ex.shutdown(); rate = GATEWAYS * PER / ((System.nanoTime() - t0) / 1e9); hashes.add(book.hash()); }
        System.out.printf("   %-52s %22d %24s %,14.0f%n", "D1) gateways call the book directly, under a lock", hashes.size(), "n/a (no journal)", rate);
        // D2
        hashes.clear(); boolean allReplay = true;
        for (int run = 0; run < 3; run++) {
            Path journal = Paths.get("/tmp/melab/journal-" + run + ".bin"); Files.deleteIfExists(journal);
            BlockingQueue<Ev> seqQ = new ArrayBlockingQueue<>(65_536); LadderBook live = new LadderBook(); long t0 = System.nanoTime();
            Thread sequencerAndMatcher = new Thread(() -> {                                     // one thread: sequence → journal → match
                try (FileChannel ch = FileChannel.open(journal, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    ByteBuffer buf = ByteBuffer.allocate(1 << 20); long seq = 0;
                    for (int n = 0; n < GATEWAYS * PER; n++) { Ev e = seqQ.take(); seq++;
                        if (buf.remaining() < 40) { buf.flip(); while (buf.hasRemaining()) ch.write(buf); buf.clear(); }
                        buf.putLong(seq).putInt(e.type()).putLong(e.id()).put((byte) (e.buy() ? 1 : 0)).putInt(e.price()).putInt(e.qty()).putLong(e.cancelId());
                        apply(live, e); }
                    buf.flip(); while (buf.hasRemaining()) ch.write(buf); ch.force(false);
                } catch (Exception ex) { throw new RuntimeException(ex); } });
            sequencerAndMatcher.start();
            ExecutorService ex = Executors.newFixedThreadPool(GATEWAYS); List<Future<?>> fs = new ArrayList<>();
            for (int g = 0; g < GATEWAYS; g++) { final int gg = g; fs.add(ex.submit(() -> { for (Ev e : input[gg]) seqQ.put(e); return null; })); }
            for (Future<?> f : fs) f.get(); ex.shutdown(); sequencerAndMatcher.join(); rate = GATEWAYS * PER / ((System.nanoTime() - t0) / 1e9);
            hashes.add(live.hash());
            LadderBook replica = replay(journal); LadderBook recovered = replay(journal);
            allReplay &= replica.hash() == live.hash() && recovered.hash() == live.hash() && replica.trades == live.trades;
            Files.deleteIfExists(journal);
        }
        System.out.printf("   %-52s %22d %24s %,14.0f%n", "D2) sequencer + journal + single matcher thread", hashes.size(), allReplay ? "yes, 3 of 3" : "NO", rate);
        System.out.println("\n   → both designs see the same orders, but in whatever order the threads happen to interleave, so different");
        System.out.println("     runs produce different trades — that race is real and unavoidable at the gateways. The difference is what");
        System.out.println("     happens next. With threads matching under a lock, the order that decided priority exists nowhere: it can't be");
        System.out.println("     audited, replayed, or reproduced on a standby. A sequencer makes the race's outcome a FACT (a numbered journal),");
        System.out.println("     and a single-threaded, deterministic matcher turns that journal into the same book and the same trades on every");
        System.out.println("     replica and after every crash. Determinism, not parallelism, is the property the design protects.");
    }

    static void apply(LadderBook b, Ev e) { switch (e.type()) { case 0 -> b.limit(e.id(), e.buy(), e.price(), e.qty()); case 1 -> b.cancel(e.cancelId()); default -> b.market(e.id(), e.buy(), e.qty()); } }
    static LadderBook replay(Path journal) throws IOException {
        LadderBook b = new LadderBook(); ByteBuffer all = ByteBuffer.wrap(Files.readAllBytes(journal));
        while (all.remaining() >= 37) { all.getLong(); int type = all.getInt(); long id = all.getLong(); boolean buy = all.get() == 1; int price = all.getInt(); int qty = all.getInt(); long cid = all.getLong(); apply(b, new Ev(type, id, buy, price, qty, cid)); }
        return b;
    }

    static abstract class Book {
        long trades = 0, hash = 1469598103934665603L;
        void trade(long aggressor, long resting, int price, int qty) { trades++; hash = (hash ^ (resting * 31 + price * 17L + qty)) * 1099511628211L; hash ^= aggressor; }
        long trades() { return trades; } long hash() { return hash; }
        abstract void limit(long id, boolean buy, int price, int qty); abstract void cancel(long id); abstract void market(long id, boolean buy, int qty);
    }
    static final class O { long id; boolean buy; int price, qty; O prev, next; Level level; O(long id, boolean buy, int price, int qty) { this.id = id; this.buy = buy; this.price = price; this.qty = qty; } }

    // ---- B3 ----
    static final class Level { O head, tail; int count; }
    static final class LadderBook extends Book {
        final int lo = BASE - BAND * TICK, levels = 2 * BAND + 1; final Level[] bid = new Level[levels], ask = new Level[levels]; int bestBid = -1, bestAsk = levels; final HashMap<Long, O> byId = new HashMap<>();
        { for (int i = 0; i < levels; i++) { bid[i] = new Level(); ask[i] = new Level(); } }
        int idx(int price) { return Math.max(0, Math.min(levels - 1, (price - lo) / TICK)); }
        void limit(long id, boolean buy, int price, int qty) { qty = match(id, buy, buy ? idx(price) : idx(price), qty); if (qty > 0) { O o = new O(id, buy, price, qty); int i = idx(price); Level l = buy ? bid[i] : ask[i]; append(l, o); byId.put(id, o);
            if (buy) bestBid = Math.max(bestBid, i); else bestAsk = Math.min(bestAsk, i); } }
        void market(long id, boolean buy, int qty) { match(id, buy, buy ? levels - 1 : 0, qty); }
        int match(long id, boolean buy, int limitIdx, int qty) {
            while (qty > 0) {
                if (buy) { while (bestAsk < levels && ask[bestAsk].count == 0) bestAsk++; if (bestAsk >= levels || bestAsk > limitIdx) break; }
                else { while (bestBid >= 0 && bid[bestBid].count == 0) bestBid--; if (bestBid < 0 || bestBid < limitIdx) break; }
                Level l = buy ? ask[bestAsk] : bid[bestBid]; O o = l.head; int t = Math.min(qty, o.qty); trade(id, o.id, o.price, t); qty -= t; o.qty -= t;
                if (o.qty == 0) { unlink(o); byId.remove(o.id); }
            }
            return qty;
        }
        void append(Level l, O o) { o.level = l; o.prev = l.tail; o.next = null; if (l.tail == null) l.head = o; else l.tail.next = o; l.tail = o; l.count++; }
        void unlink(O o) { Level l = o.level; if (o.prev == null) l.head = o.next; else o.prev.next = o.next; if (o.next == null) l.tail = o.prev; else o.next.prev = o.prev; l.count--; o.level = null; }
        void cancel(long id) { O o = byId.remove(id); if (o != null && o.level != null) unlink(o); }
    }
}
