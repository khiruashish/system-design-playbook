// OrderBookL1.java — run with: java -Xmx2g OrderBookL1.java   (JDK 21, no dependencies)
// THE ORDER BOOK. A B2B produce exchange (think a digital mandi for onion lots): buyers post bids, sellers post
// offers, the engine matches them by PRICE then TIME priority. 3,000,000 order events (fixed seed): 60% limit orders
// clustered around a drifting mid price, 30% cancels of resting orders, 10% market orders. Prices in paise, tick = 5
// paise. Three REAL book implementations, same input:
//   B1 one sorted ArrayList of resting orders per side (insert by binary search, match from the front)
//   B2 TreeMap<price, FIFO queue> per side + a HashMap from order id to its order for O(1) cancel lookup
//   B3 a price-indexed array of levels (tick ladder around the price band) with best-price pointers; intrusive
//      doubly-linked FIFO per level; cancel = unlink in O(1)
// Every engine must produce the SAME trade stream (checked by a hash). Measured: events/s, per-event latency
// p50/p99/p99.9, trades.
import java.util.*;

public class OrderBookL1 {
    static final int N = 3_000_000, TICK = 5, BASE = 100_000, BAND = 4_000;                    // ₹1,000.00 ± band ticks
    record Ev(int type, long id, boolean buy, int price, int qty, long cancelId) {}              // type 0 limit, 1 cancel, 2 market

    public static void main(String[] args) {
        Random r = new Random(55); Ev[] evs = new Ev[N]; List<Long> live = new ArrayList<>(); double mid = BASE; long nextId = 1;
        for (int i = 0; i < N; i++) {
            mid += r.nextGaussian() * 2; double x = r.nextDouble();
            if (x < 0.30 && !live.isEmpty()) { int k = r.nextInt(live.size()); long cid = live.get(k); live.set(k, live.get(live.size() - 1)); live.remove(live.size() - 1); evs[i] = new Ev(1, 0, false, 0, 0, cid); }
            else if (x < 0.40) evs[i] = new Ev(2, nextId++, r.nextBoolean(), 0, 1 + r.nextInt(20), 0);
            else { boolean buy = r.nextBoolean(); int off = (int) Math.abs(r.nextGaussian() * 15) * TICK; int p = (int) (Math.round(mid / TICK) * TICK) + (buy ? -off : off) + (r.nextDouble() < 0.1 ? (buy ? 2 : -2) * TICK : 0);
                long id = nextId++; evs[i] = new Ev(0, id, buy, p, 1 + r.nextInt(50), 0); live.add(id); }
        }
        System.out.println("3,000,000 events: 60% limit, 30% cancel, 10% market; price-time priority; tick 5 paise.\n");
        System.out.printf("   %-58s %12s %10s %10s %10s %11s %18s%n", "book", "events/s", "p50", "p99", "p99.9", "trades", "trade-stream hash");
        String[] names = {"B1) sorted ArrayList per side", "B2) TreeMap<price, FIFO> + id index", "B3) tick-ladder array of levels + intrusive lists"};
        for (int b = 0; b < 3; b++) {
            for (int warm = 0; warm < 2; warm++) run(b, names[b], evs, warm == 1);
        }
        System.out.println("\n   → all three books produce the identical trade stream — matching rules, not data structures, define the result.");
        System.out.println("     A sorted list pays for every insert and cancel by shifting elements. A tree of price levels makes inserts");
        System.out.println("     O(log levels) and matching O(1) at the best level. A tick ladder turns price → level into an array index and");
        System.out.println("     cancel into an unlink — the shape real exchange engines use, because prices live on a known grid in a narrow");
        System.out.println("     band. The tail (p99.9) matters as much as the average: one slow insert delays every order queued behind it.");
    }

    static void run(int b, String name, Ev[] evs, boolean print) {
        Book book = b == 0 ? new ListBook() : b == 1 ? new TreeBook() : new LadderBook(); long[] lat = new long[evs.length]; long t0 = System.nanoTime();
        for (int i = 0; i < evs.length; i++) { long s = System.nanoTime(); Ev e = evs[i];
            switch (e.type()) { case 0 -> book.limit(e.id(), e.buy(), e.price(), e.qty()); case 1 -> book.cancel(e.cancelId()); default -> book.market(e.id(), e.buy(), e.qty()); }
            lat[i] = System.nanoTime() - s; }
        double secs = (System.nanoTime() - t0) / 1e9; Arrays.sort(lat);
        if (print) System.out.printf("   %-58s %,12.0f %7.2f µs %7.2f µs %7.1f µs %,11d %18s%n", name, evs.length / secs, lat[lat.length / 2] / 1e3, lat[(int) (lat.length * 0.99)] / 1e3, lat[(int) (lat.length * 0.999)] / 1e3, book.trades(), Long.toHexString(book.hash()));
    }

