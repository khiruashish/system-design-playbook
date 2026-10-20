// ResidencyL3.java — run with: java ResidencyL3.java   (JDK 21, no dependencies)
// DATA RESIDENCY AND TRANSFER RULES AS POLICY-AS-CODE. Architects draw data flows — DR replicas, analytics exports, log
// shipping, vendor integrations — and each one moves some CLASSES of data to some PLACE for some PURPOSE. Whether that's
// allowed is decided by rules. Here the platform's rules are encoded as data and every flow in the architecture manifest is
// evaluated in CI, so a design that replicates payment data abroad fails review before anyone builds it.
// The rules below are THIS PLATFORM'S ENCODING of obligations it has identified (modelled on RBI's 2018 payment-data
// storage direction, RBI card-on-file tokenisation, the DPDP Act 2023's purpose/consent model, and GST record retention).
// They are illustrative engineering policy, NOT legal advice; real rules come from counsel and change over time.
import java.util.*;

public class ResidencyL3 {
    enum Region { IN_MUMBAI, IN_HYDERABAD, SG, US, EU }
    static boolean india(Region r) { return r == Region.IN_MUMBAI || r == Region.IN_HYDERABAD; }

    record Flow(String id, String description, Set<String> dataClasses, Region destination, String purpose, Set<String> safeguards) {}
    record Finding(String flow, String rule, String detail) {}

    // consented / legitimate purposes per data class (DPDP: process only for the purpose consented or a legitimate use)
    static final Map<String, Set<String>> PURPOSES = Map.of(
        "personal", Set.of("order-fulfilment", "customer-support", "fraud-prevention", "legal-compliance", "dr"),
        "payment", Set.of("order-fulfilment", "fraud-prevention", "legal-compliance", "dr"),
        "behavioural", Set.of("order-fulfilment", "recommendations-with-consent", "dr"));

    static List<Finding> evaluate(Flow f) {
        List<Finding> out = new ArrayList<>();
        Set<String> c = f.dataClasses(); Set<String> s = f.safeguards();
        // R1 payment data: stored only in India; processing abroad allowed only if deleted there within one business day
        if (c.contains("payment") && !india(f.destination())) {
            boolean transientProcessing = s.contains("processing-only") && s.contains("deleted-abroad-within-24h");
            if (!transientProcessing) out.add(new Finding(f.id(), "R1 payment-data localisation", "payment data would be STORED in " + f.destination()));
        }
        // R2 card numbers: never outside the tokenisation vault (PCI scope); merchants keep tokens, not card numbers
        if (c.contains("card-number")) out.add(new Finding(f.id(), "R2 card data", "raw card numbers leave the PCI vault — use network tokens"));
        // R3 purpose limitation: every personal data class must have the flow's purpose among its allowed purposes
        for (String cls : new TreeSet<>(c)) if (PURPOSES.containsKey(cls) && !PURPOSES.get(cls).contains(f.purpose()) && !s.contains("anonymised"))
            out.add(new Finding(f.id(), "R3 purpose limitation", "'" + cls + "' data used for '" + f.purpose() + "' without a matching purpose/consent"));
        // R4 cross-border personal data: allowed unless the destination is on a government-restricted list; require encryption in transit+rest
        Set<Region> restricted = Set.of();                     // placeholder: DPDP s.16 lets the government notify restricted countries
        if ((c.contains("personal") || c.contains("behavioural")) && !india(f.destination()) && !s.contains("anonymised")) {
            if (restricted.contains(f.destination())) out.add(new Finding(f.id(), "R4 restricted destination", f.destination() + " is restricted"));
            if (!s.contains("encrypted")) out.add(new Finding(f.id(), "R4 cross-border safeguards", "personal data to " + f.destination() + " without encryption"));
            if (!s.contains("dpa")) out.add(new Finding(f.id(), "R4 cross-border safeguards", "no data-processing agreement with the recipient"));
        }
        // R5 GST records: the invoice archive must keep 72 months after the annual-return due date; flows may not shorten it
        if (c.contains("gst-invoice") && s.stream().anyMatch(x -> x.startsWith("retention-")) && !s.contains("retention-72m+"))
            out.add(new Finding(f.id(), "R5 GST retention", "retention shorter than the statutory period"));
        return out;
    }

