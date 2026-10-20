// C4ModelL2.java — run with: java C4ModelL2.java   (JDK 21, no dependencies)
// DIAGRAMS AS CODE from ONE MODEL (the Structurizr idea). Hand-drawn diagrams drift apart: the context diagram says one
// thing, the container diagram another, and neither matches production. Instead we define the architecture ONCE as a
// model — people, systems, containers, relationships — and GENERATE every C4 view from it. Because it is a model, we can
// also VALIDATE it: every container needs an owning team, every relationship needs a protocol, nothing may be orphaned.
import java.util.*;
import java.util.stream.*;

public class C4ModelL2 {
    enum Kind { PERSON, SYSTEM, EXTERNAL, CONTAINER, DATABASE, QUEUE }
    record Element(String id, String name, Kind kind, String parent, String owner, String tech) {}
    record Rel(String from, String to, String what, String protocol) {}

    static final Map<String, Element> E = new LinkedHashMap<>();
    static final List<Rel> R = new ArrayList<>();
    static void el(String id, String name, Kind k, String parent, String owner, String tech) { E.put(id, new Element(id, name, k, parent, owner, tech)); }
    static void rel(String f, String t, String what, String proto) { R.add(new Rel(f, t, what, proto)); }

    static void buildModel() {
        el("customer", "Customer", Kind.PERSON, null, null, null);
        el("rider", "Delivery rider", Kind.PERSON, null, null, null);
        el("shop", "Grocery Platform", Kind.SYSTEM, null, "platform", null);
        el("razorpay", "Razorpay", Kind.EXTERNAL, null, null, null);
        el("sms", "SMS gateway", Kind.EXTERNAL, null, null, null);
        // containers inside the platform
        el("app", "Mobile app", Kind.CONTAINER, "shop", "mobile-team", "Kotlin/Swift");
        el("gw", "API gateway", Kind.CONTAINER, "shop", "platform", "Spring Cloud Gateway");
        el("checkout", "Checkout service", Kind.CONTAINER, "shop", "checkout-team", "Spring Boot");
        el("catalog", "Catalog service", Kind.CONTAINER, "shop", "catalog-team", "Spring Boot");
        el("dispatch", "Dispatch service", Kind.CONTAINER, "shop", null, "Spring Boot");          // ← no owner (bug)
        el("ordersdb", "Orders DB", Kind.DATABASE, "shop", "checkout-team", "PostgreSQL 16");
        el("events", "Order events", Kind.QUEUE, "shop", "platform", "Kafka");
        el("search", "Search index", Kind.DATABASE, "shop", "catalog-team", "Meilisearch");      // ← orphan (bug)

        rel("customer", "app", "orders groceries with", "HTTPS");
        rel("rider", "app", "accepts deliveries with", "HTTPS");
        rel("app", "gw", "calls", "HTTPS/JSON");
        rel("gw", "checkout", "routes checkout to", "HTTP/JSON");
        rel("gw", "catalog", "routes browse to", "HTTP/JSON");
        rel("checkout", "catalog", "reads prices from", "gRPC");
        rel("checkout", "ordersdb", "stores orders in", "JDBC");
        rel("checkout", "razorpay", "takes payment via", "HTTPS");
        rel("checkout", "events", "publishes OrderPlaced to", null);                                   // ← no protocol (bug)
        rel("events", "dispatch", "delivers OrderPlaced to", "Kafka consumer");
        rel("dispatch", "sms", "notifies customer via", "HTTPS");
    }

    // map an element to the nearest element visible at a given level (context: collapse containers into their system)
    static String visible(String id, boolean context) {
        Element e = E.get(id);
        return (context && e.parent() != null) ? e.parent() : id;
    }
    static String shape(Element e) {
        String label = e.name() + (e.tech() != null ? " - " + e.tech() : "");
        return switch (e.kind()) {
            case PERSON -> e.id() + "([" + label + "])";
            case DATABASE -> e.id() + "[(" + label + ")]";
            case QUEUE -> e.id() + "[[" + label + "]]";
            case EXTERNAL -> e.id() + "[" + label + " - external]";
            default -> e.id() + "[" + label + "]";
        };
    }
    static String render(boolean context) {
        StringBuilder sb = new StringBuilder("flowchart LR\n");
        Set<String> shown = new LinkedHashSet<>();
        Set<String> edges = new LinkedHashSet<>();
        for (Rel r : R) {
            String a = visible(r.from(), context), b = visible(r.to(), context);
            if (a.equals(b)) continue;                              // internal to the system at context level
            shown.add(a); shown.add(b);
            String label = context ? r.what() : r.what() + " - " + (r.protocol() == null ? "?" : r.protocol());
            edges.add("    " + a + " -->|" + label + "| " + b);
        }
        if (!context) {                                             // container view groups containers in a subgraph
            sb.append("    subgraph shop[Grocery Platform]\n");
            for (Element e : E.values()) if ("shop".equals(e.parent())) sb.append("      ").append(shape(e)).append("\n");
            sb.append("    end\n");
            for (String id : shown) if (!"shop".equals(E.get(id).parent())) sb.append("    ").append(shape(E.get(id))).append("\n");
        } else for (String id : shown) sb.append("    ").append(shape(E.get(id))).append("\n");
        edges.forEach(x -> sb.append(x).append("\n"));
        return sb.toString();
    }

    static List<String> validate() {
        List<String> v = new ArrayList<>();
        Set<String> connected = new HashSet<>();
        for (Rel r : R) { connected.add(r.from()); connected.add(r.to()); }
        for (Element e : E.values()) {
            boolean isContainer = e.parent() != null;
            if (isContainer && e.owner() == null) v.add("V1 no owner       container '" + e.name() + "' has no owning team");
            if (e.kind() != Kind.SYSTEM && !connected.contains(e.id())) v.add("V2 orphan         '" + e.name() + "' has no relationships (dead, or undocumented)");
        }
        for (Rel r : R) if (r.protocol() == null) v.add("V3 no protocol     " + E.get(r.from()).name() + " → " + E.get(r.to()).name() + " (" + r.what() + ")");
        return v;
    }

    public static void main(String[] args) {
        buildModel();
        System.out.println("One architecture model → every C4 view is generated from it, and the model can be validated.\n");
        System.out.println("   Model: " + E.values().stream().collect(Collectors.groupingBy(Element::kind, TreeMap::new, Collectors.counting()))
                + ", " + R.size() + " relationships\n");
        System.out.println("=== Generated C4 Level 1 — System Context (containers collapsed into their system) ===");
        System.out.print(render(true));
        System.out.println("\n=== Generated C4 Level 2 — Containers (same model, more detail) ===");
        System.out.print(render(false));
        System.out.println("\n=== Model validation ===");
        List<String> v = validate();
        v.forEach(x -> System.out.println("   ✗ " + x));
        System.out.println("   → " + v.size() + " problem(s) found.\n");
        System.out.println("   → both diagrams come from one model, so they cannot contradict each other, and a rename happens once.");
        System.out.println("     The same model answers questions a picture can't: which container has no owner (Dispatch — who gets paged?),");
        System.out.println("     what is drawn but connected to nothing (Search index — dead, or a dependency nobody documented?), and which");
        System.out.println("     arrows hide their protocol. Structurizr DSL / C4-PlantUML do this for real; keep the model in the repo and");
        System.out.println("     render it in CI so the diagrams in the design doc are always regenerated from the current model.");
    }
}