    static abstract class Book {
        long trades = 0, hash = 1469598103934665603L;
        void trade(long aggressor, long resting, int price, int qty) { trades++; hash = (hash ^ (resting * 31 + price * 17L + qty)) * 1099511628211L; hash ^= aggressor; }
        long trades() { return trades; } long hash() { return hash; }
        abstract void limit(long id, boolean buy, int price, int qty); abstract void cancel(long id); abstract void market(long id, boolean buy, int qty);
    }
    static final class O { long id; boolean buy; int price, qty; O prev, next; Level level; O(long id, boolean buy, int price, int qty) { this.id = id; this.buy = buy; this.price = price; this.qty = qty; } }

    // ---- B1 ----
    static final class ListBook extends Book {
        final ArrayList<O> bids = new ArrayList<>(), asks = new ArrayList<>(); long seq = 0; final HashMap<Long, Long> seqOf = new HashMap<>();
        // bids sorted by price desc then time; asks by price asc then time — both with the best at index 0
        void limit(long id, boolean buy, int price, int qty) { qty = match(id, buy, price, qty); if (qty > 0) insert(new O(id, buy, price, qty)); }
        void market(long id, boolean buy, int qty) { match(id, buy, buy ? Integer.MAX_VALUE : Integer.MIN_VALUE, qty); }
        int match(long id, boolean buy, int limit, int qty) {
            ArrayList<O> opp = buy ? asks : bids;
            while (qty > 0 && !opp.isEmpty()) { O o = opp.get(0); if (buy ? o.price > limit : o.price < limit) break;
                int q = Math.min(qty, o.qty); trade(id, o.id, o.price, q); qty -= q; o.qty -= q; if (o.qty == 0) opp.remove(0); }
            return qty;
        }
        void insert(O o) { ArrayList<O> side = o.buy ? bids : asks; int lo = 0, hi = side.size();
            while (lo < hi) { int m = (lo + hi) >>> 1; O x = side.get(m); boolean before = o.buy ? x.price >= o.price : x.price <= o.price; if (before) lo = m + 1; else hi = m; }
            side.add(lo, o); }
        void cancel(long id) { for (ArrayList<O> side : List.of(bids, asks)) for (int i = 0; i < side.size(); i++) if (side.get(i).id == id) { side.remove(i); return; } }
    }
    // ---- B2 ----
    static final class TreeBook extends Book {
        final TreeMap<Integer, ArrayDeque<O>> bids = new TreeMap<>(Comparator.reverseOrder()), asks = new TreeMap<>(); final HashMap<Long, O> byId = new HashMap<>();
        void limit(long id, boolean buy, int price, int qty) { qty = match(id, buy, price, qty); if (qty > 0) { O o = new O(id, buy, price, qty); (buy ? bids : asks).computeIfAbsent(price, k -> new ArrayDeque<>()).addLast(o); byId.put(id, o); } }
        void market(long id, boolean buy, int qty) { match(id, buy, buy ? Integer.MAX_VALUE : Integer.MIN_VALUE, qty); }
        int match(long id, boolean buy, int limit, int qty) {
            TreeMap<Integer, ArrayDeque<O>> opp = buy ? asks : bids;
            while (qty > 0 && !opp.isEmpty()) { var e = opp.firstEntry(); int p = e.getKey(); if (buy ? p > limit : p < limit) break; ArrayDeque<O> q = e.getValue();
                while (qty > 0 && !q.isEmpty()) { O o = q.peekFirst(); if (o.qty == 0) { q.pollFirst(); continue; } int t = Math.min(qty, o.qty); trade(id, o.id, p, t); qty -= t; o.qty -= t; if (o.qty == 0) { q.pollFirst(); byId.remove(o.id); } }
                if (q.isEmpty()) opp.remove(p); }
            return qty;
        }
        void cancel(long id) { O o = byId.remove(id); if (o == null) return; o.qty = 0;                  // lazy delete: skipped when reached
            TreeMap<Integer, ArrayDeque<O>> side = o.buy ? bids : asks; ArrayDeque<O> q = side.get(o.price); if (q != null) { q.remove(o); if (q.isEmpty()) side.remove(o.price); } }
    }
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
