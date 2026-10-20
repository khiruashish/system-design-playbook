// DispatchL3.java — run with: java DispatchL3.java   (JDK 21, no dependencies)
// WHICH RIDER TAKES WHICH ORDER? A quick-commerce evening peak (fixed seed): 6 dark stores, 10 riders each, orders
// arriving at ~0.55 per minute per store for 2 hours (just above what 10 riders can carry one order at a time),
// customers within 3 km. Packing takes 3–5 minutes; riders ride at
// 18 km/h on roads ~1.4x longer than the straight line, spend 2 minutes at the door, then ride back. Promise: 20 minutes.
//   D1 FIFO: when an order is packed, the first idle rider at the store takes it; otherwise it waits for one
//   D2 D1 + look-ahead: a rider riding back who will reach the store before the order is packed is assigned in
//      advance (dispatch plans on predicted availability, not on who is standing there)
//   D3 D1 + batching: a rider may take two packed orders whose customers are within 1 km of each other
//   D4 D2 + D3
// Measured: average and p90 order-to-door time, share delivered within the 20-minute promise, orders per rider-hour.
import java.util.*;

public class DispatchL3 {
    static final double RATE = 0.55;                                               // orders per minute per store
    record Order(int id, int store, double placed, double packed, double x, double y) {}

    public static void main(String[] args) {
        System.out.println("6 dark stores × 10 riders; ~0.55 orders/min/store for 2 h (evening peak); promise 20 min.\n");
        System.out.printf("   %-52s %10s %10s %16s %18s%n", "dispatch policy", "avg", "p90", "within 20 min", "riding min / order");
        String[] names = {"D1) FIFO to riders standing at the store", "D2) D1 + assign riders who are on their way back", "D3) D1 + batch 2 orders with nearby customers", "D4) D2 + D3"};
        for (int p = 0; p < 4; p++) run(p, names[p]);
        System.out.println("\n   → at peak the constraint is riders, not distance: every minute a rider spends riding back empty is a minute an");
        System.out.println("     order waits. Planning on predicted availability (riders about to arrive) removes idle gaps between trips, and");
        System.out.println("     batching two orders to neighbouring doors turns one return trip into two deliveries — each helps, together");
        System.out.println("     they keep most orders inside the promise at the same fleet size. Batching has a cost for the second customer,");
        System.out.println("     so cap the detour and the wait. Dispatch is an optimisation over the next few minutes, not a nearest-rider lookup.");
    }

    static void run(int policy, String name) {
        Random r = new Random(47); int stores = 6, perStore = 10; double hours = 2;
        List<Order> orders = new ArrayList<>(); int id = 0;
        for (int s = 0; s < stores; s++) for (double t = 0; t < hours * 60; t += -Math.log(1 - r.nextDouble()) / RATE) {
            double a = r.nextDouble() * 2 * Math.PI, d = 3 * Math.sqrt(r.nextDouble());
            orders.add(new Order(id++, s, t, t + 3 + 2 * r.nextDouble(), d * Math.cos(a), d * Math.sin(a)));
        }
        orders.sort(Comparator.comparingDouble(Order::packed));
        double[][] riderFree = new double[stores][perStore];                           // time each rider is back at the store
        double[] deliveredAt = new double[orders.size()]; boolean[] done = new boolean[orders.size()]; double riding = 0;
        Map<Integer, Integer> idx = new HashMap<>(); for (int i = 0; i < orders.size(); i++) idx.put(orders.get(i).id(), i);
        for (int i = 0; i < orders.size(); i++) {
            if (done[i]) continue;
            Order o = orders.get(i); double[] free = riderFree[o.store()];
            // choose a rider: D1 only riders already back by the packed time... else earliest returning (waits)
            int best = 0; for (int k = 1; k < perStore; k++) if (free[k] < free[best]) best = k;
            double start;
            if (policy == 1 || policy == 3) start = Math.max(o.packed(), free[best]);                                          // planned in advance: leaves as soon as both ready
            else { boolean anyIdle = free[best] <= o.packed(); start = anyIdle ? o.packed() : free[best] + 1.5; }                // not planned: noticed & assigned after arrival (+1.5 min)
            // batching: a second packed order with a customer within 1 km, packed within 3 min of this rider's start
            int second = -1;
            if (policy >= 2) for (int j = i + 1; j < orders.size() && orders.get(j).packed() <= start + 0.0 + 3; j++) {
                Order q = orders.get(j); if (done[j] || q.store() != o.store()) continue;
                if (Math.hypot(q.x() - o.x(), q.y() - o.y()) <= 1.0) { second = j; break; }
            }
            if (second >= 0) start = Math.max(start, orders.get(second).packed());
            double speed = 18.0 / 60, road = 1.4;                                        // km per minute; road factor
            double t1 = start + road * Math.hypot(o.x(), o.y()) / speed + 2;
            deliveredAt[i] = t1; done[i] = true; double back;
            if (second >= 0) {
                Order q = orders.get(second); double t2 = t1 + road * Math.hypot(q.x() - o.x(), q.y() - o.y()) / speed + 2;
                deliveredAt[second] = t2; done[second] = true; back = t2 + road * Math.hypot(q.x(), q.y()) / speed;
            } else back = t1 + road * Math.hypot(o.x(), o.y()) / speed;
            riding += back - start; free[best] = back;
        }
        double[] times = new double[orders.size()]; int within = 0;
        for (int i = 0; i < orders.size(); i++) { times[i] = deliveredAt[i] - orders.get(i).placed(); if (times[i] <= 20) within++; }
        Arrays.sort(times); double avg = Arrays.stream(times).average().orElse(0);
        System.out.printf("   %-52s %7.1f min %7.1f min %15.1f%% %18.1f%n", name, avg, times[(int) (times.length * 0.9)], 100.0 * within / times.length,
                riding / orders.size());
    }
}
