// SearchL1.java — run with: /opt/jdk25/bin/java SearchL1.java     (JDK 22+ launches multi-file programs: uses Catalog.java)
// An inverted index with BM25 over 20,000 grocery products, built from scratch, evaluated on 20 judged queries
// (English, Hinglish, Devanagari, typos, units). Part A adds analyzer stages one at a time and measures nDCG@10.
// Part B compares index lookup with a full scan. Part C adds business ranking (stock, popularity) and facets.
import java.util.*;
import java.util.stream.*;

public class SearchL1 {
    record Analyzer(String label, boolean units, boolean stem, boolean phonetic, boolean synonyms, int fuzzy, boolean typeField) {
        // fuzzy: 0 = off, 1 = naive (any unknown term ≥ 4 chars, full weight), 2 = guarded (≥ 5 chars, score × 0.5)
        List<String> terms(String text) {
            List<String> ts = Catalog.tokenize(text);
            if (units) ts = Catalog.units(ts);
            List<String> out = new ArrayList<>();
            for (String t : ts) { if (stem) t = Catalog.stem(t); if (phonetic) t = Catalog.phonetic(t); out.add(t); }
            return out;
        }
    }

    static final class Index {
        final Analyzer an; final List<Catalog.Product> docs;
        final Map<String, Map<Integer, int[]>> postings = new HashMap<>();   // term → doc → tf per field {name, other, type}
        final int[][] len; final double[] avg = new double[3];
        static final double K1 = 1.2, B = 0.75; static final double[] BOOST = {2.0, 1.0, 3.0};

        Index(Analyzer an, List<Catalog.Product> docs) {
            this.an = an; this.docs = docs; len = new int[docs.size()][3];
            for (int d = 0; d < docs.size(); d++) {
                var p = docs.get(d);
                // field 2: the product type, a curated attribute ("potato" for vegetables, "chips" for chips)
                String[] fields = {p.name(), p.brand() + " " + p.category() + " " + p.size(), an.typeField() ? p.type().replace('-', ' ') : ""};
                for (int f = 0; f < 3; f++) {
                    List<String> ts = an.terms(fields[f]); len[d][f] = ts.size(); avg[f] += ts.size();
                    for (String t : ts) postings.computeIfAbsent(t, k -> new HashMap<>()).computeIfAbsent(d, k -> new int[3])[f]++;
                }
            }
            for (int f = 0; f < 3; f++) avg[f] = Math.max(avg[f] / docs.size(), 1e-9);
        }

        /** Expansions of one query term: itself, its synonyms, and (if unknown) dictionary terms within edit distance 1. */
        List<String> expand(String t) {
            Set<String> ex = new LinkedHashSet<>(List.of(t));
            if (an.synonyms()) ex.addAll(Catalog.SYNONYMS.getOrDefault(t, List.of()));
            if (an.fuzzy() > 0 && !postings.containsKey(t) && t.length() >= (an.fuzzy() == 1 ? 4 : 5) && !Character.isDigit(t.charAt(0)))
                for (String cand : postings.keySet()) if (Math.abs(cand.length() - t.length()) <= 1 && editDistance(cand, t) <= 1) ex.add(cand);
            return new ArrayList<>(ex);
        }

        double idf(String term) { int df = postings.getOrDefault(term, Map.of()).size(); return Math.log(1 + (docs.size() - df + 0.5) / (df + 0.5)); }

        /** BM25F-lite: per query term, the best-scoring expansion (so synonyms don't double count). */
        Map<Integer, Double> score(String query) {
            Map<Integer, Double> scores = new HashMap<>();
            for (String qt : an.terms(query)) {
                Map<Integer, Double> best = new HashMap<>();
                for (String t : expand(qt)) {
                    double idf = idf(t) * (an.fuzzy() == 2 && !t.equals(qt) && !Catalog.SYNONYMS.getOrDefault(qt, List.of()).contains(t) ? 0.5 : 1.0);
                    for (var e : postings.getOrDefault(t, Map.of()).entrySet()) {
                        double s = 0;
                        for (int f = 0; f < 3; f++) {
                            int tf = e.getValue()[f]; if (tf == 0) continue;
                            double norm = tf * (K1 + 1) / (tf + K1 * (1 - B + B * len[e.getKey()][f] / avg[f]));
                            s += BOOST[f] * idf * norm;
                        }
                        best.merge(e.getKey(), s, Math::max);
                    }
                }
                best.forEach((d, s) -> scores.merge(d, s, Double::sum));
            }
            return scores;
        }

