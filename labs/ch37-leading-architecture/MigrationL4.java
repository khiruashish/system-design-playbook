// MigrationL4.java — run with: java MigrationL4.java services.csv   (JDK 21, no dependencies)
// A FLEET-WIDE MIGRATION, PLANNED. Moving 26 services from Spring Boot 2 to Spring Boot 3 / Java 21 (javax → jakarta, new
// observability APIs, new security config) is the kind of change an architect leads but can't do alone. It has a dependency
// structure: the shared platform starter must be migrated first, the outbox library (used by 7 services) after it, then each
// service. Effort per service comes from services.csv (1–3 sprints). We schedule it four ways with a simple list scheduler
// (each team works on one migration at a time; longest task first) and compare how long the fleet takes to finish:
//   A) CENTRAL migration team (2 parallel streams) migrates libraries and all 26 services
//   B) TEAM-OWNED: the platform team migrates the libraries first; each of the 8 stream teams migrates its own services
//   C) TEAM-OWNED, NO PLATFORM-FIRST: teams start at once and work around the unmigrated libraries (+80% effort per service
//      started before the libraries it needs are ready — shims, forks, duplicated fixes)
//   D) B + automated codemods (OpenRewrite-style recipes do the mechanical javax→jakarta/config changes): −40% service effort
import java.nio.file.*;
import java.util.*;

public class MigrationL4 {
    record Task(String name, String team, double effort, List<String> deps) {}
    static final Set<String> OUTBOX_USERS = Set.of("order", "payment", "refunds", "stock", "reservation", "dispatch", "checkout");

    // returns {finish sprint, burn-up by sprint}
    static Object[] schedule(List<Task> tasks, Map<String, Integer> slots, boolean penaltyIfLibsNotReady) {
        Map<String, Double> finish = new HashMap<>(); Map<String, Double> teamFree = new HashMap<>();
        List<Task> pending = new ArrayList<>(tasks);
        pending.sort(Comparator.comparingDouble(Task::effort).reversed());       // longest first within a team
        Map<String, PriorityQueue<Double>> teamSlots = new HashMap<>();
        for (var e : slots.entrySet()) { PriorityQueue<Double> q = new PriorityQueue<>(); for (int i = 0; i < e.getValue(); i++) q.add(0.0); teamSlots.put(e.getKey(), q); }
        // libraries first in list order when they share a team
        pending.sort(Comparator.comparing((Task t) -> !t.name().startsWith("lib:")).thenComparing(Comparator.comparingDouble(Task::effort).reversed()));
        List<Double> serviceDone = new ArrayList<>(); double spent = 0; int workarounds = 0;
        while (!pending.isEmpty()) {
            Task pick = null; double bestStart = Double.MAX_VALUE;
            for (Task t : pending) {
                double depsDone = 0; boolean ok = true;
                for (String d : t.deps()) { if (!finish.containsKey(d)) { if (!penaltyIfLibsNotReady) { ok = false; break; } } else depsDone = Math.max(depsDone, finish.get(d)); }
                if (!ok) continue;
                double slotFree = teamSlots.get(t.team()).peek();
                double start = penaltyIfLibsNotReady ? slotFree : Math.max(slotFree, depsDone);
                if (start < bestStart) { bestStart = start; pick = t; }
            }
            if (pick == null) throw new IllegalStateException("deadlock");
            double effort = pick.effort();
            if (penaltyIfLibsNotReady && !pick.name().startsWith("lib:"))
                for (String d : pick.deps()) if (!finish.containsKey(d) || finish.get(d) > bestStart) { effort *= 1.8; workarounds++; break; }
            spent += effort;
            teamSlots.get(pick.team()).poll(); double end = bestStart + effort; teamSlots.get(pick.team()).add(end);
            finish.put(pick.name(), end); pending.remove(pick);
            if (!pick.name().startsWith("lib:")) serviceDone.add(end);
        }
        double total = finish.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double effortSum = 0; for (Task t : tasks) effortSum += t.effort();
        return new Object[]{total, serviceDone, spent, workarounds};
    }

