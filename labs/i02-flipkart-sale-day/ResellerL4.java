// L4 — Reseller rings on deal day. The deal allows 2 units per customer; a ring uses 400 accounts that ship to 12 real
// drop points, each address written many different ways. Genuine buyers include neighbours in the same apartment
// complexes. Compare per-account, exact-address, normalised-address and fuzzy (token-similarity) limits: how many deal
// units reach the ring, and how many genuine buyers are wrongly blocked. Addresses are synthetic, in Indian formats.
import java.util.*;
import java.util.stream.*;

public class ResellerL4 {
    record Order(String account, String address, String pincode, boolean ring, int dropOrHome) {}

    public static void main(String[] a) {
        Random r = new Random(1205);
        String[] soc = {"Prestige Lakeside Habitat", "Sobha Dream Acres", "Brigade Metropolis", "Purva Riviera", "Salarpuria Greenage", "Mantri Espana"};
        String[] roads = {"Varthur Road", "Whitefield Main Road", "Sarjapur Road", "Outer Ring Road", "Hosur Road", "Bannerghatta Road"};
        String[] pins = {"560087", "560066", "560035", "560103", "560068", "560076"};
        List<Order> orders = new ArrayList<>();
        // 12 ring drop points: real flats/shops in the same complexes genuine customers live in
        int[][] drops = new int[12][3]; for (int d = 0; d < 12; d++) drops[d] = new int[]{r.nextInt(6), 1 + r.nextInt(12), (1 + r.nextInt(25)) * 100 + 1 + r.nextInt(8)};
        for (int acc = 0; acc < 400; acc++) {
            int[] d = drops[acc % 12];
            orders.add(new Order("R" + acc, variant(r, soc[d[0]], roads[d[0]], d[1], d[2], true), pins[d[0]], true, acc % 12));
        }
        // 20,000 genuine buyers at their own homes; 1,500 live in the same six complexes (12 towers × 25 floors × 8 flats each)
        for (int g = 0; g < 20_000; g++) {
            int c = r.nextInt(6); int tower = 1 + r.nextInt(12), flat = (1 + r.nextInt(25)) * 100 + 1 + r.nextInt(8);
            boolean inComplex = g < 1_500;
            String addr = inComplex ? variant(r, soc[c], roads[c], tower, flat, false)
                                    : (100 + r.nextInt(900)) + ", " + (1 + r.nextInt(20)) + "th Cross, " + (1 + r.nextInt(12)) + "th Main, Sector " + (1 + r.nextInt(7)) + ", HSR Layout";
            orders.add(new Order("G" + g, addr, inComplex ? pins[c] : "5601" + (10 + r.nextInt(80)), false, inComplex ? c * 1_000_000 + tower * 10_000 + flat : 100_000_000 + g));
        }
        Collections.shuffle(orders, r);
        Map<Integer, Long> homes = orders.stream().filter(o -> !o.ring()).collect(Collectors.groupingBy(Order::dropOrHome, Collectors.counting()));
        long sameHome = homes.values().stream().filter(v -> v > 1).mapToLong(v -> v - 1).sum();
        long nearDrop = orders.stream().filter(o -> !o.ring() && Arrays.stream(drops).anyMatch(d -> d[0] * 1_000_000 + d[1] * 10_000 + d[2] == o.dropOrHome())).count();
        System.out.println("deal limit: 2 units per customer; ring = 400 accounts × 2 units → 12 drop points; 20,000 genuine buyers (1,500 in the same 6 complexes)\n");
        System.out.printf("%-44s %16s %24s %12s%n", "limit keyed on", "units to ring", "genuine buyers blocked", "keys");
        run("account", orders, o -> o.account());
        run("exact address string", orders, o -> o.address() + "|" + o.pincode());
        run("normalised address (tower, flat, pincode)", orders, ResellerL4::normKey);
        run("fuzzy: normalised + token similarity ≥ 0.8", orders, null);
        System.out.printf("%nfloor on 'genuine blocked' for any address-based limit: %d buyers share a flat with an earlier genuine buyer (same household),%n", sameHome);
        System.out.printf("and %d genuine buyers live in a flat the ring uses as a drop point%n", nearDrop);
        System.out.println("\nexamples of one drop point as the ring typed it:");
        orders.stream().filter(o -> o.ring() && o.dropOrHome() == 0).limit(5).forEach(o -> System.out.println("  " + o.address() + " — " + o.pincode()));
    }

