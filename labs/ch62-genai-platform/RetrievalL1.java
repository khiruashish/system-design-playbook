import java.util.*;
import java.util.stream.*;

/**
 * L1 — Retrieval for RAG: chunk size x retriever (BM25, embeddings, hybrid RRF) on 60 judged questions.
 * Real algorithms (chunking, BM25, cosine kNN, reciprocal rank fusion, recall/MRR); synthetic help-centre corpus;
 * TOY embeddings (a synonym-concept map + character trigrams, hashed to 256 dims) stand in for a real embedding model.
 */
public class RetrievalL1 {
    // ---- corpus: help-centre articles + product pages for the grocery platform (synthetic) ----
    record Doc(String id, String title, List<String> sections) {}
    record Chunk(String docId, String text, int tokens) {}
    record Query(String text, String relevantDoc, String kind, List<String> facts) {}

    static final String[][] POLICIES = {
        {"refund", "Refunds for missing or damaged items", "If an item is missing, damaged or expired, request a refund within 48 hours from the order page. Attach a photo of the damaged item if the app asks for one. Refunds go back to the original payment method in 3-5 working days; wallet refunds are instant.", "within 48 hours|3-5 working days"},
        {"cancel", "Cancelling an order", "You can cancel an order until it is packed. After packing, cancellation is not possible; refuse the delivery instead and the amount is refunded.", "until it is packed|refuse the delivery"},
        {"slot", "Delivery slots and charges", "Delivery slots open 3 days ahead. Express delivery in 15 minutes is available in select pincodes. A delivery fee applies to carts below the free-delivery threshold.", "3 days ahead|delivery fee applies"},
        {"payfail", "Payment failed but money debited", "If your UPI or card payment failed but the amount was debited, the bank reverses it automatically within 5 working days. Do not pay again for the same order unless the order shows as unpaid.", "within 5 working days|Do not pay again"},
        {"membership", "Grocery Plus membership", "Plus members get free delivery on all slots, extra cashback and early access to sales. Membership renews monthly and can be cancelled anytime from settings.", "free delivery on all slots|cancelled anytime from settings"},
        {"wallet", "Wallet balance and cashback", "Cashback is credited to the wallet within 24 hours of delivery. Wallet balance expires 12 months after credit and cannot be withdrawn to a bank account.", "within 24 hours of delivery|cannot be withdrawn"},
        {"address", "Changing the delivery address", "The address can be changed until the order is packed, within the same city. Changing the city requires cancelling and placing a new order.", "within the same city|placing a new order"},
        {"substitute", "Substitutions for out-of-stock items", "If an item goes out of stock, our picker may substitute a similar item of equal or lower price, or remove it and refund the difference. You can turn substitutions off per order.", "equal or lower price|turn substitutions off"},
        {"invoice", "Downloading a GST invoice", "A GST invoice for every order is available on the order page after delivery. Business customers can add a GSTIN in their profile before ordering.", "on the order page after delivery|add a GSTIN"},
        {"returns", "Return policy for packaged goods", "Sealed packaged goods can be returned within 7 days if unopened. Fresh fruits, vegetables, dairy and meat cannot be returned but can be refunded if damaged.", "within 7 days if unopened|cannot be returned"},
    };
    // paraphrases users actually type: share almost no words with the article
    static final String[][] PARAPHRASES = {
        {"refund", "my eggs arrived broken how do I get my money back"},
        {"refund", "got a spoiled packet, want compensation"},
        {"cancel", "can I stop my order after placing it"},
        {"cancel", "dont want the delivery anymore, call it off"},
        {"slot", "when can I get stuff delivered and is there a fee"},
        {"slot", "fastest way to receive groceries today"},
        {"payfail", "upi failed but rupees were cut from my account"},
        {"payfail", "transaction unsuccessful still charged"},
        {"membership", "is the paid subscription worth it, what do I get"},
        {"membership", "how to stop the monthly plan"},
        {"wallet", "can I move my cashback to my bank"},
        {"wallet", "when will I get the bonus amount"},
        {"address", "need to ship to my office instead of home"},
        {"address", "wrong location on my order"},
        {"substitute", "they sent a different brand than I ordered"},
        {"substitute", "replacement product instead of what I bought"},
        {"invoice", "need a tax bill for my company"},
        {"invoice", "where is the receipt for accounting"},
        {"returns", "can I send back unopened biscuits"},
        {"returns", "return vegetables I dont like"},
    };
    static final String[] BRANDS = {"Amul", "Aashirvaad", "Tata", "Fortune", "Britannia", "Haldiram", "Nestle", "Parle", "Saffola", "Dabur"};
    static final String[] PRODUCTS = {"toned milk", "whole wheat atta", "iodised salt", "sunflower oil", "marie biscuits", "bhujia namkeen",
        "instant noodles", "glucose biscuits", "rolled oats", "honey"};
    static final String[] SIZES = {"500 g", "1 kg", "5 kg", "1 L", "200 g"};

