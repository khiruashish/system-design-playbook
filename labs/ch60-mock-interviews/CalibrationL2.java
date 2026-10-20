// L2 — Calibration: how interviewer bias and noise turn into wrong hire decisions, and what calibration and more interviewers buy.
// Model (assumptions, VERIFY against your own loop data): candidate skill ~ N(0,1); hire bar = skill > 0.5 (~31% should pass).
// Interviewer i reports skill + bias_i + noise. Uncalibrated: bias sd 0.5, noise sd 0.6. Calibrated (anchored rubric,
// shadowing, score review): bias sd 0.15, noise sd 0.45. Decision: mean of the k interviewers' scores > 0.5.
import java.util.*;

public class CalibrationL2 {
    public static void main(String[] a) {
        long seed = 60;
        int candidates = 200_000, panel = 12;
        System.out.println("200,000 simulated candidates; hire bar: true skill > 0.5 (30.9% should be hired)\n");
        System.out.printf("%-14s %3s %11s %12s %12s %10s %14s%n", "interviewers", "k", "accuracy", "false hire", "false reject", "hire rate", "2 agree (k=1)");
        for (String mode : new String[]{"uncalibrated", "calibrated"}) {
            double biasSd = mode.equals("uncalibrated") ? 0.5 : 0.15, noiseSd = mode.equals("uncalibrated") ? 0.6 : 0.45;
            for (int k : new int[]{1, 2, 3, 4}) {
                Random r = new Random(seed);
                double[] bias = new double[panel]; for (int i = 0; i < panel; i++) bias[i] = r.nextGaussian() * biasSd;
                if (k == 1) System.out.printf("   (%s panel of %d: mean bias %+.2f, most lenient %+.2f, harshest %+.2f)%n", mode, panel,
                        Arrays.stream(bias).average().orElse(0), Arrays.stream(bias).max().orElse(0), Arrays.stream(bias).min().orElse(0));
                int correct = 0, fh = 0, fr = 0, hires = 0, agree = 0;
                for (int c = 0; c < candidates; c++) {
                    double skill = r.nextGaussian(); boolean should = skill > 0.5;
                    int[] who = pick(r, panel, Math.max(k, 2));
                    double sum = 0; for (int j = 0; j < k; j++) sum += skill + bias[who[j]] + r.nextGaussian() * noiseSd;
                    boolean hire = sum / k > 0.5;
                    if (hire == should) correct++; else if (hire) fh++; else fr++;
                    if (hire) hires++;
                    // would two single interviewers, seen independently, reach the same decision?
                    boolean d1 = skill + bias[who[0]] + r.nextGaussian() * noiseSd > 0.5, d2 = skill + bias[who[1]] + r.nextGaussian() * noiseSd > 0.5;
                    if (d1 == d2) agree++;
                }
                System.out.printf("%-14s %3d %10.1f%% %11.1f%% %11.1f%% %9.1f%% %13.1f%%%n", mode, k, 100.0 * correct / candidates,
                        100.0 * fh / candidates, 100.0 * fr / candidates, 100.0 * hires / candidates, 100.0 * agree / candidates);
            }
        }
        // mock-interview angle: a candidate right at the bar (skill 0.7) vs just below (0.3) — how often a single mock says "hire"?
        System.out.println("\nOne mock, one interviewer — how often the verdict is \"hire\" for a candidate with true skill s:");
        System.out.printf("%-14s %8s %8s %8s %8s %8s%n", "interviewers", "s=0.0", "s=0.3", "s=0.7", "s=1.0", "s=1.5");
        for (String mode : new String[]{"uncalibrated", "calibrated"}) {
            double biasSd = mode.equals("uncalibrated") ? 0.5 : 0.15, noiseSd = mode.equals("uncalibrated") ? 0.6 : 0.45;
            Random r = new Random(seed + 1);
            StringBuilder row = new StringBuilder(String.format("%-14s", mode));
            for (double s : new double[]{0.0, 0.3, 0.7, 1.0, 1.5}) {
                int yes = 0, n = 100_000;
                for (int i = 0; i < n; i++) if (s + r.nextGaussian() * biasSd + r.nextGaussian() * noiseSd > 0.5) yes++;
                row.append(String.format(" %7.0f%%", 100.0 * yes / n));
            }
            System.out.println(row);
        }
    }
    static int[] pick(Random r, int n, int k) { List<Integer> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(i); Collections.shuffle(l, r); int[] x = new int[k]; for (int i = 0; i < k; i++) x[i] = l.get(i); return x; }
}
