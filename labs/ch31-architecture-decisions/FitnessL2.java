// FitnessL2.java — run with: java FitnessL2.java   (JDK 21, no dependencies)
// ARCHITECTURE FITNESS FUNCTIONS (the core idea of *evolutionary architecture*): an architectural rule you can no longer
// only draw on a whiteboard, but EXECUTE as a test in CI, so the architecture can't silently erode as the code changes.
// We model the service/module dependency graph and run three fitness functions against it:
//   F1  LAYERING — dependencies may only point "down" (api → application → domain → infrastructure), never up/sideways.
//   F2  NO CYCLES — no dependency cycle between modules (a cycle means you can't build/deploy/reason about them apart).
//   F3  DOMAIN PURITY — the domain layer must not depend on infrastructure (keep business logic free of frameworks/IO).
// We run them on a graph that has real violations (FAIL, naming them), then on a fixed graph (PASS). This is what you put
// in CI — ArchUnit/jQAssistant do exactly this on real bytecode; here we implement the checks so the mechanism is visible.
import java.util.*;

public class FitnessL2 {
    // layers, ordered top (0) to bottom; a dependency is legal only if target layer index >= source layer index
    static final List<String> LAYERS = List.of("api", "application", "domain", "infrastructure");
    static int layerOf(String module, Map<String,String> layer) { return LAYERS.indexOf(layer.get(module)); }

    record Violation(String rule, String detail) {}

    static List<Violation> run(Map<String,String> layer, Map<String,List<String>> deps) {
        List<Violation> v = new ArrayList<>();
        // F1 LAYERING + F3 DOMAIN PURITY
        for (var e : deps.entrySet()) {
            String from = e.getKey();
            for (String to : e.getValue()) {
                int lf = layerOf(from, layer), lt = layerOf(to, layer);
                if (lt < lf)  // target is in a HIGHER layer than source → upward dependency
                    v.add(new Violation("F1 layering", from + " (" + layer.get(from) + ") → " + to + " (" + layer.get(to) + ") points UP a layer"));
                if (layer.get(from).equals("domain") && layer.get(to).equals("infrastructure"))
                    v.add(new Violation("F3 domain purity", from + " (domain) depends on " + to + " (infrastructure)"));
            }
        }
        // F2 NO CYCLES — DFS cycle detection over the module graph (report the first cycle found)
        Set<String> visiting = new HashSet<>(), done = new HashSet<>();
        for (String m : deps.keySet()) {
            if (done.contains(m)) continue;
            String cyc = findCycle(m, deps, visiting, done, new ArrayList<>());
            if (cyc != null) { v.add(new Violation("F2 no cycles", cyc)); break; }   // stop at first cycle (state is now stale)
        }
        return v;
    }
    static String findCycle(String node, Map<String,List<String>> deps, Set<String> visiting, Set<String> done, List<String> path) {
        visiting.add(node); path.add(node);
        for (String next : deps.getOrDefault(node, List.of())) {
            if (visiting.contains(next)) {                       // back-edge → cycle
                int i = path.indexOf(next);
                return String.join(" → ", path.subList(i, path.size())) + " → " + next;
            }
            if (!done.contains(next)) { String c = findCycle(next, deps, visiting, done, path); if (c != null) return c; }
        }
        visiting.remove(node); done.add(node); path.remove(path.size() - 1);
        return null;
    }

    static void report(String label, Map<String,String> layer, Map<String,List<String>> deps) {
        List<Violation> v = run(layer, deps);
        System.out.println("   " + label + ":");
        if (v.isEmpty()) System.out.println("        ✓ all fitness functions PASS — architecture conforms.");
        else { for (Violation x : v) System.out.printf("        ✗ %-16s %s%n", x.rule(), x.detail()); System.out.printf("        → %d violation(s): build FAILS.%n", v.size()); }
        System.out.println();
    }

    public static void main(String[] args) {
        Map<String,String> layer = new LinkedHashMap<>();
        layer.put("OrderApi","api"); layer.put("OrderService","application"); layer.put("Order","domain");
        layer.put("Pricing","domain"); layer.put("OrderRepoJpa","infrastructure"); layer.put("PaymentGatewayHttp","infrastructure");

        System.out.println("Architecture fitness functions on the module dependency graph (api→application→domain→infrastructure).\n");

        // BEFORE: a graph that eroded — three real violations
        Map<String,List<String>> bad = new LinkedHashMap<>();
        bad.put("OrderApi", List.of("OrderService"));
        bad.put("OrderService", List.of("Order", "Pricing", "OrderRepoJpa"));
        bad.put("Order", List.of("Pricing", "PaymentGatewayHttp"));   // F3: domain → infrastructure
        bad.put("Pricing", List.of("OrderService"));                   // F1: domain → application (points UP)  AND F2 cycle
        bad.put("OrderRepoJpa", List.of());
        bad.put("PaymentGatewayHttp", List.of());
        report("BEFORE (architecture has eroded)", layer, bad);

        // AFTER: the fixes — domain depends only downward/sideways within domain, infra accessed via a port in domain
        Map<String,List<String>> good = new LinkedHashMap<>();
        good.put("OrderApi", List.of("OrderService"));
        good.put("OrderService", List.of("Order", "Pricing", "OrderRepoJpa", "PaymentGatewayHttp")); // app wires infra
        good.put("Order", List.of("Pricing"));                         // domain depends only on domain
        good.put("Pricing", List.of());                                // no upward dep → cycle broken
        good.put("OrderRepoJpa", List.of());
        good.put("PaymentGatewayHttp", List.of());
        report("AFTER (fixed: infra wired by the application layer, domain pure)", layer, good);

        System.out.println("   → the same three rules that architects usually enforce by review (and lose) are here EXECUTABLE: the eroded graph");
        System.out.println("     fails the build naming each violation — an upward dependency, a domain→infrastructure leak, and the cycle it formed;");
        System.out.println("     the fixed graph passes. Run these in CI (ArchUnit/jQAssistant on real bytecode) and the architecture can evolve");
        System.out.println("     safely because every change is checked against its constraints — governance by test, not by tribal memory.");
    }
}