    // concept map: words that mean the same thing (what an embedding model learns from data)
    static final Map<String, String> CONCEPT = new HashMap<>();
    static {
        String[][] groups = {
            {"refund", "money", "back", "compensation", "reimburse", "refunded"}, {"damaged", "broken", "spoiled", "expired", "missing"},
            {"cancel", "cancelling", "cancellation", "stop", "off", "anymore"}, {"order", "orders", "purchase", "bought", "placing"},
            {"delivery", "delivered", "deliver", "ship", "receive", "get"}, {"slot", "slots", "when", "today", "fastest", "express"},
            {"fee", "charges", "charge", "cost"}, {"payment", "upi", "card", "transaction", "paid", "pay"},
            {"failed", "unsuccessful", "fail"}, {"debited", "cut", "charged", "deducted"}, {"bank", "account"},
            {"membership", "subscription", "plan", "plus", "member", "members"}, {"monthly", "renews", "month"},
            {"wallet", "cashback", "bonus", "balance"}, {"withdrawn", "move", "transfer", "withdraw"},
            {"address", "location", "office", "home", "city"}, {"substitute", "substitutions", "replacement", "different", "similar", "brand", "instead"},
            {"invoice", "bill", "receipt", "tax", "gst", "accounting"}, {"business", "company", "gstin"},
            {"return", "returned", "returns", "send"}, {"unopened", "sealed"}, {"vegetables", "fruits", "fresh", "dairy"},
        };
        for (String[] g : groups) for (String w : g) CONCEPT.put(w, g[0]);
    }

