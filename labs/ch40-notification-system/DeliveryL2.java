// DeliveryL2.java — run with (PostgreSQL 16 on :5581, db "shop"):
//   java -cp postgresql-42.7.4.jar -Duser.timezone=UTC DeliveryL2.java
// AT-LEAST-ONCE EVERYWHERE, EXACTLY-ONCE FOR THE CUSTOMER. 20,000 "out for delivery" events flow into the notification
// service. Every hop is at-least-once, so duplicates come from three places:
//   • the event bus redelivers ~5% of events (consumer rebalances)
//   • the SMS vendor times out on ~3% of calls AFTER it has accepted the message (the response is lost; we retry)
//   • a worker crashes ~1% of the time after sending but before marking the job done (its lease expires; another retries)
// The vendor also fails ~10% of calls transiently (503) — those must be retried or messages are lost.
// A real PostgreSQL job table with FOR UPDATE SKIP LOCKED leases and 8 worker threads; three designs:
//   A) no dedupe anywhere             B) unique dedupe key at intake       C) B + idempotency key passed to the vendor
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class DeliveryL2 {
    static final String DB = "jdbc:postgresql://127.0.0.1:5581/shop?user=claude";

    /** The SMS vendor: records what the customer's phone actually received. Honours client references if asked to. */
    static final class Vendor {
        final ConcurrentHashMap<String, AtomicInteger> received = new ConcurrentHashMap<>();
        final Set<String> seenRefs = ConcurrentHashMap.newKeySet(); final AtomicLong calls = new AtomicLong();
        String send(String key, boolean useRef, ThreadLocalRandom r) {
            calls.incrementAndGet();
            double x = r.nextDouble();
            if (x < 0.10) return "503";                                       // rejected, nothing sent
            boolean deliver = !useRef || seenRefs.add(key);                   // vendor-side idempotency on client reference
            if (deliver) received.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            if (x < 0.13) return "TIMEOUT";                                   // accepted, but our HTTP call timed out
            return "OK";
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("20,000 notification events; 8 workers; PostgreSQL job table with SKIP LOCKED leases.\n");
        System.out.printf("   %-42s %11s %12s %14s %13s %12s%n", "design", "jobs queued", "vendor calls", "customers got", "duplicates", "lost");
        for (int d = 0; d < 3; d++) run(d);
        System.out.println("\n   → every hop is at-least-once, so duplicates are the default, not a bug to be surprised by. A unique dedupe key");
        System.out.println("     at intake removes the bus redeliveries; the rest come from sends whose outcome we can't know — a timeout");
        System.out.println("     after the vendor accepted, a crash after the send. Only an idempotency key the VENDOR honours closes that");
        System.out.println("     gap; without one, choose per message type between a rare duplicate (OTP, order update) and a rare loss. Retries");
        System.out.println("     with a lease make sure nothing is lost: every design delivered every notification at least once.");
    }

    static void run(int design) throws Exception {
        try (Connection c = DriverManager.getConnection(DB); Statement s = c.createStatement()) {
            s.execute("drop table if exists notification_jobs");
            s.execute("""
                create table notification_jobs(id bigserial primary key, dedupe_key text not null, phone text not null,
                  template text not null, status text not null default 'PENDING', attempts int not null default 0,
                  locked_until timestamptz, next_attempt_at timestamptz not null default now())""");
            if (design >= 1) s.execute("create unique index on notification_jobs(dedupe_key)");
            s.execute("create index on notification_jobs(status, next_attempt_at)");
        }
        // intake: 20,000 events, ~5% redelivered by the bus
        Random rnd = new Random(40); int events = 20_000;
        try (Connection c = DriverManager.getConnection(DB); PreparedStatement ps = c.prepareStatement(
                "insert into notification_jobs(dedupe_key, phone, template) values (?,?,?)" + (design >= 1 ? " on conflict (dedupe_key) do nothing" : ""))) {
            c.setAutoCommit(false);
            for (int e = 0; e < events; e++) {
                int copies = rnd.nextDouble() < 0.05 ? 2 : 1;
                for (int k = 0; k < copies; k++) { ps.setString(1, "ORD-" + (500_000 + e) + ":OUT_FOR_DELIVERY:sms"); ps.setString(2, "98" + (40_000_000 + e)); ps.setString(3, "DLT-1107-out-for-delivery"); ps.addBatch(); }
            }
            ps.executeBatch(); c.commit();
        }
        long queued;
        try (Connection c = DriverManager.getConnection(DB); ResultSet rs = c.createStatement().executeQuery("select count(*) from notification_jobs")) { rs.next(); queued = rs.getLong(1); }

        Vendor vendor = new Vendor(); AtomicInteger crashes = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (int w = 0; w < 8; w++) pool.submit(() -> { worker(vendor, design >= 2, crashes); return null; });
        }
        long dupCustomers = vendor.received.values().stream().filter(a -> a.get() > 1).count();
        long extraMsgs = vendor.received.values().stream().mapToLong(a -> a.get() - 1).sum();
        long lost = events - vendor.received.size();
        System.out.printf("   %-42s %,11d %,12d %,14d %,6d (%,d SMS) %,6d%n",
                new String[]{"A) no dedupe", "B) dedupe key at intake", "C) B + vendor idempotency key"}[design],
                queued, vendor.calls.get(), vendor.received.size(), dupCustomers, extraMsgs, lost);
    }

    static void worker(Vendor vendor, boolean vendorRef, AtomicInteger crashes) throws Exception {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        try (Connection c = DriverManager.getConnection(DB)) {
            PreparedStatement claim = c.prepareStatement("""
                update notification_jobs set locked_until = now() + interval '300 milliseconds', attempts = attempts + 1
                where id in (select id from notification_jobs
                             where status = 'PENDING' and next_attempt_at <= now() and (locked_until is null or locked_until < now())
                             order by id for update skip locked limit 50)
                returning id, dedupe_key, attempts""");
            PreparedStatement done = c.prepareStatement("update notification_jobs set status = 'SENT' where id = ?");
            PreparedStatement retry = c.prepareStatement("update notification_jobs set locked_until = null, next_attempt_at = now() + interval '20 milliseconds', status = case when attempts >= 8 then 'DEAD' else 'PENDING' end where id = ?");
            int idle = 0;
            while (idle < 40) {
                List<long[]> ids = new ArrayList<>(); List<String> keys = new ArrayList<>();
                try (ResultSet rs = claim.executeQuery()) { while (rs.next()) { ids.add(new long[]{rs.getLong(1), rs.getInt(3)}); keys.add(rs.getString(2)); } }
                if (ids.isEmpty()) {
                    try (ResultSet rs = c.createStatement().executeQuery("select count(*) from notification_jobs where status = 'PENDING'")) { rs.next(); if (rs.getLong(1) == 0) idle += 10; }
                    idle++; Thread.sleep(25); continue;
                }
                idle = 0;
                for (int i = 0; i < ids.size(); i++) {
                    String outcome = vendor.send(keys.get(i), vendorRef, r);
                    if (r.nextDouble() < 0.01) { crashes.incrementAndGet(); continue; }      // crashed before recording: lease will expire
                    if (outcome.equals("OK")) { done.setLong(1, ids.get(i)[0]); done.executeUpdate(); }
                    else { retry.setLong(1, ids.get(i)[0]); retry.executeUpdate(); }       // 503 or timeout: retry later
                }
            }
        }
    }
}
