// MarkdownL4.java — run with: java MarkdownL4.java   (JDK 21, no dependencies)
// PRICING WHAT'S ABOUT TO EXPIRE. One dark store, 60 perishable SKUs (milk, bread, greens, cut fruit; shelf life 2–5
// days), 90 days, fixed seed. Each morning the store receives enough to cover expected demand + 20% safety stock; units
// are picked oldest-first. Daily demand is Poisson around each SKU's mean with a weekday pattern. On a unit's LAST
// sellable day it may be marked down by d. Assumed response (VERIFY with store tests): a markdown pulls extra buyers
// (+0.8 × d × mean demand) but also tempts full-price buyers of that SKU to switch (a share 1.2 × d of them buy the
// cheap unit instead). Cost is 62% of list price; wasted units are written off.
//   K1 no markdowns
//   K2 30% off on the last day
//   K3 50% off on the last day
//   K4 dynamic: each morning, per SKU, pick the markdown (0/10/20/30/40/50%) with the highest EXPECTED revenue today,
//      given last-day stock, fresher stock and expected demand (same response model)
// Measured: units wasted, revenue, gross margin (revenue − cost of everything received), and full-price sales lost to
// cannibalisation.
import java.util.*;

public class MarkdownL4 {
    public static void main(String[] args) {
        System.out.println("60 perishable SKUs, 90 days, cost 62% of price, oldest-first picking, 20% safety stock.\n");
        System.out.printf("   %-52s %13s %14s %14s %16s%n", "markdown policy", "units wasted", "revenue (₹)", "gross margin", "full-price sales");
        System.out.printf("   %-52s %13s %14s %14s %16s%n", "", "", "", "(₹)", "cannibalised");
        String[] names = {"K1) no markdowns", "K2) 30% off on the last day", "K3) 50% off on the last day", "K4) per-SKU markdown maximising expected revenue"};
        for (int k = 0; k < 4; k++) run(k, names[k]);
        System.out.println("\n   → without markdowns the safety stock becomes waste (24,686 units). Any last-day markdown recovers most of it —");
        System.out.println("     a unit sold at 70% beats a unit written off, because its cost is already sunk. Deeper isn't better: 50% off");
        System.out.println("     clears almost everything but earns less than 30%, giving away price on units that would have sold anyway.");
        System.out.println("     Choosing the markdown per SKU per day from expected revenue earns the most margin with the least");
        System.out.println("     cannibalisation, while wasting a little more than a flat 30% — it optimises margin, not waste. If waste has");
        System.out.println("     its own cost (a sustainability target, disposal fees), put it in the objective. The response parameters are");
        System.out.println("     assumptions; stores learn them from their own sell-through data.");
    }

    static void run(int policy, String name) {
        Random r = new Random(49); int skus = 60; double wasted = 0, revenue = 0, cost = 0, cannibal = 0;
        double[] mean = new double[skus], price = new double[skus]; int[] life = new int[skus];
        for (int s = 0; s < skus; s++) { mean[s] = 5 + r.nextDouble() * 40; price[s] = 25 + r.nextInt(150); life[s] = 2 + r.nextInt(4); }
        double[] week = {0.9, 0.85, 0.9, 0.95, 1.05, 1.25, 1.1};
        Random dem = new Random(4949);
        for (int s = 0; s < skus; s++) {
            Deque<int[]> stock = new ArrayDeque<>();                                            // {units, daysLeft} oldest first
            for (int d = 0; d < 90; d++) {
                double mu = mean[s] * week[d % 7];
                int onHand = stock.stream().mapToInt(x -> x[0]).sum();
                int receive = (int) Math.ceil(mu * 1.2);                                            // daily fresh batch
                stock.addLast(new int[]{receive, life[s]}); cost += receive * price[s] * 0.62;
                // last-day units
                int lastDay = 0; for (int[] b : stock) if (b[1] == 1) lastDay += b[0];
                int fresher = onHand + receive - lastDay;
                double disc = switch (policy) { case 1 -> 0.3; case 2 -> 0.5; case 3 -> chooseMarkdown(lastDay, fresher, mu); default -> 0; };
                if (lastDay == 0) disc = 0;
                int fullDemand = poisson(dem, mu), extra = disc > 0 ? poisson(dem, 0.8 * disc * mu) : 0;
                int switchers = 0; for (int i = 0; i < fullDemand; i++) if (disc > 0 && dem.nextDouble() < 1.2 * disc) switchers++;
                // discounted buyers take last-day units first
                int cheapWanted = extra + switchers, cheapSold = Math.min(cheapWanted, lastDay);
                takeOldest(stock, cheapSold); revenue += cheapSold * price[s] * (1 - disc);
                int unmetCheap = cheapWanted - cheapSold;                                      // switched but none left → buy full price
                int fullWanted = fullDemand - switchers + Math.min(unmetCheap, switchers);
                cannibal += Math.min(switchers, cheapSold);
                int fullSold = Math.min(fullWanted, stock.stream().mapToInt(x -> x[0]).sum());
                // full-price buyers: oldest-first picking (last-day units sold at full price if no markdown taken them)
                takeOldest(stock, fullSold); revenue += fullSold * price[s];
                // end of day: age, expire
                for (int[] b : stock) b[1]--;
                while (!stock.isEmpty() && stock.peekFirst()[1] <= 0) wasted += stock.pollFirst()[0];
                stock.removeIf(b -> b[0] == 0);
            }
        }
        System.out.printf("   %-52s %,13.0f %,14.0f %,14.0f %,16.0f%n", name, wasted, revenue, revenue - cost, cannibal);
    }

    // expected revenue today for each markdown, from the SAME response model the store believes (its estimate of mu)
    static double chooseMarkdown(int lastDay, int fresher, double mu) {
        double best = 0, bestRev = -1;
        for (double d : new double[]{0, 0.1, 0.2, 0.3, 0.4, 0.5}) {
            double extra = 0.8 * d * mu, switchers = 1.2 * d * mu, cheap = Math.min(extra + switchers, lastDay);
            double sold = Math.min(mu + extra, lastDay + fresher);
            double rev = cheap * (1 - d) + (sold - cheap);                                     // in units of list price
            if (rev > bestRev + 1e-9) { bestRev = rev; best = d; }
        }
        return best;
    }
    static void takeOldest(Deque<int[]> stock, int n) { for (int[] b : stock) { int t = Math.min(n, b[0]); b[0] -= t; n -= t; if (n == 0) break; } }
    static int poisson(Random r, double mu) { if (mu <= 0) return 0; double l = Math.exp(-mu), p = 1; int k = 0; do { k++; p *= r.nextDouble(); } while (p > l); return k - 1; }
}
