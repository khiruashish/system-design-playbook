// UnitCostL1.java — run with: java UnitCostL1.java bill.csv usage.csv   (JDK 21, no dependencies)
// UNIT ECONOMICS FROM A CLOUD BILL. "The bill is ₹1.8 crore a month" tells nobody what to do. FinOps turns it into numbers
// teams and product owners can act on: cost PER SERVICE and cost PER ORDER. That needs three steps:
//   1. DIRECT costs — resources tagged to one service.
//   2. SHARED costs — Kafka, observability, NAT/egress, the data lake, the cluster control plane, dev/staging — allocated to
//      services. HOW you allocate changes who "owns" the cost, so we compare an even split with driver-based allocation.
//   3. UNTAGGED costs — nobody's, which in practice means nobody turns them off.
// All prices are ILLUSTRATIVE assumptions, not current vendor rates.
import java.nio.file.*;
import java.util.*;

public class UnitCostL1 {
    public static void main(String[] args) throws Exception {
        List<String[]> bill = rows(args[0]);
        Map<String, double[]> usage = new LinkedHashMap<>();      // service -> {kafkaGB, logGB, egressGB, lakeGB, requestsM}
        for (String[] u : rows(args[1])) usage.put(u[0], new double[]{d(u[1]), d(u[2]), d(u[3]), d(u[4]), d(u[5])});
        List<String> services = new ArrayList<>(usage.keySet());

        Map<String, Double> direct = new LinkedHashMap<>(); services.forEach(s -> direct.put(s, 0.0));
        Map<String, Double> shared = new LinkedHashMap<>();
        List<String[]> untagged = new ArrayList<>();
        double total = 0;
        for (String[] b : bill) {
            double c = d(b[3]); total += c;
            switch (b[2]) {
                case "shared" -> shared.put(b[0], c);
                case "untagged" -> untagged.add(b);
                default -> direct.merge(b[1], c, Double::sum);
            }
        }
        double directSum = direct.values().stream().mapToDouble(Double::doubleValue).sum();
        double sharedSum = shared.values().stream().mapToDouble(Double::doubleValue).sum();
        double untaggedSum = untagged.stream().mapToDouble(u -> d(u[3])).sum();

        // driver per shared item: which usage column decides each service's share
        Map<String, Integer> driver = Map.of("kafka-cluster", 0, "observability-logs-metrics-traces", 1,
                "nat-gateways-and-egress", 2, "s3-data-lake", 3, "eks-control-plane-and-system-nodes", 4);
        Map<String, Double> evenAlloc = new LinkedHashMap<>(), driverAlloc = new LinkedHashMap<>();
        services.forEach(s -> { evenAlloc.put(s, 0.0); driverAlloc.put(s, 0.0); });
        for (var e : shared.entrySet()) {
            for (String s : services) evenAlloc.merge(s, e.getValue() / services.size(), Double::sum);
            Integer col = driver.get(e.getKey());
            if (col != null) {
                double tot = services.stream().mapToDouble(s -> usage.get(s)[col]).sum();
                for (String s : services) driverAlloc.merge(s, e.getValue() * usage.get(s)[col] / tot, Double::sum);
            } else {    // dev/staging: allocate in proportion to each service's direct (production) spend
                for (String s : services) driverAlloc.merge(s, e.getValue() * direct.get(s) / directSum, Double::sum);
            }
        }

        double ORDERS = 30_000_000, AOV = 650;          // illustrative: ~1M orders/day, ₹650 average order value
        System.out.printf("Monthly bill ₹%s (illustrative) for %,.0f orders. Direct %.0f%%, shared %.0f%%, UNTAGGED %.1f%%.%n%n",
                crore(total), ORDERS, 100 * directSum / total, 100 * sharedSum / total, 100 * untaggedSum / total);
        System.out.printf("   %-16s %12s %14s %16s %14s %11s %9s%n", "service", "direct", "shared (even)", "shared (drivers)",
                "total (drivers)", "₹ / order", "of bill");
        for (String s : services) {
            double t = direct.get(s) + driverAlloc.get(s);
            System.out.printf("   %-16s %12s %14s %16s %14s %11.3f %8.1f%%%n", s, lakh(direct.get(s)), lakh(evenAlloc.get(s)),
                    lakh(driverAlloc.get(s)), lakh(t), t / ORDERS, 100 * t / total);
        }
        System.out.printf("   %-16s %12s %14s %16s %14s %11.3f %8.1f%%%n", "UNTAGGED", "", "", "", lakh(untaggedSum), untaggedSum / ORDERS, 100 * untaggedSum / total);
        System.out.printf("%n   Cloud cost per order: ₹%.2f  =  %.2f%% of ₹%.0f average order value.%n", total / ORDERS, 100 * total / ORDERS / AOV, AOV);

        // biggest swings between allocation methods
        System.out.println("\n   How much the allocation METHOD moves each service's shared-cost bill (drivers − even split):");
        for (String s : services) System.out.printf("        %-16s %+,12.0f ₹%n", s, driverAlloc.get(s) - evenAlloc.get(s));

        System.out.println("\n   Untagged line items (owner unknown):");
        for (String[] u : untagged) System.out.printf("        %-28s ₹%,10.0f%n", u[0], d(u[3]));

        // a value question the unit numbers make answerable
        String reco = "recommendations";
        double recoCost = direct.get(reco) + driverAlloc.get(reco);
        double upliftPct = 1.2, margin = 0.18;          // illustrative: A/B test shows +1.2% GMV; 18% contribution margin
        double upliftProfit = ORDERS * AOV * upliftPct / 100 * margin;
        System.out.printf("%n   Is recommendations worth ₹%s/month? A/B uplift %.1f%% of GMV × %.0f%% margin = ₹%s/month of profit → %s.%n",
                lakh(recoCost), upliftPct, margin * 100, lakh(upliftProfit),
                upliftProfit > recoCost ? String.format("pays for itself %.1fx", upliftProfit / recoCost) : String.format("covers only %.0f%% of its cost", 100 * upliftProfit / recoCost));

        String top = services.stream().max(Comparator.comparingDouble(x -> direct.get(x) + driverAlloc.get(x))).get();
        double maxSwing = services.stream().mapToDouble(x -> Math.abs(driverAlloc.get(x) - evenAlloc.get(x))).max().orElse(0);
        System.out.println("\n   → the bill becomes decisions only once it's per service and per order. The even split is simple but wrong: it");
        System.out.printf ("     charges services for other teams' logs and egress — driver-based allocation moves up to ₹%s a month between teams.%n", lakh(maxSwing));
        System.out.printf ("     Untagged spend (%.1f%% here) has no owner, so it's the first place to look for waste — orphaned volumes, unattached%n", 100 * untaggedSum / total);
        System.out.println("     IPs, forgotten instances — and the reason to enforce tagging at provisioning time.");
        System.out.printf ("     Per-service cost also makes value questions answerable. The two biggest lines are %s and recommendations; the%n", top);
        System.out.printf ("     latter looks like a cut candidate by size, yet its measured uplift returns ~%.1fx its cost. Judge spend by VALUE per%n", upliftProfit / recoCost);
        System.out.printf ("     rupee, not by size. Finally, context: cloud is ₹%.2f of a ₹%.0f order — for quick commerce, rider and dark-store costs%n", total / ORDERS, AOV);
        System.out.println("     dwarf it, so FinOps effort should be proportionate: remove waste and wrong incentives, don't micro-optimize.");
    }

    static List<String[]> rows(String f) throws Exception {
        return Files.readAllLines(Path.of(f)).stream().filter(l -> !l.isBlank() && !l.startsWith("#")).map(l -> l.split(",", -1)).toList();
    }
    static double d(String s) { return Double.parseDouble(s.trim()); }
    static String lakh(double v) { return String.format("%,.1f L", v / 100_000); }
    static String crore(double v) { return String.format("%.2f crore", v / 10_000_000); }
}
