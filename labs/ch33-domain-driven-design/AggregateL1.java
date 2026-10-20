// AggregateL1.java — run with: java AggregateL1.java   (JDK 21, no dependencies)
// ANEMIC MODEL vs RICH AGGREGATE. The same Order is implemented twice:
//   ANEMIC — a data bag with public setters; business rules live (or don't) in whichever service happens to touch it.
//   RICH   — an AGGREGATE ROOT that owns its invariants: state changes only through intention-revealing methods
//            (addItem, applyCoupon, place, cancel) that refuse to produce an invalid Order.
// We replay the same 8 operations — the kinds of calls real callers make: a bug, a race, a second code path — against both,
// and count how many leave the Order in a state that violates a business rule.
import java.util.*;

public class AggregateL1 {
    // ---------------- ANEMIC ----------------
    static class AnemicOrder {
        public String status = "DRAFT";
        public List<long[]> lines = new ArrayList<>();       // {skuHash, qty, unitPaise}
        public long totalPaise = 0;
        public long discountPaise = 0;
    }
    // ---------------- RICH AGGREGATE ----------------
    static final class Order {
        enum Status { DRAFT, PLACED, CANCELLED }
        record Line(String sku, int qty, long unitPaise) {}
        private Status status = Status.DRAFT;
        private final Map<String, Line> lines = new LinkedHashMap<>();
        private long discountPaise = 0;
        static final int MAX_QTY_PER_SKU = 10;

        void addItem(String sku, int qty, long unitPaise) {
            requireDraft("add items");
            if (qty <= 0) throw new IllegalArgumentException("quantity must be positive");
            if (unitPaise <= 0) throw new IllegalArgumentException("price must be positive");
            int newQty = qty + (lines.containsKey(sku) ? lines.get(sku).qty() : 0);
            if (newQty > MAX_QTY_PER_SKU) throw new IllegalStateException("max " + MAX_QTY_PER_SKU + " per SKU");
            lines.put(sku, new Line(sku, newQty, unitPaise));
        }
        void applyCoupon(long discount) {
            requireDraft("apply coupons");
            if (discount < 0 || discount > subtotal()) throw new IllegalArgumentException("discount must be within 0..subtotal");
            discountPaise = discount;
        }
        void place() {
            requireDraft("place");
            if (lines.isEmpty()) throw new IllegalStateException("cannot place an empty order");
            status = Status.PLACED;
        }
        void cancel() {
            if (status == Status.CANCELLED) return;                          // idempotent: a retried cancel is a no-op (Ch 20)
            if (status != Status.PLACED) throw new IllegalStateException("only a placed order can be cancelled");
            status = Status.CANCELLED;
        }
        long subtotal() { return lines.values().stream().mapToLong(l -> l.qty() * l.unitPaise()).sum(); }
        long total() { return subtotal() - discountPaise; }                 // DERIVED — can never disagree with lines
        private void requireDraft(String what) { if (status != Status.DRAFT) throw new IllegalStateException("cannot " + what + " when " + status); }
    }

    // business rules both models are supposed to honour — checked after every operation
    static List<String> violations(String status, List<long[]> lines, long total, long discount) {
        List<String> v = new ArrayList<>();
        long subtotal = lines.stream().mapToLong(l -> l[1] * l[2]).sum();
        if (total != subtotal - discount) v.add("total != subtotal - discount");
        if (lines.stream().anyMatch(l -> l[1] <= 0)) v.add("non-positive quantity");
        if (lines.stream().anyMatch(l -> l[1] > 10)) v.add("qty > 10 per SKU");
        if (discount < 0 || discount > subtotal) v.add("discount outside 0..subtotal");
        if (status.equals("PLACED") && lines.isEmpty()) v.add("placed with no items");
        return v;
    }

    interface Op { String name(); void anemic(AnemicOrder o); void rich(Order o); }
    static Op op(String name, java.util.function.Consumer<AnemicOrder> a, java.util.function.Consumer<Order> r) {
        return new Op() { public String name() { return name; } public void anemic(AnemicOrder o) { a.accept(o); } public void rich(Order o) { r.accept(o); } };
    }

