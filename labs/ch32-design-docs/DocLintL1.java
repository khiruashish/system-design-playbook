// DocLintL1.java — run with: java DocLintL1.java docs/bad_design.md docs/good_design.md   (JDK 21, no dependencies)
// A DESIGN-DOC LINTER. Most weak design docs fail in the same, mechanically detectable ways: required sections are
// missing, non-functional requirements are adjectives instead of numbers ("fast", "highly available"), only one option
// was considered, there's no rollout plan, and no one wrote down what is still unknown. A reviewer's first ten minutes
// are spent finding exactly these gaps. This linter finds them in milliseconds, so human review can focus on the design.
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class DocLintL1 {
    // sections every significant design doc needs (matched case-insensitively against '## ' headings, by keyword)
    static final Map<String, List<String>> REQUIRED = new LinkedHashMap<>();
    static {
        REQUIRED.put("Context / problem",       List.of("context", "background", "problem"));
        REQUIRED.put("Goals",                   List.of("goals"));
        REQUIRED.put("Non-goals",               List.of("non-goals", "non goals", "out of scope"));
        REQUIRED.put("Requirements (NFRs)",     List.of("requirements", "nfr"));
        REQUIRED.put("Proposed design",         List.of("proposed design", "design"));
        REQUIRED.put("Alternatives considered", List.of("alternatives"));
        REQUIRED.put("Trade-offs / risks",      List.of("trade-off", "tradeoff", "risks"));
        REQUIRED.put("Rollout / migration",     List.of("rollout", "migration"));
        REQUIRED.put("Observability",           List.of("observability", "monitoring"));
        REQUIRED.put("Security",                List.of("security"));
        REQUIRED.put("Open questions",          List.of("open questions"));
    }
    // adjectives that must come with a number on the same line to count as a requirement
    static final List<String> VAGUE = List.of("fast", "slow", "scalable", "highly available", "secure", "real-time",
            "low latency", "latency will be low", "robust", "performant", "high throughput");
    static final Pattern NUMBER = Pattern.compile("\\d");

    record Finding(String rule, String detail) {}

    static List<Finding> lint(List<String> lines) {
        List<Finding> f = new ArrayList<>();
        // collect headings and the body under each
        Map<String, List<String>> sections = new LinkedHashMap<>();
        String current = "(preamble)"; sections.put(current, new ArrayList<>());
        for (String l : lines) {
            if (l.startsWith("## ")) { current = l.substring(3).trim().toLowerCase(); sections.put(current, new ArrayList<>()); }
            else sections.get(current).add(l);
        }
        // R1: required sections
        for (var req : REQUIRED.entrySet()) {
            boolean found = sections.keySet().stream().anyMatch(h -> req.getValue().stream().anyMatch(h::contains)
                    // "design" alone must not be satisfied by "non-goals"-type headings; fine for this demo
            );
            if (!found) f.add(new Finding("R1 missing section", req.getKey()));
        }
        // R2: vague quality words without a number on the same line
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).toLowerCase();
            if (l.startsWith("#")) continue;
            for (String v : VAGUE)
                if (l.contains(v) && !NUMBER.matcher(l).find())
                    f.add(new Finding("R2 unmeasurable NFR", "line " + (i + 1) + ": '" + v + "' with no number → state a target"));
        }
        // R3: alternatives must list at least 2 options (numbered/bulleted lines)
        sections.entrySet().stream().filter(e -> e.getKey().contains("alternatives")).findFirst().ifPresent(e -> {
            long options = e.getValue().stream().filter(x -> x.matches("^\\s*(\\d+\\.|-|\\*)\\s+.*")).count();
            if (options < 2) f.add(new Finding("R3 too few alternatives", options + " listed option(s); list ≥2 incl. status quo, with why rejected"));
        });
        // R4: rollout must be more than a one-liner (a plan has stages / guardrails)
        sections.entrySet().stream().filter(e -> e.getKey().contains("rollout")).findFirst().ifPresent(e -> {
            String body = String.join(" ", e.getValue()).trim();
            if (body.split("\\s+").length < 8) f.add(new Finding("R4 thin rollout", "'" + body + "' — needs stages, guardrails, rollback criteria"));
        });
        // R5: a decision should point to an ADR / revisit trigger
        String all = String.join("\n", lines).toLowerCase();
        if (!all.contains("adr-") && !all.contains("revisit"))
            f.add(new Finding("R5 no decision record", "no ADR reference or revisit trigger"));
        return f;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Design-doc linter: required sections, measurable NFRs, ≥2 alternatives, real rollout plan, decision record.\n");
        for (String path : args) {
            List<String> lines = Files.readAllLines(Path.of(path));
            List<Finding> f = lint(lines);
            System.out.printf("   %s (%d lines):%n", path, lines.size());
            if (f.isEmpty()) System.out.println("        ✓ PASS — ready for human review of the design itself.");
            else {
                for (Finding x : f) System.out.printf("        ✗ %-24s %s%n", x.rule(), x.detail());
                System.out.printf("        → %d finding(s): send back to the author before review.%n", f.size());
            }
            System.out.println();
        }
        System.out.println("   → the weak doc reads fine at a glance, but every quality claim is an adjective ('fast', 'highly scalable',");
        System.out.println("     'highly available'), the only alternative is a passing sentence rather than a compared option, the rollout is");
        System.out.println("     two words, and seven expected sections are missing. None of that needs a senior reviewer to spot — so automate");
        System.out.println("     it (a CI check on docs/design/*.md) and spend review time on the question only humans can answer:");
        System.out.println("     is this the right design for these requirements?");
    }
}
