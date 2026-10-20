// ContextsL4.java — run with: java ContextsL4.java   (JDK 21, no dependencies)
// FINDING BOUNDED CONTEXTS FROM HOW THE DOMAIN IS USED. The classic mistake is to split a system by NOUNS — one service per
// entity — which puts every real use case across several services. Bounded contexts should instead group what CHANGES and
// is USED TOGETHER. We take the grocery platform's use cases (the output of an event-storming session), each with the
// entities it touches and its weekly volume, build an entity-affinity graph, and compare candidate decompositions by:
//   cross-context %   — share of weekly use-case executions that must coordinate across more than one context
//   largest context   — entities in the biggest context (a proxy for "did we just rebuild the monolith?")
import java.util.*;
import java.util.stream.*;

public class ContextsL4 {
    record UseCase(String name, int weeklyK, List<String> entities) {}
    static final List<UseCase> UCS = List.of(
        new UseCase("browse catalog",        500, List.of("ProductDescription", "ProductPrice")),
        new UseCase("search products",       400, List.of("ProductDescription")),
        new UseCase("add to cart",           300, List.of("Cart", "ProductPrice", "StockLevel")),
        new UseCase("track delivery",        200, List.of("Shipment", "Rider")),
        new UseCase("checkout",               80, List.of("Cart", "Order", "Reservation", "Payment")),
        new UseCase("pay",                    80, List.of("Payment", "Order")),
        new UseCase("generate GST invoice",   80, List.of("Invoice", "Order", "Customer")),
        new UseCase("book delivery slot",     80, List.of("DeliverySlot", "Order")),
        new UseCase("assign rider",           80, List.of("Shipment", "Rider", "DeliverySlot")),
        new UseCase("dispatch order",         80, List.of("Shipment", "Order", "Address")),
        new UseCase("apply coupon",           60, List.of("Cart", "Coupon", "ProductPrice")),
        new UseCase("expire reservations",    50, List.of("Reservation", "StockLevel")),
        new UseCase("restock",                20, List.of("StockLevel")),
        new UseCase("update address",         10, List.of("Customer", "Address")),
        new UseCase("reprice",                 5, List.of("ProductPrice")),
        new UseCase("edit product",            5, List.of("ProductDescription")),
        new UseCase("refund",                  4, List.of("Payment", "Order", "Invoice")));

    static List<String> entities() { return UCS.stream().flatMap(u -> u.entities().stream()).distinct().sorted().toList(); }

    // score a decomposition: entity -> context name
    static String score(String label, Map<String, String> ctxOf) {
        long total = 0, cross = 0; double ctxPerUc = 0;
        for (UseCase u : UCS) {
            long k = u.entities().stream().map(ctxOf::get).distinct().count();
            total += u.weeklyK(); if (k > 1) cross += u.weeklyK(); ctxPerUc += k * (double) u.weeklyK();
        }
        Map<String, List<String>> groups = new TreeMap<>();
        ctxOf.forEach((e, c) -> groups.computeIfAbsent(c, x -> new ArrayList<>()).add(e));
        int largest = groups.values().stream().mapToInt(List::size).max().orElse(0);
        return String.format("   %-40s %8d %11.1f%% %13.2f %10d %11.2f", label, groups.size(), 100.0 * cross / total, ctxPerUc / total, largest, Math.abs(q(ctxOf)) < 0.005 ? 0.0 : q(ctxOf));
    }

