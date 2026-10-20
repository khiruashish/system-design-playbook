// BlastRadiusL3.java — run with: java BlastRadiusL3.java   (JDK 21, no dependencies)
// WHAT BREAKS WHEN ONE SERVICE FAILS? The platform's 26 services (Ch 00) and its main user journeys, as a dependency
// graph: each journey is a tree of synchronous calls. Two versions of the SAME platform:
//   AS-BUILT  every call is a hard, synchronous dependency (what systems drift into): checkout also sends the
//             confirmation SMS, writes the audit record and generates the invoice inline; every request validates
//             its token by calling identity; the home page needs recommendations and promotions to render
//   DESIGNED  soft dependencies degrade (recommendations → bestsellers, promotions → no auto-coupon, search →
//             category browse, tracking map → status only, inventory on cart → "check at checkout"), tokens are
//             verified locally at the gateway (signed JWT), and notification / audit / invoice / fulfilment happen
//             asynchronously from order events (Kafka + outbox)
// Real graph traversal: for every single-service failure, which journeys fail, weighted by their share of traffic.
// Also: the longest synchronous call chain on each journey.
import java.util.*;

public class BlastRadiusL3 {
    record Call(String svc, boolean soft, List<Call> children) { Call(String s, boolean soft, Call... c) { this(s, soft, List.of(c)); } }
    static Call hard(String s, Call... c) { return new Call(s, false, c); }
    static Call soft(String s, Call... c) { return new Call(s, true, c); }

    public static void main(String[] args) {
        Map<String, Double> share = new LinkedHashMap<>();
        share.put("home / browse", 0.40); share.put("search", 0.22); share.put("add to cart", 0.15); share.put("checkout", 0.05);
        share.put("track order", 0.10); share.put("login / OTP", 0.03); share.put("seller: update catalogue", 0.05);
        for (int v = 0; v < 2; v++) {
            boolean designed = v == 1; Map<String, Call> j = journeys(designed);
            Set<String> services = new TreeSet<>(); for (Call c : j.values()) collect(c, services);
            System.out.printf("%s — %d services on synchronous paths%n", designed ? "DESIGNED" : "AS-BUILT", services.size());
            System.out.printf("   %-28s %22s   %s%n", "journey", "longest sync chain", "services it can't work without");
            for (var e : j.entrySet()) { Set<String> hardSet = new TreeSet<>(); hardDeps(e.getValue(), hardSet);
                System.out.printf("   %-28s %22d   %s%n", e.getKey(), depth(e.getValue()), String.join(", ", hardSet)); }
            List<Object[]> rows = new ArrayList<>();
            for (String s : services) { double fail = 0; List<String> broken = new ArrayList<>();
                for (var e : j.entrySet()) if (breaks(e.getValue(), s)) { fail += share.get(e.getKey()); broken.add(e.getKey()); }
                rows.add(new Object[]{s, fail, broken}); }
            rows.sort((a, b) -> Double.compare((double) b[1], (double) a[1]));
            double sum = 0; int spof = 0, breakCheckout = 0; for (Object[] r : rows) { sum += (double) r[1]; if ((double) r[1] > 0) spof++; if (((List<?>) r[2]).contains("checkout")) breakCheckout++; }
            System.out.printf("%n   services whose failure breaks user traffic: %d; that break CHECKOUT: %d; traffic lost summed over all%n", spof, breakCheckout);
            System.out.printf("   single-service failures: %.0f%% (i.e. %.1f platforms' worth of traffic if each failed once)%n", 100 * sum, sum);
            System.out.println("   worst five:");
            for (int k = 0; k < 5; k++) { Object[] r = rows.get(k); System.out.printf("   %-26s %5.0f%% of requests fail   (%s)%n", r[0], 100 * (double) r[1], r[2].toString().replaceAll("[\\[\\]]", "")); }
            System.out.println();
        }
        System.out.println("   → the difference between the two platforms is not the services — it's the edges. Every synchronous, hard");
        System.out.println("     dependency adds its failure to its callers'. Verifying tokens locally removes identity from every request;");
        System.out.println("     moving confirmations, audit, invoices and fulfilment behind events removes them from checkout; giving optional");
        System.out.println("     features a degraded answer removes them from the home page. What remains — gateway, catalogue, pricing,");
        System.out.println("     serviceability, cart, checkout, inventory, payment, order — is the platform's true critical set: those get");
        System.out.println("     redundancy, cells and the strictest SLOs. Draw this graph from real traces, not from the architecture diagram.");
    }

    static boolean breaks(Call c, String failed) {                                               // journey fails if a HARD path reaches the failed service
        if (c.svc().equals(failed)) return !c.soft();
        if (c.soft()) return false;                                                                // a soft subtree degrades instead of failing the journey
        for (Call ch : c.children()) if (breaks(ch, failed)) return true;
        return false;
    }
    static void hardDeps(Call c, Set<String> out) { if (c.soft()) return; out.add(c.svc()); for (Call ch : c.children()) hardDeps(ch, out); }
    static int depth(Call c) { int m = 0; for (Call ch : c.children()) m = Math.max(m, depth(ch)); return 1 + m; }
    static void collect(Call c, Set<String> out) { out.add(c.svc()); for (Call ch : c.children()) collect(ch, out); }

    static Map<String, Call> journeys(boolean d) {
        Map<String, Call> j = new LinkedHashMap<>();
        Call auth = d ? null : hard("identity-service");                                          // as-built: every request calls identity
        java.util.function.Function<Call[], Call> gw = kids -> { List<Call> l = new ArrayList<>(Arrays.asList(kids)); if (auth != null) l.add(0, auth); return new Call("api-gateway", false, l); };
        Call rec = d ? soft("recommendation-service") : hard("recommendation-service");
        Call promo = d ? soft("promotion-service") : hard("promotion-service");
        j.put("home / browse", gw.apply(new Call[]{hard("catalog-service"), hard("pricing-service"), hard("serviceability-service"), rec, promo}));
        j.put("search", gw.apply(new Call[]{d ? soft("search-service", hard("catalog-service")) : hard("search-service", hard("catalog-service"), hard("pricing-service")), hard("catalog-service"), hard("pricing-service")}));
        j.put("add to cart", gw.apply(new Call[]{hard("cart-service", hard("catalog-service"), hard("pricing-service"), d ? soft("inventory-service") : hard("inventory-service"))}));
        List<Call> co = new ArrayList<>(List.of(hard("customer-service"), hard("serviceability-service"), hard("pricing-service"), promo, hard("inventory-service"), hard("slot-service"), hard("payment-service"),
                d ? hard("order-service") : hard("order-service", hard("invoice-service"), hard("audit-service"), hard("fulfilment-service"))));
        if (!d) co.add(hard("notification-service"));
        j.put("checkout", gw.apply(new Call[]{hard("cart-service"), new Call("checkout-service", false, co)}));
        j.put("track order", gw.apply(new Call[]{hard("order-service"), d ? soft("tracking-service") : hard("tracking-service", hard("dispatch-service"))}));
        j.put("login / OTP", new Call("api-gateway", false, List.of(hard("identity-service", hard("notification-service")))));
        j.put("seller: update catalogue", gw.apply(new Call[]{hard("seller-service"), hard("catalog-service", d ? soft("search-service") : hard("search-service"), hard("audit-service"))}));
        return j;
    }
}
