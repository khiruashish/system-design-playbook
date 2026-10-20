// AuthzL3.java — run with: java AuthzL3.java      (JDK 17+, no dependencies)
// Three authorization models on the grocery platform's own scenarios: RBAC (roles), ABAC (attributes/policy),
// and ReBAC (relationship graph, Google Zanzibar style). Same 12 access requests through each, so you can see
// what each model can and cannot express, plus the classic IDOR bug (missing object-level check) as a negative control.
import java.util.*;
import java.util.stream.*;

public class AuthzL3 {
    // ---------------- the scenario ----------------
    record Subject(String id, Set<String> roles, String storeId, String city, String dept) {}
    record Resource(String type, String id, String ownerId, String storeId, String city, int amountPaise, boolean pii) {}
    record Request(String desc, Subject who, String action, Resource what) {}

    static final Subject CUSTOMER   = new Subject("cust-42", Set.of("customer"), null, "Bengaluru", null);
    static final Subject OTHER_CUST = new Subject("cust-99", Set.of("customer"), null, "Bengaluru", null);
    static final Subject RIDER      = new Subject("rider-7", Set.of("rider"), null, "Bengaluru", null);
    static final Subject PICKER     = new Subject("emp-1", Set.of("store_picker"), "BLR17", "Bengaluru", "ops");
    static final Subject STORE_MGR  = new Subject("emp-2", Set.of("store_manager"), "BLR17", "Bengaluru", "ops");
    static final Subject CITY_MGR   = new Subject("emp-3", Set.of("city_manager"), null, "Bengaluru", "ops");
    static final Subject SUPPORT    = new Subject("emp-4", Set.of("support_agent"), null, null, "cx");
    static final Subject FINANCE    = new Subject("emp-5", Set.of("finance_analyst"), null, null, "finance");

    static final Resource ORDER_42   = new Resource("order", "o-42", "cust-42", "BLR17", "Bengaluru", 149900, false);
    static final Resource ORDER_99   = new Resource("order", "o-99", "cust-99", "BLR17", "Bengaluru", 89900, false);
    static final Resource ORDER_MUM  = new Resource("order", "o-500", "cust-77", "MUM03", "Mumbai", 250000, false);
    static final Resource REFUND_BIG = new Resource("refund", "rf-1", "cust-42", "BLR17", "Bengaluru", 800000, false);
    static final Resource CUST_PII   = new Resource("customer_profile", "cust-42", "cust-42", null, "Bengaluru", 0, true);

    static final List<Request> REQUESTS = List.of(
        new Request("customer views their own order", CUSTOMER, "read", ORDER_42),
        new Request("customer views someone else's order (IDOR)", CUSTOMER, "read", ORDER_99),
        new Request("rider views the order they're delivering", RIDER, "read", ORDER_42),
        new Request("picker updates an order in their store", PICKER, "update", ORDER_42),
        new Request("picker updates an order in another store", PICKER, "update", ORDER_MUM),
        new Request("store manager refunds ₹1,499 in their store", STORE_MGR, "refund", ORDER_42),
        new Request("store manager refunds ₹8,000 (over limit)", STORE_MGR, "refund", REFUND_BIG),
        new Request("city manager reads a Bengaluru store order", CITY_MGR, "read", ORDER_42),
        new Request("city manager reads a Mumbai order", CITY_MGR, "read", ORDER_MUM),
        new Request("support reads customer PII (open ticket)", SUPPORT, "read", CUST_PII),
        new Request("support reads customer PII (no ticket)", SUPPORT, "read", CUST_PII),
        new Request("finance reads an order amount", FINANCE, "read", ORDER_42));

    // context: which support agent has an open ticket for which customer (a relationship, really)
    static final Map<String, String> openTickets = Map.of("emp-4", "cust-42");     // set null for the "no ticket" case below
    static boolean supportHasTicket(Subject s, Resource r, boolean ticketOpen) { return ticketOpen && "cust-42".equals(r.ownerId()); }

    // ---------------- 1. RBAC ----------------
    // Roles → allowed (action, resourceType). Simple, coarse: cannot say "own" or "same store" or "≤ limit".
    static final Map<String, Set<String>> RBAC = Map.of(
        "customer", Set.of("read:order", "read:customer_profile"),
        "rider", Set.of("read:order"),
        "store_picker", Set.of("read:order", "update:order"),
        "store_manager", Set.of("read:order", "update:order", "refund:refund", "refund:order"),
        "city_manager", Set.of("read:order", "update:order"),
        "support_agent", Set.of("read:order", "read:customer_profile"),
        "finance_analyst", Set.of("read:order"));
    static boolean rbac(Request q) {
        String perm = q.action() + ":" + q.what().type();
        return q.who().roles().stream().anyMatch(role -> RBAC.getOrDefault(role, Set.of()).contains(perm));
    }

