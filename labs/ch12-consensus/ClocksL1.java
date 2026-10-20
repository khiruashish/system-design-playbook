// ClocksL1.java — run with: java ClocksL1.java
// A. wall clocks with skew reorder causally related events   B. Lamport clocks keep causal order
// C. vector clocks tell "before" from "concurrent"            D. hybrid logical clocks (HLC): causal AND close to real time
// E. measuring a timeout with the wall clock vs the monotonic clock when NTP steps the clock
import java.time.*;
import java.util.*;

public class ClocksL1 {

    // ---- B. Lamport clock ----
    static final class Lamport {
        private long t;
        long tick() { return ++t; }                                  // local event or send
        long receive(long remote) { t = Math.max(t, remote) + 1; return t; }
    }

    // ---- C. Vector clock ----
    record VClock(Map<String, Integer> v) {
        VClock tick(String node) { var m = new TreeMap<>(v); m.merge(node, 1, Integer::sum); return new VClock(m); }
        VClock merge(VClock o, String node) {
            var m = new TreeMap<>(v); o.v.forEach((k, x) -> m.merge(k, x, Math::max)); m.merge(node, 1, Integer::sum); return new VClock(m);
        }
        boolean leq(VClock o) { return v.entrySet().stream().allMatch(e -> e.getValue() <= o.v.getOrDefault(e.getKey(), 0)); }
        String compare(VClock o) {
            if (equals(o)) return "equal";
            if (leq(o)) return "happened-before";
            if (o.leq(this)) return "happened-after";
            return "CONCURRENT";
        }
        @Override public String toString() { return v.toString(); }
    }

    // ---- D. Hybrid logical clock (Kulkarni et al. 2014) ----
    record Hlc(long wall, int logical) implements Comparable<Hlc> {
        public int compareTo(Hlc o) { return wall != o.wall ? Long.compare(wall, o.wall) : Integer.compare(logical, o.logical); }
        @Override public String toString() { return wall + "." + logical; }
    }
    static final class HlcClock {
        private Hlc last = new Hlc(0, 0);
        private final java.util.function.LongSupplier physical;
        HlcClock(java.util.function.LongSupplier physical) { this.physical = physical; }
        Hlc now() {                                                   // local event or send
            long pt = physical.getAsLong();
            last = pt > last.wall() ? new Hlc(pt, 0) : new Hlc(last.wall(), last.logical() + 1);
            return last;
        }
        Hlc receive(Hlc remote) {
            long pt = physical.getAsLong();
            long w = Math.max(pt, Math.max(last.wall(), remote.wall()));
            int l;
            if (w == last.wall() && w == remote.wall()) l = Math.max(last.logical(), remote.logical()) + 1;
            else if (w == last.wall()) l = last.logical() + 1;
            else if (w == remote.wall()) l = remote.logical() + 1;
            else l = 0;
            last = new Hlc(w, l);
            return last;
        }
    }

    public static void main(String[] args) {
        // Real time runs 1000 → 1003 ms. payment-service's clock is correct; order-service's clock is 90 ms behind.
        long[] realNow = {1000};
        java.util.function.LongSupplier paymentClock = () -> realNow[0];
        java.util.function.LongSupplier orderClock = () -> realNow[0] - 90;

        System.out.println("A. Wall-clock timestamps from two servers (order-service is 90 ms behind)");
        long tPaid = paymentClock.getAsLong();                          // 1. payment captured
        realNow[0] += 3;
        long tShip = orderClock.getAsLong();                            // 2. order-service reacts: PACKING (caused by 1)
        System.out.println("   PAYMENT_CAPTURED @" + tPaid + "  then (because of it) ORDER_PACKING @" + tShip);
        System.out.println("   sorted by timestamp: " + (tShip < tPaid ? "ORDER_PACKING before PAYMENT_CAPTURED   ← effect before cause" : "ok"));

        System.out.println("B. Lamport clocks: the message carries the counter");
        Lamport pay = new Lamport(), ord = new Lamport();
        for (int i = 0; i < 5; i++) ord.tick();                         // order-service had 5 local events earlier
        long lPaid = pay.tick();                                         // payment event, sent to order-service
        long lPack = ord.receive(lPaid);
        long lOther = pay.tick();                                        // unrelated later event on payment-service
        System.out.println("   PAYMENT_CAPTURED L=" + lPaid + "  ORDER_PACKING L=" + lPack + "  (cause < effect, always)");
        System.out.println("   unrelated REFUND_QUOTE on payment L=" + lOther + " < ORDER_PACKING L=" + lPack
                + ", yet neither caused the other: Lamport order can't tell 'concurrent' from 'before'");

        System.out.println("C. Vector clocks on a cart edited from phone and web");
        VClock base = new VClock(Map.of()).tick("server");
        VClock phone = base.merge(base, "phone");                       // phone read base, then edited
        VClock web = base.merge(base, "web");                           // web read base, then edited (no sync)
        VClock merged = phone.merge(web, "server");                     // server merges both
        System.out.println("   base " + base + " vs phone " + phone + " → " + base.compare(phone));
        System.out.println("   phone " + phone + " vs web " + web + " → " + phone.compare(web) + "   ← keep both, merge (Ch 10 OR-Set)");
        System.out.println("   merged " + merged + " vs phone → " + merged.compare(phone));

        System.out.println("D. Hybrid logical clocks with the same 90 ms skew");
        realNow[0] = 1000;
        HlcClock hPay = new HlcClock(paymentClock), hOrd = new HlcClock(orderClock);
        Hlc hPaid = hPay.now();
        realNow[0] += 3;
        Hlc hPack = hOrd.receive(hPaid);
        realNow[0] += 100;
        Hlc hLater = hOrd.now();
        System.out.println("   PAYMENT_CAPTURED " + hPaid + "  ORDER_PACKING " + hPack + "  (cause < effect: " + (hPaid.compareTo(hPack) < 0) + ")");
        System.out.println("   100 ms later on order-service: " + hLater + "  (its physical clock, 1013, has passed the HLC wall part, so logical resets to 0)");

        System.out.println("E. A 2-second timeout while NTP steps the wall clock back 5 s");
        Instant wallStart = Instant.parse("2026-09-26T14:30:00Z");
        long monoStart = 0;
        // 1.2 s pass; during them NTP corrects the wall clock by -5 s
        Instant wallNow = wallStart.plusMillis(1_200).minusSeconds(5);
        long monoNow = monoStart + 1_200_000_000L;
        long wallElapsed = Duration.between(wallStart, wallNow).toMillis();
        long monoElapsed = (monoNow - monoStart) / 1_000_000;
        System.out.println("   elapsed by wall clock (System.currentTimeMillis / Instant.now): " + wallElapsed + " ms → timeout fires in " + (2_000 - wallElapsed) + " ms");
        System.out.println("   elapsed by monotonic clock (System.nanoTime):                  " + monoElapsed + " ms → timeout fires in " + (2_000 - monoElapsed) + " ms");
    }
}
