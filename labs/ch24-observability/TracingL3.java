// TracingL3.java — run with: java TracingL3.java      (JDK 17+, no dependencies)
// Distributed tracing from scratch. Build spans with parent/child context across the product-page fan-out (Ch 19),
// then: A. reconstruct the span tree for one slow request and find the CRITICAL PATH (what to fix). B. aggregate 10,000
// traces to find which service actually drives p99. C. sampling: head-based vs tail-based, and what each keeps/loses.
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

public class TracingL3 {
    static final AtomicLong ID = new AtomicLong(1);
    static final class Span {
        final String traceId, spanId, parentId, service, op; final long startUs; long endUs; final Map<String, String> attrs = new HashMap<>();
        Span(String traceId, String parentId, String service, String op, long startUs) { this.traceId = traceId; this.spanId = "s" + ID.getAndIncrement(); this.parentId = parentId; this.service = service; this.op = op; this.startUs = startUs; }
        long durUs() { return endUs - startUs; }
    }

    static final Random R = new Random(24);
    static long lognormalUs(double muMs, double sigma, double tailProb, double tailAddMs) {
        double ms = Math.exp(R.nextGaussian() * sigma + Math.log(muMs));
        if (R.nextDouble() < tailProb) ms += tailAddMs * (0.5 + R.nextDouble());
        return (long) (ms * 1000);
    }

    /** One product-page request. Returns all spans. Structured concurrency: children overlap under the handler. */
    static List<Span> trace() {
        String tid = String.format("%016x", R.nextLong() & 0x7fffffffffffffffL);
        List<Span> spans = new ArrayList<>();
        long t = 0;
        Span root = new Span(tid, null, "bff", "GET /product", t); spans.add(root);
        // required calls run concurrently; the handler waits for the slowest (structured concurrency, Ch 19)
        long[] childEnd = new long[3];
        String[][] deps = {{"catalog", "getProduct"}, {"pricing", "getPrice"}, {"inventory", "getStock"}};
        double[] mu = {20, 30, 25};
        for (int i = 0; i < 3; i++) {
            Span c = new Span(tid, root.spanId, deps[i][0], deps[i][1], t + 500);   // ~0.5ms dispatch
            long dur = lognormalUs(mu[i], 0.4, 0, 0);                       // required deps are tight; the tail comes only from fx-rates under pricing
            if (deps[i][0].equals("pricing")) {                                     // pricing calls a slow currency service 30% of the time
                Span gc = new Span(tid, c.spanId, "fx-rates", "convert", c.startUs + 1000);
                long gcDur = R.nextDouble() < 0.3 ? lognormalUs(150, 0.3, 0, 0) : lognormalUs(5, 0.3, 0, 0);
                gc.endUs = gc.startUs + gcDur; spans.add(gc);
                dur = Math.max(dur, gcDur + 1500);                                  // pricing waits on fx
            }
            c.endUs = c.startUs + dur; spans.add(c); childEnd[i] = c.endUs;
        }
        // reviews: optional, short budget; a DB span under catalog
        Span cat = spans.stream().filter(s -> s.service.equals("catalog")).findFirst().get();
        Span db = new Span(tid, cat.spanId, "catalog-db", "SELECT product", cat.startUs + 300); db.endUs = db.startUs + lognormalUs(6, 0.3, 0, 0); spans.add(db);
        root.endUs = Arrays.stream(childEnd).max().getAsLong() + 700;               // render after the slowest required child
        return spans;
    }

    static Map<String, List<Span>> children(List<Span> spans) {
        return spans.stream().filter(s -> s.parentId != null).collect(Collectors.groupingBy(s -> s.parentId));
    }

    /** Critical path: from the root, at each step follow the child whose end time determines the parent's end. */
    static List<Span> criticalPath(List<Span> spans) {
        Map<String, Span> byId = spans.stream().collect(Collectors.toMap(s -> s.spanId, s -> s));
        var kids = children(spans);
        Span cur = spans.stream().filter(s -> s.parentId == null).findFirst().get();
        List<Span> path = new ArrayList<>(); path.add(cur);
        while (kids.containsKey(cur.spanId)) {
            Span latest = kids.get(cur.spanId).stream().max(Comparator.comparingLong(s -> s.endUs)).get();
            path.add(latest); cur = latest;
        }
        return path;
    }

