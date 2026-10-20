// ReconcileL3.java — run with: java ReconcileL3.java   (JDK 21, no dependencies)
// DAILY SETTLEMENT RECONCILIATION. Every morning the payment gateway sends a settlement file: every captured payment and
// refund it settled into our bank account, with its fee (and GST on the fee). Our ledger has its own view of the same
// day. 100,000 payments and 3,000 refunds (fixed seed), with the discrepancies that really occur — injected:
//   REAL PROBLEMS   40 captured at the PSP but missing from our ledger (dropped webhook, never reconciled)
//                   25 amount mismatches (we recorded the price before a coupon adjustment)
//                    5 payments the PSP settled twice
//                   12 refunds issued by hand in the PSP dashboard, unknown to our ledger
//                  150 fees charged at the card rate on UPI payments (UPI is charged 0% here)
//   NOISE          300 payments settled a day later (T+2, appear in tomorrow's file)
//                  200 one-paisa rounding differences
// Three ways to reconcile:
//   R1 totals only: compare the day's expected net with the amount deposited
//   R2 line-by-line exact match on (payment id, amount); everything unmatched is a break for a human
//   R3 R2 + rules: carry unmatched items forward 3 days before calling them breaks, auto-accept ±1 paisa, recompute the
//      expected fee per payment method, detect duplicates — and classify each break with its money impact
import java.util.*;

public class ReconcileL3 {
    record Pay(String id, long amount, String method, boolean refund) {}
    record Row(String id, long gross, long fee, boolean refund) {}

