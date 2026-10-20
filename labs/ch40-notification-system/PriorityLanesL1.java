// PriorityLanesL1.java — run with: java PriorityLanesL1.java   (JDK 21, no dependencies)
// ONE SMS PIPE, THREE KINDS OF MESSAGE. The SMS vendor contract allows 500 messages/s. At 19:00 marketing enqueues a
// 2,500,000-SMS Diwali campaign; meanwhile customers keep requesting OTPs (~20/s, useless after 5 minutes, should arrive
// within seconds) and the platform keeps sending transactional updates (~60/s: order confirmed, out for delivery, refunds).
// The campaign WORKS: from 19:05 customers open the app and log in (OTPs rise to ~150/s for 30 min) and from 19:10 they
// order (transactional rises to ~200/s for 50 min) — the campaign creates the traffic it competes with.
// Promotional SMS may only be sent 09:00–21:00 (TRAI), so the campaign must finish by 21:00. Simulated per second for two
// hours with Poisson arrivals; four ways to share the pipe:
//   A) one FIFO queue for everything
//   B) three lanes, strict priority: OTP > transactional > marketing
//   C) three lanes, weighted fair share OTP 20% / transactional 30% / marketing 50% (unused share flows to the others)
//   D) B, plus marketing on its own promotional route/account (400 msg/s) so it never competes for the 500
import java.util.*;

public class PriorityLanesL1 {
    public static void main(String[] args) {
        int horizon = 7200; long campaign = 2_500_000;
        System.out.printf("Vendor 500 SMS/s. 19:00 campaign of %,d SMS; OTP 20/s → 150/s (19:05–19:35), expires 300 s;%ntransactional 60/s → 200/s (19:10–20:00). Window 19:00–21:00.%n%n", campaign);
        System.out.printf("   %-44s %9s %9s %11s %12s %20s%n", "strategy", "OTP p50", "OTP p99", "OTP expired", "txn p99", "campaign unsent 21:00");
        String[] names = {"A) one FIFO queue", "B) lanes, strict priority", "C) lanes, weighted fair 20/30/50", "D) B + separate promotional route (400/s)"};
        for (int s = 0; s < 4; s++) run(s, names[s], horizon, campaign);
        System.out.println("\n   → a single queue puts OTPs behind the campaign exactly when the campaign drives logins. Strict priority");
        System.out.println("     protects OTPs and order updates, but the campaign only gets what is left and misses the 21:00 cut-off.");
        System.out.println("     'Fair' weights chosen for the campaign starve OTPs during the login surge. A separate promotional route");
        System.out.println("     (India's DLT rules already separate promotional from transactional/service traffic) removes the contention;");
        System.out.println("     size it so the campaign finishes inside the legal window. Priority is a product decision per message type.");
    }

    static void run(int strategy, String name, int horizon, long campaign) {
        Random rnd = new Random(40); expiredInDrain = 0;
        Deque<Integer> otp = new ArrayDeque<>(), txn = new ArrayDeque<>(), fifo = new ArrayDeque<>();   // arrival seconds
        long mkt = campaign; boolean fifoHasCampaign = strategy == 0;
        long fifoCampaignLeft = campaign;                                  // in FIFO the campaign sits ahead of later arrivals
        List<Integer> otpWait = new ArrayList<>(), txnWait = new ArrayList<>(); int otpExpired = 0, otpTotal = 0;
        for (int t = 0; t < horizon; t++) {
            int nOtp = poisson(rnd, t >= 300 && t < 2100 ? 150 : 20), nTxn = poisson(rnd, t >= 600 && t < 3600 ? 200 : 60);
            otpTotal += nOtp;                                                             // the campaign drives logins and orders
            if (strategy == 0) { for (int i = 0; i < nOtp; i++) fifo.add(-(t + 1)); for (int i = 0; i < nTxn; i++) fifo.add(t + 1); }   // sign marks OTP
            else { for (int i = 0; i < nOtp; i++) otp.add(t); for (int i = 0; i < nTxn; i++) txn.add(t); }
            int cap = 500;
            switch (strategy) {
                case 0 -> {
                    long c = Math.min(cap, fifoCampaignLeft); fifoCampaignLeft -= c; cap -= (int) c;       // campaign first: it arrived first
                    while (cap > 0 && !fifo.isEmpty()) {
                        int a = fifo.poll(); int arr = Math.abs(a) - 1; cap--;
                        if (a < 0) { if (t - arr > 300) otpExpired++; else otpWait.add(t - arr); } else txnWait.add(t - arr);
                    }
                    mkt = fifoCampaignLeft;
                }
                case 1, 3 -> {
                    cap = drainOtp(otp, cap, t, otpWait); otpExpired += expire(otp, t);
                    while (cap > 0 && !txn.isEmpty()) { txnWait.add(t - txn.poll()); cap--; }
                    int mktCap = strategy == 3 ? 400 : cap; long c = Math.min(mktCap, mkt); mkt -= c;
                }
                default -> {
                    int qo = 100, qt = 150, qm = 250;                                                     // 20 / 30 / 50
                    int usedO = Math.min(qo, otp.size()), usedT = Math.min(qt, txn.size()); int usedM = (int) Math.min(qm, mkt);
                    int spare = cap - usedO - usedT - usedM;
                    // redistribute unused capacity in priority order (work-conserving)
                    int extraO = Math.min(spare, otp.size() - usedO); spare -= extraO;
                    int extraT = Math.min(spare, txn.size() - usedT); spare -= extraT;
                    int extraM = (int) Math.min(spare, mkt - usedM);
                    drainOtp(otp, usedO + extraO, t, otpWait); otpExpired += expire(otp, t);
                    for (int i = 0; i < usedT + extraT; i++) txnWait.add(t - txn.poll());
                    mkt -= usedM + extraM;
                }
            }
        }
        otpExpired += (strategy == 0 ? (int) fifo.stream().filter(a -> a < 0).count() : otp.size());   // never sent in the window
        otpExpired += expiredInDrain;
        Collections.sort(otpWait); Collections.sort(txnWait);
        System.out.printf("   %-44s %9s %9s %10.1f%% %11ds %,20d%n", name, fmt(pct(otpWait, 50)), fmt(pct(otpWait, 99)), 100.0 * otpExpired / otpTotal,
                pct(txnWait, 99), mkt);
    }
    static int expiredInDrain = 0;
    static int drainOtp(Deque<Integer> q, int cap, int t, List<Integer> waits) {
        while (cap > 0 && !q.isEmpty()) { int a = q.poll(); if (t - a > 300) { expiredInDrain++; continue; } waits.add(t - a); cap--; }
        return cap;
    }
    static int expire(Deque<Integer> q, int t) { int n = 0; while (!q.isEmpty() && t - q.peek() > 300) { q.poll(); n++; } return n; }
    static String fmt(int s) { return s < 0 ? "none sent" : s + "s"; }
    static int pct(List<Integer> l, int p) { return l.isEmpty() ? -1 : l.get(Math.min(l.size() - 1, l.size() * p / 100)); }
    static int poisson(Random r, double lambda) { double l = Math.exp(-lambda), p = 1; int k = 0; do { k++; p *= r.nextDouble(); } while (p > l); return k - 1; }
}
