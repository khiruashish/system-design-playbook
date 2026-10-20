// AutoscaleL3.java — run with: java AutoscaleL3.java   (JDK 17+, no dependencies)
// Horizontal autoscaling as a control loop (Kubernetes HPA-style). A per-second load trace (diurnal ramp + a flash-sale
// spike + a noisy signal) drives a controller that adds/removes replicas to hold a target CPU utilisation. We compare:
//   A. the HPA formula: desired = ceil(current * currentMetric / targetMetric), with a scale-down stabilisation window;
//   B. no stabilisation (thrashing) vs the window;
//   C. accounting for JVM warm-up (a new replica isn't at full capacity for W seconds — Ch 25);
//   D. reactive vs a little predictive pre-scaling before a known peak.
// We measure: SLO breaches (util over 90%), replica-churn (scaling actions), and average replica count (cost).
import java.util.*;

public class AutoscaleL3 {
    static final double PER_REPLICA_RPS = 100;     // one warm replica serves 100 rps at the 70% target
    static final int TARGET_UTIL = 70;             // scale to keep utilisation ~70%
    static final int MINR = 2, MAXR = 40, WARMUP = 15;   // 15 s JVM warm-up before a replica is at full capacity (Ch 25)

    static int[] loadTrace(int seconds) {
        int[] rps = new int[seconds]; Random r = new Random(26);
        for (int t = 0; t < seconds; t++) {
            double hour = (t / (seconds / 24.0));
            double diurnal = 300 + 900 * Math.max(0, Math.sin((hour - 6) / 24.0 * Math.PI * 2));
            double spike = (t > seconds*0.55 && t < seconds*0.58) ? 2500 : 0;      // a flash sale
            rps[t] = (int) Math.max(50, diurnal + spike + r.nextGaussian() * 60);   // noisy
        }
        return rps;
    }

    /** Warm-up ALWAYS reduces real capacity; `warmAware` = does the controller compensate for it?
     *  Returns {breaches, scalingActions, avgReplicas, maxUtil}. */
    static double[] run(int[] rps, boolean stabilise, boolean warmAware, boolean predictive) {
        int replicas = MINR; int breaches = 0, actions = 0; double sumReplicas = 0, maxUtil = 0;
        Deque<Integer> recentDesired = new ArrayDeque<>();
        int[] warmAt = new int[MAXR + 5]; Arrays.fill(warmAt, -1000);
        for (int t = 0; t < rps.length; t++) {
            int warm = 0; for (int i = 0; i < replicas; i++) if (t - warmAt[i] >= WARMUP) warm++;
            double effReplicas = warm + (replicas - warm) * 0.3;                   // reality: warming replicas run at ~30%
            double realCap = Math.max(1, effReplicas) * PER_REPLICA_RPS / (TARGET_UTIL / 100.0);
            double util = 100.0 * rps[t] / realCap;                                 // the TRUE utilisation
            maxUtil = Math.max(maxUtil, util);
            if (util > 90) breaches++;

            // the controller sees utilisation; if it is NOT warm-aware it assumes all replicas are at full capacity
            double seenReplicas = warmAware ? effReplicas : replicas;
            double seenCap = Math.max(1, seenReplicas) * PER_REPLICA_RPS / (TARGET_UTIL / 100.0);
            double seenUtil = 100.0 * rps[t] / seenCap;
            int desired = (int) Math.ceil(replicas * (seenUtil / TARGET_UTIL));
            if (predictive && t + 20 < rps.length) desired = Math.max(desired, (int) Math.ceil(rps[t + 20] / (TARGET_UTIL / 100.0) / PER_REPLICA_RPS));
            desired = Math.max(MINR, Math.min(MAXR, desired));

            int next;
            if (desired > replicas) next = desired;
            else { recentDesired.addLast(desired); while (recentDesired.size() > 30) recentDesired.pollFirst();
                   next = stabilise ? recentDesired.stream().mapToInt(Integer::intValue).max().orElse(desired) : desired; }
            if (next != replicas) { actions++; if (next > replicas) for (int i = replicas; i < next; i++) warmAt[i] = t; replicas = next; }
            sumReplicas += replicas;
        }
        return new double[]{breaches, actions, sumReplicas / rps.length, maxUtil};
    }

    public static void main(String[] args) {
        int seconds = 1440;                                  // a "day" compressed to 1440 seconds (1 tick = 1 min)
        int[] rps = loadTrace(seconds);
        int peak = Arrays.stream(rps).max().getAsInt(), min = Arrays.stream(rps).min().getAsInt();
        System.out.printf("Load: %d ticks, %d–%d rps (diurnal + a flash-sale spike). Target %d%% util, %.0f rps/replica, %d s warm-up.%n%n",
                seconds, min, peak, TARGET_UTIL, PER_REPLICA_RPS, WARMUP);
        System.out.printf("   %-52s %10s %10s %12s %9s%n", "autoscaler configuration", "SLO breach", "scale ops", "avg replicas", "max util");
        record Cfg(String name, boolean stab, boolean warm, boolean pred) {}
        for (Cfg c : new Cfg[]{
                new Cfg("reactive, NO stabilisation (thrashes)", false, true, false),
                new Cfg("reactive + scale-down stabilisation (30 s)", true, true, false),
                new Cfg("controller ignores warm-up (under-scales)", true, false, false),
                new Cfg("+ predictive pre-scale for the peak", true, true, true)}) {
            double[] r = run(rps, c.stab(), c.warm(), c.pred());
            System.out.printf("   %-52s %10.0f %10.0f %12.1f %8.0f%%%n", c.name(), r[0], r[1], r[2], r[3]);
        }
        System.out.println("\n   → stabilisation stops flapping (fewer scale ops, steadier fleet); modelling warm-up matters because a just-added");
        System.out.println("     replica isn't at full capacity for ~15 s, so a purely reactive loop is always a step behind a fast spike;");
        System.out.println("     pre-scaling for a KNOWN peak (a sale) cuts the breach window that reactive scaling can't catch in time.");
        System.out.println("   (The 'ignores warm-up' row provisions the same replicas but breaches more: it treats cold replicas as full, so it scales too little.)");
    }
}
