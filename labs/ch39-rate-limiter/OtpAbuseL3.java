// OtpAbuseL3.java — run with: java OtpAbuseL3.java   (JDK 21, no dependencies)
// RATE LIMITING AS FRAUD CONTROL: OTP SMS PUMPING. Login is OTP-by-SMS. In an SMS-pumping attack, fraudsters trigger OTPs
// to phone numbers in ranges where they share the termination revenue — every OTP costs the platform money and nobody
// ever logs in. One hour of traffic is simulated (fixed seed):
//   LEGIT — 100,000 users request an OTP (some retry because the SMS is slow); 60% are on mobile data behind carrier
//           CGNAT (a few hundred shared IPs), 40% on home broadband; 1,500 international requests (NRI customers) spread
//           over many country ranges
//   ATTACK — 60,000 OTP requests to 30,000 numbers in 5 international ranges, sent through 5,000 residential-proxy IPs,
//           each request with a fresh device fingerprint (emulators), at most 2 OTPs per number
// Four limiting policies are applied to the same traffic; we count SMS sent to attackers (and their cost at an
// assumed ₹4.50 per international SMS) and the legitimate users who couldn't get an OTP at all within the hour.
import java.util.*;

public class OtpAbuseL3 {
    record Req(long t, String user, String phone, String range, String ip, String device, boolean attack, boolean intl, boolean attestOk) {}

    public static void main(String[] args) {
        Random rnd = new Random(39); List<Req> reqs = new ArrayList<>(); long hour = 3_600_000;
        String[] cgnat = new String[300]; for (int i = 0; i < cgnat.length; i++) cgnat[i] = "cgnat-" + i;
        String[] legitIntlRanges = new String[60]; for (int i = 0; i < 60; i++) legitIntlRanges[i] = "+9" + (70 + i % 10) + "-" + i;
        for (int u = 0; u < 100_000; u++) {
            boolean intl = u < 1_500;
            String phone = "p" + u, range = intl ? legitIntlRanges[rnd.nextInt(60)] : "+91";
            String ip = rnd.nextDouble() < 0.6 ? cgnat[(int) Math.min(cgnat.length - 1, Math.abs(rnd.nextGaussian()) * 90)] : "home-" + rnd.nextInt(40_000);
            String dev = "dev-" + u; boolean att = rnd.nextDouble() > 0.03;                  // 3% of real phones fail attestation
            long t = (long) (rnd.nextDouble() * hour);
            int retries = rnd.nextDouble() < 0.15 ? 1 + (rnd.nextDouble() < 0.2 ? 1 + rnd.nextInt(2) : 0) : 0;   // slow SMS → retries
            for (int k = 0; k <= retries; k++) reqs.add(new Req(t + k * (30_000 + rnd.nextInt(60_000)), "u" + u, phone, range, ip, dev, false, intl, att));
        }
        String[] attackRanges = {"+2x1-77", "+2x3-50", "+8x2-99", "+3x7-66", "+2x5-12"};
        for (int i = 0; i < 60_000; i++) {
            int num = rnd.nextInt(30_000);
            reqs.add(new Req((long) (rnd.nextDouble() * hour), "atk", "a" + num, attackRanges[num % 5], "proxy-" + rnd.nextInt(5_000),
                    "emu-" + i, true, true, rnd.nextDouble() < 0.10));                             // 10% get past attestation
        }
        reqs.sort(Comparator.comparingLong(Req::t));
        long legitUsers = 100_000;
        System.out.printf("One hour: %,d OTP requests (%,d legitimate from %,d users, %,d attack).%n%n", reqs.size(),
                reqs.stream().filter(r -> !r.attack()).count(), legitUsers, reqs.stream().filter(Req::attack).count());
        System.out.printf("   %-58s %12s %14s %18s%n", "policy", "attack SMS", "attack cost", "legit users blocked");
        String[] names = {
            "P1) per IP: 10 OTPs/hour",
            "P2) per phone: 3 per 10 min, 5 per hour",
            "P3) P2 + per IP 60/hour + per device 5/hour",
            "P4) P2 + per-range hourly budget + attestation → challenge"};
        for (int p = 0; p < 4; p++) evaluate(p, names[p], reqs, legitUsers, rnd);

        System.out.println("\n   → the obvious key is the wrong one: per-IP limits barely touch an attacker with 5,000 proxy IPs and lock out");
        System.out.println("     real customers who share a carrier CGNAT address with thousands of others. Per-phone limits stop repeats to");
        System.out.println("     one number but not a pump that spreads across 30,000 numbers. What works is keying on what the attacker must");
        System.out.println("     concentrate — the destination ranges that pay out — with budgets learned from normal traffic, and spending");
        System.out.println("     friction (attestation, then a challenge) only where risk is high. Limits here are a fraud control with a");
        System.out.println("     rupee cost on both sides, so measure false positives as carefully as attacks stopped.");
    }

    static void evaluate(int policy, String name, List<Req> reqs, long legitUsers, Random seed) {
        Random rnd = new Random(1000 + policy);
        Map<String, Deque<Long>> phoneLog = new HashMap<>(); Map<String, Integer> ipCount = new HashMap<>(), devCount = new HashMap<>(), rangeCount = new HashMap<>();
        long attackSms = 0; double cost = 0; Set<String> legitServed = new HashSet<>(), legitSeen = new HashSet<>();
        for (Req r : reqs) {
            if (!r.attack()) legitSeen.add(r.user());
            boolean allow = true;
            if (policy == 0) allow = ipCount.merge(r.ip(), 1, Integer::sum) <= 10;
            if (policy >= 1) {
                Deque<Long> log = phoneLog.computeIfAbsent(r.phone(), k -> new ArrayDeque<>());
                while (!log.isEmpty() && log.peekFirst() <= r.t() - 3_600_000) log.pollFirst();
                long last10 = log.stream().filter(x -> x > r.t() - 600_000).count();
                allow = last10 < 3 && log.size() < 5;
                if (allow) log.addLast(r.t());
            }
            if (allow && policy == 2) allow = ipCount.merge(r.ip(), 1, Integer::sum) <= 60 && devCount.merge(r.device(), 1, Integer::sum) <= 5;
            if (allow && policy == 3 && r.intl()) {
                int budget = 60;                                               // ~3x a range's normal hourly volume (learned baseline)
                if (rangeCount.merge(r.range(), 1, Integer::sum) > budget) allow = false;
                else if (!r.attestOk()) allow = rnd.nextDouble() < (r.attack() ? 0.20 : 0.95);   // challenge: humans pass, farms rarely
            }
            if (allow) {
                if (r.attack()) { attackSms++; cost += 4.50; } else legitServed.add(r.user());
            }
        }
        long blocked = legitSeen.size() - legitServed.size();
        System.out.printf("   %-58s %,12d %14s %,11d (%.2f%%)%n", name, attackSms, String.format("₹%,.0f", cost), blocked, 100.0 * blocked / legitUsers);
    }
}
