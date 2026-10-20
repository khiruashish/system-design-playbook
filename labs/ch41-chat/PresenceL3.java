// PresenceL3.java — run with: java PresenceL3.java   (JDK 21, no dependencies)
// PRESENCE ON MOBILE NETWORKS. Chat shows whether the rider is online ("Ramesh is online · typing…"), and dispatch uses the
// same signal. 60,000 riders are on shift in the evening. Their connections drop briefly and often — lifts, basements,
// tower handovers (~6 drops an hour, median 8 s, some over a minute) — and half of those drops are SILENT (no TCP close
// reaches the server). Riders also genuinely go offline (end of shift, app killed) about once every two hours.
// We simulate 10,000 riders for one hour (fixed seed) and scale load figures to 60,000. Presence designs:
//   S1 heartbeat every 5 s, offline after 10 s silence, offline immediately on socket close
//   S2 heartbeat every 30 s, offline after 60 s silence, offline immediately on socket close
//   S3 S2 + debounce: a socket close starts a 20 s grace period (a reconnect cancels it)
//   S4 no separate heartbeat: presence rides on the rider's location updates (already sent every 10 s for tracking),
//      offline after 35 s silence, 20 s grace on close
// Measured: presence-store writes per second, FALSE offline flips per rider-hour (shown offline during a brief drop),
// fan-out events (each flip notifies ~1.5 watchers: the customer's chat and dispatch), and how fast a REAL offline shows.
import java.util.*;

public class PresenceL3 {
    record Design(String name, double hb, double timeout, double grace, boolean piggyback) {}

    public static void main(String[] args) {
        Design[] ds = {
            new Design("S1) heartbeat 5 s, timeout 10 s, instant on close", 5, 10, 0, false),
            new Design("S2) heartbeat 30 s, timeout 60 s, instant on close", 30, 60, 0, false),
            new Design("S3) S2 + 20 s grace after a close", 30, 60, 20, false),
            new Design("S4) from location updates (10 s), timeout 35 s, 20 s grace", 10, 35, 20, true)};
        System.out.println("60,000 riders on shift; ~6 brief drops/hour each (median 8 s, half silent); real offline ~0.5/hour.\n");
        System.out.printf("   %-60s %13s %14s %13s %18s%n", "design", "extra writes", "false offline", "fan-out", "real offline shown");
        System.out.printf("   %-60s %13s %14s %13s %18s%n", "", "per second", "per rider-hr", "events/s", "after p50 / p95");
        for (Design d : ds) run(d);
        System.out.println("\n   → presence is a trade between honesty and noise. Frequent heartbeats detect real departures fast but cost");
        System.out.println("     writes and flag every lift ride as 'offline'; instant offline on socket close is the worst of both, because");
        System.out.println("     mobile connections close all the time and reopen seconds later. A short grace period absorbs the flapping,");
        System.out.println("     and deriving presence from traffic the rider already sends (location updates) costs no extra writes at all.");
        System.out.println("     Show 'last seen' rather than a hard offline, and never make a business decision on one missed heartbeat.");
    }

    static void run(Design d) {
        Random rnd = new Random(41); int riders = 10_000; double hour = 3600;
        long falseOffline = 0; List<Double> detect = new ArrayList<>(); long flips = 0;
        for (int r = 0; r < riders; r++) {
            double phase = rnd.nextDouble() * d.hb();                                   // heartbeat phase
            // brief drops
            for (double t = expo(rnd, 600); t < hour; t += expo(rnd, 600)) {
                double dur = Math.exp(Math.log(8) + 0.9 * rnd.nextGaussian());          // lognormal, median 8 s
                boolean silent = rnd.nextBoolean();
                boolean shownOffline;
                if (!silent) shownOffline = dur > d.grace();                            // close seen: offline after grace (0 = instantly)
                else {
                    double sinceLast = (t - phase) % d.hb(); if (sinceLast < 0) sinceLast += d.hb();
                    shownOffline = sinceLast + dur > d.timeout();                        // silence exceeds timeout
                }
                if (shownOffline) { falseOffline++; flips += 2; }                         // offline, then online again
                t += dur;
            }
            // one real offline event with probability 0.5 in the hour
            if (rnd.nextDouble() < 0.5) {
                double t = rnd.nextDouble() * hour; boolean silent = rnd.nextBoolean();
                double sinceLast = (t - phase) % d.hb(); if (sinceLast < 0) sinceLast += d.hb();
                detect.add(silent ? d.timeout() - sinceLast : d.grace()); flips++;
            }
        }
        Collections.sort(detect);
        double scale = 60_000.0 / riders;
        double writes = d.piggyback() ? 0 : 60_000 / d.hb();
        System.out.printf("   %-60s %,13.0f %14.2f %,13.1f %11.0f s / %2.0f s%n", d.name(), writes, (double) falseOffline / riders,
                flips * scale * 1.5 / 3600, detect.get(detect.size() / 2), detect.get((int) (detect.size() * 0.95)));
    }
    static double expo(Random r, double mean) { return -Math.log(1 - r.nextDouble()) * mean; }
}
