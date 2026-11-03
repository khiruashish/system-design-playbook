import java.util.*;

/**
 * L3 — Cost controls: a semantic cache (and how it serves wrong answers) and small/large model routing.
 * Real algorithms (embedding similarity, cache policies, routing); SIMULATED traffic and model quality (stated assumptions);
 * the toy embedding is the same idea as L1. Fixed seed: output is deterministic.
 */
public class CostControlsL3 {
    // ---- question families: users phrase the same intent many ways ----
    record Family(String intent, boolean personal, String entitySlot, String[] phrasings) {}
    static final Family[] FAMILIES = {
        new Family("refund-policy", false, null, new String[]{"how do refunds work", "refund policy for damaged items", "how long does a refund take",
            "when will I get my refund money back", "refund timeline for missing items"}),
        new Family("delivery-fee", false, null, new String[]{"what is the delivery fee", "delivery charges for small orders", "is delivery free",
            "how much do you charge for delivery", "minimum order for free delivery"}),
        new Family("plus-benefits", false, null, new String[]{"what do plus members get", "benefits of plus membership", "is plus worth it",
            "plus subscription benefits", "what is included in plus"}),
        new Family("cancel-policy", false, null, new String[]{"can I cancel my order", "how to cancel an order", "cancel order after placing",
            "is cancellation allowed after packing", "cancel before delivery"}),
        new Family("recipe", false, "dish", new String[]{"ingredients for %s", "what do I need to cook %s", "shopping list for %s",
            "recipe items for %s", "how to make %s at home"}),
        new Family("stock", true, "area", new String[]{"is amul milk in stock in %s", "amul milk available in %s", "can I get amul milk in %s today",
            "amul milk delivery %s", "%s amul milk availability"}),
        new Family("order-status", true, "order", new String[]{"where is my order %s", "status of order %s", "track order %s", "has order %s shipped",
            "when will order %s arrive"}),
    };
    static final String[] DISHES = {"paneer butter masala", "chicken biryani", "masala dosa", "rajma chawal", "veg pulao", "aloo paratha"};
    static final String[] AREAS = {"koramangala", "indiranagar", "whitefield", "hsr layout", "jayanagar", "malleshwaram"};

    record Q(String text, String intent, String entity, boolean personal, int complexity) {
        String key() { return intent + "|" + entity; }                 // what the correct answer actually depends on
    }

    public static void main(String[] args) {
        Random rnd = new Random(62);
        List<Q> stream = new ArrayList<>();
        double[] familyWeight = {18, 12, 8, 10, 14, 16, 22};          // order status and stock questions dominate
        for (int i = 0; i < 20_000; i++) {
            Family f = FAMILIES[pick(rnd, familyWeight)];
            String entity = switch (f.entitySlot() == null ? "" : f.entitySlot()) {
                case "dish" -> DISHES[zipf(rnd, DISHES.length)];
                case "area" -> AREAS[zipf(rnd, AREAS.length)];
                case "order" -> "A" + (100_000 + rnd.nextInt(40_000));   // every customer's own order: never repeats much
                default -> "";
            };
            String text = String.format(f.phrasings()[rnd.nextInt(f.phrasings().length)], entity);
            int complexity = f.intent().equals("recipe") ? 2 : f.personal() ? 1 : rnd.nextInt(10) < 2 ? 3 : 1;   // 3 = multi-step reasoning
            stream.add(new Q(text, f.intent(), entity, f.personal(), complexity));
        }
        System.out.printf("20,000 assistant questions from 7 intent families, 5 phrasings each (simulated traffic).%n");
        System.out.printf("Entity-specific: %.0f%% (order status, stock by area, recipes by dish).%n%n",
            100.0 * stream.stream().filter(q -> q.entity() != null && !q.entity().isEmpty()).count() / stream.size());

        System.out.println("A. Semantic cache: reuse an answer when a new question's embedding is close to a cached one");
        System.out.println("   wrong = served an answer cached for a DIFFERENT intent or entity (another customer's order, another area's stock)");
        System.out.printf("   %-46s %10s %10s %12s%n", "cache policy", "hit rate", "wrong", "LLM calls");
        for (double t : new double[]{0.60, 0.70, 0.80, 0.90, 0.99})
            report("semantic, threshold " + t + ", cache everything", stream, t, false);
        report("exact text match only", stream, 2, false);
        report("key = intent + entities (NLU), personal never", stream, 0, true);
        System.out.println("   (last row: answers for stock would also carry a short TTL in production)");
        System.out.println("   Similarity of the toy embedding: paraphrases of one question 0.60-0.78; same template with another area or dish");
        System.out.println("   0.71-0.75; another customer's order number 0.99. The ranges overlap, so no threshold separates them.");

        System.out.println("\nB. Model routing: send easy questions to a small model (ILLUSTRATIVE prices and quality)");
        System.out.println("   small: $0.10 / 1M tokens, correct on easy 97%, hard 72%; large: $2.50 / 1M tokens, easy 99%, hard 95%");
        System.out.printf("   %-42s %12s %14s %18s%n", "routing", "cost / 1k", "answers OK", "hard answers OK");
        route("everything to the large model", stream, rnd, q -> true);
        route("everything to the small model", stream, rnd, q -> false);
        route("router: large only for multi-step questions", stream, rnd, q -> q.complexity() >= 3);
        route("router + escalate when small model unsure", stream, rnd, null);
        System.out.println("\n   -> a semantic cache is a correctness feature before it is a cost feature: similarity is not equality.");
        System.out.println("     Key the cache by what the answer depends on (intent + entities + user scope), and never share personal answers.");
        System.out.println("     Routing most traffic to a small model keeps quality if the hard tail is detected and escalated.");
    }

