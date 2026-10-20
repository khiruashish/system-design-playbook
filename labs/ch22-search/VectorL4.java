// VectorL4.java — run with: /opt/jdk25/bin/java -Xmx3g VectorL4.java      (uses Catalog.java and SearchL1.java)
// Part A: approximate nearest neighbours. An HNSW graph written from scratch vs exact brute force over 100,000 vectors
//         (64 dimensions, clustered): recall@10, latency and build cost as efSearch grows.
// Part B: hybrid retrieval. BM25 (L1's final analyzer) vs vector search vs Reciprocal Rank Fusion, on the 20 lexical judged
//         queries and 8 "intent" queries ("healthy breakfast", "something for chai"). The embeddings here are a TOY:
//         hand-written concept vectors per product type, standing in for a learned embedding model.
import java.util.*;
import java.util.stream.*;

public class VectorL4 {
    // ======================= Part A: HNSW =======================
    static final class Hnsw {
        final float[][] v; final int M, M0, efC; final double mL; final boolean heuristic;
        final int[][][] links; final int[][] counts; final int[] levels;
        int entry = -1, maxLevel = -1, size = 0;
        final int[] visited; int stamp = 0;
        final Random rnd = new Random(7);

        Hnsw(float[][] v, int M, int efC, boolean heuristic) {
            this.v = v; this.heuristic = heuristic; this.M = M; this.M0 = 2 * M; this.efC = efC; this.mL = 1 / Math.log(M);
            links = new int[v.length][][]; counts = new int[v.length][]; levels = new int[v.length]; visited = new int[v.length];
        }
        static float dot(float[] a, float[] b) { float s = 0; for (int i = 0; i < a.length; i++) s += a[i] * b[i]; return s; }
        float dist(int a, float[] q) { return -dot(v[a], q); }                                   // smaller is closer (unit vectors)

        void insert(int id) {
            int level = (int) Math.floor(-Math.log(1 - rnd.nextDouble()) * mL);
            levels[id] = level; links[id] = new int[level + 1][]; counts[id] = new int[level + 1];
            for (int l = 0; l <= level; l++) links[id][l] = new int[(l == 0 ? M0 : M) + 1];
            size++;
            if (entry < 0) { entry = id; maxLevel = level; return; }
            float[] q = v[id]; int ep = entry;
            for (int l = maxLevel; l > level; l--) ep = greedy(q, ep, l);
            for (int l = Math.min(level, maxLevel); l >= 0; l--) {
                int[] cand = searchLayer(q, ep, efC, l);                                      // sorted closest first
                int[] chosen = select(q, cand, M);
                for (int c : chosen) { connect(id, c, l); connect(c, id, l); }
                ep = cand[0];
            }
            if (level > maxLevel) { maxLevel = level; entry = id; }
        }

        void connect(int from, int to, int l) {
            int max = l == 0 ? M0 : M;
            int[] ns = links[from][l];
            if (counts[from][l] < max) { ns[counts[from][l]++] = to; return; }
            ns[max] = to;                                                                     // over capacity: re-select max neighbours
            Integer[] idx = new Integer[max + 1];
            for (int i = 0; i <= max; i++) idx[i] = ns[i];
            float[] base = v[from];
            Arrays.sort(idx, Comparator.comparingDouble(n -> -dot(v[n], base)));
            int[] sorted = Arrays.stream(idx).mapToInt(Integer::intValue).toArray();
            int[] keep = select(base, sorted, max);
            System.arraycopy(keep, 0, ns, 0, keep.length); counts[from][l] = keep.length;
        }

        /** Simple: the m closest. Heuristic (HNSW paper, alg. 4): skip a candidate that is closer to an already chosen
         *  neighbour than to the base, so links spread in different directions; fill up with the pruned ones. */
        int[] select(float[] base, int[] sortedCand, int m) {
            if (!heuristic) return Arrays.copyOf(sortedCand, Math.min(m, sortedCand.length));
            List<Integer> r = new ArrayList<>(), pruned = new ArrayList<>();
            for (int c : sortedCand) {
                if (r.size() >= m) break;
                float dq = -dot(v[c], base); boolean good = true;
                for (int x : r) if (-dot(v[c], v[x]) < dq) { good = false; break; }
                if (good) r.add(c); else pruned.add(c);
            }
            for (int i = 0; r.size() < m && i < pruned.size(); i++) r.add(pruned.get(i));
            return r.stream().mapToInt(Integer::intValue).toArray();
        }

        int greedy(float[] q, int ep, int l) {
            boolean changed = true; float best = dist(ep, q);
            while (changed) {
                changed = false;
                for (int i = 0; i < counts[ep][l]; i++) { int n = links[ep][l][i]; float d = dist(n, q); if (d < best) { best = d; ep = n; changed = true; } }
            }
            return ep;
        }

