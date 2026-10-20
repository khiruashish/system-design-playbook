// PavedRoadL3.java — run with: java PavedRoadL3.java services.csv   (JDK 21, no dependencies)
// THE PAVED ROAD (GOLDEN PATH) AT SCALE. At 26 services and 8 teams, "please follow the standards" doesn't scale; a
// platform team gives teams a service TEMPLATE that already does the right thing — Spring Boot 3 on Java 21, the platform
// starter (JSON logs, tracing, outbox), health and metrics endpoints, an ArchUnit test, CI with SBOM + vulnerability scan,
// and an ownership file for the service catalog. Services are created from whatever template version existed at the time,
// and some were hand-rolled. This lab GENERATES the 26 repos on disk (from template v1, v2, v3 or by hand), then runs a
// conformance SCANNER over the real files to measure adoption of each capability and where drift accumulates.
import java.nio.file.*;
import java.util.*;

public class PavedRoadL3 {
    enum Origin { TEMPLATE_V3, TEMPLATE_V2, TEMPLATE_V1, HAND_ROLLED }

    // ---------- generator: what each origin produces ----------
    static void generate(Path repo, String svc, String owner, Origin o, Random r) throws Exception {
        Files.createDirectories(repo.resolve("src/main/resources")); Files.createDirectories(repo.resolve(".github/workflows"));
        Files.createDirectories(repo.resolve("src/test/java/in/grocery/" + svc));
        boolean hr = o == Origin.HAND_ROLLED;
        String boot = switch (o) { case TEMPLATE_V3, TEMPLATE_V2 -> "3.3.4"; case TEMPLATE_V1 -> "2.7.18"; default -> r.nextBoolean() ? "3.1.2" : "2.7.5"; };
        int starter = switch (o) { case TEMPLATE_V3 -> 4; case TEMPLATE_V2 -> 3; case TEMPLATE_V1 -> 1 + r.nextInt(2); default -> 0; };
        String java = switch (o) { case TEMPLATE_V3 -> "21"; case TEMPLATE_V2 -> r.nextBoolean() ? "21" : "17"; default -> "17"; };
        Files.writeString(repo.resolve("pom.xml"), "<project>\n  <parent><artifactId>spring-boot-starter-parent</artifactId><version>" + boot + "</version></parent>\n"
                + "  <properties><java.version>" + java + "</java.version></properties>\n  <dependencies>\n"
                + (starter > 0 ? "    <dependency><artifactId>grocery-platform-starter</artifactId><version>" + starter + ".2.0</version></dependency>\n" : "")
                + "  </dependencies>\n</project>\n");
        boolean json = o == Origin.TEMPLATE_V3 || o == Origin.TEMPLATE_V2 || (o == Origin.TEMPLATE_V1 && r.nextBoolean()) || (hr && r.nextDouble() < 0.3);
        boolean prom = !hr || r.nextBoolean();
        Files.writeString(repo.resolve("src/main/resources/application.yml"), "spring.application.name: " + svc + "\n"
                + (json ? "logging.structured.format.console: ecs\n" : "logging.pattern.console: \"%d %p %c %m%n\"\n")
                + "management.endpoints.web.exposure.include: " + (prom ? "health,prometheus" : "health") + "\n");
        Files.writeString(repo.resolve("Dockerfile"), "FROM eclipse-temurin:" + java + "-jre\nCOPY target/app.jar /app.jar\nENTRYPOINT [\"java\",\"-jar\",\"/app.jar\"]\n");
        boolean sbom = o == Origin.TEMPLATE_V3, scan = o == Origin.TEMPLATE_V3 || o == Origin.TEMPLATE_V2;
        Files.writeString(repo.resolve(".github/workflows/ci.yml"), "name: ci\njobs:\n  build:\n    steps:\n      - run: mvn -B verify\n"
                + (sbom ? "      - run: mvn cyclonedx:makeAggregateBom\n" : "") + (scan ? "      - uses: aquasecurity/trivy-action@0.24.0\n" : ""));
        boolean arch = o == Origin.TEMPLATE_V3 || (o == Origin.TEMPLATE_V2 && r.nextBoolean());
        if (arch) Files.writeString(repo.resolve("src/test/java/in/grocery/" + svc + "/ArchitectureTest.java"), "@AnalyzeClasses class ArchitectureTest { /* layered + domain purity rules */ }\n");
        boolean catalog = o != Origin.HAND_ROLLED && (o != Origin.TEMPLATE_V1 || r.nextBoolean());
        if (catalog) Files.writeString(repo.resolve("catalog-info.yaml"), "kind: Component\nmetadata:\n  name: " + svc + "\nspec:\n  owner: " + owner + "\n");
        Files.writeString(repo.resolve(".template-origin"), o.name() + "\n");
    }

