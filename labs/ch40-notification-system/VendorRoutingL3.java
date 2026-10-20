// VendorRoutingL3.java — run with: java VendorRoutingL3.java   (JDK 21, no dependencies)
// SILENT VENDOR FAILURE. Three SMS vendors (cost per SMS, normal delivery rate): V1 ₹0.12 97%, V2 ₹0.15 96%, V3 ₹0.20 98%.
// For two hours OTPs are sent at ~50/s. From minute 30 to minute 70, V1's route to one large operator breaks SILENTLY: its
// API still answers 200 "accepted", but only 35% of messages are delivered, late. The only evidence is delivery reports
// (DLRs), which arrive after delivery — or never. An OTP is useful only if it arrives within 30 s. Four routing policies:
//   S1 all traffic to the cheapest vendor
//   S2 fail over on API errors (5xx/timeouts)                          — V1 never errors, so nothing happens
//   S3 health from DLRs: per vendor, share of the last 60 s of sends delivered within 30 s; below 85% → route to the
//      next-cheapest healthy vendor, keep 5% probe traffic on the sick one to detect recovery
//   S4 S3 + if no DLR within 20 s, resend the OTP once through a different vendor
import java.util.*;

public class VendorRoutingL3 {
    static final double[] COST = {0.12, 0.15, 0.20}, RATE = {0.97, 0.96, 0.98};

    record Sent(long t, int vendor, boolean delivered, long deliveredAt) {}

    public static void main(String[] args) {
        System.out.println("OTPs at ~50/s for 2 h; V1 silently degrades to 35% delivery in minutes 30–70 (API still returns 200).\n");
        System.out.printf("   %-52s %14s %16s %13s %16s%n", "policy", "OTPs ≤30 s", "during incident", "SMS sent", "₹ per useful OTP");
        String[] names = {"S1) cheapest vendor only", "S2) fail over on API errors", "S3) route on DLR health (85%, 60 s window)", "S4) S3 + resend via other vendor after 20 s"};
        for (int p = 0; p < 4; p++) run(p, names[p]);
        System.out.println("\n   → the vendor's API said 'accepted' throughout; failing over on errors never triggered. Delivery reports are the");
        System.out.println("     only honest health signal, so route on them: measured over a sliding window they exposed the silent failure");
        System.out.println("     in under a minute (a send can only be judged once its 30 s deadline passes) and moved traffic, keeping a trickle on the");
        System.out.println("     sick vendor to see recovery. A single resend through another vendor covers the messages already lost —");
        System.out.println("     worth its extra SMS for OTPs, where a failed login costs far more than ₹0.20; not for marketing.");
    }

    static void run(int policy, String name) {
        Random rnd = new Random(40); int horizon = 7200; long incidentFrom = 1800_000, incidentTo = 4200_000;
        Deque<Sent> window = new ArrayDeque<>();                                  // sends from the last 90 s, for DLR health
        boolean[] healthy = {true, true, true};
        long useful = 0, total = 0, usefulIncident = 0, totalIncident = 0, sms = 0; double cost = 0; long firstShift = -1;
        for (long ms = 0; ms < horizon * 1000L; ms += 20) {                          // ~50/s
            if (rnd.nextDouble() > 0.999) continue;
            total++; boolean inInc = ms >= incidentFrom && ms < incidentTo; if (inInc) totalIncident++;
            // --- health from DLRs visible NOW: a send counts once 30 s have passed (delivered in time or not) ---
            if (policy >= 2 && ms % 1000 == 0) {
                while (!window.isEmpty() && window.peekFirst().t() < ms - 90_000) window.pollFirst();
                for (int v = 0; v < 3; v++) {
                    long n = 0, ok = 0;
                    for (Sent s : window) if (s.vendor() == v && s.t() <= ms - 30_000 && s.t() > ms - 90_000) { n++; if (s.delivered() && s.deliveredAt() - s.t() <= 30_000) ok++; }
                    boolean was = healthy[v]; healthy[v] = n < 20 || (double) ok / n >= 0.85;
                    if (was && !healthy[v] && firstShift < 0) firstShift = ms;
                }
            }
            int v = 0;
            if (policy >= 2) { v = -1; for (int k = 0; k < 3; k++) if (healthy[k]) { v = k; break; } if (v < 0) v = 2; if (!healthy[0] && rnd.nextDouble() < 0.05) v = 0; }
            Sent s = send(ms, v, rnd, incidentFrom, incidentTo); sms++; cost += COST[v]; window.addLast(s);
            boolean ok = s.delivered() && s.deliveredAt() - ms <= 30_000;
            if (!ok && policy == 3 && !(s.delivered() && s.deliveredAt() - ms <= 20_000)) {   // no DLR by 20 s → resend elsewhere
                int alt = v == 0 ? 1 : 0; if (!healthy[alt]) alt = 2;
                Sent r2 = send(ms + 20_000, alt, rnd, incidentFrom, incidentTo); sms++; cost += COST[alt];
                ok = r2.delivered() && r2.deliveredAt() - ms <= 30_000;
            }
            if (ok) { useful++; if (inInc) usefulIncident++; }
        }
        System.out.printf("   %-52s %13.1f%% %15.1f%% %,13d %16.3f%n", name, 100.0 * useful / total, 100.0 * usefulIncident / totalIncident, sms, cost / useful);
        if (firstShift >= 0) System.out.printf("        first shift away from V1 at minute %.1f (incident began at minute 30.0)%n", firstShift / 60_000.0);
    }

    static Sent send(long t, int v, Random rnd, long from, long to) {
        boolean broken = v == 0 && t >= from && t < to;
        double rate = broken ? 0.35 : RATE[v];
        boolean delivered = rnd.nextDouble() < rate;
        long latency = broken ? 20_000 + (long) (rnd.nextDouble() * 90_000) : 2_000 + (long) (-Math.log(1 - rnd.nextDouble()) * 3_000);
        return new Sent(t, v, delivered, t + latency);
    }
}
