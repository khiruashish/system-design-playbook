// L2 — "Money debited, but the payment failed." In UPI the payer's bank debits, and the beneficiary bank's credit
// response can time out. The payer's bank must then decide without knowing whether the credit happened.
// One day, 2,000,000 payments; a beneficiary bank has three incidents where 30% of its responses time out.
// Timing, incident and settlement-cycle numbers are ILLUSTRATIVE; the T+1 auto-reversal rule and its compensation
// follow the RBI turnaround-time framework as commonly summarised (VERIFY the current circular before relying on it).
import java.util.*;

public class UpiTimeoutL2 {
    record Txn(double t, boolean credited, double statusFrom) {}  // time (h), did the credit really happen, when status becomes queryable (h)

    public static void main(String[] a) {
        Random r = new Random(1301); List<Txn> unknown = new ArrayList<>(); int total = 2_000_000;
        double[][] incidents = {{10.2, 0.6}, {14.0, 3.5}, {20.5, 0.3}};                  // start hour, duration hours
        for (int i = 0; i < total; i++) {
            double t = r.nextDouble() * 24;
            for (double[] in : incidents) if (t >= in[0] && t < in[0] + in[1] && r.nextDouble() < 0.30) {
                boolean credited = r.nextDouble() < 0.55;                                     // many timeouts happen after the credit posted
                double recover = in[0] + in[1] + r.nextDouble() * 0.5;                        // status API usable again after recovery
                unknown.add(new Txn(t, credited, Math.max(t, recover)));
            }
        }
        System.out.printf("%,d payments in a day; %,d timed out with the outcome unknown (%.2f%%)%n", total, unknown.size(), 100.0 * unknown.size() / total);
        System.out.printf("%,d of those were in fact credited to the beneficiary%n%n", unknown.stream().filter(Txn::credited).count());
        System.out.printf("%-46s %14s %16s %16s %14s %13s%n", "remitter bank's action on timeout", "paid twice", "payer short >1 h", "payer short >24 h", "status calls", "exceptions");
        run("R1 reverse the debit immediately", unknown, 1);
        run("R2 treat as success, fix at settlement", unknown, 2);
        run("R3 status checks with backoff, then decide", unknown, 3);
        run("R4 R3 + unresolved → reverse at settlement", unknown, 4);
        System.out.println("\npaid twice: payer refunded although the beneficiary was credited (bank must recover the money)");
        System.out.println("payer short: payer's money neither with the payer nor with the beneficiary for longer than the window");
        System.out.println("settlement: settlement/reconciliation files reveal every outcome at the next cycle (every 4 h here); T+1 batch = end of next day");
        System.out.println("status checks at +30 s, 2, 5, 15, 30 min, 1 h, 2 h; the beneficiary bank's status API is down during its incident");
    }

    static void run(String name, List<Txn> xs, int mode) {
        long twice = 0, short1h = 0, shortT1 = 0, calls = 0, ops = 0;
        for (Txn x : xs) {
            double settle = Math.ceil((x.t() + 0.01) / 4) * 4;                               // next settlement cycle
            double resolvedAt; boolean refunded = false;
            switch (mode) {
                case 1 -> { refunded = true; resolvedAt = x.t(); if (x.credited()) { twice++; ops++; } }
                case 2 -> { resolvedAt = x.credited() ? x.t() : 48;                             // not credited: reversed in the T+1 end-of-day batch
                            if (!x.credited()) ops++; }
                default -> {
                    // query at +30 s, +2 min, +5 min, +15 min, +30 min, +1 h, +2 h (7 calls max)
                    double[] at = {0.0083, 0.033, 0.083, 0.25, 0.5, 1, 2}; double known = -1;
                    for (double d : at) { calls++; if (x.t() + d >= x.statusFrom()) { known = x.t() + d; break; } }
                    if (known >= 0) resolvedAt = x.credited() ? x.t() : known;              // not credited → reverse now
                    else if (mode == 3) { resolvedAt = x.credited() ? x.t() : 48; ops++; }   // falls back to the T+1 batch
                    else { resolvedAt = x.credited() ? x.t() : settle; }                     // reverse at settlement, same day
                }
            }
            double shortFor = x.credited() && !refunded ? 0 : refunded ? 0 : resolvedAt - x.t();
            if (shortFor > 1) short1h++;
            if (shortFor > 24) shortT1++;
        }
        System.out.printf("%-46s %,14d %,16d %,16d %,14d %,13d%n", name, twice, short1h, shortT1, calls, ops);
    }
}