    public static void main(String[] args) {
        // A. one slow request
        List<Span> slow = null;
        for (int i = 0; i < 500; i++) { var t = trace(); Span root = t.get(0); if (root.durUs() > 200_000) { slow = t; break; } }
        if (slow == null) slow = trace();
        Span root = slow.get(0);
        System.out.printf("A. One slow trace (%.1f ms end to end). The span tree:%n", root.durUs() / 1000.0);
        printTree(slow, root, 0);
        var cp = criticalPath(slow);
        System.out.printf("   critical path: %s%n", cp.stream().map(s -> s.service + " " + s.durUs() / 1000 + "ms").collect(Collectors.joining(" → ")));
        System.out.println("   → optimising anything OFF the critical path (e.g. catalog) does not shrink the request; fix the fx-rates call under pricing.");

        // B. aggregate 10,000 traces
        System.out.println("\nB. 10,000 traces: which service drives the p99 of the product page?");
        int n = 10_000; long[] totals = new long[n];
        Map<String, List<Long>> onCritical = new HashMap<>();
        Map<String, long[]> selfTime = new HashMap<>();
        for (int i = 0; i < n; i++) {
            var t = trace(); totals[i] = t.get(0).durUs();
            for (Span s : criticalPath(t)) onCritical.computeIfAbsent(s.service, k -> new ArrayList<>()).add(s.durUs());
        }
        Arrays.sort(totals);
        System.out.printf("   product page: p50 %.1f ms, p95 %.1f ms, p99 %.1f ms%n", totals[n/2]/1000.0, totals[(int)(n*.95)]/1000.0, totals[(int)(n*.99)]/1000.0);
        System.out.printf("   %-12s %-22s %s%n", "service", "appears on crit. path", "its p99 when on it");
        onCritical.entrySet().stream().sorted((a,b) -> Long.compare(b.getValue().size(), a.getValue().size())).forEach(e -> {
            var v = e.getValue().stream().sorted().toList();
            System.out.printf("   %-12s %-22s %.1f ms%n", e.getKey(), String.format("%,d of %,d (%.0f%%)", v.size(), n, 100.0*v.size()/n), v.get((int)(v.size()*.99))/1000.0);
        });
        System.out.println("   → pricing/fx-rates sits on the critical path ~68% of the time and carries the whole tail (the 30% of calls that hit the slow currency service): that's the p99 lever.");

        // C. sampling
        System.out.println("\nC. Sampling 10,000 traces (each ~5 spans): head-based vs tail-based");
        List<List<Span>> traces = IntStream.range(0, n).mapToObj(i -> trace()).toList();
        long allSpans = traces.stream().mapToLong(List::size).sum();
        long slowMs = 200; long errors = traces.stream().filter(t -> t.get(0).durUs() > slowMs*1000).count();   // treat > p95 (200ms) as the interesting ones
        for (double head : new double[]{1.0, 0.1, 0.01}) {
            long keptTraces = traces.stream().filter(t -> (Math.abs(t.get(0).traceId.hashCode()) % 1000)/1000.0 < head).count();
            long slowKept = traces.stream().filter(t -> t.get(0).durUs() > slowMs*1000).filter(t -> (Math.abs(t.get(0).traceId.hashCode()) % 1000)/1000.0 < head).count();
            System.out.printf("   head-based %4.0f%%: keeps %,6d traces (%,d spans), slow traces kept %d/%d%n", head*100, keptTraces, keptTraces*allSpans/n, slowKept, errors);
        }
        long tailKept = errors + (long)(n * 0.01);   // tail: keep every slow trace + 1% baseline
        System.out.printf("   tail-based (keep all >200ms + 1%% baseline): keeps ~%,d traces, slow traces kept %d/%d — but must buffer every trace until it completes%n", tailKept, errors, errors);
        System.out.println("   → head sampling is cheap and decides early, but drops most slow traces; tail sampling keeps the interesting ones at the cost of buffering.");
    }

    static void printTree(List<Span> spans, Span s, int depth) {
        System.out.printf("     %s%-11s %-14s %5.1f ms%s%n", "  ".repeat(depth), s.service, s.op, s.durUs()/1000.0, depth == 0 ? "" : "");
        children(spans).getOrDefault(s.spanId, List.of()).stream().sorted(Comparator.comparingLong(c -> c.startUs)).forEach(c -> printTree(spans, c, depth+1));
    }
}
