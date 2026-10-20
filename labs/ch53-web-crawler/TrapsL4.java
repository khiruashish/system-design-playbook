// TrapsL4.java — run with: java TrapsL4.java   (JDK 21, no dependencies)
// CRAWLER TRAPS. A competitor's site (generated, fixed seed) has 5,000 product pages in 50 categories; each category is
// paginated 10 pages deep. It also has what real sites have: faceted navigation (every filter combination is a new
// URL: brand × pack size × price band × diet × offer × rating), an events calendar with endless "next day" links,
// and some links that carry a fresh session id on every page view. The crawl budget is 20,000 fetches for this site.
//   T1 breadth-first, every new URL string
//   T2 T1 with a depth limit of 4 (a common trap guard)
//   T3 canonicalise (drop session ids) + cap fetches per URL TEMPLATE (path with ids → {n}, query parameter names)
//      at 300, unless the template keeps yielding new products
//   T4 T3 + read robots.txt (facets and calendar disallowed) and seed from sitemap.xml (lists product URLs)
// Measured: product pages found, fetches spent on traps (facets, calendar, session duplicates), fetches used.
import java.util.*;

public class TrapsL4 {
    static final String[] FACETS = {"brand", "size", "price", "diet", "offer", "rating"};

    public static void main(String[] args) {
        System.out.println("5,000 products in 50 paginated categories + facets, calendar, session ids; budget 20,000 fetches.\n");
        System.out.printf("   %-66s %16s %16s %12s%n", "crawl policy", "products found", "fetches on traps", "fetches");
        String[] names = {"T1) breadth-first, every new URL", "T2) T1 + depth limit 4", "T3) canonicalise + per-template cap (300) unless it yields products", "T4) T3 + robots.txt + sitemap.xml seeding"};
        for (int p = 0; p < 4; p++) run(p, names[p]);
        System.out.println("\n   → breadth-first crawling of a real site is mostly spent in its infinite corners: every filter combination,");
        System.out.println("     every calendar day and every session-id spelling is a new URL. A depth limit changed nothing here — the facet");
        System.out.println("     explosion lives at shallow depth, and the budget ran out before depth 4. Recognising URL templates and capping");
        System.out.println("     the ones that stop yielding products helps (4,307 products), but facet combinations create dozens of templates.");
        System.out.println("     The decisive move is to use what the site publishes: robots.txt says what not to crawl, sitemap.xml lists what");
        System.out.println("     exists — every product in about a quarter of the budget. Honouring robots.txt is also a legal and ethical");
        System.out.println("     baseline, not just an optimisation.");
    }

    static void run(int policy, String name) {
        Random sid = new Random(7); Set<String> seen = new HashSet<>(); ArrayDeque<String[]> q = new ArrayDeque<>();   // {url, depth}
        Set<Integer> products = new HashSet<>(); int fetches = 0, trap = 0;
        Map<String, int[]> tmpl = new HashMap<>();                                                  // template → {fetches, newProductsFromIt}
        java.util.function.BiConsumer<String, Integer> add = (u, d) -> {
            String c = policy >= 2 ? u.replaceAll("[;&?]sid=\\d+", "") : u;
            if (policy == 3 && (isFacet(c) || c.contains("/events"))) return;                          // robots.txt: Disallow facets and /events
            if (policy == 1 && d > 4) return;
            if (seen.add(c)) q.add(new String[]{c, String.valueOf(d)}); };
        add.accept("/", 0);
        if (policy == 3) for (int i = 0; i < 5000; i++) add.accept("/p/" + i, 1);                     // sitemap.xml
        while (!q.isEmpty() && fetches < 20_000) {
            String[] e = q.poll(); String u = e[0]; int d = Integer.parseInt(e[1]);
            String t = template(u);
            if (policy >= 2) { int[] s = tmpl.computeIfAbsent(t, k -> new int[2]); if (s[0] >= 300 && s[1] * 20 < s[0]) continue; s[0]++; }
            fetches++; boolean isTrap = isFacet(u) || u.contains("/events") || u.contains("sid=");
            if (isTrap) trap++;
            int before = products.size();
            for (String link : links(u, sid)) add.accept(link, d + 1);
            if (u.startsWith("/p/")) products.add(Integer.parseInt(u.substring(3).split("[;?]")[0]));
            if (policy >= 2 && products.size() > before) tmpl.get(t)[1]++;
        }
        System.out.printf("   %-66s %,16d %,16d %,12d%n", name, products.size(), trap, fetches);
    }

    static boolean isFacet(String u) { for (String f : FACETS) if (u.contains("?" + f + "=") || u.contains("&" + f + "=")) return true; return false; }

    static String template(String u) {
        String path = u.split("\\?")[0].replaceAll(";sid=\\d+", "").replaceAll("\\d+", "{n}");
        String q = u.contains("?") ? u.substring(u.indexOf('?') + 1) : ""; List<String> keys = new ArrayList<>();
        for (String kv : q.split("&")) if (!kv.isEmpty()) keys.add(kv.split("=")[0]); Collections.sort(keys);
        return path + "?" + String.join("&", keys);
    }

    // the site: links on each page (session ids appear on 30% of links, fresh on each view)
    static List<String> links(String u, Random sid) {
        List<String> out = new ArrayList<>(); String base = u.replaceAll("[;&?]sid=\\d+", "");
        if (base.equals("/")) { for (int c = 0; c < 50; c++) out.add("/cat/" + c + "?page=1"); out.add("/events?date=0"); for (String f : FACETS) out.add("/cat/0?" + f + "=1"); }
        else if (base.startsWith("/events")) { int d = Integer.parseInt(base.split("=")[1]); out.add("/events?date=" + (d + 1)); out.add("/events?date=" + (d - 1)); }
        else if (base.startsWith("/p/")) { int id = Integer.parseInt(base.substring(3)); Random r = new Random(id); for (int i = 0; i < 3; i++) out.add("/p/" + ((id / 100) * 100 + r.nextInt(100))); out.add("/cat/" + id / 100 + "?page=1"); }
        else if (base.startsWith("/cat/")) {
            int c = Integer.parseInt(base.substring(5, base.indexOf('?'))); String qs = base.substring(base.indexOf('?') + 1);
            if (qs.startsWith("page=")) { int pg = Integer.parseInt(qs.substring(5)); for (int i = 0; i < 10; i++) out.add("/p/" + (c * 100 + (pg - 1) * 10 + i)); if (pg < 10) out.add("/cat/" + c + "?page=" + (pg + 1));
                for (String f : FACETS) out.add("/cat/" + c + "?" + f + "=1"); }
            else {                                                                                   // facet page: 10 products (mostly the first pages') + deeper filters
                Random r = new Random(base.hashCode()); for (int i = 0; i < 10; i++) out.add("/p/" + (c * 100 + r.nextInt(40)));
                for (String f : FACETS) if (!qs.contains(f + "=")) for (int v = 1; v <= 3; v++) out.add("/cat/" + c + "?" + qs + "&" + f + "=" + v);
                for (int v = 1; v <= 3; v++) out.add("/cat/" + c + "?" + qs.replaceAll("=\\d+", "=" + v)); }
        }
        List<String> withSid = new ArrayList<>(); for (String l : out) withSid.add(sid.nextDouble() < 0.3 ? l + (l.contains("?") ? "&" : "?") + "sid=" + sid.nextInt(1_000_000) : l);
        return withSid;
    }
}