    public static void main(String[] args) {
        Random rnd = new Random(62);
        List<Doc> docs = new ArrayList<>();
        // policy articles: the answer paragraph is buried among boilerplate sections (like real help pages)
        // Real help pages are long: the answer sits between navigation text, eligibility notes and FAQs. The two key facts of
        // each answer are a few sentences apart, so a chunk can contain one without the other.
        String[] boiler = {
            "This article applies to all cities where the grocery platform operates. Policies may change from time to time; the version shown in the app at checkout is the one that applies to your order.",
            "Before you contact support, check the order page in the app. Most questions about an order, including its status, the items packed and the amount charged, are answered there within seconds.",
            "Our support team is available from 7 am to 11 pm every day in English, Hindi, Kannada, Tamil and Telugu. Average waiting time in chat is under two minutes outside sale days.",
            "Frequently asked: can I talk to a person? Yes, choose Chat with us at the bottom of any help page. Can I email? Yes, but chat is faster because the agent can see your order immediately.",
            "Related topics are listed at the end of this page. You can also browse the full help centre by category, or search it from the top of the Help tab.",
            "Terms and conditions of the platform apply. Offers, cashback rates and fees shown in banners are indicative; the final amounts appear on the payment screen before you pay."};
        String[] filler = {"Eligibility depends on the item, your city and the payment method used, which the app shows on the order page.",
            "Sale days and festival weeks may take longer because of higher volumes across all cities.",
            "Our team reviews these requests in the order they arrive, and you will receive a notification when the status changes."};
        int f = 0;
        for (String[] p : POLICIES) {
            String[] sentences = p[2].split("(?<=\\.) ");
            int mid = sentences.length / 2;
            String first = String.join(" ", Arrays.copyOfRange(sentences, 0, mid)), second = String.join(" ", Arrays.copyOfRange(sentences, mid, sentences.length));
            List<String> sec = new ArrayList<>(List.of(boiler[0], boiler[1], first, filler[f++ % 3], filler[f++ % 3], second, boiler[2], boiler[3], boiler[4], boiler[5]));
            docs.add(new Doc("help-" + p[0], p[1], sec));
        }
        // 300 product pages with SKU codes: exact identifiers that embeddings blur together
        List<Query> queries = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            String brand = BRANDS[i % BRANDS.length], prod = PRODUCTS[(i / BRANDS.length) % PRODUCTS.length], size = SIZES[rnd.nextInt(SIZES.length)];
            String sku = String.format("SKU-%s%05d", brand.substring(0, 3).toUpperCase(), 10_000 + rnd.nextInt(90_000));
            docs.add(new Doc("prod-" + i, brand + " " + prod + " " + size, List.of(
                brand + " " + prod + " " + size + ", item code " + sku + ".",
                "Ingredients and nutrition: see pack. Store in a cool and dry place. Best before 6 months from packing.",
                "Sold by the grocery platform. Prices include GST. Delivery as per slot availability.")));
            if (i % 15 == 0) queries.add(new Query("is " + sku + " available", "prod-" + i, "exact-id", List.of(sku)));
        }
        Map<String, List<String>> factsOf = new HashMap<>();
        for (String[] p : POLICIES) factsOf.put(p[0], List.of(p[3].split("\\|")));
        for (String[] p : PARAPHRASES) queries.add(new Query(p[1], "help-" + p[0], "paraphrase", factsOf.get(p[0])));
        for (String[] p : POLICIES) queries.add(new Query(p[1].toLowerCase(), "help-" + p[0], "keyword", factsOf.get(p[0])));
        for (int i = 0; i < 10; i++) {
            int k = rnd.nextInt(300); Doc d = docs.get(10 + k);
            queries.add(new Query(d.title().toLowerCase() + " price", d.id(), "product-name", List.of(d.sections().get(0).replaceAll(".*item code ", "").replace(".", ""))));
        }

        System.out.printf("Corpus: %d documents (10 help articles, 300 product pages); %d judged questions (%s).%n%n", docs.size(), queries.size(),
            queries.stream().collect(Collectors.groupingBy(Query::kind, TreeMap::new, Collectors.counting())).toString().replace("=", ": ").replaceAll("[{}]", ""));

        System.out.println("A. Chunk size x retriever, top 5 chunks sent to the LLM");
        System.out.println("   recall@5 = right document retrieved; answer@5 = EVERY key fact of the answer is in the retrieved text");
        System.out.printf("   %-11s %-20s %9s %9s %7s %17s%n", "chunk", "retriever", "recall@5", "answer@5", "MRR", "prompt tokens/q");
        int[][] chunkings = {{25, 0}, {120, 20}, {400, 50}};
        double bestHybrid = 0;
        for (int[] cs : chunkings) {
            List<Chunk> chunks = chunk(docs, cs[0], cs[1]);
            Index idx = new Index(chunks);
            for (String r : List.of("BM25", "embeddings (toy)", "hybrid RRF (k=60)")) {
                double rec = 0, ans = 0, mrr = 0, ctx = 0;
                for (Query q : queries) {
                    List<Integer> top = idx.search(q.text(), r, 5);
                    int rank = rankOfDoc(top, chunks, q.relevantDoc());
                    if (rank > 0) { rec++; mrr += 1.0 / rank; }
                    String context = top.stream().map(c -> chunks.get(c).text()).collect(Collectors.joining(" "));
                    if (q.facts().stream().allMatch(context::contains)) ans++;
                    ctx += top.stream().mapToInt(c -> chunks.get(c).tokens()).sum();
                }
                int n = queries.size();
                if (cs[0] == 120 && r.startsWith("hybrid")) bestHybrid = ans / n;
                System.out.printf("   %-11s %-20s %8.1f%% %8.1f%% %7.3f %17.0f%n", cs[0] + " words", r, 100 * rec / n, 100 * ans / n, mrr / n, ctx / n);
            }
        }

