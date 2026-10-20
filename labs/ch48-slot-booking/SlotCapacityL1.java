// SlotCapacityL1.java — run with: java SlotCapacityL1.java   (JDK 21, no dependencies)
// WHAT DOES "THIS SLOT IS FULL" MEAN? One delivery zone, 30 days, 16 one-hour slots a day (7 am – 11 pm), fixed seed.
// The rider roster varies by hour: 6 riders early morning, 8 through the day, 14 in the 5–9 pm peak, 8 late.
// Each order costs rider-minutes: ride out and back (0.5–4 km, roads 1.4x the straight line, 18 km/h), plus the door
// (1–2 min for a house, 4–9 for a gated tower), plus 3 min for a bulky order (25% of orders). Customers pick their
// preferred slot (evenings are popular); if it's shown as full, 50% pick the next free slot within 2 hours, the rest leave.
// Orders beyond a slot's real rider-minutes are delivered late (they spill past the slot window).
//   C1 cap each slot at 40 orders (the number ops set years ago)
//   C2 cap each slot at 28 orders (ops lowered it after complaints)
//   C3 cap per slot = the roster's rider-minutes; each booking deducts its ESTIMATED minutes (distance + building door
//      history + bulky flag), stop at 95%
//   C4 C3, but the estimate also knows each slot's traffic (evening rides are 35% slower)
// Measured over 30 days: orders booked, customers lost, orders delivered late, and rider utilisation.
import java.util.*;

public class SlotCapacityL1 {
    static final int SLOTS = 16, DAYS = 30;
    static final int[] RIDERS = {6, 6, 6, 8, 8, 8, 8, 8, 8, 8, 14, 14, 14, 14, 8, 8};              // 7,8,...,22 h
    static final double[] PREF = {2, 4, 5, 5, 6, 7, 6, 5, 5, 7, 12, 16, 15, 11, 6, 3};             // preferred-slot weights
    record Order(int pref, double km, boolean gated, boolean bulky, double doorActual, double noise) {}

    static double slowdown(int s) { int h = 7 + s; return h >= 17 && h <= 20 ? 1.35 : 1.0; }
    static double actualMinutes(Order o, int s) { return (2 * 1.4 * o.km() / 0.3) * slowdown(s) * o.noise() + o.doorActual() + (o.bulky() ? 3 : 0); }
    static double estMinutes(Order o, int s, boolean traffic) { return 2 * 1.4 * o.km() / 0.3 * (traffic ? slowdown(s) : 1.0) + (o.gated() ? 6.5 : 1.5) + (o.bulky() ? 3 : 0); }

    public static void main(String[] args) {
        System.out.println("One zone, 30 days, 16 hourly slots; roster 6 / 8 / 14 (5–9 pm) / 8 riders; ~320 orders wanted per day.\n");
        System.out.printf("   %-56s %9s %9s %10s %12s %12s%n", "slot capacity rule", "booked", "lost", "late", "on time", "rider util.");
        String[] names = {"C1) 40 orders per slot", "C2) 28 orders per slot", "C3) rider-minutes from roster, est. minutes per order",
                "C4) C3 + estimate knows evening traffic"};
        for (int p = 0; p < 4; p++) run(p, names[p]);
        System.out.println("\n   → riders are the bottleneck at peak, so no capacity rule delivers many more orders on time (~6,900–7,050 in all");
        System.out.println("     four). What the rule decides is WHEN the customer hears the truth: an order count accepts evening towers it");
        System.out.println("     can't deliver (26% late under C1), while rider-minutes from the roster, with each booking deducting its own");
        System.out.println("     estimated cost, says 'full' at booking time instead (late: 2,444 → 21). The estimate must know what makes an");
        System.out.println("     order expensive, including evening traffic (C3 → C4). Winning back the lost customers is a demand problem (L4).");
    }

    static void run(int policy, String name) {
        Random r = new Random(48); long booked = 0, lost = 0, late = 0; double used = 0, avail = 0;
        double prefSum = Arrays.stream(PREF).sum();
        for (int d = 0; d < DAYS; d++) {
            int n = 270 + r.nextInt(100);
            double[] estUsed = new double[SLOTS], realUsed = new double[SLOTS]; int[] count = new int[SLOTS];
            for (int i = 0; i < n; i++) {
                double x = r.nextDouble() * prefSum; int pref = 0; while (x > PREF[pref]) { x -= PREF[pref]; pref++; }
                boolean gated = r.nextDouble() < 0.3;
                Order o = new Order(pref, 0.5 + 3.5 * Math.sqrt(r.nextDouble()), gated, r.nextDouble() < 0.25,
                        gated ? 4 + 5 * r.nextDouble() : 1 + r.nextDouble(), Math.exp(0.15 * r.nextGaussian()));
                boolean second = r.nextDouble() < 0.5; int chosen = -1;
                for (int s = pref; s <= Math.min(SLOTS - 1, pref + (second ? 2 : 0)); s++) {
                    boolean ok = switch (policy) {
                        case 0 -> count[s] < 40;
                        case 1 -> count[s] < 28;
                        default -> estUsed[s] + estMinutes(o, s, policy == 3) <= 0.95 * RIDERS[s] * 60;
                    };
                    if (ok) { chosen = s; break; }
                }
                if (chosen < 0) { lost++; continue; }
                booked++; count[chosen]++; estUsed[chosen] += estMinutes(o, chosen, policy == 3);
                double m = actualMinutes(o, chosen);
                if (realUsed[chosen] + m > RIDERS[chosen] * 60) late++;
                realUsed[chosen] += m;
            }
            for (int s = 0; s < SLOTS; s++) { used += Math.min(realUsed[s], RIDERS[s] * 60); avail += RIDERS[s] * 60; }
        }
        System.out.printf("   %-56s %,9d %,9d %,10d %,12d %11.1f%%%n", name, booked, lost, late, booked - late, 100 * used / avail);
    }
}
