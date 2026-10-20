// L4 — Mule accounts. Fraud proceeds are sent by many victims to "mule" accounts, which forward them within minutes
// to a few collector accounts. Legitimate look-alikes exist: small shops (many payers), people who pay rent right after
// salary (pass-through), friends collecting group money and paying a vendor. One simulated day of transfers; compare
// detection rules by precision and recall. Behaviours and thresholds are ILLUSTRATIVE (VERIFY with labelled data).
import java.util.*;

public class MuleL4 {
    record Tx(int from, int to, long paise, double minute) {}

    public static void main(String[] a) {
        Random r = new Random(1303); List<Tx> txs = new ArrayList<>(); int N = 200_000;
        Set<Integer> mules = new HashSet<>(), collectors = new HashSet<>();
        // ordinary P2P and P2M traffic
        int shops = 3_000;
        for (int i = 0; i < 600_000; i++) {
            int from = shops + r.nextInt(N - shops);
            int to = r.nextDouble() < 0.6 ? r.nextInt(shops) : shops + r.nextInt(N - shops);
            txs.add(new Tx(from, to, 2_000 + r.nextInt(300_000), r.nextDouble() * 1440));
        }
        // 2,000 salary-then-rent accounts: one big credit, 80–95% paid out within an hour to one landlord
        for (int i = 0; i < 2_000; i++) { int p = shops + r.nextInt(N - shops), land = shops + r.nextInt(N - shops); double t = r.nextDouble() * 1300;
            long sal = 3_000_000 + r.nextInt(7_000_000); txs.add(new Tx(-1, p, sal, t)); txs.add(new Tx(p, land, (long) (sal * (0.8 + r.nextDouble() * 0.15)), t + 5 + r.nextDouble() * 50)); }
        // 800 group collectors: 8–30 friends send small amounts, collector pays one vendor within the hour
        for (int i = 0; i < 800; i++) { int c = shops + r.nextInt(N - shops), vendor = r.nextInt(shops); double t = r.nextDouble() * 1300; long sum = 0; int k = 8 + r.nextInt(23);
            for (int j = 0; j < k; j++) { long amt = 20_000 + r.nextInt(80_000); sum += amt; txs.add(new Tx(shops + r.nextInt(N - shops), c, amt, t + r.nextDouble() * 40)); }
            txs.add(new Tx(c, vendor, sum, t + 45 + r.nextDouble() * 15)); }
        // 8 fraud rings: 3 collectors each, 10 mules each; each mule receives from 15–60 victims, forwards 90–99% within
        // 2–25 minutes to 1–2 of its ring's collectors
        for (int g = 0; g < 8; g++) {
            int[] col = {shops + r.nextInt(N - shops), shops + r.nextInt(N - shops), shops + r.nextInt(N - shops)}; for (int c : col) collectors.add(c);
            for (int m = 0; m < 10; m++) {
                int mule = shops + r.nextInt(N - shops); mules.add(mule); int k = 15 + r.nextInt(46); double t0 = r.nextDouble() * 1300; long got = 0;
                for (int j = 0; j < k; j++) { long amt = 50_000 + r.nextInt(450_000); got += amt; txs.add(new Tx(shops + r.nextInt(N - shops), mule, amt, t0 + r.nextDouble() * 90)); }
                long fwd = (long) (got * (0.90 + r.nextDouble() * 0.09));
                int n = 1 + r.nextInt(2); for (int j = 0; j < n; j++) txs.add(new Tx(mule, col[r.nextInt(3)], fwd / n, t0 + 90 + 2 + r.nextDouble() * 23));
            }
        }
        txs.sort(Comparator.comparingDouble(Tx::minute));
        Map<Integer, List<Tx>> in = new HashMap<>(), out = new HashMap<>();
        for (Tx t : txs) { if (t.from() >= 0) out.computeIfAbsent(t.from(), k -> new ArrayList<>()).add(t); in.computeIfAbsent(t.to(), k -> new ArrayList<>()).add(t); }
        Set<Integer> bad = new HashSet<>(mules); bad.addAll(collectors);
        System.out.printf("%,d transfers among %,d accounts; hidden: %d mules and %d collectors in 8 rings%n", txs.size(), N, mules.size(), collectors.size());
        System.out.printf("look-alikes: %,d shops, 2,000 salary-then-rent payers, 800 group collectors%n%n", shops);
        System.out.printf("%-58s %9s %11s %9s %8s%n", "rule", "flagged", "true bad", "precision", "recall");
        Set<Integer> all = new HashSet<>(in.keySet()); all.addAll(out.keySet());
        Set<Integer> d1 = new HashSet<>(), d2 = new HashSet<>(), d3 = new HashSet<>();
        for (int acc : all) {
            List<Tx> ins = in.getOrDefault(acc, List.of());
            List<Tx> p2p = out.getOrDefault(acc, List.of()).stream().filter(t -> t.to() >= shops).toList();   // payments to registered merchants excluded
            // fan-in: most distinct senders within any 2-hour window
            int fanIn = 0; for (int i = 0, j = 0; i < ins.size(); i++) { while (ins.get(i).minute() - ins.get(j).minute() > 120) j++;
                Set<Integer> snd = new HashSet<>(); for (int k = j; k <= i; k++) snd.add(ins.get(k).from()); fanIn = Math.max(fanIn, snd.size()); }
            // pass-through: for each outgoing P2P transfer, share of the last 2 hours' inflow that has left by then
            double pass = 0; long winIn = 0; long receivers = 0;
            for (Tx o : p2p) { long inW = ins.stream().filter(t -> t.minute() <= o.minute() && o.minute() - t.minute() <= 120).mapToLong(Tx::paise).sum();
                long outW = p2p.stream().filter(t -> t.minute() <= o.minute() && o.minute() - t.minute() <= 120).mapToLong(Tx::paise).sum();
                if (inW > 0 && (double) outW / inW > pass) { pass = (double) outW / inW; winIn = inW;
                    receivers = p2p.stream().filter(t -> t.minute() <= o.minute() && o.minute() - t.minute() <= 120).mapToInt(Tx::to).distinct().count(); } }
            if (fanIn >= 15) d1.add(acc);
            if (pass >= 0.8) d2.add(acc);
            if (fanIn >= 15 && pass >= 0.85 && receivers <= 2 && winIn >= 1_500_000) d3.add(acc);
        }
        // D4: 2-hop — accounts receiving from ≥ 2 D3-flagged accounts are likely collectors; add them
        Map<Integer, Set<Integer>> fedBy = new HashMap<>();
        for (int m : d3) for (Tx t : out.getOrDefault(m, List.of())) fedBy.computeIfAbsent(t.to(), k -> new HashSet<>()).add(m);
        Set<Integer> d4 = new HashSet<>(d3); fedBy.forEach((acc, src) -> { if (src.size() >= 2) d4.add(acc); });
        report("D1 fan-in: ≥ 15 distinct senders within 2 h", d1, bad);
        report("D2 pass-through: ≥ 80% of 2-h inflow sent on (P2P)", d2, bad);
        report("D3 fan-in + pass ≥ 85% to ≤ 2 receivers + ≥ ₹15k (same 2 h)", d3, bad);
        report("D4 D3 + accounts fed by ≥ 2 D3 accounts (collectors)", d4, bad);
        long d3mule = d3.stream().filter(mules::contains).count(), d4col = d4.stream().filter(collectors::contains).count();
        System.out.printf("%nD3 finds %d of %d mules and %d collectors; D4 adds %d of %d collectors%n", d3mule, mules.size(), d3.stream().filter(collectors::contains).count(), d4col, collectors.size());
        System.out.printf("false positives: D3 %d, D4 %d%n", d3.stream().filter(x -> !bad.contains(x)).count(), d4.stream().filter(x -> !bad.contains(x)).count());
    }
    static void report(String name, Set<Integer> flagged, Set<Integer> bad) {
        long tp = flagged.stream().filter(bad::contains).count();
        System.out.printf("%-58s %,9d %11d %8.1f%% %7.1f%%%n", name, flagged.size(), tp, flagged.isEmpty() ? 0 : 100.0 * tp / flagged.size(), 100.0 * tp / bad.size());
    }
}
