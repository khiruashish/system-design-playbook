// ScalingL2.java — run with: java ScalingL2.java   (JDK 21, no dependencies)
// RIGHTSIZING AND AUTOSCALING, costed. Fourteen days of checkout demand in 5-minute buckets: morning and evening peaks,
// busier weekends, noise, and one festival flash sale (day 11, 19:00–21:00) at ~6x normal. Each pod serves 150 req/s at
// the target utilisation (~60%, the knee before the queueing cliff of Ch 31 L3) and at most 250 req/s before it overloads.
// Four capacity policies, each priced at an ILLUSTRATIVE ₹18 per pod-hour:
//   A) static, sized for the all-time peak (+20% headroom)      — the "never page me" fleet
//   B) static, sized for a normal day's peak (+20%)            — cheap until the festival
//   C) reactive autoscaling to 60% target, 5-min reaction lag, 15-min scale-down cooldown, min 4 pods
//   D) C + a SCHEDULED pre-scale for the known sale (capacity for 6x from 18:30 to 21:30 on day 11)
import java.util.*;

public class ScalingL2 {
    static final int BUCKETS_PER_DAY = 288, DAYS = 14, N = BUCKETS_PER_DAY * DAYS;
    static final double TARGET = 150, MAX = 250, PRICE = 18;

    static double[] demand() {
        Random r = new Random(11); double[] d = new double[N];
        for (int i = 0; i < N; i++) {
            int day = i / BUCKETS_PER_DAY; double hour = (i % BUCKETS_PER_DAY) / 12.0;
            double base = 120 + 900 * Math.exp(-Math.pow((hour - 8.5) / 1.6, 2)) + 1300 * Math.exp(-Math.pow((hour - 19.5) / 1.8, 2))
                        + 350 * Math.exp(-Math.pow((hour - 13) / 2.0, 2));
            if (day % 7 == 5 || day % 7 == 6) base *= 1.25;                              // weekend
            base *= 1 + 0.08 * r.nextGaussian();                                          // noise
            if (day == 10 && hour >= 19 && hour < 21) base *= 6;                          // flash sale
            d[i] = Math.max(50, base);
        }
        return d;
    }

    record Result(double podHours, double cost, int overloadedMin, int degradedMin, int peakPods) {}

    static Result run(double[] d, java.util.function.IntUnaryOperator policy) {
        double podHours = 0; int overloaded = 0, degraded = 0, peak = 0;
        for (int i = 0; i < N; i++) {
            int pods = policy.applyAsInt(i); peak = Math.max(peak, pods);
            podHours += pods / 12.0;
            if (d[i] > pods * MAX) overloaded += 5;                                      // requests failing / timing out
            else if (d[i] > pods * TARGET * 1.33) degraded += 5;                          // ρ > ~0.8: latency on the cliff
        }
        return new Result(podHours, podHours * PRICE, overloaded, degraded, peak);
    }

    public static void main(String[] args) {
        double[] d = demand();
        double allPeak = Arrays.stream(d).max().getAsDouble();
        double normalPeak = 0; for (int i = 0; i < N; i++) if (i / BUCKETS_PER_DAY != 10) normalPeak = Math.max(normalPeak, d[i]);
        int podsA = (int) Math.ceil(allPeak * 1.2 / TARGET), podsB = (int) Math.ceil(normalPeak * 1.2 / TARGET);

        // C: reactive — decides pods for bucket i from demand observed in bucket i-1 (5-min lag), scale-down after 3 calm buckets
        int[] c = new int[N]; int cur = 4, calm = 0;
        for (int i = 0; i < N; i++) {
            if (i > 0) {
                int want = Math.max(4, (int) Math.ceil(d[i - 1] / TARGET));
                if (want > cur) { cur = want; calm = 0; }
                else if (want < cur) { if (++calm >= 3) { cur = want; calm = 0; } }
                else calm = 0;
            }
            c[i] = cur;
        }
        // D: C plus a calendar-driven floor for the known event
        int saleStart = 10 * BUCKETS_PER_DAY + 18 * 12 + 6, saleEnd = 10 * BUCKETS_PER_DAY + 21 * 12 + 6;
        double salePeak = 0; for (int i = saleStart; i < saleEnd; i++) salePeak = Math.max(salePeak, d[i]);
        int floor = (int) Math.ceil(salePeak * 1.1 / TARGET);
        int[] dd = c.clone(); for (int i = saleStart; i < saleEnd; i++) dd[i] = Math.max(dd[i], floor);

        System.out.printf("14 days of checkout demand: normal daily peak %,.0f req/s, festival peak %,.0f req/s (%.1fx). Pod = 150 req/s at target.%n%n",
                normalPeak, allPeak, allPeak / normalPeak);
        System.out.printf("   %-46s %9s %11s %12s %12s %10s%n", "capacity policy", "peak pods", "pod-hours", "cost (₹)", "overloaded", "degraded");
        Object[][] rows = {
            {"A) static for all-time peak (+20%)", run(d, i -> podsA)},
            {"B) static for a normal day's peak (+20%)", run(d, i -> podsB)},
            {"C) reactive autoscaling (60% target, 5-min lag)", run(d, i -> c[i])},
            {"D) C + scheduled pre-scale for the sale", run(d, i -> dd[i])}};
        for (Object[] row : rows) { Result x = (Result) row[1];
            System.out.printf("   %-46s %9d %,11.0f %,12.0f %9d min %7d min%n", row[0], x.peakPods(), x.podHours(), x.cost(), x.overloadedMin(), x.degradedMin()); }
        Result a = (Result) rows[0][1], cc = (Result) rows[2][1], ddr = (Result) rows[3][1];
        System.out.printf("%n   Static-for-peak fleet: pod-hours actually needed at target ÷ pod-hours provisioned = %.0f%% (the rest is idle headroom)%n",
                100 * Arrays.stream(d).map(x -> x / TARGET / 12.0).sum() / a.podHours());
        System.out.printf("   Autoscaling + pre-scale costs %.0f%% of the static-for-peak fleet.%n", 100 * ddr.cost() / a.cost());

        System.out.println("\n   → sizing statically for the festival peak buys safety by paying for idle pods almost all fortnight. Sizing for a");
        System.out.println("     normal day is cheap and fails exactly when revenue is highest. Reactive autoscaling tracks the daily curve at a");
        System.out.println("     fraction of the cost, but a ~5x step arrives faster than a 5-minute control loop: the first bucket of the sale is");
        System.out.println("     overloaded. Known events are not surprises — a calendar-driven pre-scale removes that gap for a few extra pod-hours.");
        System.out.println("     Rightsizing is therefore two decisions: the TARGET utilisation (cost vs the queueing cliff) and how you cover the");
        System.out.println("     demand your control loop can't react to in time (pre-scaling, warm pools, load shedding).");
    }
}
