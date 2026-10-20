// EstimateL1.java — run with: java EstimateL1.java   (JDK 21, no dependencies)
// ESTIMATION IN THE INTERVIEW: IS MENTAL MATH GOOD ENOUGH? Eight classic prompts. For each, the exact arithmetic from
// stated assumptions vs two interview-style estimates (every input rounded COARSELY to 1, 5 or 10 × a power of ten, or
// to ONE SIGNIFICANT FIGURE; 1 day ≈ 10^5 s,
// 1 month ≈ 2.5 × 10^6 s, 1 year ≈ 3 × 10^7 s). Then the question that matters: does the rounding change a DESIGN
// DECISION? Each estimate is checked against decision thresholds (from Parts I–II; rules of thumb, VERIFY for your
// hardware): one PostgreSQL primary ≈ 5,000 writes/s comfortable; one Redis ≈ 100,000 ops/s; one server's NIC ≈
// 1 GB/s; a single machine's disk ≈ 10 TB; > 1,000 reads per write → cache / CDN.
import java.util.*;

public class EstimateL1 {
    record Prompt(String name, double dau, double actionsPerUser, double readWrite, double bytesPerWrite, double peak, double years) {}

    public static void main(String[] args) {
        List<Prompt> ps = List.of(
            new Prompt("URL shortener (writes = new links)", 1.2e8, 0.1, 100, 500, 3, 5),
            new Prompt("Grocery orders (this handbook)", 6e6, 0.25, 40, 4000, 20, 3),
            new Prompt("Chat messages", 5e7, 40, 1.5, 300, 4, 2),
            new Prompt("Rider GPS pings (writes)", 4e4, 21600, 0.2, 200, 1.5, 0.08),
            new Prompt("Product reviews", 2e6, 0.02, 800, 1500, 5, 5),
            new Prompt("Notification sends", 6e6, 6, 0.1, 800, 10, 0.25),
            new Prompt("Photo uploads (social app)", 3e8, 0.3, 50, 2.5e6, 3, 3),
            new Prompt("Ad click events", 1.5e8, 15, 0.05, 250, 5, 1));
        for (mode = 0; mode < 2; mode++) {
            System.out.println(mode == 0 ? "COARSE rounding (each input to 1, 5 or 10 × 10^k):" : "\nONE SIGNIFICANT FIGURE (each input to d × 10^k):");
            System.out.printf("   %-38s %-22s %-22s %-24s %s%n", "prompt", "peak writes/s", "peak reads/s", "storage (3 copies)", "decision from exact → from estimate");
            System.out.printf("   %-38s %-22s %-22s %-24s%n", "", "exact / estimate", "exact / estimate", "exact / estimate");
            int agree = 0; double worst = 1;
            for (Prompt p : ps) {
                double[] e = calc(p, false), r = calc(p, true);
                String de = decide(e), dr = decide(r); if (de.equals(dr)) agree++;
                for (int i = 0; i < 3; i++) worst = Math.max(worst, Math.max(e[i] / r[i], r[i] / e[i]));
                System.out.printf("   %-38s %-22s %-22s %-24s %s%n", p.name(), h(e[0]) + " / " + h(r[0]), h(e[1]) + " / " + h(r[1]), b(e[2]) + " / " + b(r[2]), de.equals(dr) ? "same: " + de : de + "  →  " + dr);
            }
            System.out.printf("   worst ratio estimate vs exact: %.1fx; design decisions unchanged in %d of %d prompts%n", worst, agree, ps.size());
        }
        System.out.println("\n   → interviewers don't grade arithmetic; they grade whether the numbers drive the design. But rounding error");
        System.out.println("     compounds: rounding five inputs to 1-or-5 multiplies small errors into a 7x miss and flips decisions for");
        System.out.println("     prompts near a threshold. Keeping ONE significant figure per input is still easy mental math and keeps the");
        System.out.println("     estimate close enough that the architecture it implies rarely changes. When a result lands near a threshold,");
        System.out.println("     say so out loud and design for the higher side.");
    }

    static int mode;
    static double r1(double x) { double p = Math.pow(10, Math.floor(Math.log10(x))); double m = x / p;
        return mode == 0 ? (m < 2.2 ? 1 : m < 7 ? 5 : 10) * p : Math.round(m) * p; }                // coarse: 1/5/10 × 10^k; fine: one significant figure
    static double[] calc(Prompt p, boolean round) {
        java.util.function.DoubleUnaryOperator R = x -> round ? r1(x) : x;
        double day = round ? 1e5 : 86400, year = round ? 3e7 : 365 * 86400.0;
        double writesPerDay = R.applyAsDouble(p.dau()) * R.applyAsDouble(p.actionsPerUser());
        double avgW = writesPerDay / day, peakW = avgW * R.applyAsDouble(p.peak());
        double peakR = peakW * R.applyAsDouble(p.readWrite());
        double storage = writesPerDay * R.applyAsDouble(p.bytesPerWrite()) * (p.years() * year / day) * 3;   // ×3 replication
        return new double[]{peakW, peakR, storage};
    }
    static String decide(double[] v) {
        List<String> d = new ArrayList<>();
        if (v[0] > 5000) d.add("shard/partition writes");
        if (v[1] > 100_000) d.add("cache tier");
        if (v[1] / Math.max(1, v[0]) > 1000) d.add("CDN/edge");
        if (v[2] > 10e12) d.add("distributed storage");
        if (v[0] > 50_000) d.add("stream ingest");
        return d.isEmpty() ? "single primary + replicas" : String.join(", ", d);
    }
    static String h(double x) { return x >= 1e6 ? String.format("%.1fM", x / 1e6) : x >= 1e3 ? String.format("%.1fk", x / 1e3) : String.format("%.0f", x); }
    static String b(double x) { return x >= 1e15 ? String.format("%.1f PB", x / 1e15) : x >= 1e12 ? String.format("%.1f TB", x / 1e12) : String.format("%.0f GB", x / 1e9); }
}
