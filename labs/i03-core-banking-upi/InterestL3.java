// L3 — Savings interest to the paisa. 1,000,000 accounts, 3.0% p.a. on the end-of-day balance, credited at quarter end
// (Jan–Mar of a leap year: 91 days). Compare rounding policies against exact arithmetic (BigDecimal, 20 digits).
// Rates, balances and the day-count convention are ILLUSTRATIVE; the bank's product terms and regulator decide (VERIFY).
import java.math.*;
import java.util.*;

public class InterestL3 {
    static final BigDecimal RATE = new BigDecimal("0.03"), PAISE = new BigDecimal("0.01");
    static final MathContext MC = new MathContext(20);

    public static void main(String[] a) {
        int accounts = 1_000_000, days = 91; Random r = new Random(1302);
        String[] names = {"exact (20 digits), round once at credit", "round each day's interest HALF_UP, sum", "truncate each day's interest (DOWN), sum",
                          "round each day HALF_EVEN, sum", "round once, but divide by 365 in a leap year"};
        BigDecimal[] total = new BigDecimal[5]; Arrays.fill(total, BigDecimal.ZERO);
        long[] differs = new long[5]; BigDecimal[] maxDiff = new BigDecimal[5]; Arrays.fill(maxDiff, BigDecimal.ZERO);
        long zeroInterest = 0;
        for (int i = 0; i < accounts; i++) {
            // balances: many small (₹50–₹5,000), some large; a few deposits/withdrawals during the quarter
            double u = r.nextDouble(); long bal = (long) ((u < 0.5 ? 5_000 + r.nextInt(495_000) : u < 0.9 ? 500_000 + r.nextInt(9_500_000) : 10_000_000 + r.nextInt(190_000_000)));
            BigDecimal exact = BigDecimal.ZERO, halfUp = BigDecimal.ZERO, down = BigDecimal.ZERO, even = BigDecimal.ZERO, wrongDays = BigDecimal.ZERO;
            for (int d = 0; d < days; d++) {
                if (r.nextDouble() < 0.03) bal = Math.max(0, bal + (r.nextBoolean() ? 1 : -1) * r.nextInt(200_000));
                BigDecimal b = BigDecimal.valueOf(bal, 2);                                   // rupees with 2 decimals
                BigDecimal day = b.multiply(RATE, MC).divide(BigDecimal.valueOf(366), MC);   // leap year: actual/actual → 366
                exact = exact.add(day, MC);
                halfUp = halfUp.add(day.setScale(2, RoundingMode.HALF_UP));
                down = down.add(day.setScale(2, RoundingMode.DOWN));
                even = even.add(day.setScale(2, RoundingMode.HALF_EVEN));
                wrongDays = wrongDays.add(b.multiply(RATE, MC).divide(BigDecimal.valueOf(365), MC), MC);
            }
            BigDecimal credit = exact.setScale(2, RoundingMode.HALF_UP);
            if (credit.signum() == 0) zeroInterest++;
            BigDecimal[] got = {credit, halfUp, down, even, wrongDays.setScale(2, RoundingMode.HALF_UP)};
            for (int k = 0; k < 5; k++) {
                total[k] = total[k].add(got[k]);
                BigDecimal diff = got[k].subtract(credit).abs();
                if (diff.signum() != 0) differs[k]++;
                if (diff.compareTo(maxDiff[k]) > 0) maxDiff[k] = diff;
            }
        }
        System.out.printf("%,d savings accounts, 3.0%% p.a., %d days (leap-year quarter), interest credited once at quarter end%n%n", accounts, days);
        System.out.printf("%-48s %18s %16s %16s %14s%n", "policy", "total interest ₹", "vs exact ₹", "accounts differ", "max per acct");
        for (int k = 0; k < 5; k++)
            System.out.printf("%-48s %,18.2f %+,16.2f %,16d %13s%n", names[k], total[k], total[k].subtract(total[0]), differs[k], "₹" + maxDiff[k].toPlainString());
        System.out.printf("%naccounts earning ₹0.00 this quarter: %,d%n", zeroInterest);
    }
}
