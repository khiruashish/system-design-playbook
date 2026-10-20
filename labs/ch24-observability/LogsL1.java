// LogsL1.java — run with: java LogsL1.java      (JDK 17+, no dependencies)
// Structured logging and correlation IDs. Simulate 5,000 requests flowing through 5 services (gateway → order → pricing →
// inventory → payment), each emitting log lines. 3 % of requests fail somewhere. Compare:
//   A. unstructured text logs vs structured JSON — can you actually query them?
//   B. no correlation id vs a propagated trace id — can you reconstruct one request's journey across services?
//   C. log levels and sampling — volume vs. keeping the signal (always keep errors)
//   D. label/field cardinality — the metric-and-log killer.
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

public class LogsL1 {
    record Event(String traceId, String service, long tsMillis, String level, String msg, Map<String, String> fields) {}
    static final String[] SERVICES = {"gateway", "order", "pricing", "inventory", "payment"};
    static final Random R = new Random(24);

    static String traceId() { return String.format("%016x", R.nextLong() & 0x7fffffffffffffffL); }

    /** One request: a chain of spans across services; may fail at a random hop (3%). Returns the events it emitted. */
    static List<Event> request(long t0, int userId, String sku) {
        String trace = traceId();
        List<Event> ev = new ArrayList<>();
        int failAt = R.nextDouble() < 0.03 ? 1 + R.nextInt(SERVICES.length - 1) : -1;
        long t = t0;
        for (int i = 0; i < SERVICES.length; i++) {
            String svc = SERVICES[i];
            var f = new LinkedHashMap<String, String>();
            f.put("trace_id", trace); f.put("user_id", "u" + userId); f.put("sku", sku); f.put("http_method", "POST");
            t += 2 + R.nextInt(30);
            if (i == failAt) {
                f.put("error", "timeout calling " + (i + 1 < SERVICES.length ? SERVICES[i + 1] : "db"));
                ev.add(new Event(trace, svc, t, "ERROR", "request failed", f));
                break;
            }
            ev.add(new Event(trace, svc, t, "INFO", svc + " handled", f));
        }
        return ev;
    }

    // ---- log formats ----
    static String unstructured(Event e) {   // the "just print a sentence" style
        String base = new Date(e.tsMillis()) + " [" + e.service() + "] " + e.level() + " " + e.msg()
                + " for user " + e.fields().get("user_id") + " sku " + e.fields().get("sku");
        return e.fields().containsKey("error") ? base + " - " + e.fields().get("error") : base;
    }
    static String json(Event e) {
        var sb = new StringBuilder("{");
        sb.append("\"ts\":").append(e.tsMillis()).append(",\"level\":\"").append(e.level()).append("\",\"service\":\"").append(e.service()).append("\",\"msg\":\"").append(e.msg()).append("\"");
        e.fields().forEach((k, v) -> sb.append(",\"").append(k).append("\":\"").append(v).append("\""));
        return sb.append("}").toString();
    }

    public static void main(String[] args) {
        List<Event> all = new ArrayList<>();
        long clock = System.currentTimeMillis();
        List<String> traces = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            var req = request(clock += R.nextInt(5), R.nextInt(2000), "sku-" + R.nextInt(500));
            all.addAll(req);
            if (!req.isEmpty()) traces.add(req.get(0).traceId());
        }
        long errors = all.stream().filter(e -> e.level().equals("ERROR")).count();
        System.out.printf("%,d requests → %,d log events, %,d ERROR events%n%n", 5_000, all.size(), errors);

        System.out.println("A. Querying: \"which requests failed in inventory, and what was the error?\"");
        // structured: filter by fields
        long structHits = all.stream().filter(e -> e.level().equals("ERROR") && e.service().equals("inventory")).count();
        System.out.printf("   structured JSON: filter level=ERROR AND service=inventory → %d events, each with trace_id, user_id, sku, error%n", structHits);
        // unstructured: you must regex the sentence, and fields like sku are positional/fragile
        String sampleErr = all.stream().filter(e -> e.level().equals("ERROR") && e.service().equals("inventory")).map(LogsL1::unstructured).findFirst().orElse("(none)");
        System.out.printf("   unstructured   : grep 'inventory' | grep 'ERROR', then parse free text. One line looks like:%n     %s%n", sampleErr.substring(0, Math.min(sampleErr.length(), 110)));
        System.out.println("   → structured logs are queryable by field; text logs force brittle regex and can't be aggregated reliably.");

        System.out.println("\nB. Correlation: reconstruct ONE failed request's path across services");
        String failedTrace = all.stream().filter(e -> e.level().equals("ERROR")).map(Event::traceId).findFirst().orElseThrow();
        var journey = all.stream().filter(e -> e.traceId().equals(failedTrace)).sorted(Comparator.comparingLong(Event::tsMillis)).toList();
        System.out.printf("   with a correlation id: filter trace_id=%s → %d ordered events:%n", failedTrace, journey.size());
        for (Event e : journey) System.out.printf("     %-9s %-5s %s%n", e.service(), e.level(), e.fields().getOrDefault("error", e.msg()));
        System.out.println("   without one: the 5 lines are scattered among " + String.format("%,d", all.size()) + " events with no shared key — you'd correlate by guessing on timestamp+user.");

        System.out.println("\nC. Levels and sampling: cut INFO volume without losing errors");
        Map<String, Long> byLevel = all.stream().collect(Collectors.groupingBy(Event::level, Collectors.counting()));
        System.out.printf("   full volume: %s%n", byLevel);
        for (double keep : new double[]{1.0, 0.1, 0.01}) {
            // sample INFO by trace so a kept request keeps ALL its INFO lines; always keep every ERROR and its whole trace
            Set<String> errorTraces = all.stream().filter(e -> e.level().equals("ERROR")).map(Event::traceId).collect(Collectors.toSet());
            Set<String> keptInfoTraces = traces.stream().filter(t -> (Math.abs(t.hashCode()) % 1000) / 1000.0 < keep).collect(Collectors.toSet());
            long kept = all.stream().filter(e -> e.level().equals("ERROR") || errorTraces.contains(e.traceId()) || keptInfoTraces.contains(e.traceId())).count();
            long errKept = all.stream().filter(e -> e.level().equals("ERROR")).filter(e -> errorTraces.contains(e.traceId())).count();
            System.out.printf("   keep %4.0f%% of INFO traces (errors always kept): %,7d events stored (%.0f%%), ERROR events kept %d/%d%n",
                    keep * 100, kept, 100.0 * kept / all.size(), errKept, errors);
        }
        System.out.println("   → sample by trace, never by line (so a kept request is whole), and never sample errors.");

        System.out.println("\nD. Cardinality: what happens if you put high-cardinality values in labels");
        System.out.printf("   distinct services: %d  (safe as a label)%n", SERVICES.length);
        System.out.printf("   distinct users   : %,d  distinct traces: %,d  (NEVER a metric label — one time series each)%n",
                all.stream().map(e -> e.fields().get("user_id")).distinct().count(), traces.size());
        long labelSeries = (long) SERVICES.length * 2 /*level*/ * all.stream().map(e -> e.fields().get("user_id")).distinct().count();
        System.out.printf("   a counter labelled by (service, level, user_id) would create ~%,d time series; by (service, level) only %d.%n", labelSeries, SERVICES.length * 2);
        System.out.println("   → high-cardinality identifiers belong in logs/traces (searchable), not in metric labels (they explode storage).");
    }
}