    // ---------- scanner: reads only the files ----------
    record Check(String name, java.util.function.Predicate<Path> test) {}
    static String read(Path p) { try { return Files.exists(p) ? Files.readString(p) : ""; } catch (Exception e) { return ""; } }
    static final List<Check> CHECKS = List.of(
        new Check("Spring Boot 3.x", p -> read(p.resolve("pom.xml")).contains("<version>3.")),
        new Check("Java 21 runtime image", p -> read(p.resolve("Dockerfile")).contains("temurin:21")),
        new Check("platform starter ≥ 4", p -> read(p.resolve("pom.xml")).matches("(?s).*platform-starter</artifactId><version>[4-9].*")),
        new Check("structured JSON logs", p -> read(p.resolve("src/main/resources/application.yml")).contains("structured.format")),
        new Check("health + metrics exposed", p -> read(p.resolve("src/main/resources/application.yml")).contains("prometheus")),
        new Check("architecture test", p -> { try (var w = Files.walk(p.resolve("src/test"))) { return w.anyMatch(f -> f.toString().endsWith("ArchitectureTest.java")); } catch (Exception e) { return false; } }),
        new Check("CI: SBOM + vuln scan", p -> { String c = read(p.resolve(".github/workflows/ci.yml")); return c.contains("cyclonedx") && c.contains("trivy"); }),
        new Check("catalog entry with owner", p -> read(p.resolve("catalog-info.yaml")).contains("owner:")));

    public static void main(String[] args) throws Exception {
        List<String[]> svcs = Files.readAllLines(Path.of(args[0])).stream().filter(l -> !l.isBlank() && !l.startsWith("#")).map(l -> l.split(",")).toList();
        Path root = Path.of("repos"); if (Files.exists(root)) try (var w = Files.walk(root)) { w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        Random r = new Random(4);
        Origin[] plan = new Origin[svcs.size()];                                   // 8 v3, 9 v2, 5 v1, 4 hand-rolled
        List<Origin> pool = new ArrayList<>();
        for (int i = 0; i < 8; i++) pool.add(Origin.TEMPLATE_V3); for (int i = 0; i < 9; i++) pool.add(Origin.TEMPLATE_V2);
        for (int i = 0; i < 5; i++) pool.add(Origin.TEMPLATE_V1); for (int i = 0; i < 4; i++) pool.add(Origin.HAND_ROLLED);
        Collections.shuffle(pool, r);
        for (int i = 0; i < svcs.size(); i++) { plan[i] = pool.get(i); generate(root.resolve(svcs.get(i)[0]), svcs.get(i)[0], svcs.get(i)[3], plan[i], r); }
        long files; try (var w = Files.walk(root)) { files = w.filter(Files::isRegularFile).count(); }
        System.out.printf("Generated %d service repos (%,d files) from template v3/v2/v1 or by hand; scanning them against the v3 golden path.%n%n", svcs.size(), files);

        // scan
        Map<String, Integer> passByCheck = new LinkedHashMap<>(); Map<Origin, int[]> byOrigin = new EnumMap<>(Origin.class);
        List<String> worst = new ArrayList<>(); int[] score = new int[svcs.size()];
        for (int i = 0; i < svcs.size(); i++) {
            Path p = root.resolve(svcs.get(i)[0]);
            Origin o = Origin.valueOf(read(p.resolve(".template-origin")).trim());
            for (Check c : CHECKS) if (c.test().test(p)) { passByCheck.merge(c.name(), 1, Integer::sum); score[i]++; } else passByCheck.putIfAbsent(c.name(), 0);
            byOrigin.computeIfAbsent(o, k -> new int[2]); byOrigin.get(o)[0] += score[i]; byOrigin.get(o)[1] += CHECKS.size();
        }
        System.out.println("   Adoption by capability (26 services):");
        for (var e : passByCheck.entrySet()) System.out.printf("        %-26s %2d/26  %s%n", e.getKey(), e.getValue(), "█".repeat(e.getValue()));
        System.out.println("\n   Conformance by how the service was created:");
        for (var e : byOrigin.entrySet()) System.out.printf("        %-12s %5.0f%% of checks pass%n", e.getKey(), 100.0 * e.getValue()[0] / e.getValue()[1]);
        Integer[] idx = new Integer[svcs.size()]; for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, Comparator.comparingInt(i -> score[i]));
        System.out.print("\n   Lowest-scoring services: ");
        for (int k = 0; k < 5; k++) System.out.printf("%s %d/8 (%s)%s", svcs.get(idx[k])[0], score[idx[k]], plan[idx[k]], k < 4 ? ", " : "\n");
        long fully = Arrays.stream(score).filter(x -> x == CHECKS.size()).count();
        System.out.printf("   Fully on the current golden path: %d of 26 services.%n", fully);

        System.out.println("\n   → the golden path works where it was used: services created from the current template pass every check, with no");
        System.out.println("     one having to remember the standards. The drift is in services created from OLD template versions and in");
        System.out.println("     hand-rolled ones — templates are a one-time copy, so every improvement since then (SBOM, Java 21, starter v4)");
        System.out.println("     never reached them. At scale the platform team's job is therefore not just the template but keeping the fleet on");
        System.out.println("     it: a scanner like this in CI, automated upgrade PRs (Renovate/OpenRewrite recipes) for the mechanical gaps, and a");
        System.out.println("     scorecard per team so the remaining work is visible — golden path by default, drift made visible, not policed.");
    }
}
