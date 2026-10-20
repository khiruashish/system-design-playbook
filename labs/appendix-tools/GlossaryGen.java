// Appendix tool — build the glossary page from glossary.tsv (term, chapter, definition) and CHECK it against the
// chapters: the term must actually appear in the chapter it links to; if it doesn't, suggest where it does appear.
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class GlossaryGen {
    public static void main(String[] a) throws Exception {
        Path docs = Path.of(a[0]); Path out = Path.of(a[1]);
        Map<String, Path> chapters = new TreeMap<>(); Map<String, String> text = new HashMap<>(), titles = new HashMap<>(), raw = new HashMap<>();
        try (Stream<Path> s = Files.walk(docs, 2)) { s.filter(p -> p.getFileName().toString().matches("\\d\\d-.*\\.md")).forEach(p -> {
            String n = p.getFileName().toString().substring(0, 2); chapters.put(n, p);
            try { String t = Files.readString(p); text.put(n, norm(t)); raw.put(n, t); titles.put(n, t.lines().findFirst().orElse("").replaceFirst("^# ", "").replaceFirst("^\\d+\\. ", "").replace(" ⭐", "")); }
            catch (Exception e) { throw new RuntimeException(e); } }); }
        List<String[]> rows = Files.readAllLines(Path.of("glossary.tsv")).stream().filter(l -> !l.isBlank()).map(l -> l.split("\t")).toList();
        int ok = 0; List<String> problems = new ArrayList<>();
        StringBuilder md = new StringBuilder(); char letter = 0;
        List<String[]> sorted = rows.stream().sorted(Comparator.comparing(r -> key(r[0]))).toList();
        for (String[] r : sorted) {
            String term = r[0], ch = r[1], def = r[2];
            if (!chapters.containsKey(ch)) { problems.add("no chapter " + ch + " for " + term); continue; }
            List<String> probes = r.length > 3 ? Arrays.asList(r[3].split("\\|")) : probes(term);
            boolean found = probes.stream().anyMatch(p -> has(ch, p, text, raw));
            if (found) ok++;
            else {
                String best = chapters.keySet().stream().max(Comparator.comparingInt(c -> probes.stream().mapToInt(p -> count(text.get(c), norm(p))).sum())).get();
                int hits = probes.stream().mapToInt(p -> count(text.get(best), norm(p))).sum();
                problems.add(String.format("%-36s not in Ch %s; most mentions in Ch %s (%d)", term, ch, best, hits));
            }
            char first = Character.toUpperCase(key(term).charAt(0)); if (!Character.isLetter(first)) first = '#';
            if (first != letter) { md.append(first == '#' ? "\n## 0–9\n\n" : "\n## " + first + "\n\n"); letter = first; }
            String link = "../" + docs.relativize(chapters.get(ch)).toString().replace('\\', '/');
            md.append("**").append(term).append("**\n:   ").append(def).append(" → [Ch ").append(ch).append(" ").append(titles.get(ch)).append("](").append(link).append(")\n\n");
        }
        Files.writeString(out, md.toString());
        System.out.printf("%d terms, %d found in their linked chapter, %d problems%n", rows.size(), ok, problems.size());
        problems.forEach(p -> System.out.println("  " + p));
        Map<String, Long> perPart = sorted.stream().collect(Collectors.groupingBy(r -> part(r[1]), TreeMap::new, Collectors.counting()));
        System.out.println("terms per part: " + perPart);
    }
    // hyphens, en/em dashes and spaces are equivalent; case-insensitive unless the probe starts with "="
    static String norm(String s) { return s.toLowerCase().replaceAll("[-\u2010-\u2015\\s]+", " "); }
    static boolean has(String ch, String p, Map<String, String> text, Map<String, String> raw) {
        return p.startsWith("=") ? raw.get(ch).matches("(?s).*\\b" + java.util.regex.Pattern.quote(p.substring(1)) + "\\b.*") : text.get(ch).contains(norm(p)); }
    static String key(String t) { return t.toLowerCase().replaceAll("^[^a-z0-9]+", ""); }
    static List<String> probes(String term) {
        String base = term.replaceAll("\\s*\\(.*\\)", "").toLowerCase();
        List<String> l = new ArrayList<>();
        for (String alt : base.split("\\s*/\\s*")) l.add(alt.trim());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\(([^)]*)\\)").matcher(term); if (m.find()) l.add(m.group(1).toLowerCase());
        return l;
    }
    static int count(String s, String p) { int n = 0, i = 0; while ((i = s.indexOf(p, i)) >= 0) { n++; i += p.length(); } return n; }
    static String part(String ch) { int n = Integer.parseInt(ch); return n <= 1 ? "0" : n <= 9 ? "I" : n <= 20 ? "II" : n <= 30 ? "III" : n <= 37 ? "IV" : n <= 55 ? "V" : n == 56 ? "VI" : "VII"; }
}
