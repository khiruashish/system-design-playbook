// BudgetPacingL3.java — run with: java BudgetPacingL3.java   (JDK 21, no dependencies)
// A ₹5 LAKH CAMPAIGN: "₹100 off your next order", 7 days. 700,000 eligible app sessions (fixed seed) follow the usual
// daily curve (evening peak) with a TV ad on day 4 doubling traffic. Each session has a baseline chance to order
// (2–30%, from purchase history); seeing the coupon raises it by an uplift that is largest for mid-propensity customers
// and near zero for people who order every day anyway. Every redemption costs ₹100. The response model is an assumption
// (VERIFY with holdout experiments before trusting it).
//   P1 first come, first served until the budget is gone
//   P2 equal daily caps (budget / 7), first come each day
//   P3 pacing: show the coupon with probability p, adjusted every 15 minutes so spend follows the expected traffic curve
//      (feedback: overspending lowers p, underspending raises it)
//   P4 P3 + targeting: show it only to sessions whose predicted uplift is in the top half (an uplift model, with noise)
// Measured: budget spent, redemptions, INCREMENTAL orders (orders that happened because of the coupon), cost per
// incremental order, and how many hours of the week the offer was live.
import java.util.*;

public class BudgetPacingL3 {
    record Session(int minute, double base, double uplift, double predictedUplift) {}

    public static void main(String[] args) {
        Random r = new Random(49); List<Session> ss = new ArrayList<>();
        double[] hourW = {0.3, 0.2, 0.1, 0.1, 0.1, 0.2, 0.5, 1.0, 1.5, 1.6, 1.4, 1.3, 1.5, 1.4, 1.2, 1.1, 1.3, 1.8, 2.4, 2.8, 2.6, 2.0, 1.2, 0.6};
        double[] minuteW = new double[7 * 1440]; double sum = 0;
        for (int m = 0; m < minuteW.length; m++) { minuteW[m] = hourW[(m / 60) % 24] * (m / 1440 == 3 ? 2 : 1); sum += minuteW[m]; }
        double[] cdf = new double[minuteW.length]; double acc = 0; for (int m = 0; m < minuteW.length; m++) { acc += minuteW[m] / sum; cdf[m] = acc; }
        for (int i = 0; i < 700_000; i++) {
            double u = r.nextDouble(); int m = Arrays.binarySearch(cdf, u); if (m < 0) m = -m - 1; m = Math.min(m, minuteW.length - 1);
            double base = 0.02 + 0.28 * Math.pow(r.nextDouble(), 1.5);
            double uplift = 0.10 * Math.exp(-Math.pow((base - 0.10) / 0.06, 2));                       // peaks for base ≈ 10%
            ss.add(new Session(m, base, uplift, uplift * Math.exp(0.5 * r.nextGaussian())));
        }
        ss.sort(Comparator.comparingInt(Session::minute));
        double[] upl = ss.stream().mapToDouble(Session::predictedUplift).sorted().toArray(); double median = upl[upl.length / 2];
        // expected spend curve for pacing = traffic share by 15-minute block (from last week's traffic, which had no TV ad)
        double[] expectedShare = new double[7 * 96]; double tot = 0;
        for (int b = 0; b < expectedShare.length; b++) { expectedShare[b] = hourW[(b / 4) % 24]; tot += expectedShare[b]; }
        for (int b = 0; b < expectedShare.length; b++) expectedShare[b] /= tot;
        System.out.println("₹5,00,000 budget, ₹100 per redemption, 7 days, 700,000 eligible sessions (TV ad doubles day 4).\n");
        System.out.printf("   %-50s %11s %12s %12s %16s %11s %10s%n", "policy", "spent", "redemptions", "incremental", "₹ per incremental", "hours live", "day-4");
        System.out.printf("   %-50s %11s %12s %12s %16s %11s %10s%n", "", "", "", "orders", "order", "(of 168)", "spend");
        String[] names = {"P1) first come, first served", "P2) equal daily caps", "P3) paced to the expected traffic curve", "P4) P3 + show only to top-half predicted uplift"};
        for (int p = 0; p < 4; p++) {
            Random rr = new Random(7); long budget = 5_00_000, spent = 0, red = 0; double incr = 0; double prob = 1.0; boolean[] liveHour = new boolean[168]; long day4 = 0;
            double cumExpected = 0; int block = -1;
            for (Session s : ss) {
                int b = s.minute() / 15;
                while (block < b) { block++; cumExpected += expectedShare[block] * budget;
                    if (p >= 2) { double target = cumExpected; double err = (target - spent) / (budget * 0.02); prob = Math.max(0.01, Math.min(1.0, prob * Math.exp(0.5 * Math.tanh(err)))); } }
                if (spent + 100 > budget) continue;
                if (p == 1 && spent + 100 > budget / 7 * (s.minute() / 1440 + 1)) continue;
                if (p == 3 && s.predictedUplift() < median) continue;
                if (p >= 2 && rr.nextDouble() > prob) continue;
                liveHour[s.minute() / 60] = true;
                boolean orders = rr.nextDouble() < s.base() + s.uplift();
                if (orders) { spent += 100; red++; if (s.minute() / 1440 == 3) day4 += 100; incr += s.uplift() / (s.base() + s.uplift()); }
            }
            int hours = 0; for (boolean h : liveHour) if (h) hours++;
            System.out.printf("   %-50s ₹%,10d %,12d %,12.0f %16.0f %11d %9.0f%%%n", names[p], spent, red, incr, spent / Math.max(1, incr), hours, 100.0 * day4 / Math.max(1, spent));
        }
        System.out.println("\n   → a coupon mostly pays people who would have ordered anyway: of ~5,000 redemptions only ~1,200 are new orders.");
        System.out.println("     First come, first served spends the whole budget in the first 13 hours, on whoever opened the app then. Daily");
        System.out.println("     caps run dry every morning and never reach the evening peak. Pacing keeps the offer live all week — but it");
        System.out.println("     buys the SAME number of incremental orders: it fixes coverage and fairness, not efficiency. Note it also holds");
        System.out.println("     day 4 to its planned share (the plan didn't know about the TV ad — feed known events into the curve).");
        System.out.println("     Targeting by predicted uplift is what nearly halves the cost per incremental order. The uplift model is the");
        System.out.println("     hard part: validate it with holdout groups that never see the coupon.");
    }
}
