// Appendix tool — build the lab catalogue from every chapter's §5: each L1–L4 lab, its band, the programs it shows,
// and what it needs to run (detected from the code itself), plus whether the chapter has a labs/ folder.
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class LabCatalog {
    record Lab(String ch, String title, String file, String level, String band, List<String> programs, Set<String> needs) {}

    public static void main(String[] a) throws Exception {
        Path docs = Path.of(a[0]), labsDir = Path.of(a[1]), out = Path.of(a[2]);
        Set<String> labFolders; try (Stream<Path> s = Files.list(labsDir)) { labFolders = s.map(p -> p.getFileName().toString().substring(2, 4)).collect(Collectors.toSet()); }
        List<Path> files; try (Stream<Path> s = Files.walk(docs, 2)) { files = s.filter(p -> p.getFileName().toString().matches("\\d\\d-.*\\.md")).sorted().toList(); }
        List<Lab> labs = new ArrayList<>(); Map<String, String> chTitle = new LinkedHashMap<>(), chFile = new HashMap<>();
        Pattern head = Pattern.compile("(?m)^#{3,4} (L[1-4])\\b[^\\n]*?[—-]\\s*(.*?)(<span class=\"band (b\\d)\".*)?$");
        for (Path f : files) {
            String t = Files.readString(f), ch = f.getFileName().toString().substring(0, 2);
            int s5 = t.indexOf("\n## 5."), s6 = t.indexOf("\n## 6.", Math.max(s5, 0));
            if (s5 < 0) continue;
            String sec = t.substring(s5, s6 > 0 ? s6 : t.length());
            chTitle.put(ch, t.lines().findFirst().orElse("").replaceFirst("^# ", "").replaceFirst("^\\d+\\. ", ""));
            chFile.put(ch, "../" + docs.relativize(f).toString().replace('\\', '/'));
            Matcher m = head.matcher(sec); List<int[]> starts = new ArrayList<>(); List<String[]> meta = new ArrayList<>();
            while (m.find()) { starts.add(new int[]{m.start()}); meta.add(new String[]{m.group(1), m.group(2).replaceAll("<[^>]+>", "").replaceAll("\\*", "").trim(), m.group(4) == null ? "" : m.group(4).toUpperCase()}); }
            for (int i = 0; i < starts.size(); i++) {
                String body = sec.substring(starts.get(i)[0], i + 1 < starts.size() ? starts.get(i + 1)[0] : sec.length());
                List<String> progs = new ArrayList<>(); Matcher c = Pattern.compile("```(?:java|kotlin|python|yaml|bash|sql|sh)[^\\n]*title=\"([^\"]+)\"").matcher(body);
                while (c.find()) { String ti = c.group(1).split(" ")[0]; if (ti.matches(".*\\.(java|py|yaml|yml|sql|sh|kts?)") && !progs.contains(ti)) progs.add(ti); }
                String code = String.join("\n", codeBlocks(body));
                Set<String> needs = new LinkedHashSet<>();
                if (code.contains("jdbc:postgresql") || code.contains("PGSimpleDataSource")) needs.add("PostgreSQL");
                if (Pattern.compile("Jedis|RedisTemplate|RedisClient|Lettuce|redis-cli|:63\\d\\d|new Socket\\([^)]*63\\d\\d|RESP").matcher(code).find()) needs.add("Redis");
                if (code.contains("HttpServer") || code.contains("HttpClient")) needs.add("HTTP (local)");
                if (code.contains("@SpringBootApplication") || code.contains("org.springframework")) needs.add("Spring");
                if (code.contains("Testcontainers") || code.contains("org.testcontainers")) needs.add("Testcontainers");
                if (code.contains("org.apache.kafka")) needs.add("Kafka client");
                if (code.contains("ProcessBuilder")) needs.add("child JVM");
                labs.add(new Lab(ch, meta.get(i)[1], chFile.get(ch), meta.get(i)[0], meta.get(i)[2], progs, needs));
            }
        }
        StringBuilder md = new StringBuilder(); String lastPart = "";
        for (String ch : chTitle.keySet()) {
            if (labs.stream().noneMatch(x -> x.ch().equals(ch))) continue;
            String part = part(ch); if (!part.equals(lastPart)) { md.append("\n## ").append(part).append("\n\n| Ch | Lab | Band | Programs | Needs |\n|---|---|---|---|---|\n"); lastPart = part; }
            for (Lab l : labs.stream().filter(x -> x.ch().equals(ch)).toList())
                md.append("| [").append(ch).append("](").append(l.file()).append(") | **").append(l.level()).append("** ").append(l.title().replace("|", "\\|"))
                  .append(" | ").append(l.band()).append(" | ").append(l.programs().isEmpty() ? "—" : l.programs().stream().map(p -> "`" + p + "`").collect(Collectors.joining(" ")))
                  .append(" | ").append(l.needs().isEmpty() ? "JDK only" : String.join(", ", l.needs())).append(" |\n");
        }
        Files.writeString(out, md.toString());
        System.out.printf("%d chapters with labs, %d labs, %d named programs%n", labs.stream().map(Lab::ch).distinct().count(), labs.size(), labs.stream().mapToInt(l -> l.programs().size()).sum());
        Map<String, Long> needs = labs.stream().flatMap(l -> l.needs().isEmpty() ? Stream.of("JDK only") : l.needs().stream()).collect(Collectors.groupingBy(x -> x, TreeMap::new, Collectors.counting()));
        System.out.println("labs by requirement: " + needs);
        List<String> odd = chTitle.keySet().stream().filter(ch -> !ch.equals("00")).filter(ch -> labs.stream().filter(l -> l.ch().equals(ch)).count() != 4).toList();
        System.out.println("chapters without exactly 4 labs: " + odd.stream().map(ch -> ch + "(" + labs.stream().filter(l -> l.ch().equals(ch)).count() + ")").toList());
        System.out.println("chapters with §5 but no labs/ folder: " + chTitle.keySet().stream().filter(ch -> !labFolders.contains(ch)).toList());
    }
    static List<String> codeBlocks(String s) { List<String> r = new ArrayList<>(); Matcher m = Pattern.compile("(?s)```[^\\n]*\\n(.*?)\\n\\s*```").matcher(s); while (m.find()) r.add(m.group(1)); return r; }
    static String part(String ch) { int n = Integer.parseInt(ch);
        return n <= 1 ? "Part 0 · Orientation" : n <= 9 ? "Part I · Foundations" : n <= 20 ? "Part II · Distributed Core" : n <= 30 ? "Part III · Production"
             : n <= 37 ? "Part IV · Architect" : n <= 55 ? "Part V · Case Studies" : n == 56 ? "Part VI · Capstone" : "Part VII · Interview"; }
}
