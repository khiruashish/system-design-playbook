// L1 — "Deal unlocks at 12:00:00". A million phones must start the deal at the same moment, but phone clocks are
// wrong (some by minutes). Compare four client strategies: how much load hits the origin before and at unlock, and how
// many shoppers are ready within 1 s of the true unlock. Clock-skew distribution is an assumption (VERIFY with your
// own client telemetry).
import java.util.*;

public class DealUnlockL1 {
    static final int USERS = 1_000_000, WINDOW = 300;            // users waiting on the deal page in the last 5 minutes

    public static void main(String[] a) {
        Random r = new Random(1202);
        double[] skew = new double[USERS], rtt = new double[USERS], arrive = new double[USERS];
        for (int i = 0; i < USERS; i++) {
            double u = r.nextDouble();                                   // phone clock minus true time, seconds
            skew[i] = u < 0.70 ? r.nextGaussian() * 0.4 : u < 0.92 ? r.nextGaussian() * 15 : r.nextGaussian() * 120;
            rtt[i] = 0.05 + Math.abs(r.nextGaussian()) * (r.nextDouble() < 0.2 ? 0.8 : 0.15);   // 20% on slow networks
            arrive[i] = -WINDOW * Math.sqrt(r.nextDouble());            // more arrivals close to unlock
        }
        System.out.printf("%,d shoppers on the deal page during the last %d s; phone clocks: 70%% within ~1 s, 22%% off by ~15 s, 8%% by ~2 min%n%n", USERS, WINDOW);
        System.out.printf("%-48s %14s %14s %12s %14s %12s%n", "strategy", "origin reqs", "peak origin/s", "early taps", "within ±1 s", "late > 5 s");
        for (int s = 1; s <= 4; s++) run(s, skew, rtt, arrive, r);
        System.out.println("\norigin reqs / peak: requests reaching the origin from arrival until 10 s after unlock (edge-cached polls excluded)");
        System.out.println("early taps: 'Buy' requests sent before the true unlock (rejected, and often retried)");
        System.out.println("within ±1 s: shoppers whose app shows the Buy button within 1 s of the true unlock (early or late)");
    }

    static void run(int s, double[] skew, double[] rtt, double[] arrive, Random r) {
        long origin = 0, early = 0, ready = 0, late = 0; long[] perSec = new long[WINDOW + 20];
        for (int i = 0; i < USERS; i++) {
            double t0 = arrive[i], start;                                // true time the app enables Buy
            switch (s) {
                case 1 -> {                                              // poll the origin every 2 s until it says "live"
                    double first = t0 + r.nextDouble() * 2; int polls = 0; double t = first;
                    while (t < 0) { add(perSec, t); polls++; t += 2; }
                    add(perSec, t); polls++; origin += polls; start = t + rtt[i];
                }
                case 2 -> start = -skew[i];                              // local timer on the phone's own clock
                case 3 -> {                                              // one origin call on page load: offset = server Date − local clock
                    add(perSec, t0); origin++;
                    // the Date header is truncated to whole seconds (up to 1 s behind) and the one-way delay is unknown (±RTT/2)
                    start = r.nextDouble() + (r.nextDouble() - 0.5) * rtt[i];
                }
                default -> {                                             // S3, then at T−10 s re-sync from an edge-cached, ms-precision time endpoint
                    add(perSec, t0); origin++;
                    start = (r.nextDouble() - 0.5) * rtt[i] * 0.5;       // RTT measured, so only asymmetry error remains
                }
            }
            // the shopper taps Buy ~0.3–1.5 s after the button appears; that tap always goes to the origin
            double tap = start + 0.3 + r.nextDouble() * 1.2;
            add(perSec, tap); origin++;
            if (tap < 0) early++;
            if (Math.abs(start) <= 1) ready++;
            if (start > 5) late++;
        }
        long peak = Arrays.stream(perSec).max().getAsLong();
        String[] names = {"", "S1 poll the origin every 2 s", "S2 timer on the phone's clock", "S3 offset from server Date on page load", "S4 S3 + ms-precision re-sync at T−10 s"};
        System.out.printf("%-48s %,14d %,14d %11.1f%% %13.1f%% %11.1f%%%n", names[s], origin, peak, 100.0 * early / USERS, 100.0 * ready / USERS, 100.0 * late / USERS);
    }
    static void add(long[] perSec, double t) { int idx = (int) Math.floor(t) + WINDOW; if (idx >= 0 && idx < perSec.length) perSec[idx]++; }
}