        System.out.println("\nB. Where each retriever fails (120-word chunks): recall@5 by question type");
        List<Chunk> chunks = chunk(docs, 120, 20); Index idx = new Index(chunks);
        System.out.printf("   %-14s %10s %18s %20s%n", "question type", "BM25", "embeddings (toy)", "hybrid RRF");
        for (String kind : List.of("exact-id", "keyword", "paraphrase", "product-name")) {
            List<Query> qs = queries.stream().filter(q -> q.kind().equals(kind)).toList();
            System.out.printf("   %-14s", kind + " (" + qs.size() + ")");
            for (String r : List.of("BM25", "embeddings (toy)", "hybrid RRF (k=60)")) {
                long hit = qs.stream().filter(q -> rankOfDoc(idx.search(q.text(), r, 5), chunks, q.relevantDoc()) > 0).count();
                System.out.printf(r.startsWith("BM25") ? "%9.0f%%" : r.startsWith("emb") ? "%17.0f%%" : "%19.0f%%", 100.0 * hit / qs.size());
            }
            System.out.println();
        }
        System.out.printf("%n   -> hybrid search over 120-word chunks puts the complete answer in the prompt for %.0f%% of questions.%n", 100 * bestHybrid);
        System.out.println("     BM25 nails exact identifiers (SKU codes) and misses paraphrases; embeddings do the opposite.");
        System.out.println("     Fusing ranks (RRF) needs no score calibration and keeps both strengths.");
        System.out.println("     Tiny chunks split the answer: the right page is found but half the facts never reach the model.");
        System.out.println("     Huge chunks carry the whole answer but multiply prompt tokens (cost and time to first token) and bury it in noise.");
    }

    static int rankOfDoc(List<Integer> top, List<Chunk> chunks, String doc) {
        for (int i = 0; i < top.size(); i++) if (chunks.get(top.get(i)).docId().equals(doc)) return i + 1;
        return 0;
    }

    /** Split each document into ~size-token windows with overlap, keeping the title on every chunk (a common trick). */
    static List<Chunk> chunk(List<Doc> docs, int size, int overlap) {
        List<Chunk> out = new ArrayList<>();
        for (Doc d : docs) {
            List<String> words = new ArrayList<>();
            for (String s : d.sections()) words.addAll(Arrays.asList(s.split("\\s+")));
            for (int start = 0; start < words.size(); start += size - overlap) {
                List<String> w = words.subList(start, Math.min(words.size(), start + size));
                out.add(new Chunk(d.id(), d.title() + ". " + String.join(" ", w), w.size() + d.title().split(" ").length));
                if (start + size >= words.size()) break;
            }
        }
        return out;
    }

    static List<String> tokens(String s) {
        List<String> t = new ArrayList<>();
        for (String w : s.toLowerCase().split("[^a-z0-9-]+")) if (!w.isEmpty() && !STOP.contains(w)) t.add(w);
        return t;
    }
    static final Set<String> STOP = Set.of("the", "a", "an", "is", "i", "my", "to", "of", "and", "or", "in", "on", "for", "it", "can", "how", "do",
        "what", "are", "be", "with", "this", "your", "you", "our", "if", "at", "as", "by", "from", "all", "not", "but", "we", "me", "need", "want", "got", "they");

    static final class Index {
        final List<Chunk> chunks; final List<Map<String, Integer>> tf = new ArrayList<>(); final Map<String, Integer> df = new HashMap<>();
        final double avgLen; final float[][] vec;
        Index(List<Chunk> chunks) {
            this.chunks = chunks; double len = 0; vec = new float[chunks.size()][];
            for (int i = 0; i < chunks.size(); i++) {
                Map<String, Integer> f = new HashMap<>(); List<String> t = tokens(chunks.get(i).text());
                for (String w : t) f.merge(w, 1, Integer::sum);
                for (String w : f.keySet()) df.merge(w, 1, Integer::sum);
                tf.add(f); len += t.size(); vec[i] = embed(chunks.get(i).text());
            }
            avgLen = len / chunks.size();
        }
        List<Integer> search(String q, String retriever, int k) {
            if (retriever.startsWith("BM25")) return top(bm25(q), k);
            if (retriever.startsWith("emb")) return top(cosine(q), k);
            // Reciprocal Rank Fusion: score = sum over retrievers of 1 / (60 + rank). Ranks only, so no score calibration needed.
            Map<Integer, Double> fused = new HashMap<>();
            List<Integer> a = top(bm25(q), 50), b = top(cosine(q), 50);
            for (int i = 0; i < a.size(); i++) fused.merge(a.get(i), 1.0 / (60 + i + 1), Double::sum);
            for (int i = 0; i < b.size(); i++) fused.merge(b.get(i), 1.0 / (60 + i + 1), Double::sum);
            return fused.entrySet().stream().sorted(Map.Entry.<Integer, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(k).map(Map.Entry::getKey).toList();
        }
        double[] bm25(String q) {
            double k1 = 1.2, b = 0.75; double[] s = new double[chunks.size()]; int n = chunks.size();
            for (String w : tokens(q)) {
                Integer d = df.get(w); if (d == null) continue; double idf = Math.log(1 + (n - d + 0.5) / (d + 0.5));
                for (int i = 0; i < n; i++) { Integer f = tf.get(i).get(w); if (f == null) continue;
                    int len = tf.get(i).values().stream().mapToInt(Integer::intValue).sum();
                    s[i] += idf * f * (k1 + 1) / (f + k1 * (1 - b + b * len / avgLen)); }
            }
            return s;
        }
        double[] cosine(String q) {
            float[] qv = embed(q); double[] s = new double[chunks.size()];
            for (int i = 0; i < s.length; i++) { double dot = 0; for (int j = 0; j < qv.length; j++) dot += qv[j] * vec[i][j]; s[i] = dot; }
            return s;
        }
        static List<Integer> top(double[] s, int k) {
            return IntStream.range(0, s.length).boxed().filter(i -> s[i] > 0)
                .sorted(Comparator.<Integer>comparingDouble(i -> -s[i]).thenComparingInt(i -> i)).limit(k).toList();
        }
    }

    /**
     * TOY embedding: concepts (synonyms collapse) + character trigrams of words, hashed into 256 dims, L2-normalised.
     * Stated assumption: like real embedding models, it represents rare codes poorly. Any token containing a digit
     * (SKU-AMU12345, 500, 48) becomes one generic "number" feature, so all item codes look alike to it.
     */
    static float[] embed(String text) {
        float[] v = new float[256];
        for (String w : tokens(text)) {
            if (w.matches(".*\\d.*")) { v[Math.floorMod("num".hashCode(), 256)] += 0.5f; continue; }
            String c = CONCEPT.get(w);
            if (c != null) v[Math.floorMod(("c:" + c).hashCode(), 256)] += 2f;
            String p = "#" + w + "#";
            for (int i = 0; i + 3 <= p.length(); i++) v[Math.floorMod(p.substring(i, i + 3).hashCode(), 256)] += 0.25f;
        }
        double n = 0; for (float x : v) n += x * x; n = Math.sqrt(n);
        if (n > 0) for (int i = 0; i < v.length; i++) v[i] /= n;
        return v;
    }
}
