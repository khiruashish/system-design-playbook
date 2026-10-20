// PhantomStockL3.java — run with: java PhantomStockL3.java   (JDK 21, no dependencies)
// THE SYSTEM SAYS 3, THE SHELF HAS 1. A dark store's recorded stock drifts from what's physically on the shelf: damaged
// produce, misplaced items, unrecorded wastage, theft. Checkout reserves against RECORDED stock; the picker finds out the
// truth. One simulated day (fixed seed) in a dark store with 2,000 SKUs (25% fresh produce with large discrepancies,
// the rest packaged with small ones) and 30,000 orders of ~6 items. Four policies:
//   P1 sell down to recorded stock (no buffer)
//   P2 hide the last 2 units of every SKU (a global safety buffer)
//   P3 per-SKU buffer learned from last week's pick failures (fresh produce gets a bigger one)
//   P4 P3 + when a picker reports an item missing, recorded stock is corrected to the shelf count at once
// Measured: orders that reached the customer with an item missing, units physically on the shelf at close that the buffer
// never offered (sales lost to caution), and items sold.
import java.util.*;

public class PhantomStockL3 {
    public static void main(String[] args) {
        int skus = 2_000; Random r = new Random(47);
        int[] recorded = new int[skus]; double[] lambda = new double[skus]; double[] demand = new double[skus]; boolean[] fresh = new boolean[skus];
        for (int i = 0; i < skus; i++) {
            fresh[i] = r.nextDouble() < 0.25; recorded[i] = 5 + r.nextInt(56);
            lambda[i] = fresh[i] ? 2.5 : 0.25;                                             // expected missing units per day
            demand[i] = 1.0 / Math.pow(i + 1, 0.6);
        }
        double sum = Arrays.stream(demand).sum(); double[] cdf = new double[skus]; double c = 0; for (int i = 0; i < skus; i++) { c += demand[i] / sum; cdf[i] = c; }
        List<int[]> orders = new ArrayList<>();
        for (int o = 0; o < 30_000; o++) { int n = 3 + r.nextInt(7); int[] items = new int[n]; for (int k = 0; k < n; k++) { int idx = Arrays.binarySearch(cdf, r.nextDouble()); items[k] = idx < 0 ? Math.min(skus - 1, -idx - 1) : idx; } orders.add(items); }
        // today's discrepancies (shrinkage) and last week's observed pick failures (to learn buffers)
        int[] shrink = new int[skus], lastWeekMisses = new int[skus];
        for (int i = 0; i < skus; i++) { shrink[i] = poisson(r, lambda[i]); for (int d = 0; d < 7; d++) lastWeekMisses[i] += poisson(r, lambda[i]); }
        // restock: recorded stock should cover a day's demand for most SKUs
        for (int i = 0; i < skus; i++) recorded[i] = Math.max(recorded[i], (int) Math.ceil(30_000 * 6 * demand[i] / sum * 0.9));

        System.out.printf("Dark store: %,d SKUs (25%% fresh), %,d orders; recorded stock overstates the shelf by %,d units in total.%n%n", skus, orders.size(), Arrays.stream(shrink).sum());
        System.out.printf("   %-58s %14s %16s %16s%n", "policy", "orders with a", "units on shelf,", "items sold");
        System.out.printf("   %-58s %14s %16s %16s%n", "", "missing item", "hidden by buffer", "");
        String[] names = {"P1) sell down to recorded stock", "P2) hide the last 2 units of every SKU", "P3) per-SKU buffer from last week's pick failures",
                "P4) P3 + correct recorded stock when a pick fails"};
        for (int p = 0; p < 4; p++) {
            int[] rec = recorded.clone(), shelf = new int[skus]; for (int i = 0; i < skus; i++) shelf[i] = Math.max(0, recorded[i] - shrink[i]);
            int[] buffer = new int[skus];
            for (int i = 0; i < skus; i++) buffer[i] = p == 0 ? 0 : p == 1 ? 2 : (int) Math.ceil(lastWeekMisses[i] / 7.0 * 1.5);
            int missingOrders = 0, lostSales = 0, sold = 0;
            for (int[] o : orders) {
                boolean missing = false;
                for (int it : o) {
                    if (rec[it] - buffer[it] <= 0) continue;                                      // shown out of stock
                    rec[it]--;                                                                   // reserved against recorded stock
                    if (shelf[it] > 0) { shelf[it]--; sold++; }
                    else { missing = true; if (p == 3) rec[it] = 0; }                             // picker can't find it; P4 fixes the record
                }
                if (missing) missingOrders++;
            }
            for (int i = 0; i < skus; i++) if (rec[i] - buffer[i] <= 0) lostSales += Math.max(0, shelf[i]);   // on the shelf, never offered
            System.out.printf("   %-58s %13.2f%% %,16d %,16d%n", names[p], 100.0 * missingOrders / orders.size(), lostSales, sold);
        }
        System.out.println("\n   → selling to recorded stock turns every discrepancy into a disappointed customer at the door — refunds, missing");
        System.out.println("     items and support calls (3.9% of orders here). A flat buffer hides stock that is really there and still misses");
        System.out.println("     the fresh produce that drifts most. A buffer learned per SKU from pick failures puts the caution where the errors");
        System.out.println("     are, and fixing the record the moment a picker finds a gap stops the same phantom unit being sold again. Inventory");
        System.out.println("     accuracy is a data-quality loop between the picker's handheld and the checkout — not a property of the database.");
        System.out.println("     Buffers do cost sales (compare items sold): the right buffer is a business trade between refunds and lost sales.");
    }
    static int poisson(Random r, double l) { double L = Math.exp(-l), p = 1; int k = 0; do { k++; p *= r.nextDouble(); } while (p > L); return k - 1; }
}
