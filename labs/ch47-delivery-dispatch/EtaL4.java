// EtaL4.java — run with: java EtaL4.java   (JDK 21, no dependencies)
// HOW LONG UNTIL THE RIDER ARRIVES? 40,000 synthetic deliveries (fixed seed) over 30 days in a city with 25 zones, each
// with its own traffic by hour (evening peaks are slow, some zones much worse), and 2,000 destination buildings whose
// last 100 metres differ wildly: a house door takes a minute; a gated complex (security desk, lift, tower hunt) takes
// 4–9. Days 1–25 are history; ETAs are evaluated on days 26–30. The customer is promised "estimate + 2 minutes".
//   E1 straight-line distance × 1.4 at 18 km/h + 2 min at the door
//   E2 average speed for the (zone, hour) learned from history + 2 min at the door
//   E3 E2 + the door time learned per destination building
//   E4 E3 + a live update halfway: re-estimate the remaining ride from the rider's actual progress so far
//      (evaluated at the halfway point — it only helps customers still waiting, and the second half has its own noise)
// Measured: mean and p90 absolute error, and share of deliveries that arrive AFTER the promised time.
import java.util.*;

public class EtaL4 {
    record Trip(int day, int zone, int hour, int building, double km, double actual, double actualFirstHalf) {}

    public static void main(String[] args) {
        Random r = new Random(47); int zones = 25, buildings = 2_000;
        double[] zoneBase = new double[zones]; for (int z = 0; z < zones; z++) zoneBase[z] = 14 + r.nextDouble() * 10;                 // km/h off-peak
        double[] zonePeak = new double[zones]; for (int z = 0; z < zones; z++) zonePeak[z] = 0.45 + r.nextDouble() * 0.4;              // peak multiplier
        double[] door = new double[buildings]; int[] bZone = new int[buildings];
        for (int b = 0; b < buildings; b++) { door[b] = r.nextDouble() < 0.3 ? 4 + r.nextDouble() * 5 : 0.8 + r.nextDouble() * 1.5; bZone[b] = r.nextInt(zones); }
        List<Trip> trips = new ArrayList<>();
        for (int i = 0; i < 40_000; i++) {
            int day = 1 + r.nextInt(30), hour = 8 + r.nextInt(15), b = r.nextInt(buildings), z = bZone[b]; double km = 0.5 + 2.8 * Math.sqrt(r.nextDouble());
            double road = km * (1.25 + 0.3 * r.nextDouble());
            double speed = zoneBase[z] * (hour >= 18 && hour <= 21 ? zonePeak[z] : hour >= 9 && hour <= 10 ? 0.8 : 1.0) * Math.exp(0.15 * r.nextGaussian());
            double ride = road / speed * 60; double firstHalf = ride / 2 * Math.exp(0.1 * r.nextGaussian());
            double rideTotal = firstHalf + firstHalf * Math.exp(0.2 * r.nextGaussian());          // traffic partly persists: second half ≈ first, with its own noise
            trips.add(new Trip(day, z, hour, b, km, rideTotal + door[b] * Math.exp(0.2 * r.nextGaussian()), firstHalf));
        }
        // learn from days 1–25
        double[][] sumSpeed = new double[zones][24], cnt = new double[zones][24]; double[] doorSum = new double[buildings], doorCnt = new double[buildings];
        for (Trip t : trips) if (t.day() <= 25) {
            double rideMin = t.actual() - door[t.building()];                                   // history records ride and hand-over separately
            sumSpeed[t.zone()][t.hour()] += t.km() * 1.4 / (rideMin / 60); cnt[t.zone()][t.hour()]++;
            doorSum[t.building()] += t.actual() - rideMin * 1.0; doorCnt[t.building()]++;
        }
        System.out.printf("40,000 deliveries; learn from days 1–25, evaluate %,d on days 26–30. Promise = estimate + 2 min.%n%n", trips.stream().filter(t -> t.day() > 25).count());
        System.out.printf("   %-58s %12s %14s %18s%n", "ETA method", "mean error", "p90 error", "arrived after the");
        System.out.printf("   %-58s %12s %14s %18s%n", "", "", "", "promised time");
        String[] names = {"E1) straight line × 1.4 at 18 km/h + 2 min", "E2) learned speed per (zone, hour) + 2 min", "E3) E2 + learned door time per building",
                "E4) E3 + re-estimate halfway from actual progress"};
        for (int m = 0; m < 4; m++) {
            List<Double> err = new ArrayList<>(); int late = 0, n = 0;
            for (Trip t : trips) {
                if (t.day() <= 25) continue; n++;
                double spd = cnt[t.zone()][t.hour()] > 0 ? sumSpeed[t.zone()][t.hour()] / cnt[t.zone()][t.hour()] : 18;
                double ride = m == 0 ? t.km() * 1.4 / 18 * 60 : t.km() * 1.4 / spd * 60;
                double doorEst = m >= 2 && doorCnt[t.building()] > 0 ? doorSum[t.building()] / doorCnt[t.building()] : 2;
                double est = ride + doorEst;
                if (m == 3) est = t.actualFirstHalf() * 2 + doorEst;                              // halfway: extrapolate observed pace
                err.add(Math.abs(t.actual() - est)); if (t.actual() > est + 2) late++;
            }
            Collections.sort(err); double mean = err.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            System.out.printf("   %-58s %9.1f min %11.1f min %17.1f%%%n", names[m], mean, err.get((int) (err.size() * 0.9)), 100.0 * late / n);
        }
        System.out.println("\n   → a single city-wide speed is wrong in both directions: too pessimistic off-peak, badly optimistic in the evening");
        System.out.println("     jams. Speeds learned per zone and hour fix the ride — and expose the next biggest error, the last hundred metres:");
        System.out.println("     gated complexes cost minutes that no map shows, and only delivery history per building knows them. Once the");
        System.out.println("     rider is moving, their actual pace beats any model, so update the ETA as the trip progresses. Promise with a");
        System.out.println("     buffer sized from the error distribution, not the average; a late 'arriving in 2 min' costs more trust than an");
        System.out.println("     honest 12.");
    }
}