    // ---------------- 2. ABAC ----------------
    // Policy over attributes of subject, action, resource and environment. Expresses ownership, scope, limits, purpose.
    static boolean abac(Request q, boolean ticketOpen) {
        Subject s = q.who(); Resource r = q.what(); String a = q.action();
        if (s.roles().contains("customer"))
            return a.equals("read") && s.id().equals(r.ownerId());                              // only your own resources
        if (s.roles().contains("rider"))
            return a.equals("read") && r.type().equals("order") && isAssignedRider(s, r);        // only the order you're delivering
        if (s.roles().contains("store_picker"))
            return List.of("read", "update").contains(a) && r.type().equals("order") && s.storeId().equals(r.storeId());
        if (s.roles().contains("store_manager")) {
            if (a.equals("refund")) return s.storeId().equals(r.storeId()) && r.amountPaise() <= 500000;   // ≤ ₹5,000 in own store
            return List.of("read", "update").contains(a) && s.storeId().equals(r.storeId());
        }
        if (s.roles().contains("city_manager"))
            return List.of("read", "update").contains(a) && s.city().equals(r.city());          // whole city
        if (s.roles().contains("support_agent"))
            return a.equals("read") && supportHasTicket(s, r, ticketOpen);                        // purpose limitation: needs an open ticket
        if (s.roles().contains("finance_analyst"))
            return a.equals("read") && !r.pii();                                                 // finance sees amounts, not PII
        return false;
    }
    static boolean isAssignedRider(Subject s, Resource r) { return "rider-7".equals(s.id()) && "o-42".equals(r.id()); }

    // ---------------- 3. ReBAC (Zanzibar-style relationship tuples) ----------------
    // Tuples: object#relation@subject. Check(object, relation, user) walks userset rewrites.
    record Tuple(String object, String relation, String user) {}
    static final Set<Tuple> TUPLES = Set.of(
        new Tuple("order:o-42", "owner", "user:cust-42"),
        new Tuple("order:o-99", "owner", "user:cust-99"),
        new Tuple("order:o-42", "delivering_rider", "user:rider-7"),
        new Tuple("store:BLR17", "member", "user:emp-1"),
        new Tuple("store:BLR17", "manager", "user:emp-2"),
        new Tuple("order:o-42", "parent_store", "store:BLR17"),
        new Tuple("order:o-99", "parent_store", "store:BLR17"),
        new Tuple("city:Bengaluru", "manager", "user:emp-3"),
        new Tuple("store:BLR17", "parent_city", "city:Bengaluru"),
        new Tuple("ticket:t-1", "assignee", "user:emp-4"),
        new Tuple("ticket:t-1", "subject_customer", "user:cust-42"));

    static boolean rebac(String object, String relation, String user, int depth) {
        if (depth > 8) return false;
        if (TUPLES.contains(new Tuple(object, relation, user))) return true;
        // userset rewrites (the schema): who can "view" an order?
        if (object.startsWith("order:") && relation.equals("view")) {
            if (rebac(object, "owner", user, depth + 1)) return true;
            if (rebac(object, "delivering_rider", user, depth + 1)) return true;
            for (Tuple t : TUPLES) if (t.object().equals(object) && t.relation().equals("parent_store"))
                if (rebac(t.user(), "manages", user, depth + 1)) return true;                    // store manager/member manages its orders
        }
        if (object.startsWith("store:") && relation.equals("manages")) {
            if (rebac(object, "manager", user, depth + 1) || rebac(object, "member", user, depth + 1)) return true;
            for (Tuple t : TUPLES) if (t.object().equals(object) && t.relation().equals("parent_city"))
                if (rebac(t.user(), "manager", user, depth + 1)) return true;                    // city manager inherits store management
        }
        return false;
    }

    // ---------------- the IDOR negative control ----------------
    // A handler that authenticates (valid token) but forgets the object-level ownership check.
    static boolean idorVulnerableHandler(Request q) { return q.who().roles().contains("customer") && q.action().equals("read"); }

    public static void main(String[] args) {
        System.out.println("Twelve access requests through each model (✓ allow, · deny). The 'no ticket' row flips ABAC's environment.");
        System.out.printf("   %-46s %-6s %-6s %-6s%n", "request", "RBAC", "ABAC", "ReBAC");
        int i = 0;
        for (Request q : REQUESTS) {
            boolean ticketOpen = !q.desc().contains("no ticket");
            boolean rb = rbac(q), ab = abac(q, ticketOpen);
            String reb = rebacApplies(q) ? (rebac("order:" + q.what().id(), "view", "user:" + q.who().id(), 0) ? "✓" : "·") : "n/a";
            System.out.printf("   %-46s %-6s %-6s %-6s%n", q.desc(), rb ? "✓" : "·", ab ? "✓" : "·", reb);
            i++;
        }

        System.out.println("\nWhat each model gets right or wrong on these requests:");
        System.out.println("   RBAC alone ALLOWS the IDOR read (customer→other's order), the cross-store update, the over-limit refund,");
        System.out.println("      the Mumbai read by the Bengaluru manager, and PII reads with no ticket: roles can't express own/scope/limit/purpose.");
        System.out.println("   ABAC denies all of those via attributes (ownership, store, ₹ limit, city, open-ticket purpose).");
        System.out.println("   ReBAC answers order 'view' from the relationship graph: owner, delivering rider, and manager via store→city inheritance,");
        System.out.println("      without the resource carrying storeId/city columns — the edges do. (Refund limits still need an ABAC-style condition.)");

        System.out.println("\nIDOR negative control — the same 'read order' requests through a handler that checks the role but not ownership:");
        for (Request q : REQUESTS) if (q.action().equals("read") && q.what().type().equals("order")) {
            boolean vuln = idorVulnerableHandler(q);
            boolean fixed = abac(q, true);
            System.out.printf("   %-46s vulnerable handler: %-8s  with object check: %s%n", q.desc(), vuln ? "LEAKS" : "denies", fixed ? "allows" : "denies");
        }
        System.out.println("   → Authentication is not authorization. Every object fetch must check the subject's relation to THAT object.");
    }

    static boolean rebacApplies(Request q) { return q.what().type().equals("order") && q.action().equals("read"); }
}