        List<Catalog.Product> search(String q, int k) {
            return score(q).entrySet().stream().sorted(Map.Entry.<Integer, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .limit(k).map(e -> docs.get(e.getKey())).toList();
        }
    }

    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    public static void main(String[] args) {
        List<Catalog.Product> docs = Catalog.products(20_000);
        List<Analyzer> analyzers = List.of(
            new Analyzer("lowercase + split", false, false, false, false, 0, false),
            new Analyzer("+ units (5kg, 1 litre) + plural stem", true, true, false, false, 0, false),
            new Analyzer("+ Devanagari + phonetic (aata/atta/आटा)", true, true, true, false, 0, false),
            new Analyzer("+ synonyms (curd↔dahi, milk↔doodh …)", true, true, true, true, 0, false),
            new Analyzer("+ fuzzy, naive (edit distance 1, ≥ 4 chars)", true, true, true, true, 1, false),
            new Analyzer("  fuzzy guarded instead (≥ 5 chars, score ×0.5)", true, true, true, true, 2, false),
            new Analyzer("+ product-type field, boost 3", true, true, true, true, 2, true));

        System.out.println("A. 20 judged queries over 20,000 products: analyzer stages vs relevance (nDCG@10, BM25, name boost 2)");
        System.out.printf("   %-49s %9s %13s   %s%n", "analyzer", "nDCG@10", "zero results", "queries still failing (nDCG < 0.5)");
        Index last = null;
        for (Analyzer an : analyzers) {
            Index idx = new Index(an, docs); last = idx;
            double sum = 0; int zero = 0; List<String> failing = new ArrayList<>();
            for (var q : Catalog.QUERIES) {
                var res = idx.search(q.query(), 10);
                double n = Catalog.ndcg(q, res, docs, 10); sum += n;
                if (res.isEmpty()) zero++;
                if (n < 0.5) failing.add(q.query());
            }
            System.out.printf("   %-49s %9.3f %13d   %s%n", an.label(), sum / Catalog.QUERIES.size(), zero, failing.isEmpty() ? "-" : String.join(", ", failing));
        }
        final Index fin = last;
        System.out.printf("   analyzed forms: %s%n", Stream.of("आटा", "aata", "Atta", "दूध", "doodh", "मूंग", "moong", "1 litre", "5kg")
                .map(s -> s + "→" + String.join(" ", fin.an.terms(s))).collect(Collectors.joining(", ")));

        System.out.println("B. Inverted index vs scanning every product (final analyzer, 20 queries × 50 rounds)");
        Index idx = last;
        long t0 = System.nanoTime(); int hits = 0;
        for (int r = 0; r < 50; r++) for (var q : Catalog.QUERIES) hits += idx.search(q.query(), 10).size();
        double indexUs = (System.nanoTime() - t0) / 1e3 / (50 * Catalog.QUERIES.size());
        t0 = System.nanoTime();
        for (int r = 0; r < 5; r++) for (var q : Catalog.QUERIES) {
            var qt = idx.an.terms(q.query()).stream().flatMap(t -> idx.expand(t).stream()).collect(Collectors.toSet());
            var scored = new ArrayList<Integer>();
            for (int d = 0; d < docs.size(); d++) {                                         // re-analyse every document per query
                var dt = idx.an.terms(docs.get(d).text());
                for (String t : dt) if (qt.contains(t)) { scored.add(d); break; }
            }
            hits += scored.size();
        }
        double scanUs = (System.nanoTime() - t0) / 1e3 / (5 * Catalog.QUERIES.size());
        long postings = idx.postings.values().stream().mapToLong(Map::size).sum();
        System.out.printf("   index: %,d terms, %,d postings | per query: index %,.0f µs, full scan %,.0f µs (%.0fx)%n",
                idx.postings.size(), postings, indexUs, scanUs, scanUs / indexUs);

        System.out.println("C. Business ranking and facets (final analyzer)");
        int oosTop3 = 0, oosTop3Boosted = 0; double ndcgText = 0, ndcgBoosted = 0;
        for (var q : Catalog.QUERIES) {
            var scores = idx.score(q.query());
            var byText = scores.entrySet().stream().sorted(Map.Entry.<Integer, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .map(e -> docs.get(e.getKey())).limit(10).toList();
            // function score: text relevance × (1 + 0.1·log(1 + popularity)), out-of-stock demoted ×0.3
            var boosted = scores.entrySet().stream().sorted(Comparator.comparingDouble((Map.Entry<Integer, Double> e) -> {
                        var p = docs.get(e.getKey());
                        return -e.getValue() * (1 + 0.1 * Math.log1p(p.popularity())) * (p.inStock() ? 1 : 0.3); }).thenComparing(Map.Entry.comparingByKey()))
                    .map(e -> docs.get(e.getKey())).limit(10).toList();
            oosTop3 += (int) byText.stream().limit(3).filter(p -> !p.inStock()).count();
            oosTop3Boosted += (int) boosted.stream().limit(3).filter(p -> !p.inStock()).count();
            ndcgText += Catalog.ndcg(q, byText, docs, 10); ndcgBoosted += Catalog.ndcg(q, boosted, docs, 10);
        }
        System.out.printf("   text only       : nDCG@10 %.3f, out-of-stock in top 3: %d of 60%n", ndcgText / 20, oosTop3);
        System.out.printf("   + stock & popularity boost: nDCG@10 %.3f, out-of-stock in top 3: %d of 60%n", ndcgBoosted / 20, oosTop3Boosted);

        var matches = idx.score("masala").keySet().stream().map(docs::get).toList();
        var byCat = matches.stream().collect(Collectors.groupingBy(Catalog.Product::category, TreeMap::new, Collectors.counting()));
        var byBrand = matches.stream().filter(p -> p.category().equals("Masala")).collect(Collectors.groupingBy(Catalog.Product::brand, Collectors.counting()));
        var top3 = byBrand.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(3).map(e -> e.getKey() + " " + e.getValue()).toList();
        System.out.printf("   facets for \"masala\" (%,d matches): category %s; after filter category=Masala, top brands %s%n", matches.size(), byCat, top3);
    }
}