    public static void main(String[] args) {
        List<Op> ops = List.of(
            op("add 2 x milk @ ₹60",                o -> { o.lines.add(new long[]{1, 2, 6000}); o.totalPaise += 12000; },
                                                    o -> o.addItem("milk", 2, 6000)),
            op("add -1 x bread (UI bug)",           o -> { o.lines.add(new long[]{2, -1, 4000}); o.totalPaise += -4000; },
                                                    o -> o.addItem("bread", -1, 4000)),
            op("add 15 x rice (bulk abuse)",        o -> { o.lines.add(new long[]{3, 15, 9000}); o.totalPaise += 135000; },
                                                    o -> o.addItem("rice", 15, 9000)),
            op("admin tool sets total directly",    o -> o.totalPaise = 99,
                                                    o -> { /* no setter exists: the total is derived */ }),
            op("coupon larger than subtotal",       o -> { o.discountPaise = 500000; o.totalPaise -= 500000; },
                                                    o -> o.applyCoupon(500000)),
            op("place the order",                   o -> o.status = "PLACED",
                                                    Order::place),
            op("add item AFTER placing (2nd path)", o -> o.lines.add(new long[]{4, 1, 2500}),     // forgot to update total too
                                                    o -> o.addItem("eggs", 1, 2500)),
            op("cancel the order",                  o -> o.status = "CANCELLED",
                                                    Order::cancel),
            op("cancel again (client retry)",       o -> o.status = "CANCELLED",
                                                    Order::cancel)
        );

        AnemicOrder a = new AnemicOrder(); Order r = new Order();
        int anemicBad = 0, richBad = 0;
        System.out.println("Replaying the same 9 operations against an ANEMIC Order and a RICH aggregate.\n");
        System.out.printf("   %-36s | %-40s | %s%n", "operation", "anemic: rules broken after this op", "rich aggregate");
        Set<String> seen = new LinkedHashSet<>();
        for (Op o : ops) {
            o.anemic(a);
            List<String> va = violations(a.status, a.lines, a.totalPaise, a.discountPaise);
            String richResult;
            try { o.rich(r); richResult = "accepted"; }
            catch (RuntimeException e) { richResult = "REJECTED: " + e.getMessage(); }
            List<long[]> rl = new ArrayList<>();
            for (var l : r.lines.values()) rl.add(new long[]{0, l.qty(), l.unitPaise()});
            List<String> vr = violations(r.status.name(), rl, r.total(), r.discountPaise);
            if (!va.isEmpty()) anemicBad++;
            if (!vr.isEmpty()) richBad++;
            List<String> fresh = new ArrayList<>(va); fresh.removeAll(seen); seen.addAll(va);
            String anemicCol = va.isEmpty() ? "valid" : va.size() + " broken" + (fresh.isEmpty() ? " (carried over)" : " (+" + String.join(", +", fresh) + ")");
            System.out.printf("   %-36s | %-40s | %s%n", o.name(), anemicCol, richResult + (vr.isEmpty() ? "" : "  → INVALID " + vr));
        }
        System.out.printf("%n   Operations that left the order violating a business rule:  anemic %d/9   rich %d/9%n", anemicBad, richBad);
        System.out.printf("   Final anemic order: status=%s, %d line(s), total ₹%.2f (true subtotal ₹%.2f)%n", a.status, a.lines.size(),
                a.totalPaise / 100.0, a.lines.stream().mapToLong(l -> l[1] * l[2]).sum() / 100.0);
        System.out.printf("   Final rich order:   status=%s, %d line(s), total ₹%.2f%n%n", r.status, r.lines.size(), r.total() / 100.0);
        System.out.println("   → the anemic model accepts every call, and once one invalid write lands the damage persists: every later state is");
        System.out.printf ("     invalid too, ending as a %s order with a negative quantity and a total of ₹%,.2f against a real subtotal of ₹%,.2f.%n",
                a.status, a.totalPaise / 100.0, a.lines.stream().mapToLong(l -> l[1] * l[2]).sum() / 100.0);
        System.out.println("     The rules existed, but in callers' heads; each new code path (admin tool, retry, second UI) was a chance to skip them.");
        System.out.println("     The aggregate makes illegal states unrepresentable: it rejects the bad commands at the boundary, derives the total");
        System.out.println("     instead of storing it, treats the retried cancel as an idempotent no-op, and ends valid. That is the core DDD move:");
        System.out.println("     put the invariant WITH the data it protects.");
    }
}