    interface Key { String of(Order o); }
    static void run(String name, List<Order> orders, Key key) {
        Map<String, Integer> used = new HashMap<>(); List<String[]> fuzzyKeys = new ArrayList<>(); List<Integer> fuzzyUsed = new ArrayList<>();
        int ringUnits = 0, blocked = 0;
        for (Order o : orders) {
            int allowed;
            if (key != null) { String k = key.of(o); int u = used.getOrDefault(k, 0); allowed = Math.max(0, 2 - u); used.put(k, u + allowed); }
            else {                                                         // fuzzy: compare with earlier keys in the same pincode
                String[] me = {o.pincode(), normText(o.address())}; int hit = -1;
                for (int i = 0; i < fuzzyKeys.size(); i++) if (fuzzyKeys.get(i)[0].equals(me[0]) && sim(fuzzyKeys.get(i)[1], me[1]) >= 0.8) { hit = i; break; }
                if (hit < 0) { fuzzyKeys.add(me); fuzzyUsed.add(0); hit = fuzzyKeys.size() - 1; }
                allowed = Math.max(0, 2 - fuzzyUsed.get(hit)); fuzzyUsed.set(hit, fuzzyUsed.get(hit) + allowed);
            }
            if (o.ring()) ringUnits += allowed; else if (allowed < 2) blocked++;
        }
        long keys = key != null ? used.size() : fuzzyKeys.size();
        System.out.printf("%-44s %16d %24d %,12d%n", name, ringUnits, blocked, keys);
    }

    // normalise: lowercase, expand common abbreviations, pull out tower/block and flat numbers
    static String normText(String s) {
        s = s.toLowerCase().replaceAll("[.,#/\\-]", " ")
             .replaceAll("\\b(flt|flat no|flat|apt|apartment|no)\\b", " flat ").replaceAll("\\b(twr|tower|block|blk|wing)\\b", " tower ")
             .replaceAll("\\b(rd)\\b", " road ").replaceAll("\\b(opp|opposite|near|nr|behind)\\b.*", " ").replaceAll("\\s+", " ").trim();
        return s;
    }
    static String normKey(Order o) {
        String s = normText(o.address());
        String tw = find(s, "\\btower (\\d+)", "\\bt(\\d+)\\b");
        String fl = find(s, "\\bflat (\\d{3,4})\\b", "\\b(?:tower |t)\\d+ (\\d{3,4})\\b", "(?:^| )(\\d{3,4})(?= |$)");
        return tw == null || fl == null ? s + "|" + o.pincode() : tw + "|" + fl + "|" + o.pincode();   // unparsed: fall back to the full text
    }
    static String find(String s, String... patterns) {
        for (String p : patterns) { java.util.regex.Matcher m = java.util.regex.Pattern.compile(p).matcher(s); if (m.find()) return m.group(1); }
        return null;
    }
    static double sim(String a, String b) {                              // Jaccard similarity of word sets
        Set<String> x = new HashSet<>(Arrays.asList(a.split(" "))), y = new HashSet<>(Arrays.asList(b.split(" ")));
        Set<String> i = new HashSet<>(x); i.retainAll(y); Set<String> u = new HashSet<>(x); u.addAll(y);
        return u.isEmpty() ? 0 : (double) i.size() / u.size();
    }

    // one real address, written the way different people (or a ring deliberately) would write it
    static String variant(Random r, String soc, String road, int tower, int flat, boolean evasive) {
        int unit = flat;                                                  // floor × 100 + flat on the floor, e.g. 1203
        String[] towerForms = {"Tower " + tower, "T" + tower, "Block " + tower, "Twr-" + tower, "Wing " + tower};
        String[] flatForms = {"Flat " + unit, "Flat No. " + unit, "#" + unit, unit + "", "Apt " + unit, "Flt " + unit};
        String tf = towerForms[r.nextInt(evasive ? 5 : 2)], ff = flatForms[r.nextInt(evasive ? 6 : 2)];
        String socName = evasive && r.nextBoolean() ? soc.replace(" ", "").toUpperCase() : soc;
        String rd = r.nextBoolean() ? road : road.replace("Road", "Rd");
        String landmark = evasive && r.nextDouble() < 0.5 ? ", near " + (r.nextBoolean() ? "Decathlon" : "Forum Mall") : "";
        String name = evasive && r.nextDouble() < 0.4 ? "C/o " + (char) ('A' + r.nextInt(26)) + ". Kumar, " : "";
        return r.nextBoolean() ? name + ff + ", " + tf + ", " + socName + ", " + rd + landmark
                               : name + tf + "-" + unit + ", " + socName + ", " + rd + landmark;
    }
}
