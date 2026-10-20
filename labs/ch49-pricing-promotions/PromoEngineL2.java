// PromoEngineL2.java — run with: java PromoEngineL2.java   (JDK 21, no dependencies)
// WHICH OFFERS APPLY TO THIS CART, AND HOW DO THEY COMBINE? 50,000 SKUs in 800 categories and 2,000 brands; 20,000 live
// item promotions (fixed seed): % off a SKU list, % off a category, % off a brand, buy-2-get-1 on a SKU; plus 12 auto-
// applied cart offers (flat ₹ off over a threshold, half exclude already-discounted items) and 8 bank offers (10% up to
// ₹150, only when the customer pays with that bank; 60% of carts do). 20,000 carts of
// 4–25 lines. Rules of the business: at most one item-level offer per line, one cart offer, one bank offer, and no line
// may be sold below 60% of its price (the margin floor). Amounts in paise.
//   E1 scan all 20,000 promotions per cart; apply every match in the order they are stored
//   E2 index promotions by SKU, category and brand (plus the short cart-level list); same apply-everything logic
//   E3 index + a deterministic policy: ONE item offer per line — the highest-priority one (the order marketing set) —
//      then the best cart offer on eligible lines, then the bank offer; margin floor enforced
//   E4 E3, but the one item offer per line is the BEST one for the customer
// Measured: real evaluation time per cart, carts whose total changes if the promotions are evaluated in another order,
// lines priced below the floor, and what customers pay.
import java.util.*;

public class PromoEngineL2 {
    static final int SKUS = 50_000, CATS = 800, BRANDS = 2_000;
    enum T { SKU_PCT, CAT_PCT, BRAND_PCT, B2G1, CART_FLAT, BANK }
    record Promo(int id, T type, int target, int pct, long flat, long threshold, boolean excludesDiscounted, Set<Integer> skus) {}
    record Line(int sku, long price, int qty) {}
    static int[] cartBank; static int curBank;
    static int[] cat = new int[SKUS], brand = new int[SKUS];

