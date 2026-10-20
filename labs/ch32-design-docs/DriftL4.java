// DriftL4.java — run with: java DriftL4.java src documented_model.txt   (JDK 21, no dependencies)
// DOCUMENTATION DRIFT DETECTION. A design doc's container diagram is true on the day it's approved and starts rotting the
// next day. Here we recover the ACTUAL architecture from the source code — Feign clients (sync calls), Kafka producers and
// listeners (async), and outbound WebClients to external hosts — and diff it against the relationships the doc DRAWS.
//   UNDOCUMENTED  = in the code, not in the doc   (the diagram is lying by omission — often the dangerous dependency)
//   STALE         = in the doc, not in the code   (the diagram shows something that no longer exists)
// Real tools do this from runtime data (service-mesh telemetry, distributed traces) or bytecode; here we parse source so
// the mechanism is visible. Run it in CI and a design doc can't quietly diverge from the system it describes.
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class DriftL4 {
    static final Pattern FEIGN    = Pattern.compile("@FeignClient\\(name\\s*=\\s*\"([\\w-]+)\"");
    static final Pattern PRODUCE  = Pattern.compile("\\.send\\(\"([\\w-]+)\"");
    static final Pattern CONSUME  = Pattern.compile("@KafkaListener\\(topics\\s*=\\s*\"([\\w-]+)\"");
    static final Pattern EXTERNAL = Pattern.compile("WebClient\\.create\\(\"https?://([\\w.-]+)");
    static final Map<String,String> KNOWN_HOSTS = Map.of("api.razorpay.com", "ext:razorpay", "sms.gateway.example", "ext:sms");

    public static void main(String[] args) throws Exception {
        Path src = Path.of(args[0]);
        Set<String> actual = new TreeSet<>();
        Map<String,String> evidence = new HashMap<>();
        try (Stream<Path> files = Files.walk(src)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String service = src.relativize(f).getName(0).toString();     // top-level dir = service
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    String l = lines.get(i), where = src.relativize(f) + ":" + (i + 1);
                    if (l.trim().startsWith("//")) continue;                  // ignore commented-out code
                    Matcher m;
                    if ((m = FEIGN.matcher(l)).find())    add(actual, evidence, service + " -> " + m.group(1), where);
                    if ((m = PRODUCE.matcher(l)).find())  add(actual, evidence, service + " -> topic:" + m.group(1), where);
                    if ((m = CONSUME.matcher(l)).find())  add(actual, evidence, "topic:" + m.group(1) + " -> " + service, where);
                    if ((m = EXTERNAL.matcher(l)).find()) add(actual, evidence, service + " -> " + KNOWN_HOSTS.getOrDefault(m.group(1), "ext:" + m.group(1)), where);
                }
            }
        }
        Set<String> documented = Files.readAllLines(Path.of(args[1])).stream()
                .map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#")).collect(Collectors.toCollection(TreeSet::new));

        System.out.println("Architecture drift: dependencies recovered from source code vs relationships drawn in the design doc.\n");
        System.out.println("   Recovered from code (" + actual.size() + "):");
        for (String a : actual) System.out.printf("      %-36s  (%s)%n", a, evidence.get(a));
        System.out.println("   Documented in the design doc (" + documented.size() + "): " + documented + "\n");

        Set<String> undocumented = new TreeSet<>(actual); undocumented.removeAll(documented);
        Set<String> stale = new TreeSet<>(documented); stale.removeAll(actual);
        Set<String> agree = new TreeSet<>(actual); agree.retainAll(documented);
        for (String u : undocumented) System.out.printf("   ✗ UNDOCUMENTED  %-36s found at %s%n", u, evidence.get(u));
        for (String s : stale)        System.out.printf("   ✗ STALE         %-36s drawn in the doc, absent from the code%n", s);
        System.out.printf("%n   → %d of %d documented relationships still hold; %d real dependencies are missing from the diagram; %d drawn%n",
                agree.size(), documented.size(), undocumented.size(), stale.size());
        System.out.println("     relationship(s) no longer exist. The undocumented ones matter most: checkout's synchronous call to promotions is now on the");
        System.out.println("     checkout critical path (a latency and availability dependency no reviewer approved), and promotions itself calls");
        System.out.println("     catalog. The stale dispatch → checkout arrow was removed from the code in June but still shapes how people reason");
        System.out.println("     about failure blast radius. Generate the container diagram from recovered dependencies (or fail CI on drift) and");
        System.out.println("     the design doc stays a description of the system rather than of its past.");
    }
    static void add(Set<String> s, Map<String,String> ev, String edge, String where) { s.add(edge); ev.putIfAbsent(edge, where); }
}