    public static void main(String[] args) {
        Random r = new Random(45); List<Pay> ledger = new ArrayList<>(); List<Row> fileD = new ArrayList<>(), fileD1 = new ArrayList<>();
        Map<String, String> truth = new HashMap<>();                                      // id -> injected issue
        for (int i = 0; i < 100_000; i++) ledger.add(new Pay("pay_" + i, 10_000 + r.nextInt(300_000), r.nextDouble() < 0.7 ? "upi" : "card", false));
        for (int i = 0; i < 3_000; i++) ledger.add(new Pay("rfnd_" + i, -(1_000 + r.nextInt(50_000)), "upi", true));
        List<Pay> shuffled = new ArrayList<>(ledger.subList(0, 100_000)); Collections.shuffle(shuffled, r); int k = 0;
        Set<String> late = new HashSet<>(), dup = new HashSet<>(), rounding = new HashSet<>(), feeWrong = new HashSet<>(), amountWrong = new HashSet<>();
        for (int i = 0; i < 300; i++) late.add(shuffled.get(k++).id());
        for (int i = 0; i < 5; i++) dup.add(shuffled.get(k++).id());
        for (int i = 0; i < 200; i++) rounding.add(shuffled.get(k++).id());
        for (int i = 0; i < 25; i++) amountWrong.add(shuffled.get(k++).id());
        while (feeWrong.size() < 150) { Pay p = shuffled.get(k++); if (p.method().equals("upi")) feeWrong.add(p.id()); }
        for (Pay p : ledger) {
            long gross = p.amount();
            if (rounding.contains(p.id())) gross += 1; if (amountWrong.contains(p.id())) gross -= 5_000 + r.nextInt(20_000);
            long fee = p.refund() ? 0 : expectedFee(p.method(), gross); if (feeWrong.contains(p.id())) fee = expectedFee("card", gross);
            Row row = new Row(p.id(), gross, fee, p.refund());
            if (late.contains(p.id())) fileD1.add(row); else fileD.add(row);
            if (dup.contains(p.id())) fileD.add(row);
        }
        for (int i = 0; i < 40; i++) { long g = 10_000 + r.nextInt(300_000); fileD.add(new Row("pay_x" + i, g, expectedFee("upi", g), false)); truth.put("pay_x" + i, "missing in ledger"); }
        for (int i = 0; i < 12; i++) fileD.add(new Row("rfnd_manual_" + i, -(2_000 + r.nextInt(40_000)), 0, true));
        amountWrong.forEach(id -> truth.put(id, "amount")); dup.forEach(id -> truth.put(id, "duplicate")); feeWrong.forEach(id -> truth.put(id, "fee"));
        for (int i = 0; i < 12; i++) truth.put("rfnd_manual_" + i, "unknown refund");
        for (int i = 0; i < 200; i++) fileD1.add(new Row("pay_next_" + i, 50_000, expectedFee("upi", 50_000), false));   // tomorrow's own payments
        int realIssues = truth.size();

        System.out.printf("Day D: ledger %,d payments + %,d refunds; settlement file %,d rows (+ %,d in the D+1 file).%n", 100_000, 3_000, fileD.size(), fileD1.size());
        System.out.printf("Injected: %d real problems, 500 noise items (300 settled late, 200 one-paisa rounding).%n%n", realIssues);
        System.out.printf("   %-54s %14s %13s %13s %22s%n", "method", "breaks for", "real problems", "false alarms", "money impact explained");
        System.out.printf("   %-54s %14s %13s %13s %22s%n", "", "humans", "found", "", "");

        // R1: totals only
        long expectedNet = 0; for (Pay p : ledger) expectedNet += p.amount() - (p.refund() ? 0 : expectedFee(p.method(), p.amount()));
        long depositedNet = 0; for (Row x : fileD) depositedNet += x.gross() - x.fee();
        System.out.printf("   %-54s %14s %13s %13s %22s%n", "R1) totals only", "1 (the total)", "0 identified", "—", String.format("₹%,.2f unexplained", (depositedNet - expectedNet) / 100.0));

        // R2 and R3: line matching
        for (int m = 2; m <= 3; m++) {
            Map<String, Pay> ours = new HashMap<>(); for (Pay p : ledger) ours.put(p.id(), p);
            Map<String, Integer> seen = new HashMap<>(); List<String[]> breaks = new ArrayList<>();       // [id, category, impact paise]
            for (Row x : fileD) {
                int n = seen.merge(x.id(), 1, Integer::sum); Pay p = ours.get(x.id());
                if (p == null) { breaks.add(new String[]{x.id(), x.refund() ? "unknown refund" : "missing in ledger", String.valueOf(x.gross())}); continue; }
                if (n > 1) { breaks.add(new String[]{x.id(), "duplicate", String.valueOf(x.gross() - x.fee())}); continue; }
                long diff = x.gross() - p.amount();
                if (diff != 0) { if (m == 3 && Math.abs(diff) <= 1) continue; breaks.add(new String[]{x.id(), Math.abs(diff) <= 1 ? "rounding" : "amount", String.valueOf(diff)}); continue; }
                if (m == 3 && !p.refund() && x.fee() != expectedFee(p.method(), x.gross())) breaks.add(new String[]{x.id(), "fee", String.valueOf(x.fee() - expectedFee(p.method(), x.gross()))});
            }
            Set<String> inFile = seen.keySet();
            Set<String> inD1 = new HashSet<>(); for (Row x : fileD1) inD1.add(x.id());
            for (Pay p : ledger) if (!inFile.contains(p.id())) {
                if (m == 3 && inD1.contains(p.id())) continue;                                   // carried forward, matched in D+1
                breaks.add(new String[]{p.id(), "not settled", String.valueOf(p.amount())});
            }
            long found = breaks.stream().filter(b -> truth.containsKey(b[0])).map(b -> b[0]).distinct().count();
            long falseAlarms = breaks.stream().filter(b -> !truth.containsKey(b[0])).count();
            long impact = breaks.stream().filter(b -> truth.containsKey(b[0])).mapToLong(b -> Math.abs(Long.parseLong(b[2]))).sum();
            System.out.printf("   %-54s %,14d %,13d %,13d %22s%n", m == 2 ? "R2) exact line match (id, amount)" : "R3) + T+3 carry-forward, ±1 paisa, fee rules, dedupe",
                    breaks.size(), found, falseAlarms, String.format("₹%,.2f", impact / 100.0));
            if (m == 3) {
                Map<String, long[]> byCat = new TreeMap<>(); for (String[] b : breaks) { long[] a = byCat.computeIfAbsent(b[1], x -> new long[2]); a[0]++; a[1] += Math.abs(Long.parseLong(b[2])); }
                System.out.println("\n   R3 exception queue by category:");
                byCat.forEach((cat, a) -> System.out.printf("        %-20s %4d   ₹%,12.2f%n", cat, a[0], a[1] / 100.0));
            }
        }
        System.out.println("\n   → a matching total is not proof of anything, and a mismatched total tells you nothing about where the money went.");
        System.out.println("     Line-by-line matching finds the problems but buries them under hundreds of timing and rounding differences that");
        System.out.println("     fix themselves — people stop reading the report. Rules for known benign patterns (settlement delays, rounding)");
        System.out.println("     and for known costs (the fee each method should carry) leave a short queue of real exceptions, each with a");
        System.out.println("     category and a rupee value: customers charged without an order, duplicate settlements to return, refunds");
        System.out.println("     nobody recorded, and a gateway overcharging fees. Reconciliation is how a payment system finds its own bugs.");
    }

    static long expectedFee(String method, long gross) { if (method.equals("upi")) return 0; long fee = Math.round(gross * 0.02); return fee + Math.round(fee * 0.18); }   // 2% + 18% GST (illustrative)
}