    static void report(String name, List<Q> stream, double threshold, boolean keyed) {
        List<float[]> keys = new ArrayList<>(); List<Q> owners = new ArrayList<>();
        Map<String, Q> exact = new HashMap<>();
        int hits = 0, wrong = 0, calls = 0;
        for (Q q : stream) {
            Q owner = null;
            if (keyed) {
                // NLU extracts intent + entities first; the cache key is what the answer depends on. Personal answers are never shared.
                if (q.personal()) { calls++; continue; }
                owner = exact.get(q.key());
                if (owner == null) exact.put(q.key(), q);
            } else if (threshold > 1) {
                owner = exact.get(q.text());
                if (owner == null) exact.put(q.text(), q);
            } else {
                float[] e = embed(q.text());
                int best = -1; double bestSim = -1;
                for (int i = 0; i < keys.size(); i++) { double s = dot(keys.get(i), e); if (s > bestSim) { bestSim = s; best = i; } }
                if (best >= 0 && bestSim >= threshold) owner = owners.get(best);
                else if (keys.size() < 1_500) { keys.add(e); owners.add(q); }
            }
            if (owner != null) { hits++; if (!owner.key().equals(q.key())) wrong++; } else calls++;
        }
        System.out.printf("   %-46s %9.1f%% %9.2f%% %12d%n", name, 100.0 * hits / stream.size(), 100.0 * wrong / stream.size(), calls);
    }

    static void route(String name, List<Q> stream, Random seed, java.util.function.Predicate<Q> large) {
        Random rnd = new Random(7);
        double cost = 0; int ok = 0, hard = 0, hardOk = 0;
        for (Q q : stream) {
            boolean isHard = q.complexity() >= 3;
            int tokens = 3_000 + (isHard ? 600 : 200);
            boolean useLarge;
            if (large != null) useLarge = large.test(q);
            else {
                // escalation: small model answers first; a cheap check (self-reported confidence / verifier) escalates 80% of its
                // likely-wrong hard answers and 3% of fine ones
                boolean smallOk = rnd.nextDouble() < (isHard ? 0.72 : 0.97);
                cost += tokens * 0.10 / 1e6;
                boolean escalate = smallOk ? rnd.nextDouble() < 0.03 : rnd.nextDouble() < 0.80;
                if (!escalate) { if (smallOk) ok++; if (isHard) { hard++; if (smallOk) hardOk++; } continue; }
                useLarge = true;
            }
            boolean correct = rnd.nextDouble() < (useLarge ? (isHard ? 0.95 : 0.99) : (isHard ? 0.72 : 0.97));
            cost += tokens * (useLarge ? 2.50 : 0.10) / 1e6;
            if (correct) ok++;
            if (isHard) { hard++; if (correct) hardOk++; }
        }
        System.out.printf("   %-42s %11s %13.1f%% %17.1f%%%n", name, String.format("$%.2f", cost / stream.size() * 1000), 100.0 * ok / stream.size(),
            100.0 * hardOk / hard);
    }

    static int pick(Random r, double[] w) { double s = 0; for (double x : w) s += x; double u = r.nextDouble() * s; for (int i = 0; i < w.length; i++) if ((u -= w[i]) < 0) return i; return w.length - 1; }
    static int zipf(Random r, int n) { double[] w = new double[n]; for (int i = 0; i < n; i++) w[i] = 1.0 / (i + 1); return pick(r, w); }
    static double dot(float[] a, float[] b) { double d = 0; for (int i = 0; i < a.length; i++) d += a[i] * b[i]; return d; }

    /** Same toy embedding idea as L1: synonym concepts + character trigrams; entity words get only a small weight. */
    static final Map<String, String> CONCEPT = new HashMap<>();
    static {
        String[][] g = {{"refund", "refunds", "money"}, {"delivery", "deliver"}, {"fee", "charges", "charge", "free", "minimum"},
            {"cancel", "cancellation", "cancelled"}, {"order", "orders"}, {"plus", "membership", "subscription", "members"},
            {"benefits", "included", "get", "worth"}, {"ingredients", "recipe", "need", "list", "make", "cook", "shopping"},
            {"stock", "available", "availability"}, {"status", "where", "track", "shipped", "arrive"}};
        for (String[] x : g) for (String w : x) CONCEPT.put(w, x[0]);
    }
    static float[] embed(String text) {
        float[] v = new float[256];
        for (String w : text.toLowerCase().split("[^a-z0-9]+")) {
            if (w.isEmpty()) continue;
            String c = CONCEPT.get(w);
            if (c != null) v[Math.floorMod(("c:" + c).hashCode(), 256)] += 1f;
            float wt = w.matches(".*\\d.*") ? 0.08f : 0.45f;         // like real models: ids and numbers barely move the vector
            String p = "#" + w + "#";
            for (int i = 0; i + 3 <= p.length(); i++) v[Math.floorMod(p.substring(i, i + 3).hashCode(), 256)] += wt;
        }
        double n = 0; for (float x : v) n += x * x; n = Math.sqrt(n);
        for (int i = 0; i < v.length; i++) v[i] /= n;
        return v;
    }
}
