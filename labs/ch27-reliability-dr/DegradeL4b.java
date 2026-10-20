// DegradeL4b.java — graceful degradation ladder: as dependencies fail, the checkout page sheds features in priority order
// instead of going fully down. Each of 6 dependencies has a criticality; when it's down, the page drops just that feature.
// We measure, at each failure level, whether the CORE journey (add-to-cart → pay) still works and what the user still sees.
import java.util.*;
public class DegradeL4b {
    record Dep(String name, boolean core, String ifDown) {}
    static final List<Dep> DEPS = List.of(
        new Dep("catalog (product data)", true,  "cannot show the product — hard dependency"),
        new Dep("inventory (stock)",       true,  "cannot promise availability — hard dependency"),
        new Dep("payment gateway",         true,  "cannot take money — hard dependency"),
        new Dep("recommendations",         false, "hide the 'you may also like' row"),
        new Dep("reviews/ratings",         false, "hide stars; show 'ratings unavailable'"),
        new Dep("personalization/promos",  false, "fall back to default (non-personalized) pricing & banners"));
    public static void main(String[] a){
        System.out.println("Checkout page dependencies (core = the purchase cannot complete without it):");
        for (Dep d : DEPS) System.out.printf("   %-28s %s%n", d.name(), d.core()?"CORE":"degradable → " + d.ifDown());
        System.out.println("\nProgressive failure: bring dependencies down one by one (non-core first) and check the core journey:");
        System.out.printf("   %-40s %-14s %s%n","dependencies down","core works?","user experience");
        // fail non-core first (graceful), then a core one (hard fail)
        List<Dep> order = new ArrayList<>(); DEPS.stream().filter(d->!d.core()).forEach(order::add); DEPS.stream().filter(Dep::core).forEach(order::add);
        Set<String> down = new LinkedHashSet<>();
        System.out.printf("   %-40s %-14s %s%n","(none)","YES","full experience");
        for (Dep d : order){
            down.add(d.name());
            boolean coreOk = DEPS.stream().filter(Dep::core).noneMatch(c->down.contains(c.name()));
            String ux = coreOk ? "degraded: " + (int)DEPS.stream().filter(x->!x.core()&&down.contains(x.name())).count() + " feature(s) hidden, checkout still works"
                               : "HARD DOWN: " + d.name() + " is core — checkout cannot complete";
            System.out.printf("   %-40s %-14s %s%n", down.size()+" ("+shortName(d)+")", coreOk?"YES":"NO", ux);
            if (!coreOk) break;
        }
        System.out.println("\n   → losing all 3 non-core dependencies still lets customers buy (a visibly reduced but working page).");
        System.out.println("     Only a CORE dependency (catalog/inventory/payment) takes checkout down — those get the strongest resilience");
        System.out.println("     (replicas, failover, Ch 18 patterns). A degradation catalogue decides, per feature, what to drop before an incident.");
    }
    static String shortName(Dep d){ return d.name().split(" ")[0]; }
}
