// DrillL3.java — run with: java DrillL3.java <docs-dir> <band> [seed]   (JDK 21, no dependencies)
// A 45-MINUTE DRILL FROM THE BANK. Picks 12 questions for a target band from the handbook's own Q&A (parsed live from
// the chapters): at most one per chapter, spread across parts, ⭐ questions weighted 3×, questions from the band itself
// plus one band below for warm-up. Deterministic for a given seed (use the date), so a study group gets the same set.
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class DrillL3 {
    record Q(int ch, String file, String part, String band, boolean star, String text) {}
    public static void main(String[] args) throws Exception {
        Path docs = Paths.get(args[0]); String band = args.length > 1 ? args[1] : "B3"; long seed = args.length > 2 ? Long.parseLong(args[2]) : 20260930L;
        Pattern qp = Pattern.compile("^\\?\\?\\? question \"Q(\\d+) \\[(B\\d)\\] (⭐ )?(.*)\"\\s*$"); List<Q> all = new ArrayList<>();
        try (Stream<Path> s = Files.walk(docs, 2)) { for (Path f : s.filter(p -> p.getFileName().toString().matches("\\d\\d-.*\\.md")).sorted().toList())
            for (String l : Files.readAllLines(f)) { Matcher m = qp.matcher(l); if (m.matches()) all.add(new Q(Integer.parseInt(f.getFileName().toString().substring(0, 2)), f.getFileName().toString(), f.getParent().getFileName().toString(), m.group(2), m.group(3) != null, m.group(4))); } }
        int b = band.charAt(1) - '0'; String below = "B" + Math.max(1, b - 1);
        List<Q> pool = all.stream().filter(q -> q.band().equals(band) || q.band().equals(below)).toList();
        Random r = new Random(seed); List<Q> picked = new ArrayList<>(); Set<Integer> usedCh = new HashSet<>(); Map<String, Integer> perPart = new HashMap<>();
        int warm = 0;
        while (picked.size() < 12) {
            double total = 0; List<Q> cand = new ArrayList<>(); List<Double> w = new ArrayList<>();
            for (Q q : pool) { if (usedCh.contains(q.ch())) continue; if (perPart.getOrDefault(q.part(), 0) >= 3) continue; if (q.band().equals(below) && warm >= 2) continue; if (!q.band().equals(below) && picked.size() < 2 && warm < 2 && b > 1) continue;
                double wt = q.star() ? 3 : 1; cand.add(q); w.add(wt); total += wt; }
            if (cand.isEmpty()) break;
            double x = r.nextDouble() * total; int i = 0; while (x > w.get(i)) { x -= w.get(i); i++; }
            Q q = cand.get(i); picked.add(q); usedCh.add(q.ch()); perPart.merge(q.part(), 1, Integer::sum); if (q.band().equals(below)) warm++;
        }
        System.out.printf("Drill for %s (seed %d): %d questions from %,d in the pool; answer each aloud in ≤ 3 minutes, then check the chapter.%n%n", band, seed, picked.size(), pool.size());
        int n = 1; for (Q q : picked) System.out.printf("   %2d. [%s]%s %s   → Ch %02d (%s)%n", n++, q.band(), q.star() ? " ⭐" : "", q.text(), q.ch(), q.part());
        System.out.printf("%n   parts covered: %s%n", picked.stream().map(Q::part).distinct().sorted().collect(Collectors.joining(", ")));
    }
}
