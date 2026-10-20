// MoneyL1.java — run with: java MoneyL1.java   (JDK 21, no dependencies)
// MONEY IS INTEGERS. 200,000 grocery carts (fixed seed), 3–30 lines each, prices ₹5–₹900 with paise, GST 0/5/12/18%
// per line (rates here are illustrative — VERIFY per HSN code), and a ₹75 cart-level coupon on carts over ₹999.
//   M1 amounts as double rupees: line = price × qty, tax per line rounded to 2 decimals, totals summed as doubles
//   M2 amounts as long paise (BigDecimal only for the rate multiplication, rounded HALF_UP per line)
// Then the cart coupon must be spread over the lines, because GST applies to the discounted value of each line and a
// returned line must refund what was actually paid for it:
//   P1 each line gets round(coupon × line / cart) — rounded independently
//   P2 largest remainder: floor every share, then give the leftover paise to the largest fractional parts
// Finally the customer returns every line, one at a time:
//   R1 refund = the line's list price (ignores the coupon)
//   R2 refund = line price − share of coupon RECOMPUTED at refund time from the remaining cart (rounded)
//   R3 refund = line price − the share STORED on the order at checkout (P2)
// Measured: carts where the invoice total is off by at least one paisa, carts whose coupon shares don't add up to the
// coupon, and money refunded beyond what the customer paid.
import java.math.*;
import java.util.*;

public class MoneyL1 {
    record Line(long pricePaise, int qty, int gst) { long gross() { return pricePaise * qty; } }

    public static void main(String[] args) {
        Random r = new Random(49); int[] rates = {0, 5, 12, 18}; List<List<Line>> carts = new ArrayList<>();
        for (int c = 0; c < 200_000; c++) {
            List<Line> cart = new ArrayList<>(); int n = 3 + r.nextInt(18);
            for (int i = 0; i < n; i++) cart.add(new Line(500 + r.nextInt(39_500), 1 + (r.nextDouble() < 0.3 ? r.nextInt(5) : 0), rates[r.nextInt(4)]));
            carts.add(cart);
        }
        // M1 vs M2
        int wrongTotal = 0, wrongRounded = 0; long maxOff = 0;
        for (List<Line> cart : carts) {
            double d = 0; long exact = 0;
            for (Line l : cart) {
                double lineRupees = (l.pricePaise() / 100.0) * l.qty();
                double taxRupees = Math.round(lineRupees * l.gst() / 100.0 * 100) / 100.0;
                d += lineRupees + taxRupees;
                exact += l.gross() + tax(l.gross(), l.gst());
            }
            long dPaise = (long) (d * 100);                                    // how "₹%.2f" code often gets back to paise
            if (dPaise != exact) { wrongTotal++; maxOff = Math.max(maxOff, Math.abs(dPaise - exact)); }
            if (Math.round(d * 100) != exact) wrongRounded++;
        }
        System.out.println("200,000 carts, 3–20 lines, GST 0/5/12/18% per line, ₹75 coupon on carts over ₹999.\n");
        System.out.printf("   M1) double rupees, (long)(d*100):       %,7d carts with the invoice total wrong by at least 1 paisa (worst %d paise)%n", wrongTotal, maxOff);
        System.out.printf("   M1) double rupees, Math.round at the end: %,5d carts wrong%n", wrongRounded);
        System.out.printf("   M2) long paise:                          %,7d carts wrong%n%n", 0);
        // proration + refunds
        int p1Bad = 0, p2Bad = 0, eligible = 0; long p1Drift = 0;
        long[] over = new long[3]; int[] overCarts = new int[3];
        for (List<Line> cart : carts) {
            long total = cart.stream().mapToLong(Line::gross).sum(); if (total <= 99_900) continue; eligible++;
            long coupon = 7_500;
            long[] naive = new long[cart.size()]; long sumNaive = 0;
            for (int i = 0; i < cart.size(); i++) { naive[i] = Math.round((double) coupon * cart.get(i).gross() / total); sumNaive += naive[i]; }
            if (sumNaive != coupon) { p1Bad++; p1Drift += Math.abs(sumNaive - coupon); }
            long[] share = largestRemainder(cart, coupon, total);
            if (Arrays.stream(share).sum() != coupon) p2Bad++;
            long paid = total - coupon;
            long[] refunded = new long[3]; long remaining = total, remainingCoupon = coupon;
            for (int i = 0; i < cart.size(); i++) {
                long g = cart.get(i).gross();
                refunded[0] += g;
                long s = i == cart.size() - 1 ? remainingCoupon : Math.round((double) remainingCoupon * g / remaining);
                refunded[1] += g - s; remaining -= g; remainingCoupon -= s;
                if (remaining > 0 && remaining <= 99_900) { remainingCoupon = 0; }         // "coupon no longer applies" → recomputation stops deducting
                refunded[2] += g - share[i];
            }
            for (int k = 0; k < 3; k++) if (refunded[k] > paid) { over[k] += refunded[k] - paid; overCarts[k]++; }
        }
        System.out.printf("   %,d carts got the coupon; it must be spread over their lines:%n", eligible);
        System.out.printf("   P1) round each share independently:  %,7d carts whose shares don't add up to ₹75.00 (%,d paise adrift in total)%n", p1Bad, p1Drift);
        System.out.printf("   P2) largest remainder:               %,7d carts%n%n", p2Bad);
        System.out.println("   the customer returns every line, one at a time:");
        String[] names = {"R1) refund list price (ignore the coupon)", "R2) recompute coupon share at refund time", "R3) refund price − share stored at checkout"};
        for (int k = 0; k < 3; k++) System.out.printf("   %-46s %,7d carts refunded more than paid, ₹%,.2f in total%n", names[k], overCarts[k], over[k] / 100.0);
        System.out.println("\n   → binary doubles can't represent most paise, so totals drift, and '(long)(d * 100)' truncates the drift into a");
        System.out.println("     wrong invoice. Rounding at the end helps, but can't fix a per-line tax that landed on 0.4999… instead of 0.5.");
        System.out.println("     Keep money as integer paise (or BigDecimal with explicit rounding) end to end. A cart-level discount must be");
        System.out.println("     allocated to lines exactly once — largest remainder makes the shares add up — and STORED on the order, because");
        System.out.println("     GST, refunds and seller settlement all need 'what was actually paid for this line'. Recomputing at refund time");
        System.out.println("     gives a different answer as soon as the cart changes (here: the coupon's threshold stops applying).");
    }

    static long tax(long grossPaise, int rate) {
        return new BigDecimal(grossPaise).multiply(BigDecimal.valueOf(rate)).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValue();
    }

    static long[] largestRemainder(List<Line> cart, long amount, long total) {
        long[] s = new long[cart.size()]; long given = 0; Integer[] idx = new Integer[cart.size()]; long[] rem = new long[cart.size()];
        for (int i = 0; i < cart.size(); i++) { long num = amount * cart.get(i).gross(); s[i] = num / total; rem[i] = num % total; given += s[i]; idx[i] = i; }
        Arrays.sort(idx, (a, b) -> Long.compare(rem[b], rem[a]));
        for (int k = 0; k < amount - given; k++) s[idx[k]]++;
        return s;
    }
}
