// PolicyEngineL4.java — run with: java PolicyEngineL4.java   (JDK 21, no dependencies)
// WHAT TO SEND, ON WHICH CHANNEL, AND WHEN. One simulated day for 100,000 customers (fixed seed). Events:
//   • order lifecycle for ~30% of customers: confirmed, picked, packed, out for delivery, arriving, delivered — several
//     within minutes of each other (quick-commerce), plus occasional refunds          → CRITICAL (the customer is waiting)
//   • 6 marketing campaigns across the day to everyone (one at 22:30)                 → PROMOTIONAL
//   • price-drop alerts on wishlisted items (0–8 per user per day)                     → INFORMATIONAL
// Channels: push (free; 80% of customers have a working push token; a push is seen within 60 s ~85% of the time when the
// phone is reachable) and SMS (₹0.15; delivered and read within 2 minutes ~97% of the time). Four policies:
//   P1 send everything, critical on push AND SMS
//   P2 P1 + respect opt-outs (20% opted out of marketing) + quiet hours 22:00–08:00 for non-critical (held to 08:00)
//   P3 P2 + collapse order updates within 3 min (send only the latest) + marketing cap 2/day + price drops as one digest
//   P4 P3 + critical goes to push first; SMS only if the push isn't delivered/seen within 60 s
import java.util.*;

public class PolicyEngineL4 {
    enum Kind { CRITICAL, PROMO, INFO }
    record Ev(int user, int minute, Kind kind, String type) {}

    public static void main(String[] args) {
        int users = 100_000; Random rnd = new Random(40); List<Ev> evs = new ArrayList<>();
        boolean[] hasPush = new boolean[users], optOutPromo = new boolean[users];
        for (int u = 0; u < users; u++) { hasPush[u] = rnd.nextDouble() < 0.80; optOutPromo[u] = rnd.nextDouble() < 0.20; }
        String[] stages = {"confirmed", "picked", "packed", "out_for_delivery", "arriving", "delivered"};
        int[] gaps = {0, 2, 2, 3, 6, 4};                                                         // minutes between stages
        for (int u = 0; u < users; u++) {
            if (rnd.nextDouble() < 0.30) {
                int orders = rnd.nextDouble() < 0.2 ? 2 : 1;
                for (int o = 0; o < orders; o++) {
                    int m = 7 * 60 + rnd.nextInt(15 * 60);                                         // orders 07:00–22:00
                    for (int s = 0; s < stages.length; s++) { m += gaps[s] + rnd.nextInt(2); evs.add(new Ev(u, m, Kind.CRITICAL, stages[s])); }
                    if (rnd.nextDouble() < 0.05) evs.add(new Ev(u, m + 60, Kind.CRITICAL, "refund"));
                }
            }
            for (int c : new int[]{9 * 60, 11 * 60 + 30, 13 * 60, 17 * 60, 19 * 60, 22 * 60 + 30}) evs.add(new Ev(u, c, Kind.PROMO, "campaign"));
            int drops = rnd.nextInt(9); for (int d = 0; d < drops; d++) evs.add(new Ev(u, rnd.nextInt(24 * 60), Kind.INFO, "price_drop"));
        }
        evs.sort(Comparator.comparingInt(Ev::user).thenComparingInt(Ev::minute));
        System.out.printf("One day, %,d customers, %,d events (%,d critical, %,d promotional, %,d informational).%n%n", users, evs.size(),
                evs.stream().filter(e -> e.kind() == Kind.CRITICAL).count(), evs.stream().filter(e -> e.kind() == Kind.PROMO).count(),
                evs.stream().filter(e -> e.kind() == Kind.INFO).count());
        System.out.printf("   %-46s %10s %9s %10s %15s %13s %13s%n", "policy", "push", "SMS", "SMS ₹/day", "critical seen", "quiet-hours", "users >10");
        System.out.printf("   %-46s %10s %9s %10s %15s %13s %13s%n", "", "", "", "", "≤ 2 min", "promo sends", "a day");
        String[] names = {"P1) send everything; critical on push + SMS", "P2) P1 + opt-outs + quiet hours", "P3) P2 + collapse + caps + digest", "P4) P3 + push first, SMS fallback at 60 s"};
        for (int p = 0; p < 4; p++) run(p, names[p], evs, users, hasPush, optOutPromo);
        System.out.println("\n   → sending everything everywhere costs ~₹51,000 a day in SMS and buries the messages that matter: over half of");
        System.out.println("     customers get more than 10 notifications in a day. Preferences and quiet hours are");
        System.out.println("     obligations (consent, TRAI time windows), not optimisations. Collapsing updates that a newer one supersedes,");
        System.out.println("     capping marketing and batching alerts cut volume sharply without losing anything a customer needed. Escalating");
        System.out.println("     from push to SMS only when the push isn't seen cuts SMS spend by more than half again, and critical updates");
        System.out.println("     still reach ~99% of customers within two minutes — the fallback, not the double send, makes push dependable.");
    }

    static void run(int policy, String name, List<Ev> evs, int users, boolean[] hasPush, boolean[] optOut) {
        Random rnd = new Random(400 + policy);
        long push = 0, sms = 0, critTotal = 0, critSeen = 0, quietPromo = 0; int[] perUser = new int[users];
        int i = 0;
        while (i < evs.size()) {
            int u = evs.get(i).user(); int j = i; while (j < evs.size() && evs.get(j).user() == u) j++;
            List<Ev> mine = evs.subList(i, j); int promoSent = 0; boolean digestSent = false;
            for (int k = 0; k < mine.size(); k++) {
                Ev e = mine.get(k);
                if (e.kind() == Kind.CRITICAL) {
                    critTotal++;
                    if (policy >= 2 && k + 1 < mine.size() && mine.get(k + 1).kind() == Kind.CRITICAL && mine.get(k + 1).minute() - e.minute() <= 3
                            && !e.type().equals("refund")) { critSeen++; continue; }    // superseded within 3 min: the next update carries it
                    boolean pushSeen = hasPush[u] && rnd.nextDouble() < 0.85;
                    if (hasPush[u]) { push++; perUser[u]++; }
                    boolean smsSent = policy < 3 || !pushSeen;
                    if (smsSent) { sms++; perUser[u]++; }
                    boolean smsSeen = smsSent && rnd.nextDouble() < 0.97;                  // SMS delivered and read within 2 min
                    if (pushSeen || smsSeen) critSeen++;
                } else if (e.kind() == Kind.PROMO) {
                    if (policy >= 1 && optOut[u]) continue;
                    boolean quiet = e.minute() >= 22 * 60 || e.minute() < 8 * 60;
                    if (policy >= 1 && quiet) continue;                                    // held: in practice re-queued to 08:00 next day
                    if (policy >= 2 && promoSent >= 2) continue;
                    promoSent++; if (quiet) quietPromo++;
                    if (hasPush[u]) push++; else sms++;
                    perUser[u]++;
                } else {
                    if (policy >= 2) { if (digestSent) continue; digestSent = true; }       // one daily digest
                    boolean quiet = e.minute() >= 22 * 60 || e.minute() < 8 * 60;
                    if (policy >= 1 && quiet && policy < 2) continue;
                    if (hasPush[u]) { push++; perUser[u]++; }
                }
            }
            i = j;
        }
        long heavy = Arrays.stream(perUser).filter(x -> x > 10).count();
        System.out.printf("   %-46s %,10d %,9d %,10.0f %14.1f%% %,13d %12.1f%%%n", name, push, sms, sms * 0.15, 100.0 * critSeen / critTotal, quietPromo, 100.0 * heavy / users);
    }
}