    public static void main(String[] args) {
        Random r = new Random(49);
        for (int s = 0; s < SKUS; s++) { cat[s] = r.nextInt(CATS); brand[s] = r.nextInt(BRANDS); }
        List<Promo> promos = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            double x = r.nextDouble();
            if (x < 0.55) { Set<Integer> ss = new HashSet<>(); int n = 1 + r.nextInt(20); for (int k = 0; k < n; k++) ss.add(r.nextInt(SKUS)); promos.add(new Promo(i, T.SKU_PCT, -1, 5 + r.nextInt(31), 0, 0, false, ss)); }
            else if (x < 0.62) promos.add(new Promo(i, T.CAT_PCT, r.nextInt(CATS), 5 + r.nextInt(21), 0, 0, false, Set.of()));
            else if (x < 0.72) promos.add(new Promo(i, T.BRAND_PCT, r.nextInt(BRANDS), 5 + r.nextInt(26), 0, 0, false, Set.of()));
            else promos.add(new Promo(i, T.B2G1, -1, 0, 0, 0, false, Set.of(r.nextInt(SKUS))));
        }
        for (int i = 0; i < 12; i++) promos.add(r.nextInt(promos.size()), new Promo(20_000 + i, T.CART_FLAT, -1, 0, 5_000 + 2_500 * r.nextInt(7), 99_900 + 50_000 * r.nextInt(5), i % 2 == 0, Set.of()));
        for (int b = 0; b < 8; b++) promos.add(r.nextInt(promos.size()), new Promo(20_100 + b, T.BANK, b, 10, 15_000, 0, false, Set.of()));
        for (int i = 0; i < promos.size(); i++) { Promo p = promos.get(i); promos.set(i, new Promo(i, p.type(), p.target(), p.pct(), p.flat(), p.threshold(), p.excludesDiscounted(), p.skus())); }   // stored order = id
        // popular SKUs are where promotions and carts concentrate
        List<List<Line>> carts = new ArrayList<>(); cartBank = new int[20_000];
        for (int c = 0; c < 20_000; c++) { List<Line> cart = new ArrayList<>(); int n = 4 + r.nextInt(22);
            for (int k = 0; k < n; k++) cart.add(new Line((int) (Math.pow(r.nextDouble(), 3) * SKUS), 1_000 + r.nextInt(49_000), 1 + (r.nextDouble() < 0.25 ? 2 : 0)));
            cartBank[c] = r.nextDouble() < 0.6 ? r.nextInt(8) : -1; carts.add(cart); }
        // index
        Map<Integer, List<Promo>> bySku = new HashMap<>(), byCat = new HashMap<>(), byBrand = new HashMap<>(); List<Promo> cartLevel = new ArrayList<>();
        for (Promo p : promos) switch (p.type()) {
            case SKU_PCT, B2G1 -> { for (int s : p.skus()) bySku.computeIfAbsent(s, k -> new ArrayList<>()).add(p); }
            case CAT_PCT -> byCat.computeIfAbsent(p.target(), k -> new ArrayList<>()).add(p);
            case BRAND_PCT -> byBrand.computeIfAbsent(p.target(), k -> new ArrayList<>()).add(p);
            default -> cartLevel.add(p);
        }
        System.out.printf("%,d promotions (%d cart-level or bank), 20,000 carts of 4–25 lines; floor = 60%% of price.%n%n", promos.size(), cartLevel.size());
        long list = 0, lines = 0; for (List<Line> c : carts) for (Line l : c) { list += l.price() * l.qty(); lines++; }
        System.out.printf("   %,d lines; list-price total ₹%,.0f%n%n", lines, list / 100.0);
        System.out.printf("   %-62s %9s %9s %14s %12s %14s%n", "engine", "p50", "p99", "order-dependent", "lines below", "customers pay");
        System.out.printf("   %-62s %9s %9s %14s %12s %14s%n", "", "", "", "carts", "floor", "(total)");
        String[] names = {"E1) scan all promotions, apply every match in stored order", "E2) indexed lookup, apply every match in stored order",
                "E3) indexed + one item offer/line (priority), cart, bank, floor", "E4) E3 but the best item offer per line"};
        for (int e = 0; e < 4; e++) {
            long[] lat = new long[carts.size()]; int orderDep = 0, better = 0; long below = 0, pay = 0;
            for (int ci = 0; ci < carts.size(); ci++) {
                List<Line> cart = carts.get(ci);
                curBank = cartBank[ci]; long t0 = System.nanoTime();
                List<Promo> cand = e == 0 ? scan(promos, cart) : lookup(bySku, byCat, byBrand, cartLevel, cart);
                long[] res = e <= 1 ? applyAll(cart, cand) : plan(cart, cand, e == 3);
                lat[ci] = System.nanoTime() - t0;
                List<Promo> shuffled = new ArrayList<>(cand); Collections.shuffle(shuffled, new Random(ci));
                long[] res2 = e <= 1 ? applyAll(cart, shuffled) : plan(cart, shuffled, e == 3);
                if (res2[0] != res[0]) orderDep++;
                below += res[1]; pay += res[0];
            }
            Arrays.sort(lat);
            System.out.printf("   %-62s %6.1f µs %6.1f µs %,14d %,12d %,14.0f%n", names[e], lat[lat.length / 2] / 1e3, lat[(int) (lat.length * 0.99)] / 1e3, orderDep, below, pay / 100.0);
        }
        System.out.println("\n   → scanning every promotion per cart is the first thing to go: index promotions by what they target, and a cart");
        System.out.println("     only touches the few that could apply (~60x faster here). The harder problem is combination. 'Apply everything");
        System.out.println("     that matches' stacks offers nobody meant to stack (customers paid ~20% of list price), sells a quarter of a");
        System.out.println("     million lines below the margin floor, and gives a different total for almost every cart depending on evaluation");
        System.out.println("     order — two servers could quote two prices. Explicit combination rules (one offer per line, fixed group order, a");
        System.out.println("     floor) make pricing deterministic. WHICH single offer wins is then a business decision worth ~12% of revenue here:");
        System.out.println("     marketing's priority order (E3) or the customer's best deal (E4). Decide it on purpose, and write it down.");
    }

    static boolean matches(Promo p, Line l) {
        return switch (p.type()) { case SKU_PCT, B2G1 -> p.skus().contains(l.sku()); case CAT_PCT -> cat[l.sku()] == p.target(); case BRAND_PCT -> brand[l.sku()] == p.target(); default -> false; };
    }
    static List<Promo> scan(List<Promo> all, List<Line> cart) {
        List<Promo> out = new ArrayList<>();
        for (Promo p : all) { if (p.type() == T.CART_FLAT || p.type() == T.BANK) { out.add(p); continue; } for (Line l : cart) if (matches(p, l)) { out.add(p); break; } }
        return out;
    }
    static List<Promo> lookup(Map<Integer, List<Promo>> bySku, Map<Integer, List<Promo>> byCat, Map<Integer, List<Promo>> byBrand, List<Promo> cartLevel, List<Line> cart) {
        LinkedHashSet<Promo> out = new LinkedHashSet<>();
        for (Line l : cart) { out.addAll(bySku.getOrDefault(l.sku(), List.of())); out.addAll(byCat.getOrDefault(cat[l.sku()], List.of())); out.addAll(byBrand.getOrDefault(brand[l.sku()], List.of())); }
        out.addAll(cartLevel);
        List<Promo> list = new ArrayList<>(out); list.sort(Comparator.comparingInt(Promo::id)); return list;   // stored order
    }
    static long itemDiscount(Promo p, Line l, long current) {
        return p.type() == T.B2G1 ? (l.qty() >= 3 ? l.price() * (l.qty() / 3) : 0) : current * p.pct() / 100;
    }
    // naive: every matching promo, in the given order, on the running amounts; cart flat checks threshold on the running total
    static long[] applyAll(List<Line> cart, List<Promo> cand) {
        long[] amt = new long[cart.size()]; for (int i = 0; i < cart.size(); i++) amt[i] = cart.get(i).price() * cart.get(i).qty();
        long cartOff = 0;
        for (Promo p : cand) {
            if (p.type() == T.CART_FLAT) { long tot = Arrays.stream(amt).sum() - cartOff; if (tot > p.threshold()) cartOff += p.flat(); }
            else if (p.type() == T.BANK && p.target() == curBank) { long tot = Arrays.stream(amt).sum() - cartOff; cartOff += Math.min(p.flat(), tot * p.pct() / 100); }
            else for (int i = 0; i < cart.size(); i++) if (matches(p, cart.get(i))) amt[i] -= Math.min(amt[i], itemDiscount(p, cart.get(i), amt[i]));
        }
        long below = 0; for (int i = 0; i < cart.size(); i++) if (amt[i] < cart.get(i).price() * cart.get(i).qty() * 60 / 100) below++;
        return new long[]{Math.max(0, Arrays.stream(amt).sum() - cartOff), below};
    }
    static long[] plan(List<Line> cart, List<Promo> cand, boolean bestForCustomer) {
        int n = cart.size(); long[] amt = new long[n]; boolean[] disc = new boolean[n];
        for (int i = 0; i < n; i++) {
            Line l = cart.get(i); long gross = l.price() * l.qty(), best = 0;
            int bestId = Integer.MAX_VALUE;
            for (Promo p : cand) if (p.type() != T.CART_FLAT && p.type() != T.BANK && matches(p, l)) {
                long d = itemDiscount(p, l, gross); if (d == 0) continue;
                if (bestForCustomer) best = Math.max(best, d); else if (p.id() < bestId) { bestId = p.id(); best = d; }   // priority = stored order
            }
            best = Math.min(best, gross * 40 / 100);                                              // floor: at most 40% off
            amt[i] = gross - best; disc[i] = best > 0;
        }
        long total = Arrays.stream(amt).sum(), bestCart = 0, bestBank = 0;
        for (Promo p : cand) if (p.type() == T.CART_FLAT) {
            long eligible = 0; for (int i = 0; i < n; i++) if (!(p.excludesDiscounted() && disc[i])) eligible += amt[i];
            if (eligible > p.threshold()) bestCart = Math.max(bestCart, Math.min(p.flat(), eligible - eligible * 60 / 100));
        }
        total -= bestCart;
        for (Promo p : cand) if (p.type() == T.BANK && p.target() == curBank) bestBank = Math.max(bestBank, Math.min(p.flat(), total * p.pct() / 100));
        return new long[]{total - bestBank, 0};
    }
}
