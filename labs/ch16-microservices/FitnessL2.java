// FitnessL2.java — run with: java FitnessL2.java
// Architecture fitness functions over the platform's service graph: the "distributed monolith" smells as numbers.
// The same checks run in CI against a machine-readable service catalog (Backstage catalog-info, OpenAPI clients, etc.).
import java.util.*;
import java.util.stream.*;

public class FitnessL2 {

    record Edge(String from, String to, boolean sync) { }
    record Catalog(String name, Map<String, String> database, List<Edge> edges) { }

    // ---------- the checks ----------
    static List<List<String>> syncCycles(Catalog c) {                 // Tarjan's SCC on synchronous edges
        Map<String, List<String>> g = new TreeMap<>();
        c.edges().stream().filter(Edge::sync).forEach(e -> g.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to()));
        Map<String, Integer> index = new HashMap<>(), low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>(); Set<String> on = new HashSet<>();
        List<List<String>> out = new ArrayList<>(); int[] counter = {0};
        class T { void visit(String v) {
            index.put(v, counter[0]); low.put(v, counter[0]++); stack.push(v); on.add(v);
            for (String w : g.getOrDefault(v, List.of())) {
                if (!index.containsKey(w)) { visit(w); low.put(v, Math.min(low.get(v), low.get(w))); }
                else if (on.contains(w)) low.put(v, Math.min(low.get(v), index.get(w)));
            }
            if (low.get(v).equals(index.get(v))) {
                List<String> scc = new ArrayList<>(); String w;
                do { w = stack.pop(); on.remove(w); scc.add(w); } while (!w.equals(v));
                if (scc.size() > 1) { Collections.sort(scc); out.add(scc); }
            }
        } }
        T t = new T();
        new TreeSet<>(c.edges().stream().flatMap(e -> Stream.of(e.from(), e.to())).toList()).forEach(v -> { if (!index.containsKey(v)) t.visit(v); });
        return out;
    }

    static List<String> longestSyncChain(Catalog c, String from) {    // DFS on the (acyclic part of the) sync graph
        List<String> best = List.of(from);
        for (Edge e : c.edges()) if (e.sync() && e.from().equals(from)) {
            List<String> path = new ArrayList<>(List.of(from));
            List<String> rest = longestFrom(c, e.to(), new HashSet<>(Set.of(from)));
            path.addAll(rest);
            if (path.size() > best.size()) best = path;
        }
        return best;
    }
    static List<String> longestFrom(Catalog c, String v, Set<String> seen) {
        seen = new HashSet<>(seen); seen.add(v);
        List<String> best = List.of(v);
        for (Edge e : c.edges()) if (e.sync() && e.from().equals(v) && !seen.contains(e.to())) {
            List<String> p = new ArrayList<>(List.of(v)); p.addAll(longestFrom(c, e.to(), seen));
            if (p.size() > best.size()) best = p;
        }
        return best;
    }

    static Map<String, List<String>> sharedDatabases(Catalog c) {
        return c.database().entrySet().stream().collect(Collectors.groupingBy(Map.Entry::getValue, TreeMap::new,
                Collectors.mapping(Map.Entry::getKey, Collectors.toList()))).entrySet().stream()
                .filter(e -> e.getValue().size() > 1).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, TreeMap::new));
    }

    static void report(Catalog c) {
        System.out.println(c.name());
        var cycles = syncCycles(c);
        var chain = longestSyncChain(c, "api-gateway");
        var shared = sharedDatabases(c);
        Map<String, Long> syncFanIn = c.edges().stream().filter(Edge::sync).collect(Collectors.groupingBy(Edge::to, TreeMap::new, Collectors.counting()));
        var hub = syncFanIn.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
        long sync = c.edges().stream().filter(Edge::sync).count();
        System.out.printf("   edges: %d sync, %d async (events)%n", sync, c.edges().size() - sync);
        System.out.println("   synchronous cycles         : " + (cycles.isEmpty() ? "none" : cycles));
        System.out.printf ("   longest sync chain         : %d hops %s%n", chain.size() - 1, chain);
        Set<String> reach = new TreeSet<>(); Deque<String> todo = new ArrayDeque<>(List.of("checkout-service"));
        while (!todo.isEmpty()) { String v = todo.pop(); if (reach.add(v)) c.edges().stream().filter(e -> e.sync() && e.from().equals(v)).forEach(e -> todo.push(e.to())); }
        System.out.printf ("   a checkout needs, synchronously: %d services → %.2f %% available if each is 99.9 %%%n",
                reach.size(), Math.pow(0.999, reach.size()) * 100);
        System.out.println("   shared databases           : " + (shared.isEmpty() ? "none" : shared));
        System.out.println("   highest sync fan-in        : " + hub.getKey() + " (" + hub.getValue() + " callers)");
        boolean pass = cycles.isEmpty() && shared.isEmpty() && chain.size() - 1 <= 4;
        System.out.println("   fitness (no cycles, no shared DB, chain ≤ 4): " + (pass ? "PASS" : "FAIL"));
    }

    static Edge s(String a, String b) { return new Edge(a, b, true); }
    static Edge a(String a, String b) { return new Edge(a, b, false); }

    public static void main(String[] args) {
        Map<String, String> dbBefore = new TreeMap<>(Map.of(
                "order-service", "orders_db", "fulfilment-service", "orders_db", "invoice-service", "orders_db",
                "payment-service", "payments_db", "inventory-service", "inventory_db", "catalog-service", "catalog_db",
                "pricing-service", "catalog_db", "checkout-service", "checkout_db", "cart-service", "redis_cart"));
        Catalog before = new Catalog("BEFORE: the 'microservices' that grew out of the monolith", dbBefore, List.of(
                s("api-gateway", "checkout-service"), s("checkout-service", "cart-service"), s("cart-service", "pricing-service"),
                s("pricing-service", "catalog-service"), s("catalog-service", "inventory-service"), s("inventory-service", "catalog-service"),
                s("checkout-service", "order-service"), s("order-service", "payment-service"), s("payment-service", "order-service"),
                s("order-service", "inventory-service"), s("order-service", "notification-service"), s("order-service", "invoice-service"),
                s("invoice-service", "pricing-service"), s("fulfilment-service", "order-service"), s("order-service", "fulfilment-service"),
                s("api-gateway", "order-service")));
        Map<String, String> dbAfter = new TreeMap<>(Map.of(
                "order-service", "orders_db", "fulfilment-service", "fulfilment_db", "invoice-service", "invoice_db",
                "payment-service", "payments_db", "inventory-service", "inventory_db", "catalog-service", "catalog_db",
                "pricing-service", "pricing_db", "checkout-service", "checkout_db", "cart-service", "redis_cart"));
        Catalog after = new Catalog("AFTER: boundaries redrawn, facts flow as events", dbAfter, List.of(
                s("api-gateway", "checkout-service"), s("checkout-service", "cart-service"), s("checkout-service", "pricing-service"),
                s("checkout-service", "inventory-service"), s("checkout-service", "payment-service"), s("checkout-service", "order-service"),
                a("catalog-service", "pricing-service"), a("catalog-service", "inventory-service"),
                a("order-service", "notification-service"), a("order-service", "invoice-service"), a("order-service", "fulfilment-service"),
                a("payment-service", "order-service"), a("fulfilment-service", "order-service"), a("pricing-service", "invoice-service"),
                s("api-gateway", "order-service")));
        report(before);
        report(after);
    }
}
