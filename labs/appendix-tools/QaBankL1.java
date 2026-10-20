// QaBankL1.java — run with: java QaBankL1.java <docs-dir> <out-dir>   (JDK 21, no dependencies)
// BUILD THE QUESTION BANK FROM THE HANDBOOK ITSELF. Parses every chapter's "??? question" blocks (§8 of each chapter),
// extracting chapter, level band, the ⭐ high-frequency marker, the question and its 30-second answer. Writes:
//   bank_starred.md — every ⭐ question with its 30-second answer, grouped by part and chapter (the drill bank)
//   index_all.md    — every question title, grouped by part and chapter, with links back to the chapter
// and prints what it found, so the bank can never drift from the chapters: re-run it after editing any chapter.
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class QaBankL1 {
    static String fixLinks(String s, String dir) { return s.replaceAll("\\]\\(((?:\\d\\d-|i\\d\\d|index)[^)/]*\\.md)", "](../" + dir + "/$1"); }   // same-folder links, incl. industry pages (i06-x.md, index.md)
    record Q(int ch, String chapterTitle, String file, String part, int num, String band, boolean star, String text, String answer) {}
    static final Pattern QP = Pattern.compile("^\\?\\?\\? question \"Q(\\d+) \\[(B\\d)\\] (⭐ )?(.*)\"\\s*$");

    public static void main(String[] args) throws Exception {
        Path docs = Paths.get(args.length > 0 ? args[0] : "docs"), out = Paths.get(args.length > 1 ? args[1] : ".");
        List<Q> all = new ArrayList<>(); Map<String, String> partNames = Map.of("00-orientation", "Part 0 · Orientation", "01-foundations", "Part I · Foundations",
                "02-distributed-core", "Part II · Distributed Core", "03-production", "Part III · Production", "04-architect", "Part IV · Architect",
                "05-case-studies", "Part V · Case Studies", "06-capstone", "Part VI · Capstone", "07-interview", "Part VII · Interview", "08-industry", "Part VIII · Industry Designs");
        List<Path> files; try (Stream<Path> s = Files.walk(docs, 2)) { files = s.filter(p -> p.getFileName().toString().matches("(\\d\\d|i\\d\\db?)-.*\\.md")).sorted().toList(); }
        for (Path f : files) {
            List<String> lines = Files.readAllLines(f); String title = lines.stream().filter(l -> l.startsWith("# ")).findFirst().orElse("# ?").substring(2).trim();
            String fn = f.getFileName().toString(); int ch = fn.startsWith("i")  /* industry I01..I18 → 101..118 */ ? 100 + Integer.parseInt(fn.substring(1, 3)) : Integer.parseInt(fn.substring(0, 2));
            String part = partNames.getOrDefault(f.getParent().getFileName().toString(), "?");
            for (int i = 0; i < lines.size(); i++) { Matcher m = QP.matcher(lines.get(i)); if (!m.matches()) continue;
                String ans = ""; for (int j = i + 1; j < Math.min(lines.size(), i + 8); j++) { String l = lines.get(j).trim(); if (l.startsWith("**30-second answer:**")) { ans = l.substring("**30-second answer:**".length()).trim();
                        if (ans.isEmpty()) { StringBuilder b = new StringBuilder(); for (int k = j + 1; k < lines.size(); k++) { String x = lines.get(k).trim(); if (x.isEmpty() || x.startsWith("**")) break; b.append(b.length() > 0 ? "\n    " : "").append(x); } ans = b.toString(); }   // bullet-list answers
                        break; } }
                String dir = docs.relativize(f).getParent().toString();
                // same-folder links like (28-x.md) must point at ../<folder>/28-x.md once the text moves to another page
                all.add(new Q(ch, title, docs.relativize(f).toString(), part, Integer.parseInt(m.group(1)), m.group(2), m.group(3) != null, fixLinks(m.group(4), dir), fixLinks(ans, dir))); }
        }
        // bank of starred questions
        StringBuilder bank = new StringBuilder(), index = new StringBuilder(); String lastPart = "", lastCh = "";
        for (Q q : all) { if (!q.star()) continue;
            if (!q.part().equals(lastPart)) { bank.append("\n### ").append(q.part()).append("\n"); lastPart = q.part(); lastCh = ""; }
            if (!q.chapterTitle().equals(lastCh)) { bank.append("\n**[").append(q.chapterTitle()).append("](../").append(q.file()).append(")**\n\n"); lastCh = q.chapterTitle(); }
            bank.append("??? example \"").append(q.band()).append(" · ").append(q.text().replace("\"", "'")).append("\"\n    ").append(q.answer().isEmpty() ? "_See the chapter._" : q.answer()).append("\n\n"); }
        lastPart = ""; lastCh = "";
        for (Q q : all) {
            if (!q.part().equals(lastPart)) { index.append("\n## ").append(q.part()).append("\n"); lastPart = q.part(); lastCh = ""; }
            if (!q.chapterTitle().equals(lastCh)) { index.append("\n### [").append(q.chapterTitle()).append("](../").append(q.file()).append(")\n\n"); lastCh = q.chapterTitle(); }
            index.append("- **Q").append(q.num()).append("** `").append(q.band()).append("`").append(q.star() ? " ⭐" : "").append(" ").append(q.text()).append("\n"); }
        Files.writeString(out.resolve("bank_starred.md"), bank.toString()); Files.writeString(out.resolve("index_all.md"), index.toString());
        long missing = all.stream().filter(q -> q.star() && q.answer().isEmpty()).count();
        if (System.getenv("SHOWMISSING") != null) all.stream().filter(q -> q.star() && q.answer().isEmpty()).forEach(q -> System.out.println("MISSING " + q.file() + " Q" + q.num()));
        System.out.printf("parsed %d chapter files → %,d questions, %,d ⭐ (%d ⭐ without a 30-second answer)%n", files.size(), all.size(), all.stream().filter(Q::star).count(), missing);
        System.out.printf("wrote bank_starred.md (%,d words) and index_all.md (%,d words)%n", words(bank), words(index));
        // L2: coverage by part × band
        System.out.printf("%n   %-30s %6s %6s %6s %6s %8s %8s%n", "part", "B1", "B2", "B3", "B4", "total", "⭐");
        for (String p : partNames.values().stream().sorted(Comparator.comparing(QaBankL1::partOrder)).toList()) {
            List<Q> qs = all.stream().filter(q -> q.part().equals(p)).toList(); if (qs.isEmpty()) continue;
            System.out.printf("   %-30s %6d %6d %6d %6d %8d %8d%n", p, cnt(qs, "B1"), cnt(qs, "B2"), cnt(qs, "B3"), cnt(qs, "B4"), qs.size(), qs.stream().filter(Q::star).count());
        }
        System.out.printf("   %-30s %6d %6d %6d %6d %8d %8d%n", "ALL", cnt(all, "B1"), cnt(all, "B2"), cnt(all, "B3"), cnt(all, "B4"), all.size(), all.stream().filter(Q::star).count());
        // per-chapter format check: 20 questions each
        List<String> off = new ArrayList<>(); Map<Integer, Long> perCh = all.stream().collect(Collectors.groupingBy(Q::ch, TreeMap::new, Collectors.counting()));
        perCh.forEach((c, n) -> { if (n < 20) off.add((c > 100 ? "I" + (c - 100) : "Ch " + c) + ": " + n); });
        System.out.printf("%n   chapters with Q&A: %d; chapters with fewer than 20 questions: %s%n", perCh.size(), off.isEmpty() ? "none" : String.join(", ", off));
    }
    static int partOrder(String p) { return List.of("Part 0 · Orientation", "Part I · Foundations", "Part II · Distributed Core", "Part III · Production", "Part IV · Architect", "Part V · Case Studies", "Part VI · Capstone", "Part VII · Interview", "Part VIII · Industry Designs").indexOf(p); }
    static long cnt(List<Q> qs, String b) { return qs.stream().filter(q -> q.band().equals(b)).count(); }
    static int words(CharSequence s) { return s.toString().split("\\s+").length; }
}