        int[] searchLayer(float[] q, int ep, int ef, int l) {
            stamp++;
            PriorityQueue<float[]> cand = new PriorityQueue<>((a, b) -> Float.compare(a[0], b[0]));      // closest first
            PriorityQueue<float[]> res = new PriorityQueue<>((a, b) -> Float.compare(b[0], a[0]));       // farthest first
            float d0 = dist(ep, q); cand.add(new float[]{d0, ep}); res.add(new float[]{d0, ep}); visited[ep] = stamp;
            while (!cand.isEmpty()) {
                float[] c = cand.poll();
                if (c[0] > res.peek()[0] && res.size() >= ef) break;
                int node = (int) c[1];
                for (int i = 0; i < counts[node][l]; i++) {
                    int n = links[node][l][i];
                    if (visited[n] == stamp) continue;
                    visited[n] = stamp;
                    float d = dist(n, q);
                    if (res.size() < ef || d < res.peek()[0]) { cand.add(new float[]{d, n}); res.add(new float[]{d, n}); if (res.size() > ef) res.poll(); }
                }
            }
            int[] out = new int[res.size()];
            for (int i = out.length - 1; i >= 0; i--) out[i] = (int) res.poll()[1];
            return out;
        }

        int[] search(float[] q, int k, int ef) {
            int ep = entry;
            for (int l = maxLevel; l > 0; l--) ep = greedy(q, ep, l);
            int[] r = searchLayer(q, ep, Math.max(ef, k), 0);
            return Arrays.copyOf(r, Math.min(k, r.length));
        }
    }

    static float[][] clustered(int n, int dim, int clusters, long seed) {
        Random r = new Random(seed), c = new Random(1);
        float[][] centers = new float[clusters][dim];
        for (float[] ce : centers) for (int i = 0; i < dim; i++) ce[i] = (float) c.nextGaussian();
        float[][] out = new float[n][dim];
        for (int j = 0; j < n; j++) {
            float[] ce = centers[r.nextInt(clusters)];
            double norm = 0;
            for (int i = 0; i < dim; i++) { out[j][i] = ce[i] + (float) (r.nextGaussian() * 0.6); norm += out[j][i] * out[j][i]; }
            float inv = (float) (1 / Math.sqrt(norm)); for (int i = 0; i < dim; i++) out[j][i] *= inv;
        }
        return out;
    }

    static int[] exactTopK(float[][] data, float[] q, int k) {
        PriorityQueue<float[]> pq = new PriorityQueue<>((a, b) -> Float.compare(a[0], b[0]));      // min-heap of scores
        for (int i = 0; i < data.length; i++) {
            float s = Hnsw.dot(data[i], q);
            if (pq.size() < k) pq.add(new float[]{s, i}); else if (s > pq.peek()[0]) { pq.poll(); pq.add(new float[]{s, i}); }
        }
        return pq.stream().mapToInt(a -> (int) a[1]).toArray();
    }

