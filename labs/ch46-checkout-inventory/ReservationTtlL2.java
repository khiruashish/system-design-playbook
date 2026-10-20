// ReservationTtlL2.java — run with: java ReservationTtlL2.java   (JDK 21, no dependencies)
// HOW LONG DO YOU HOLD STOCK FOR SOMEONE WHO MIGHT PAY? A one-hour flash sale: 10,000 mango boxes, 25,000 customers
// arriving (fixed seed). Checkout reserves one box. 60% of customers go on to pay — median 90 s (switching to the UPI app,
// entering the PIN), 5% take over 10 minutes (retries, bank OTP delays); 40% abandon and never pay. A reservation that
// expires returns its box to stock. A payment that completes after the reservation expired is a LATE CAPTURE: the box
// may already be someone else's, so the customer is charged for nothing and must be refunded (or the order oversold).
//   T30  reservation TTL 30 minutes          T10  TTL 10 minutes          T3  TTL 3 minutes
//   T3+  TTL 3 minutes, extended to 15 minutes the moment the customer actually starts paying (enters the gateway)
// Measured: boxes sold, customers told "sold out" while boxes were sitting in abandoned reservations, late captures
// that had to be refunded, and boxes left unsold at the end.
import java.util.*;

public class ReservationTtlL2 {
    record Customer(double arrive, boolean pays, double payStart, double payDone) {}

    public static void main(String[] args) {
        Random r = new Random(46); List<Customer> cs = new ArrayList<>();
        for (int i = 0; i < 25_000; i++) {
            double arrive = r.nextDouble() * 3600; boolean pays = r.nextDouble() < 0.6;
            double start = arrive + 10 + r.nextDouble() * 40;                                    // time to reach the gateway
            double dur = r.nextDouble() < 0.05 ? 600 + r.nextDouble() * 600 : Math.exp(Math.log(90) + 0.6 * r.nextGaussian());
            cs.add(new Customer(arrive, pays, start, start + dur));
        }
        cs.sort(Comparator.comparingDouble(Customer::arrive));
        System.out.println("Flash sale: 10,000 boxes, 25,000 customers over 1 hour; 60% pay (median 90 s, 5% > 10 min), 40% abandon.\n");
        System.out.printf("   %-52s %8s %22s %16s %14s%n", "reservation policy", "sold", "turned away while stock", "late captures", "unsold at end");
        System.out.printf("   %-52s %8s %22s %16s %14s%n", "", "", "sat in abandoned carts", "refunded", "");
        String[][] ps = {{"T30) hold 30 minutes", "1800", "0"}, {"T10) hold 10 minutes", "600", "0"}, {"T3) hold 3 minutes", "180", "0"},
                {"T3+) hold 3 minutes, extend to 15 when paying starts", "180", "900"}};
        for (String[] p : ps) run(cs, p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]));
        System.out.println("\n   → a long hold protects slow payers and starves everyone else: boxes sit in carts that will never pay while");
        System.out.println("     real buyers are told 'sold out'. A short hold recycles abandoned stock fast, but the slow payers' reservations");
        System.out.println("     expire under them — they get charged for a box that's gone, and must be refunded. Holding briefly and");
        System.out.println("     extending only when there's evidence of intent (the customer actually entered the payment flow) gets most of");
        System.out.println("     both. The TTL is a business policy with a measurable cost on each side; make the expiry and the late-capture");
        System.out.println("     path (auto-refund, or honour it if stock allows) explicit states, not accidents.");
    }

    static void run(List<Customer> cs, String name, double ttl, double extendTo) {
        int stock = 10_000, sold = 0, turnedAwayStranded = 0, lateRefunded = 0;
        // events: reservation expiries and payment completions
        PriorityQueue<double[]> ev = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));   // [time, type(0 expire,1 pay), idx]
        double[] expiresAt = new double[cs.size()]; boolean[] active = new boolean[cs.size()]; int abandonedHeld = 0;
        int ci = 0;
        while (ci < cs.size() || !ev.isEmpty()) {
            double nextArrive = ci < cs.size() ? cs.get(ci).arrive() : Double.MAX_VALUE;
            if (!ev.isEmpty() && ev.peek()[0] <= nextArrive) {
                double[] e = ev.poll(); int idx = (int) e[2]; Customer c = cs.get(idx);
                if (e[1] == 0) {                                                         // expiry check
                    if (active[idx] && expiresAt[idx] <= e[0] + 1e-9) { active[idx] = false; stock++; if (!c.pays()) abandonedHeld--; }
                } else if (e[1] == 2) {                                                  // payment started: extend
                    if (active[idx] && extendTo > 0) { expiresAt[idx] = Math.max(expiresAt[idx], e[0] + extendTo); ev.add(new double[]{expiresAt[idx], 0, idx}); }
                } else {                                                                 // payment completed
                    if (active[idx]) { active[idx] = false; sold++; }
                    else if (stock > 0) { stock--; sold++; }                             // late, but a box happens to be free: honour it
                    else lateRefunded++;
                }
                continue;
            }
            Customer c = cs.get(ci); int idx = ci++;
            if (stock == 0) { if (abandonedHeld > 0) turnedAwayStranded++; continue; }
            stock--; active[idx] = true; expiresAt[idx] = c.arrive() + ttl; ev.add(new double[]{expiresAt[idx], 0, idx});
            if (c.pays()) { ev.add(new double[]{c.payStart(), 2, idx}); ev.add(new double[]{c.payDone(), 1, idx}); } else abandonedHeld++;
        }
        System.out.printf("   %-52s %,8d %,22d %,16d %,14d%n", name, sold, turnedAwayStranded, lateRefunded, stock);
    }
}
