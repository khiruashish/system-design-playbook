// L2 — Cash on delivery (COD) and "return to origin" (RTO): the customer refuses the parcel at the door and it
// travels back. Which orders should be offered COD? Compare policies on 300,000 simulated orders with a risk model.
// All rates, costs and behaviours are ILLUSTRATIVE assumptions (VERIFY against your own data before using the numbers).
import java.util.*;

public class CodPolicyL2 {
    record Order(double value, double margin, double pRto, boolean wouldPrepay, double pAbandonIfNoCod, double pAcceptOtp, boolean newCust) {}

    public static void main(String[] a) {
        Random r = new Random(1203); List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 300_000; i++) {
            double tier = r.nextDouble();                                            // city tier: metro / tier-2 / tier-3+
            boolean newCust = r.nextDouble() < 0.35, pastRto = !newCust && r.nextDouble() < 0.08;
            double value = Math.exp(6.3 + r.nextGaussian() * 0.8);                   // ₹ (median ~₹545)
            double base = tier < 0.4 ? 0.06 : tier < 0.75 ? 0.12 : 0.20;              // RTO rate for COD by tier (assumption)
            double p = base * (newCust ? 1.5 : 1.0) * (pastRto ? 3.0 : 1.0) * (value > 2000 ? 1.3 : 1.0);
            p = Math.min(0.85, p * Math.exp(r.nextGaussian() * 0.35));               // unobserved noise
            boolean prepay = r.nextDouble() < (tier < 0.4 ? 0.55 : tier < 0.75 ? 0.35 : 0.20);   // would pay online anyway
            orders.add(new Order(value, 0.18 * value, p, prepay, tier < 0.4 ? 0.35 : 0.60, 0.85, newCust));
        }
        double shipFwd = 45, shipRev = 40, codFee = 15;                               // ₹ per parcel (assumption)
        System.out.println("300,000 orders; contribution margin 18%; forward shipping ₹45, return ₹40, COD handling ₹15;");
        System.out.println("a successfully delivered NEW customer is worth ₹" + (int) LTV + " of future margin (all illustrative)\n");
        System.out.printf("%-46s %11s %11s %13s %13s %15s%n", "policy", "orders kept", "RTO parcels", "this order ₹L", "₹ per order", "incl. future ₹L");
        // the model's score is the true risk plus estimation error (a real model is not perfect)
        double[] score = orders.stream().mapToDouble(o -> Math.min(0.95, o.pRto() * Math.exp(r.nextGaussian() * 0.30))).toArray();
        policy("P1 COD for everyone", orders, score, 1.01, 1.01, shipFwd, shipRev, codFee, r);
        policy("P2 no COD at all", orders, score, -1, -1, shipFwd, shipRev, codFee, r);
        policy("P3 block COD if risk > 25%", orders, score, 0.25, 0.25, shipFwd, shipRev, codFee, r);
        policy("P4 block > 25%, OTP-confirm COD at 12–25%", orders, score, 0.12, 0.25, shipFwd, shipRev, codFee, r);
        policy("P5 block > 40%, OTP-confirm COD at 12–40%", orders, score, 0.12, 0.40, shipFwd, shipRev, codFee, r);
        System.out.println("\nOTP-confirm: the customer confirms by OTP/IVR before dispatch; assumed to cut RTO risk by 40% and lose 15% of those orders.");
    }

    // risk < confirmAt: plain COD offered; confirmAt ≤ risk < blockAt: COD with OTP confirmation; risk ≥ blockAt: prepaid only
    static final double LTV = 250;
    static void policy(String name, List<Order> os, double[] score, double confirmAt, double blockAt, double fwd, double rev, double fee, Random r) {
        Random rr = new Random(99); long kept = 0, rto = 0; double profit = 0, future = 0;
        for (int i = 0; i < os.size(); i++) {
            Order o = os.get(i); double s = score[i];
            if (o.wouldPrepay()) { kept++; profit += o.margin() - fwd; if (o.newCust()) future += LTV; continue; }       // prepaid orders: no RTO modelled
            if (s >= blockAt) {                                                            // COD refused: some pay online, others leave
                if (rr.nextDouble() < o.pAbandonIfNoCod()) continue;
                kept++; profit += o.margin() - fwd; if (o.newCust()) future += LTV; continue;
            }
            double p = o.pRto();
            if (s >= confirmAt) { if (rr.nextDouble() > o.pAcceptOtp()) continue; p *= 0.6; }
            kept++;
            if (rr.nextDouble() < p) { rto++; profit -= fwd + rev; }                       // lost both legs, no sale
            else { profit += o.margin() - fwd - fee; if (o.newCust()) future += LTV; }
        }
        System.out.printf("%-46s %,11d %,11d %13.1f %13.1f %15.1f%n", name, kept, rto, profit / 1e5, profit / os.size(), (profit + future) / 1e5);
    }
}