    public static void main(String[] args) throws Exception {
        List<String[]> svcs = Files.readAllLines(Path.of(args[0])).stream().filter(l -> !l.isBlank() && !l.startsWith("#")).map(l -> l.split(",")).toList();
        System.out.printf("Fleet migration: Spring Boot 2 → 3 / Java 21 across %d services + 2 shared libraries (starter: 3 sprints, outbox: 2).%n%n", svcs.size());
        System.out.printf("   %-62s %11s %14s %12s %22s%n", "strategy", "done after", "effort spent", "workarounds", "services done by 4 / 8 / 12");
        double[] finishAt = new double[4], spentBy = new double[4];
        String[] labels = {"A) central migration team, 2 parallel streams", "B) platform migrates libraries first, teams own their services",
                "C) teams start immediately, libraries not ready (workarounds)", "D) B + automated codemods (−40% service effort)"};
        for (int s = 0; s < 4; s++) {
            List<Task> tasks = new ArrayList<>(); Map<String, Integer> slots = new HashMap<>();
            String libTeam = s == 0 ? "central" : "platform";
            tasks.add(new Task("lib:starter", libTeam, 3, List.of()));
            tasks.add(new Task("lib:outbox", libTeam, 2, List.of("lib:starter")));
            for (String[] v : svcs) {
                double eff = Double.parseDouble(v[4]) * (s == 3 ? 0.6 : 1.0);
                List<String> deps = new ArrayList<>(List.of("lib:starter")); if (OUTBOX_USERS.contains(v[0])) deps.add("lib:outbox");
                tasks.add(new Task(v[0], s == 0 ? "central" : v[3], eff, deps));
            }
            if (s == 0) slots.put("central", 2); else { slots.put("platform", 1); for (String[] v : svcs) slots.put(v[3], 1); }
            Object[] res = schedule(tasks, slots, s == 2);
            @SuppressWarnings("unchecked") List<Double> done = (List<Double>) res[1];
            long at4 = done.stream().filter(x -> x <= 4).count(), at8 = done.stream().filter(x -> x <= 8).count(), at12 = done.stream().filter(x -> x <= 12).count();
            double effort = tasks.stream().mapToDouble(Task::effort).sum();
            finishAt[s] = (double) res[0]; spentBy[s] = (double) res[2];
            System.out.printf("   %-62s %7.1f spr %10.1f spr %12d %13d / %2d / %2d%n", labels[s], finishAt[s], spentBy[s], (int) res[3], at4, at8, at12);
        }
        System.out.println("   (1 sprint = 2 weeks. Effort per service from services.csv; penalties/savings are stated assumptions.)");

        System.out.printf("%n   → a central team is the bottleneck: %.0f sprints of work through 2 streams takes %.0f sprints (~%.0f months), and the teams%n",
                spentBy[0], finishAt[0], finishAt[0] * 2 / 4.33);
        System.out.println("     who own the services learn nothing about their new stack. Team ownership parallelises across 8 teams and finishes");
        System.out.printf ("     in %.0f sprints. Starting every team immediately, before the shared libraries are migrated, finishes at about the%n", finishAt[1]);
        System.out.printf ("     same time (%.1f vs %.1f sprints) — parallelism hides the wait — but burns %.0f%% more effort on workarounds that must%n",
                finishAt[2], finishAt[1], 100 * (spentBy[2] - spentBy[1]) / spentBy[1]);
        System.out.println("     later be removed: a false economy. Codemods cut both time and effort. The architect's job is the plan and the");
        System.out.println("     enablers: sequence dependencies, fund the platform work first, supply recipes, track progress with a fitness");
        System.out.println("     function (L3's scanner: % of services on Boot 3), and let each team own its migration.");
    }
}