    public static void main(String[] args) {
        List<Flow> manifest = List.of(
            new Flow("F1", "orders DB → DR replica (Hyderabad)", Set.of("personal", "payment", "gst-invoice"), Region.IN_HYDERABAD, "dr", Set.of("encrypted", "retention-72m+")),
            new Flow("F2", "orders DB → DR replica (Singapore)", Set.of("personal", "payment"), Region.SG, "dr", Set.of("encrypted", "dpa")),
            new Flow("F3", "payment auth → card network (abroad leg)", Set.of("payment"), Region.US, "order-fulfilment", Set.of("processing-only", "deleted-abroad-within-24h", "encrypted", "dpa")),
            new Flow("F4", "clickstream → US marketing SaaS", Set.of("personal", "behavioural"), Region.US, "ad-targeting", Set.of("encrypted", "dpa")),
            new Flow("F5", "clickstream → US marketing SaaS (aggregated)", Set.of("behavioural"), Region.US, "ad-targeting", Set.of("anonymised")),
            new Flow("F6", "app logs → EU observability vendor", Set.of("personal", "card-number"), Region.EU, "customer-support", Set.of("encrypted", "dpa")),
            new Flow("F7", "support tickets → SG helpdesk vendor", Set.of("personal"), Region.SG, "customer-support", Set.of("encrypted", "dpa")),
            new Flow("F8", "fraud signals → US fraud vendor", Set.of("personal", "payment"), Region.US, "fraud-prevention", Set.of("encrypted", "dpa")),
            new Flow("F9", "invoice archive → cold storage", Set.of("gst-invoice", "personal"), Region.IN_MUMBAI, "legal-compliance", Set.of("encrypted", "retention-36m")),
            new Flow("F10", "recommendations model training", Set.of("behavioural"), Region.IN_MUMBAI, "recommendations-with-consent", Set.of("encrypted")));

        System.out.printf("Evaluating %d data flows from the architecture manifest against %d residency/transfer rules.%n%n", manifest.size(), 5);
        int blocked = 0;
        for (Flow f : manifest) {
            List<Finding> fs = evaluate(f);
            System.out.printf("   %-4s %-46s → %-12s %s%n", f.id(), f.description(), f.destination(), fs.isEmpty() ? "PASS" : "BLOCK");
            for (Finding x : fs) System.out.printf("          ✗ %-28s %s%n", x.rule(), x.detail());
            if (!fs.isEmpty()) blocked++;
        }
        System.out.printf("%n   %d of %d flows blocked.%n", blocked, manifest.size());

        // consequence for DR design: where can a full orders-DB replica go?
        System.out.print("   Allowed regions for a full orders-DB DR replica: ");
        List<String> ok = new ArrayList<>();
        for (Region r : Region.values())
            if (evaluate(new Flow("DR", "", Set.of("personal", "payment", "gst-invoice"), r, "dr", Set.of("encrypted", "dpa", "retention-72m+"))).isEmpty()) ok.add(r.name());
        System.out.println(ok);

        System.out.println("\n   → the dangerous flows are ordinary engineering decisions: a DR replica in Singapore (F2) is 'just' a failover region,");
        System.out.println("     but it stores payment data outside India; debug logs shipped to an observability vendor (F6) carry card numbers");
        System.out.println("     (L2 finds them); a clickstream export (F4) reuses personal data for ad targeting the customer never agreed to — the");
        System.out.println("     aggregated version (F5) of the same export passes. A payment network's foreign leg (F3) passes only because it");
        System.out.println("     processes transiently and deletes within a day. Encoding the rules and running them over every manifest change");
        System.out.println("     turns compliance from a yearly audit surprise into a failed build, and makes the DR decision explicit: full-copy");
        System.out.println("     replicas stay in Indian regions; anything abroad must be transient, anonymised, or have payment data removed.");
    }
}