    // ======================= Part B: hybrid =======================
    static final String[] DIMS = {"breakfast", "dairy", "snack", "cooking", "spice", "cleaning", "hygiene", "beverage", "sweet", "protein",
            "vegetable", "fruit", "grain", "lentil", "party", "healthy", "fat", "teatime"};
    static final Map<String, String> TYPE_CONCEPTS = Map.ofEntries(
        Map.entry("atta", "grain cooking"), Map.entry("basmati", "grain cooking"), Map.entry("sona-masoori", "grain cooking"),
        Map.entry("toor-dal", "lentil protein cooking"), Map.entry("moong-dal", "lentil protein healthy cooking"), Map.entry("chana-dal", "lentil protein cooking"),
        Map.entry("sugar", "sweet cooking teatime"), Map.entry("salt", "cooking spice:0.5"), Map.entry("jaggery", "sweet healthy teatime:0.5"),
        Map.entry("sunflower-oil", "fat cooking"), Map.entry("mustard-oil", "fat cooking"), Map.entry("groundnut-oil", "fat cooking"),
        Map.entry("ghee", "fat dairy cooking"), Map.entry("milk", "dairy breakfast teatime beverage:0.5"), Map.entry("curd", "dairy healthy protein:0.5"),
        Map.entry("paneer", "dairy protein"), Map.entry("butter", "dairy breakfast fat:0.5"), Map.entry("tomato", "vegetable cooking"),
        Map.entry("onion", "vegetable cooking"), Map.entry("potato", "vegetable cooking"), Map.entry("banana", "fruit breakfast healthy"),
        Map.entry("turmeric", "spice cooking"), Map.entry("cumin", "spice cooking"), Map.entry("tea", "beverage teatime breakfast:0.5"),
        Map.entry("coffee", "beverage breakfast"), Map.entry("biscuit", "snack teatime sweet:0.5"), Map.entry("namkeen", "snack party teatime:0.5"),
        Map.entry("chips", "snack party"), Map.entry("bread", "breakfast grain:0.5"), Map.entry("eggs", "protein breakfast"),
        Map.entry("cornflakes", "breakfast grain:0.5"), Map.entry("oats", "breakfast healthy grain:0.5"), Map.entry("poha", "breakfast grain:0.5 healthy:0.5"),
        Map.entry("shampoo", "hygiene"), Map.entry("soap", "hygiene cleaning:0.3"), Map.entry("toothpaste", "hygiene"), Map.entry("detergent", "cleaning"),
        Map.entry("dishwash", "cleaning"), Map.entry("cola", "beverage party sweet:0.5"), Map.entry("mango-juice", "beverage fruit:0.5 sweet:0.5 party:0.5"));
    static final Map<String, String> QUERY_LEXICON = Map.ofEntries(
        Map.entry("breakfast", "breakfast"), Map.entry("healthy", "healthy"), Map.entry("party", "party"), Map.entry("snacks", "snack"),
        Map.entry("snack", "snack"), Map.entry("chai", "teatime beverage"), Map.entry("tea", "teatime beverage"), Map.entry("cleaning", "cleaning"),
        Map.entry("supplies", ""), Map.entry("bath", "hygiene"), Map.entry("essentials", ""), Map.entry("dal", "lentil"), Map.entry("tadka", "spice fat cooking"),
        Map.entry("ingredients", "cooking"), Map.entry("protein", "protein"), Map.entry("veg", "vegetable:0.2"), Map.entry("milk", "dairy"),
        Map.entry("doodh", "dairy"), Map.entry("atta", "grain"), Map.entry("rice", "grain"), Map.entry("chawal", "grain"), Map.entry("oil", "fat cooking"),
        Map.entry("cooking", "cooking"), Map.entry("potato", "vegetable"), Map.entry("namkeen", "snack"), Map.entry("paneer", "dairy protein"),
        Map.entry("curd", "dairy"), Map.entry("shampoo", "hygiene"), Map.entry("haldi", "spice"), Map.entry("anda", "protein"), Map.entry("gur", "sweet"));

    static float[] concepts(String spec, Random noise, double sigma) {
        float[] x = new float[DIMS.length];
        for (String part : spec.split(" ")) {
            if (part.isBlank()) continue;
            String[] kv = part.split(":"); int d = Arrays.asList(DIMS).indexOf(kv[0]);
            x[d] += kv.length > 1 ? Float.parseFloat(kv[1]) : 1f;
        }
        double norm = 0;
        for (int i = 0; i < x.length; i++) { if (noise != null) x[i] += (float) (noise.nextGaussian() * sigma); norm += x[i] * x[i]; }
        if (norm == 0) return x;
        for (int i = 0; i < x.length; i++) x[i] /= (float) Math.sqrt(norm);
        return x;
    }
    static float[] embedQuery(String q) {
        String spec = Catalog.tokenize(q).stream().map(t -> QUERY_LEXICON.getOrDefault(t, "")).collect(Collectors.joining(" "));
        return concepts(spec, null, 0);
    }

