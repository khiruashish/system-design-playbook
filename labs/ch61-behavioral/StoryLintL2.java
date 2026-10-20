// L2 — Story lint: the mechanical problems interviewers notice in behavioural answers.
// Checks: spoken length, how much is set-up vs action, "we" vs "I" in the action, numbers in the result, a specific learning.
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class StoryLintL2 {
    static final int WPM = 140;   // typical interview speaking pace (assumption)

    public static void main(String[] a) throws Exception {
        System.out.printf("%-4s %-40s %5s %6s %8s %8s %6s %7s  %s%n", "id", "title", "words", "spoken", "set-up", "I:we", "nums", "learn", "flags");
        for (String f : a) {
            System.out.println("— " + f);
            for (String block : Files.readString(Path.of(f)).split("\n## ")) {
                Matcher h = Pattern.compile("^(S\\d+) (.+)").matcher(block.strip()); if (!h.find()) continue;
                Map<String, String> p = new HashMap<>();
                for (String k : List.of("situation", "task", "action", "result", "learning")) {
                    Matcher m = Pattern.compile("- " + k + ": (.+)").matcher(block); p.put(k, m.find() ? m.group(1) : ""); }
                int sit = words(p.get("situation")) + words(p.get("task")), act = words(p.get("action")), res = words(p.get("result")), lrn = words(p.get("learning"));
                int total = sit + act + res + lrn;
                int i = count(p.get("action"), "\\b(I|I'm|I've|my|me)\\b"), we = count(p.get("action"), "\\b(we|We|our|Our|us)\\b");
                int nums = count(p.get("result"), "\\b\\d[\\d,.]*%?|\\b(two|three|four|five|six|seven|eight|nine|ten|half|doubled|halved)\\b");
                List<String> flags = new ArrayList<>();
                double spoken = total * 60.0 / WPM;
                if (spoken < 60) flags.add("too short (<1 min)");
                if (spoken > 180) flags.add("too long (>3 min)");
                if ((double) sit / total > 0.35) flags.add("set-up >35%");
                if (we > i) flags.add("action says 'we' more than 'I'");
                if (nums == 0) flags.add("result has no numbers");
                if (lrn < 10) flags.add("learning too thin");
                System.out.printf("%-4s %-40.40s %5d %5.0fs %7.0f%% %4d:%-3d %6d %6dw  %s%n", h.group(1), h.group(2), total, spoken,
                        100.0 * sit / total, i, we, nums, lrn, flags.isEmpty() ? "ok" : String.join("; ", flags));
            }
        }
        System.out.println("\nspoken = words at " + WPM + " wpm; set-up = situation + task share; I:we counted in the action only; nums = figures in the result.");
    }
    static int words(String s) { return s.isBlank() ? 0 : s.trim().split("\\s+").length; }
    static int count(String s, String re) { Matcher m = Pattern.compile(re).matcher(s); int n = 0; while (m.find()) n++; return n; }
}
