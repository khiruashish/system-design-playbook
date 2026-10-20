// RoutingL4.java — run with: java RoutingL4.java   (JDK 21, no dependencies)
// WHOSE FAILURE IS IT? Payment success depends on the gateway (PSP) AND on the customer's bank. Two hours of UPI
// checkouts (~40/s, fixed seed) across two PSPs and 8 issuer banks. Normally 93% succeed (the rest are customer-side:
// wrong PIN, insufficient balance). Two incidents:
//   PSP incident     minutes 20–50: PSP-A's UPI switch degrades, 40% success for every bank through PSP-A
//   BANK incident    minutes 70–90: issuer Bank-3 (20% of customers) is down, 5% success through ANY PSP
// A customer whose payment fails retries once 60% of the time. Four routing policies:
//   S1 everything through PSP-A (the cheaper contract)
//   S2 PSP-level health: if PSP-A's success over the last 5 min falls below 80%, send ALL traffic to PSP-B
//   S3 health per (PSP, bank): move only the traffic that is failing; if a bank fails on BOTH PSPs, it's the bank —
//      don't reroute, tell the customer ("Bank-3 UPI is down — pay by card or another UPI account")
//   S4 S3 + a failed attempt is retried once on the other PSP automatically if the failure looks PSP-side
import java.util.*;

public class RoutingL4 {
    static final double[] BANK_SHARE = {0.22, 0.18, 0.20, 0.12, 0.10, 0.08, 0.06, 0.04};

    public static void main(String[] args) {
        System.out.println("UPI checkouts ~40/s for 2 h; PSP-A degraded minutes 20–50; Bank-3 (20% of customers) down minutes 70–90.\n");
        System.out.printf("   %-58s %9s %12s %13s %15s %13s%n", "routing policy", "overall", "during PSP", "during BANK", "routing flips", "customers");
        System.out.printf("   %-58s %9s %12s %13s %15s %13s%n", "", "success", "incident", "incident", "", "nudged");
        String[] names = {"S1) everything via PSP-A", "S2) PSP-level health, fail over all traffic", "S3) health per (PSP, bank); bank-side → nudge customer",
                "S4) S3 + auto-retry on the other PSP for PSP-side failures"};
        for (int p = 0; p < 4; p++) run(p, names[p]);
        System.out.println("\n   → a gateway-level health check can't tell a gateway problem from a bank problem: when Bank-3 went down, S2 saw");
        System.out.println("     PSP-A's success fall and moved everyone to PSP-B, which failed Bank-3 just the same — churn without benefit.");
        System.out.println("     Measuring success per (gateway, bank) shows where the failure is: move only the traffic a healthy gateway can");
        System.out.println("     carry, and when every gateway fails for one bank, no routing helps — the only rescue is steering the customer to");
        System.out.println("     another bank or method. An automatic retry on a second gateway rescues gateway-side failures within the same");
        System.out.println("     checkout, but only with an idempotent order so the customer can't be charged twice. (S3/S4 flip often because an");
        System.out.println("     avoided path gets no traffic, so its window empties and it looks healthy again — real routers keep a small");
        System.out.println("     probe share on avoided paths, as in Ch 40's vendor routing.)");
    }

    static void run(int policy, String name) {
        Random r = new Random(45); int seconds = 7200; double rate = 40;
        long ok = 0, total = 0, okPsp = 0, totPsp = 0, okBank = 0, totBank = 0, flips = 0, nudged = 0;
        // health windows: success counts per (psp, bank) and per psp over the last 300 s, bucketed per 10 s
        int buckets = 30; long[][][] att = new long[2][9][buckets], suc = new long[2][9][buckets];
        int allTo = 0; boolean[][] avoid = new boolean[2][8];
        for (int t = 0; t < seconds; t++) {
            int b = (t / 10) % buckets;
            if (t % 10 == 0) for (int ps = 0; ps < 2; ps++) for (int k = 0; k < 9; k++) { att[ps][k][b] = 0; suc[ps][k][b] = 0; }
            if (t % 30 == 0 && t > 300) {                                                    // re-evaluate routing every 30 s
                if (policy == 1) { int want = rateOf(att[0][8], suc[0][8]) < 0.80 ? 1 : 0; if (want != allTo) { flips++; allTo = want; } }
                if (policy >= 2) for (int k = 0; k < 8; k++) for (int ps = 0; ps < 2; ps++) {
                    boolean bad = rateOf(att[ps][k], suc[ps][k]) < 0.80; if (bad != avoid[ps][k]) { flips++; avoid[ps][k] = bad; } }
            }
            int n = poisson(r, rate);
            for (int i = 0; i < n; i++) {
                int bank = pickBank(r); boolean inPsp = t >= 1200 && t < 3000, inBank = t >= 4200 && t < 5400;
                total++; if (inPsp) totPsp++; if (inBank) totBank++;
                int psp = policy == 0 ? 0 : policy == 1 ? allTo : (avoid[0][bank] && !avoid[1][bank] ? 1 : 0);
                boolean bankDown = policy >= 2 && avoid[0][bank] && avoid[1][bank];
                if (bankDown && r.nextDouble() < 0.6) { nudged++; ok++; if (inPsp) okPsp++; if (inBank) okBank++; continue; }   // pays by card / other account
                boolean s = attempt(r, t, psp, bank, att, suc, b);
                if (!s && policy == 3 && pspSide(t, psp)) s = attempt(r, t, 1 - psp, bank, att, suc, b);                         // automatic second gateway
                if (!s && r.nextDouble() < 0.6) s = attempt(r, t, psp, bank, att, suc, b);                                        // customer retries once
                if (s) { ok++; if (inPsp) okPsp++; if (inBank) okBank++; }
            }
        }
        System.out.printf("   %-58s %8.1f%% %11.1f%% %12.1f%% %,15d %,13d%n", name, 100.0 * ok / total, 100.0 * okPsp / totPsp, 100.0 * okBank / totBank, flips, nudged);
    }
    static boolean pspSide(int t, int psp) { return psp == 0 && t >= 1200 && t < 3000; }   // failure codes say "gateway" (PSP-side) in this window
    static boolean attempt(Random r, int t, int psp, int bank, long[][][] att, long[][][] suc, int b) {
        double p = 0.93;
        if (psp == 0 && t >= 1200 && t < 3000) p = 0.40;
        if (bank == 2 && t >= 4200 && t < 5400) p = 0.05;
        boolean s = r.nextDouble() < p;
        att[psp][bank][b]++; att[psp][8][b]++; if (s) { suc[psp][bank][b]++; suc[psp][8][b]++; }
        return s;
    }
    static double rateOf(long[] a, long[] s) { long x = 0, y = 0; for (int i = 0; i < a.length; i++) { x += a[i]; y += s[i]; } return x < 30 ? 1 : (double) y / x; }
    static int pickBank(Random r) { double x = r.nextDouble(), c = 0; for (int i = 0; i < 8; i++) { c += BANK_SHARE[i]; if (x < c) return i; } return 7; }
    static int poisson(Random r, double l) { double L = Math.exp(-l), p = 1; int k = 0; do { k++; p *= r.nextDouble(); } while (p > L); return k - 1; }
}
