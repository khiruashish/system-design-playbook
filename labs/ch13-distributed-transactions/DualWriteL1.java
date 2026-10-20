// DualWriteL1.java — run with: java DualWriteL1.java
// A service must (1) commit an order to its database and (2) publish "order placed" to a broker.
// There is no transaction spanning both. Crash at a random point and count what goes wrong.
import java.util.*;

public class DualWriteL1 {

    enum Strategy { DB_THEN_PUBLISH, PUBLISH_THEN_DB, OUTBOX }

    public static void main(String[] args) {
        int n = 100_000; Random rnd = new Random(42);
        System.out.printf("%,d orders; the process crashes at a random point in 2 %% of them; the broker is down 1 %% of the time; the relay crashes after publishing 1 %% of the time%n", n);
        System.out.printf("   %-17s %14s %16s %12s%n", "strategy", "lost events", "phantom events", "duplicates");
        for (Strategy s : Strategy.values()) {
            int lost = 0, phantom = 0, dup = 0;
            for (int i = 0; i < n; i++) {
                boolean crash = rnd.nextDouble() < 0.02;
                int crashPoint = rnd.nextInt(3);                          // 0 = before step 1, 1 = between, 2 = after both
                boolean brokerDown = rnd.nextDouble() < 0.01;
                boolean dbCommitted = false, published = false;
                switch (s) {
                    case DB_THEN_PUBLISH -> {
                        if (crash && crashPoint == 0) break;
                        dbCommitted = true;
                        if (crash && crashPoint == 1) break;                 // committed, never published
                        if (brokerDown) break;                              // publish failed after commit
                        published = true;
                    }
                    case PUBLISH_THEN_DB -> {
                        if (crash && crashPoint == 0) break;
                        if (brokerDown) break;                              // nothing happened: caller retries, fine
                        published = true;
                        if (crash && crashPoint == 1) break;                 // event out, order never saved
                        dbCommitted = rnd.nextDouble() >= 0.005;            // 0.5 % constraint failures / rollbacks
                    }
                    case OUTBOX -> {
                        if (crash && crashPoint == 0) break;
                        dbCommitted = true;                                 // order + outbox row: ONE local transaction
                        // the relay publishes later; if it crashes after publishing but before marking, it republishes
                        published = true;
                        if (rnd.nextDouble() < 0.01) dup++;
                    }
                }
                if (dbCommitted && !published) lost++;
                if (published && !dbCommitted) phantom++;
            }
            System.out.printf("   %-17s %14d %16d %12d%n", s, lost, phantom, dup);
        }
        System.out.println("   → the outbox trades lost/phantom events for duplicates, which consumers can dedup (L2 inbox)");
    }
}