    public static void main(String[] args) {
        // ---------- Part A ----------
        int n = 100_000, dim = 64;
        float[][] data = clustered(n, dim, 200, 11), queries = clustered(200, dim, 200, 99);
        System.out.printf("A. %,d vectors × %d dims (200 clusters), 200 queries, top-10 by cosine%n", n, dim);
        long t0 = System.nanoTime();
        int[][] truth = new int[queries.length][];
        for (int i = 0; i < queries.length; i++) truth[i] = exactTopK(data, queries[i], 10);
        double bruteMs = (System.nanoTime() - t0) / 1e6 / queries.length;
        System.out.printf("   brute force (exact)          : recall 1.000, %6.2f ms/query%n", bruteMs);
        for (boolean heur : List.of(false, true)) {
            t0 = System.nanoTime();
            Hnsw h = new Hnsw(data, 16, 100, heur);
            for (int i = 0; i < n; i++) h.insert(i);
            long buildMs = (System.nanoTime() - t0) / 1_000_000;
            long edges = 0; for (int i = 0; i < n; i++) for (int c : h.counts[i]) edges += c;
            System.out.printf("   HNSW, %s neighbour selection (M=16, efConstruction=100): build %,d ms, %,d links (~%,d KB on top of %,d KB of vectors)%n",
                    heur ? "heuristic" : "simple   ", buildMs, edges, edges * 4 / 1024, (long) n * dim * 4 / 1024);
            for (int ef : new int[]{10, 20, 40, 80, 160}) {
                double recall = 0; long s = System.nanoTime();
                for (int r = 0; r < 3; r++) for (int i = 0; i < queries.length; i++) {
                    int[] got = h.search(queries[i], 10, ef);
                    if (r == 0) { Set<Integer> tset = Arrays.stream(truth[i]).boxed().collect(Collectors.toSet()); recall += Arrays.stream(got).filter(tset::contains).count() / 10.0; }
                }
                double ms = (System.nanoTime() - s) / 1e6 / (3 * queries.length);
                System.out.printf("      efSearch=%-4d : recall@10 %.3f, %6.3f ms/query (%.0fx faster than brute force)%n", ef, recall / queries.length, ms, bruteMs / ms);
            }
        }

        // ---------- Part B ----------
        List<Catalog.Product> docs = Catalog.products(20_000);
        var bm25 = new SearchL1.Index(new SearchL1.Analyzer("final", true, true, true, true, 2, true), docs);
        Random noise = new Random(3);
        float[][] pv = new float[docs.size()][];
        for (int i = 0; i < docs.size(); i++) pv[i] = concepts(TYPE_CONCEPTS.get(docs.get(i).type()), noise, 0.05);

        List<Catalog.Judged> intents = List.of(
            new Catalog.Judged("healthy breakfast", Set.of("oats", "poha", "banana"), null),
            new Catalog.Judged("something for chai", Set.of("tea", "biscuit", "milk", "sugar", "namkeen"), null),
            new Catalog.Judged("party snacks", Set.of("chips", "namkeen", "cola"), null),
            new Catalog.Judged("cleaning supplies", Set.of("detergent", "dishwash"), null),
            new Catalog.Judged("dal tadka ingredients", Set.of("toor-dal", "moong-dal", "chana-dal", "cumin", "turmeric", "ghee", "onion", "tomato"), null),
            new Catalog.Judged("high protein veg", Set.of("paneer", "toor-dal", "moong-dal", "chana-dal", "curd"), null),
            new Catalog.Judged("breakfast", Set.of("cornflakes", "oats", "poha", "bread", "eggs", "milk", "butter", "banana"), null),
            new Catalog.Judged("bath essentials", Set.of("soap", "shampoo"), null));

        System.out.println("B. Lexical vs vector vs hybrid (RRF, k = 60, top 100 from each) — toy concept embeddings, see header");
        System.out.printf("   %-36s %18s %18s%n", "retriever", "20 lexical queries", "8 intent queries");
        for (String mode : List.of("BM25 (L1 final analyzer)", "vector (concept embedding)", "hybrid: RRF(BM25, vector)", "hybrid: weighted RRF (vector ×0.5)")) {
            double[] sums = new double[2]; int set = 0;
            for (List<Catalog.Judged> qs : List.of(Catalog.QUERIES, intents)) {
                for (var q : qs) {
                    List<Integer> lex = bm25.score(q.query()).entrySet().stream().sorted(Map.Entry.<Integer, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                            .limit(100).map(Map.Entry::getKey).toList();
                    float[] qv = embedQuery(q.query());
                    boolean emptyQ = true; for (float x : qv) if (x != 0) emptyQ = false;
                    List<Integer> vec = emptyQ ? List.of() : Arrays.stream(exactTopK(pv, qv, 100)).boxed()
                            .sorted(Comparator.comparingDouble((Integer i) -> -Hnsw.dot(pv[i], qv)).thenComparing(i -> i)).toList();
                    List<Integer> ranked;
                    if (mode.startsWith("BM25")) ranked = lex;
                    else if (mode.startsWith("vector")) ranked = vec;
                    else {
                        Map<Integer, Double> rrf = new HashMap<>();
                        for (int i = 0; i < lex.size(); i++) rrf.merge(lex.get(i), 1.0 / (60 + i + 1), Double::sum);
                        double w = mode.contains("weighted") ? 0.5 : 1.0;
                        for (int i = 0; i < vec.size(); i++) rrf.merge(vec.get(i), w / (60 + i + 1), Double::sum);
                        ranked = rrf.entrySet().stream().sorted(Map.Entry.<Integer, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                                .map(Map.Entry::getKey).toList();
                    }
                    sums[set] += Catalog.ndcg(q, ranked.stream().limit(10).map(docs::get).toList(), docs, 10);
                }
                sums[set] /= qs.size(); set++;
            }
            System.out.printf("   %-36s %18.3f %18.3f%n", mode, sums[0], sums[1]);
        }
    }
}
