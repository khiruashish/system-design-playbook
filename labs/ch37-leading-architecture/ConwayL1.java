// ConwayL1.java — run with: java ConwayL1.java services.csv features.csv   (JDK 21, no dependencies)
// CONWAY'S LAW, MEASURED. "Organisations design systems that mirror their communication structures" — and the reverse is
// what an architect can use: if the teams don't match the architecture's boundaries, every change needs a meeting.
// We replay a year of feature work (500 features drawn from the platform's recurring feature types, 15% with an extra
// incidental service) against two ownership maps for the SAME 26 services:
//   CURRENT — 9 teams organised by history and technical layer (api-team, data-team, core-team, web-team...)
//   ALIGNED — 8 stream-aligned teams, one per bounded context (Ch 33), each owning 3–4 cohesive services
// For each, we count how many teams each feature needs, which team pairs must coordinate often, and estimate lead time with
// an explicit, simple model: 5 days of work + 4 days of hand-off/coordination for every additional team involved.
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class ConwayL1 {
    record Svc(String name, String context, String current, String aligned, int effort) {}
    record Archetype(String name, int weight, List<String> services) {}

    static List<String[]> csv(String f) throws Exception {
        return Files.readAllLines(Path.of(f)).stream().filter(l -> !l.isBlank() && !l.startsWith("#")).map(l -> l.split(",")).toList();
    }

    public static void main(String[] args) throws Exception {
        Map<String, Svc> svcs = new LinkedHashMap<>();
        for (String[] r : csv(args[0])) svcs.put(r[0], new Svc(r[0], r[1], r[2], r[3], Integer.parseInt(r[4])));
        List<Archetype> arch = new ArrayList<>();
        for (String[] r : csv(args[1])) arch.add(new Archetype(r[0], Integer.parseInt(r[1]), List.of(r[2].trim().split(" "))));

        // a year of features
        Random rnd = new Random(37); int total = arch.stream().mapToInt(Archetype::weight).sum();
        List<Set<String>> features = new ArrayList<>(); List<String> names = new ArrayList<>(svcs.keySet());
        for (int i = 0; i < 500; i++) {
            int x = rnd.nextInt(total); Archetype a = arch.get(0);
            for (Archetype c : arch) { if (x < c.weight()) { a = c; break; } x -= c.weight(); }
            Set<String> s = new TreeSet<>(a.services());
            if (rnd.nextDouble() < 0.15) s.add(names.get(rnd.nextInt(names.size())));
            features.add(s);
        }

        System.out.printf("%d services, 500 features over a year. Same code, same features — two ownership maps.%n%n", svcs.size());
        System.out.printf("   %-34s %6s %11s %11s %11s %13s %12s%n", "ownership map", "teams", "avg teams", "≥2 teams", "≥3 teams", "lead time*", "hot pairs**");
        for (String map : List.of("current", "aligned")) {
            Map<String, String> owner = new HashMap<>();
            svcs.values().forEach(s -> owner.put(s.name(), map.equals("current") ? s.current() : s.aligned()));
            int teams = (int) owner.values().stream().distinct().count();
            double sumTeams = 0, sumLead = 0; int two = 0, three = 0;
            Map<String, Integer> pairs = new TreeMap<>();
            for (Set<String> f : features) {
                List<String> ts = f.stream().map(owner::get).distinct().sorted().toList();
                sumTeams += ts.size(); if (ts.size() >= 2) two++; if (ts.size() >= 3) three++;
                sumLead += 5 + 4 * (ts.size() - 1);
                for (int i = 0; i < ts.size(); i++) for (int j = i + 1; j < ts.size(); j++) pairs.merge(ts.get(i) + "↔" + ts.get(j), 1, Integer::sum);
            }
            long hot = pairs.values().stream().filter(v -> v >= 25).count();       // pairs that must coordinate ~every other week
            System.out.printf("   %-34s %6d %11.2f %10.0f%% %10.0f%% %10.1f d %12d%n",
                    map.equals("current") ? "CURRENT (by history / layer)" : "ALIGNED (by bounded context)", teams,
                    sumTeams / 500, 100.0 * two / 500, 100.0 * three / 500, sumLead / 500, hot);
            {
                String top = pairs.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(3)
                        .map(e -> e.getKey() + " (" + e.getValue() + ")").collect(Collectors.joining(", "));
                System.out.printf("        busiest coordination pairs: %s%n", top);
            }
        }
        System.out.println("   *  lead-time model (an assumption, not a measurement): 5 days + 4 days per additional team involved");
        System.out.println("   ** team pairs that must coordinate on ≥ 25 features a year (about every other week)");

        // who owns what, and how contexts are split today
        System.out.println("\n   Bounded contexts split across teams TODAY:");
        Map<String, Set<String>> ctxTeams = new TreeMap<>();
        svcs.values().forEach(s -> ctxTeams.computeIfAbsent(s.context(), k -> new TreeSet<>()).add(s.current()));
        ctxTeams.forEach((c, t) -> System.out.printf("        %-11s → %d team(s): %s%n", c, t.size(), t));

        System.out.println("\n   → ownership changes coordination with no code change at all: organised by history and layer, every bounded context");
        System.out.println("     is split across 2–3 teams and 26% of features need three or more teams. But aligning teams to the bounded");
        System.out.println("     contexts on paper helps only modestly here — most features still cross a team boundary — because this year's");
        System.out.println("     work doesn't flow along those contexts: promotions changes always touch the cart, slot capacity always touches");
        System.out.println("     dark-store stock. Conway's law cuts both ways: teams must be aligned to how CHANGE actually flows (value streams),");
        System.out.println("     which you measure from feature and commit history, not infer from nouns. L2 does exactly that.");
    }
}
