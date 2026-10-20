// L1 — Story bank coverage: which competencies interviewers ask about are covered by your stories, at which level,
// and which stories you lean on too much. Reads a markdown story bank (## title, "- key: value" lines).
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class StoryBankL1 {
    record Story(String id, String title, Set<String> comps, String level) {}
    // competencies commonly probed in behavioural / leadership rounds, with the minimum level where it's expected
    static final LinkedHashMap<String, String> EXPECTED = new LinkedHashMap<>();
    static {
        EXPECTED.put("ownership", "B1"); EXPECTED.put("failure", "B1"); EXPECTED.put("conflict", "B2"); EXPECTED.put("communication", "B1");
        EXPECTED.put("customer-focus", "B1"); EXPECTED.put("pressure", "B2"); EXPECTED.put("technical-decision", "B2"); EXPECTED.put("ambiguity", "B2");
        EXPECTED.put("mentoring", "B2"); EXPECTED.put("influence", "B3"); EXPECTED.put("cross-team", "B3"); EXPECTED.put("incident", "B2");
        EXPECTED.put("delivery", "B2"); EXPECTED.put("hiring", "B3"); EXPECTED.put("strategy", "B4"); EXPECTED.put("cost", "B4");
        EXPECTED.put("growing-others", "B3"); EXPECTED.put("learning", "B1"); EXPECTED.put("prioritisation", "B3"); EXPECTED.put("org-change", "B4");
    }

    public static void main(String[] a) throws Exception {
        String target = a.length > 1 ? a[1] : "B3";
        List<Story> stories = parse(Files.readString(Path.of(a.length > 0 ? a[0] : "stories.md")));
        System.out.printf("%d stories; target level %s%n%n", stories.size(), target);
        System.out.printf("%-20s %-6s %-22s %s%n", "competency", "from", "stories (level)", "status");
        int gaps = 0, thin = 0;
        for (var e : EXPECTED.entrySet()) {
            if (lvl(e.getValue()) > lvl(target)) continue;                  // not expected at this level
            List<Story> hits = stories.stream().filter(s -> s.comps().contains(e.getKey())).toList();
            boolean atLevel = hits.stream().anyMatch(s -> lvl(s.level()) >= Math.min(lvl(target), 3));
            String st = hits.isEmpty() ? "GAP — no story" : hits.size() == 1 ? "thin — one story" + (atLevel ? "" : ", below level") : atLevel ? "ok" : "below level";
            if (hits.isEmpty()) gaps++; else if (hits.size() == 1 || !atLevel) thin++;
            System.out.printf("%-20s %-6s %-22s %s%n", e.getKey(), e.getValue(),
                    hits.stream().map(s -> s.id() + "(" + s.level() + ")").reduce((x, y) -> x + " " + y).orElse("-"), st);
        }
        System.out.printf("%ngaps: %d, thin or below level: %d%n%n", gaps, thin);
        System.out.println("load per story (competencies it is tagged for — a story used for 4+ is a single point of failure):");
        for (Story s : stories) System.out.printf("  %-4s %-48s %d  %s%n", s.id(), s.title(), s.comps().size(), s.comps());
    }

    static int lvl(String b) { return b.charAt(1) - '0'; }
    static List<Story> parse(String md) {
        List<Story> out = new ArrayList<>();
        for (String block : md.split("\n## ")) {
            Matcher h = Pattern.compile("^(S\\d+) (.+)").matcher(block.strip()); if (!h.find()) continue;
            Matcher c = Pattern.compile("- competencies: (.+)").matcher(block); Matcher l = Pattern.compile("- level: (B\\d)").matcher(block);
            if (!c.find() || !l.find()) continue;
            out.add(new Story(h.group(1), h.group(2), new LinkedHashSet<>(Arrays.stream(c.group(1).split(",")).map(String::strip).toList()), l.group(1)));
        }
        return out;
    }
}
