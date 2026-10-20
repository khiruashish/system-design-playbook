// RegionsFailuresL4.java — run with: java RegionsFailuresL4.java   (JDK 21, no dependencies)
// A LIMITER THAT SPANS REGIONS AND SURVIVES ITS OWN OUTAGES. Deterministic simulation, second by second.
// Part 1 — a partner's contract is 1,000 req/s GLOBALLY; we serve it from two regions (Mumbai, Hyderabad), each with its own
// Redis. Over 10 minutes the partner shifts traffic from 80/20 to 25/75 (their DNS failover), offering 900 req/s — within
// contract — except for a runaway batch job in minutes 6–7 at 1,400 req/s. The ideal limiter rejects nothing in contract
// and never admits more than 1,000 in a second. Four designs:
//   S1 static split 500/500          S2 static split from last month's traffic, 800/200
//   S3 rebalance every 10 s: each region's share ∝ its demand in the last 10 s (regions exchange counts asynchronously)
//   S4 one central limiter in Mumbai: exact, but every Hyderabad request crosses regions (+~25 ms) and, during a 60 s
//      inter-region partition (minutes 6–7), Hyderabad must fail open or closed
// Part 2 — the limiter's Redis in one region is down for 2 minutes during an SMS-pumping attack (L3). What should the
// OTP endpoint do? fail OPEN (allow), fail CLOSED (reject), or fall back to LOCAL per-node limits with each node holding
// a 1/N share of the range budgets.
import java.util.*;

public class RegionsFailuresL4 {
    public static void main(String[] args) {
        int seconds = 600; double limit = 1000, offered = 900; Random rnd = new Random(39);
        double[] mumShare = new double[seconds];
        for (int s = 0; s < seconds; s++) mumShare[s] = s < 180 ? 0.80 : s < 300 ? 0.80 - 0.55 * (s - 180) / 120.0 : 0.25;
        double[][] demand = new double[seconds][2];
        for (int s = 0; s < seconds; s++) { double noise = 0.95 + 0.1 * rnd.nextDouble(); double off = (s >= 360 && s < 420) ? 1_400 : offered; demand[s][0] = off * mumShare[s] * noise; demand[s][1] = off * (1 - mumShare[s]) * noise; }

        System.out.println("Part 1 — contract 1,000 req/s global; offered 900 req/s (1,400 in minutes 6–7); Mumbai share 80% → 25% over minutes 3–5\n");
        System.out.printf("   %-52s %18s %20s %22s%n", "design", "in-contract reqs", "max admitted in 1 s", "cross-region calls");
        System.out.printf("   %-52s %18s %20s %22s%n", "", "rejected", "(contract 1,000)", "");
        String[] names = {"S1) static split 500 / 500", "S2) static split from history 800 / 200", "S3) rebalance every 10 s by demand",
                "S4) central in Mumbai (partition min 6–7: fail open)"};
        for (int d = 0; d < 4; d++) {
            double[] share = d == 0 ? new double[]{500, 500} : new double[]{800, 200};
            double rejected = 0, maxAdmit = 0, cross = 0; double[] window = new double[2];
            for (int s = 0; s < seconds; s++) {
                if (d == 2 && s > 0 && s % 10 == 0) {                       // rebalance on the last 10 s of demand (known with a lag)
                    double tot = window[0] + window[1]; share[0] = limit * window[0] / tot; share[1] = limit - share[0]; window = new double[2];
                }
                double admit = 0;
                if (d == 3) {
                    boolean partitioned = s >= 360 && s < 420;
                    cross += demand[s][1];
                    double central = Math.min(limit, demand[s][0] + (partitioned ? 0 : demand[s][1]));
                    admit = central + (partitioned ? demand[s][1] : 0);    // Hyderabad fails open while partitioned
                    
                } else {
                    for (int g = 0; g < 2; g++) { double a = Math.min(share[g], demand[s][g]); admit += a; window[g] += demand[s][g]; }
                }
                rejected += Math.max(0, Math.min(limit, demand[s][0] + demand[s][1]) - admit);   // only in-contract traffic counts
                maxAdmit = Math.max(maxAdmit, admit);
            }
            System.out.printf("   %-52s %,18.0f %,20.0f %,22.0f%n", names[d], rejected, maxAdmit, cross);
        }

        // ---------------- Part 2 ----------------
        System.out.println("\nPart 2 — OTP limiter's Redis down for 120 s (minutes 4–6) during an SMS-pumping attack; 8 gateway nodes\n");
        int nodes = 8; double legitPerSec = 33, attackPerSec = 17, rangeBudgetPerHour = 60 * 5;   // 5 attacked ranges, 60/h each (L3)
        System.out.printf("   %-50s %22s %22s %16s%n", "behaviour while Redis is down", "attack SMS in outage", "legit OTPs refused", "attack cost");
        String[] modes = {"fail OPEN (allow everything)", "fail CLOSED (reject everything)", "LOCAL fallback (1/8 of each budget per node)"};
        for (int m = 0; m < 3; m++) {
            double attackSms = 0, legitRefused = 0; double[] localBudget = new double[nodes];
            Arrays.fill(localBudget, rangeBudgetPerHour / nodes * (120.0 / 3600) * 3);            // a node's share for the outage, with 3x headroom
            for (int s = 0; s < 120; s++) {
                double legit = legitPerSec * (0.9 + 0.2 * rnd.nextDouble()), atk = attackPerSec * (0.9 + 0.2 * rnd.nextDouble());
                switch (m) {
                    case 0 -> attackSms += atk;
                    case 1 -> legitRefused += legit;
                    default -> {                                                 // per-phone limits are local too: legit mostly unaffected
                        for (int n = 0; n < nodes; n++) { double a = Math.min(localBudget[n], atk / nodes); localBudget[n] -= a; attackSms += a; }
                        legitRefused += legit * 0.004;                          // retries that hit a different node's phone limit (assumption)
                    }
                }
            }
            System.out.printf("   %-50s %,22.0f %,22.0f %16s%n", modes[m], attackSms, legitRefused, String.format("₹%,.0f", attackSms * 4.50));
        }

        System.out.println("\n   → a global quota split statically between regions is wrong as soon as traffic moves: the partner is refused");
        System.out.println("     while well inside its contract. Rebalancing shares by recent demand keeps both properties with only periodic");
        System.out.println("     count exchange; a single central limiter is exact but puts a cross-region call on every remote request and");
        System.out.println("     turns a partition into a choice between over-admitting (1,468/s here) and an outage. When the limiter itself fails, the");
        System.out.println("     right default depends on what the limit protects: failing open on an OTP endpoint during an attack pays the");
        System.out.println("     fraudsters, failing closed stops all logins; a conservative local fallback bounds both. Decide per limit, in");
        System.out.println("     advance, and test it.");
    }
}
