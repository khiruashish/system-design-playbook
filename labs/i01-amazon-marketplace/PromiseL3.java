// L3 — Which fulfilment centre (FC) ships what? A multi-item order can ship from one FC or be split. Compare three
// strategies on 20,000 simulated orders across 12 FCs: per-item nearest FC, "one box if any FC can do it", and an
// exhaustive cost optimiser under the delivery promise. Geography, stock and costs are illustrative (VERIFY for real use).
import java.util.*;

public class PromiseL3 {
    record FC(String name, double x, double y, double size) {}
    static final FC[] FCS = {
        new FC("DEL-1", 770, 285, 1.0), new FC("DEL-2", 785, 300, 0.6), new FC("BOM-1", 720, 1905, 1.0), new FC("BOM-2", 740, 1880, 0.5),
        new FC("BLR-1", 1290, 2600, 1.0), new FC("HYD-1", 1300, 2150, 0.7), new FC("MAA-1", 1500, 2600, 0.7), new FC("CCU-1", 2050, 1380, 0.7),
        new FC("AMD-1", 460, 1450, 0.5), new FC("LKO-1", 1100, 560, 0.4), new FC("GAU-1", 2450, 870, 0.3), new FC("PNQ-1", 830, 1990, 0.4)};
    static final int SKUS = 5000, ORDERS = 20_000, PROMISE_DAYS = 2;
    static final double FIXED = 35, PER_KM = 0.04;           // ₹ per shipment, ₹ per km (illustrative)

    public static void main(String[] a) {
        Random r = new Random(1101);
        boolean[][] stock = new boolean[FCS.length][SKUS];
        for (int f = 0; f < FCS.length; f++) for (int s = 0; s < SKUS; s++) {
            double popularity = 1.0 / Math.pow(s + 1, 0.35);            // popular SKUs stocked widely
            stock[f][s] = r.nextDouble() < Math.min(0.97, FCS[f].size() * (0.35 + 0.9 * popularity));
        }
        simulate("stock as is", stock, new Random(7));
        // placement change: the 300 best-selling SKUs are stocked in every FC (costs working capital, not routing code)
        for (int f = 0; f < FCS.length; f++) for (int sku = 0; sku < 300; sku++) stock[f][sku] = true;
        simulate("top 300 SKUs stocked in every FC", stock, new Random(7));
    }

    static void simulate(String label, boolean[][] stock, Random r) {
        double[] cust = new double[2]; String[] names = {"S1 nearest FC per item", "S2 one box if possible, else S1", "S3 cheapest plan within promise"};
        double[] cost = new double[3], ships = new double[3], met = new double[3], split = new double[3]; long[] nanos = new long[3]; int[] noPlan = new int[3];
        for (int o = 0; o < ORDERS; o++) {
            FC home = FCS[r.nextInt(FCS.length)];                        // customers cluster around metros
            cust[0] = home.x() + r.nextGaussian() * 220; cust[1] = home.y() + r.nextGaussian() * 220;
            int items = 1 + Math.min(5, (int) Math.abs(r.nextGaussian() * 2));
            int[] skus = new int[items]; for (int i = 0; i < items; i++) skus[i] = zipf(r);
            for (int k = 0; k < 3; k++) {
                long t0 = System.nanoTime();
                int[] plan = k == 0 ? nearest(skus, stock, cust) : k == 1 ? oneBox(skus, stock, cust) : optimal(skus, stock, cust);
                nanos[k] += System.nanoTime() - t0;
                if (plan == null) { noPlan[k]++; continue; }
                Set<Integer> used = new HashSet<>(); for (int f : plan) used.add(f);
                double c = 0; int worst = 0; for (int f : used) { double d = dist(FCS[f], cust); c += FIXED + PER_KM * d; worst = Math.max(worst, days(d)); }
                cost[k] += c; ships[k] += used.size(); if (worst <= PROMISE_DAYS) met[k]++; if (used.size() > 1) split[k]++;
            }
        }
        System.out.printf("%n=== %s: %,d orders (1–6 items, popular SKUs more likely), 12 FCs, promise = %d days%n", label, ORDERS, PROMISE_DAYS);
        System.out.printf("%-34s %10s %11s %12s %13s %11s%n", "strategy", "₹/order", "boxes/order", "split orders", "within promise", "µs/order");
        for (int k = 0; k < 3; k++) {
            int n = ORDERS - noPlan[k];
            System.out.printf("%-34s %10.1f %11.2f %11.1f%% %12.1f%% %11.1f%n", names[k], cost[k] / n, ships[k] / n, 100 * split[k] / n, 100 * met[k] / n, nanos[k] / 1e3 / ORDERS);
        }
        System.out.printf("orders with an item in no FC at all: %d (excluded)%n", noPlan[0]);
    }
    static int zipf(Random r) { return Math.min(SKUS - 1, (int) Math.floor(Math.pow(r.nextDouble(), 2.2) * SKUS)); }
    static double dist(FC f, double[] c) { return Math.hypot(f.x() - c[0], f.y() - c[1]); }
    static int days(double km) { return km < 300 ? 1 : km < 1000 ? 2 : km < 1800 ? 3 : 4; }
    static int[] nearest(int[] skus, boolean[][] st, double[] c) {
        int[] p = new int[skus.length];
        for (int i = 0; i < skus.length; i++) { int best = -1; for (int f = 0; f < FCS.length; f++) if (st[f][skus[i]] && (best < 0 || dist(FCS[f], c) < dist(FCS[best], c))) best = f; if (best < 0) return null; p[i] = best; }
        return p;
    }
    static int[] oneBox(int[] skus, boolean[][] st, double[] c) {
        int best = -1;
        for (int f = 0; f < FCS.length; f++) { boolean all = true; for (int s : skus) all &= st[f][s];
            if (all && days(dist(FCS[f], c)) <= PROMISE_DAYS && (best < 0 || dist(FCS[f], c) < dist(FCS[best], c))) best = f; }
        if (best < 0) return nearest(skus, st, c);
        int[] p = new int[skus.length]; Arrays.fill(p, best); return p;
    }
    // exhaustive: every assignment of items to FCs that stock them; minimise cost among plans meeting the promise,
    // falling back to the fastest plan if none meets it
    static int[] optimal(int[] skus, boolean[][] st, double[] c) {
        List<List<Integer>> opts = new ArrayList<>();
        for (int s : skus) { List<Integer> l = new ArrayList<>(); for (int f = 0; f < FCS.length; f++) if (st[f][s]) l.add(f); if (l.isEmpty()) return null; opts.add(l); }
        int[] cur = new int[skus.length], best = null, fastest = null; double bestCost = Double.MAX_VALUE; int bestDays = 99; double fastCost = Double.MAX_VALUE;
        int[] idx = new int[skus.length];
        while (true) {
            for (int i = 0; i < skus.length; i++) cur[i] = opts.get(i).get(idx[i]);
            Set<Integer> used = new HashSet<>(); for (int f : cur) used.add(f);
            double cost = 0; int worst = 0; for (int f : used) { double d = dist(FCS[f], c); cost += FIXED + PER_KM * d; worst = Math.max(worst, days(d)); }
            if (worst <= PROMISE_DAYS && cost < bestCost) { bestCost = cost; best = cur.clone(); }
            if (worst < bestDays || worst == bestDays && cost < fastCost) { bestDays = worst; fastCost = cost; fastest = cur.clone(); }
            int i = 0; while (i < skus.length && ++idx[i] == opts.get(i).size()) { idx[i] = 0; i++; }
            if (i == skus.length) break;
        }
        return best != null ? best : fastest;
    }
}