    // affinity-threshold clustering: keep edges with co-usage >= t, contexts = connected components
    static Map<String, String> cluster(int t) {
        List<String> es = entities();
        Map<String, Map<String, Integer>> w = new HashMap<>();
        for (UseCase u : UCS)
            for (String a : u.entities()) for (String b : u.entities())
                if (!a.equals(b)) w.computeIfAbsent(a, x -> new HashMap<>()).merge(b, u.weeklyK(), Integer::sum);
        Map<String, String> ctx = new HashMap<>(); int id = 0;
        for (String s : es) {
            if (ctx.containsKey(s)) continue;
            String name = "C" + (++id); Deque<String> st = new ArrayDeque<>(List.of(s)); ctx.put(s, name);
            while (!st.isEmpty()) {
                String x = st.pop();
                for (var e : w.getOrDefault(x, Map.of()).entrySet())
                    if (e.getValue() >= t && !ctx.containsKey(e.getKey())) { ctx.put(e.getKey(), name); st.push(e.getKey()); }
            }
        }
        return ctx;
    }
    // modularity-based clustering (Louvain phase 1, local moving): a context is a group whose internal co-usage is much
    // higher than you'd expect by chance given each entity's total usage — avoids single-linkage "chaining"
    static double[][] adjacency(List<String> es) {
        int n = es.size(); double[][] a = new double[n][n];
        for (UseCase u : UCS) for (String x : u.entities()) for (String y : u.entities())
            if (!x.equals(y)) a[es.indexOf(x)][es.indexOf(y)] += u.weeklyK();
        return a;
    }
    static double modularity(double[][] a, int[] c) {
        int n = a.length; double[] k = new double[n]; double m2 = 0;
        for (int i = 0; i < n; i++) { for (int j = 0; j < n; j++) k[i] += a[i][j]; m2 += k[i]; }
        double q = 0;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) if (c[i] == c[j]) q += a[i][j] - k[i] * k[j] / m2;
        return q / m2;
    }
    static int[] louvain(double[][] a) {
        int n = a.length; int[] c = new int[n]; for (int i = 0; i < n; i++) c[i] = i;
        boolean moved = true;
        while (moved) {
            moved = false;
            for (int i = 0; i < n; i++) {
                int best = c[i]; double bestQ = modularity(a, c);
                Set<Integer> cands = new TreeSet<>(); for (int j = 0; j < n; j++) if (a[i][j] > 0) cands.add(c[j]);
                for (int cand : cands) { int old = c[i]; c[i] = cand; double q = modularity(a, c); c[i] = old;
                    if (q > bestQ + 1e-12) { bestQ = q; best = cand; } }
                if (best != c[i]) { c[i] = best; moved = true; }
            }
        }
        return c;
    }
    static Map<String, String> modularityContexts() {
        List<String> es = entities(); int[] c = louvain(adjacency(es));
        Map<String, String> m = new HashMap<>(); for (int i = 0; i < es.size(); i++) m.put(es.get(i), "M" + c[i]); return m;
    }
    static double q(Map<String, String> ctx) {
        List<String> es = entities(); List<String> names = new ArrayList<>(new TreeSet<>(ctx.values()));
        int[] c = new int[es.size()]; for (int i = 0; i < es.size(); i++) c[i] = names.indexOf(ctx.get(es.get(i)));
        return modularity(adjacency(es), c);
    }

    static Map<String, String> fromGroups(Map<String, List<String>> g) {
        Map<String, String> m = new HashMap<>(); g.forEach((c, es) -> es.forEach(e -> m.put(e, c))); return m;
    }
    static String describe(Map<String, String> ctx) {
        Map<String, List<String>> g = new TreeMap<>();
        ctx.forEach((e, c) -> g.computeIfAbsent(c, x -> new ArrayList<>()).add(e));
        return g.values().stream().map(l -> { Collections.sort(l); return "{" + String.join(", ", l) + "}"; }).sorted()
                .collect(Collectors.joining("\n        "));
    }

    public static void main(String[] args) {
        List<String> es = entities();
        System.out.printf("%d use cases over %d entities (weekly volume in thousands). Candidate decompositions:%n%n", UCS.size(), es.size());
        System.out.printf("   %-40s %8s %12s %13s %10s %11s%n", "decomposition", "contexts", "cross-ctx %", "ctx per call", "largest", "modularity");

        Map<String, String> perEntity = new HashMap<>(); es.forEach(e -> perEntity.put(e, e));
        System.out.println(score("A) one service per entity (nouns)", perEntity));
        Map<String, String> mono = new HashMap<>(); es.forEach(e -> mono.put(e, "Monolith"));
        System.out.println(score("B) one big module", mono));
        for (int t : new int[]{80, 100}) System.out.println(score("C) threshold clustering, edges ≥ " + t + "k/wk", cluster(t)));
        System.out.println(score("E) modularity clustering (Louvain)", modularityContexts()));
        Map<String, String> expert = fromGroups(Map.of(
            "Catalog",     List.of("ProductDescription", "ProductPrice", "Coupon"),
            "Ordering",    List.of("Cart", "Order", "Payment", "Invoice", "Customer", "Address"),
            "Inventory",   List.of("StockLevel", "Reservation"),
            "Fulfilment",  List.of("Shipment", "Rider", "DeliverySlot")));
        System.out.println(score("D) domain-expert draft (4 contexts)", expert));

        System.out.println("\n   C) threshold clustering at 80k/wk (single linkage):\n        " + describe(cluster(80)));
        System.out.println("\n   E) modularity clustering:\n        " + describe(modularityContexts()));
        System.out.println("\n   D) domain-expert draft:\n        " + describe(expert));

        System.out.println("\n   → no single number picks the design. Splitting by nouns (A) makes 79% of weekly work cross service boundaries —");
        System.out.println("     a distributed monolith. Minimising cross-context calls alone rewards one big module (B), and single-linkage");
        System.out.println("     clustering (C) chains everything into a blob via a few busy use cases. Modularity (E) is the most useful signal:");
        System.out.println("     it independently finds Catalog {ProductDescription, ProductPrice} and a Fulfilment context — and pulls Address");
        System.out.println("     into Fulfilment, because the busy use cases need a DELIVERY LOCATION, not the customer's address book. But it also");
        System.out.println("     proposes {Cart, Coupon, StockLevel}, which follows call patterns, not invariants or language. So the data doesn't");
        System.out.println("     replace the domain experts; it challenges their draft (D) in two concrete places: model Address separately per");
        System.out.println("     context (a DeliveryLocation copied onto the order), and question whether Reservation belongs to Inventory or to");
        System.out.println("     checkout. Boundaries are decided on ubiquitous language, invariants and team ownership, with this data as evidence.");
    }
}
