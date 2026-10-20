// Catalog.java — shared by the Ch 22 labs (JDK 25 multi-file source launch: `java SearchL1.java` finds this file).
// A deterministic grocery catalog (invented brands), the judged query set, and the analyzer building blocks:
// tokenising, unit normalisation, plural stemming, Devanagari → Latin transliteration, phonetic normalisation, synonyms.
import java.text.Normalizer;
import java.util.*;

public class Catalog {
    public record Type(String key, String category, List<String> names, List<String> sizes) {}
    public record Product(int id, String type, String name, String brand, String category, String size, int popularity, boolean inStock, int pricePaise) {
        public String text() { return name + " " + brand + " " + size; }
    }

    public static final List<Type> TYPES = List.of(
        new Type("atta", "Staples", List.of("Chakki Fresh Atta", "Whole Wheat Atta"), List.of("1 kg", "5 kg", "10 kg")),
        new Type("basmati", "Staples", List.of("Basmati Rice", "Classic Basmati Rice"), List.of("1 kg", "5 kg")),
        new Type("sona-masoori", "Staples", List.of("Sona Masoori Rice"), List.of("5 kg", "10 kg")),
        new Type("toor-dal", "Staples", List.of("Toor Dal", "Unpolished Toor Dal"), List.of("500 g", "1 kg")),
        new Type("moong-dal", "Staples", List.of("Moong Dal", "Yellow Moong Dal"), List.of("500 g", "1 kg")),
        new Type("chana-dal", "Staples", List.of("Chana Dal"), List.of("500 g", "1 kg")),
        new Type("sugar", "Staples", List.of("Sugar", "Refined Sugar"), List.of("1 kg", "5 kg")),
        new Type("salt", "Staples", List.of("Iodised Salt"), List.of("1 kg")),
        new Type("jaggery", "Staples", List.of("Organic Jaggery", "Jaggery Cubes"), List.of("500 g", "1 kg")),
        new Type("sunflower-oil", "Oils", List.of("Refined Sunflower Oil"), List.of("1 L", "5 L")),
        new Type("mustard-oil", "Oils", List.of("Kachi Ghani Mustard Oil"), List.of("1 L", "5 L")),
        new Type("groundnut-oil", "Oils", List.of("Groundnut Oil"), List.of("1 L")),
        new Type("ghee", "Oils", List.of("Desi Ghee", "Cow Ghee"), List.of("500 ml", "1 L")),
        new Type("milk", "Dairy", List.of("Toned Milk", "Full Cream Milk"), List.of("500 ml", "1 L")),
        new Type("curd", "Dairy", List.of("Fresh Dahi", "Set Curd"), List.of("400 g", "1 kg")),
        new Type("paneer", "Dairy", List.of("Malai Paneer", "Fresh Paneer"), List.of("200 g", "500 g")),
        new Type("butter", "Dairy", List.of("Salted Butter"), List.of("100 g", "500 g")),
        new Type("tomato", "Vegetables", List.of("Tomato", "Hybrid Tomato"), List.of("500 g", "1 kg")),
        new Type("onion", "Vegetables", List.of("Onion"), List.of("1 kg", "2 kg")),
        new Type("potato", "Vegetables", List.of("Potato", "New Potato"), List.of("1 kg", "2 kg")),
        new Type("banana", "Fruits", List.of("Robusta Banana", "Yelakki Banana"), List.of("6 pcs", "12 pcs")),
        new Type("turmeric", "Masala", List.of("Turmeric Powder"), List.of("100 g", "200 g")),
        new Type("cumin", "Masala", List.of("Cumin Seeds", "Jeera Whole"), List.of("100 g")),
        new Type("tea", "Beverages", List.of("Assam Tea", "Premium Tea Leaves"), List.of("250 g", "500 g")),
        new Type("coffee", "Beverages", List.of("Filter Coffee Powder"), List.of("200 g")),
        new Type("biscuit", "Snacks", List.of("Glucose Biscuits", "Marie Biscuits"), List.of("250 g")),
        new Type("namkeen", "Snacks", List.of("Aloo Bhujia", "Namkeen Mixture"), List.of("200 g", "400 g")),
        new Type("chips", "Snacks", List.of("Potato Chips Salted", "Masala Potato Chips"), List.of("52 g", "90 g")),
        new Type("bread", "Bakery", List.of("Brown Bread", "Sandwich Bread"), List.of("400 g")),
        new Type("eggs", "Dairy", List.of("Farm Fresh Eggs", "Brown Eggs"), List.of("6 pcs", "12 pcs")),
        new Type("cornflakes", "Breakfast", List.of("Corn Flakes"), List.of("475 g")),
        new Type("oats", "Breakfast", List.of("Rolled Oats"), List.of("1 kg")),
        new Type("poha", "Breakfast", List.of("Thick Poha"), List.of("500 g")),
        new Type("shampoo", "Personal Care", List.of("Anti-Dandruff Shampoo", "Herbal Shampoo"), List.of("180 ml", "340 ml")),
        new Type("soap", "Personal Care", List.of("Sandal Soap", "Neem Bathing Bar"), List.of("4 x 100 g")),
        new Type("toothpaste", "Personal Care", List.of("Herbal Toothpaste"), List.of("150 g")),
        new Type("detergent", "Home Care", List.of("Detergent Powder", "Matic Liquid Detergent"), List.of("1 kg", "2 L")),
        new Type("dishwash", "Home Care", List.of("Dishwash Liquid Lemon"), List.of("500 ml")),
        new Type("cola", "Beverages", List.of("Cola Soft Drink"), List.of("750 ml", "2 L")),
        new Type("mango-juice", "Beverages", List.of("Mango Drink"), List.of("1 L")));

    public static final List<String> BRANDS = List.of("Sunehri", "Aranya", "Bhoomika", "Pavitra", "Hariyali Farms", "Chandan Mills",
            "Deshi Dairy", "Nirmal", "Ujjwal", "Gauri", "Tarangini", "Mitti", "Saral", "Annika", "Vasundhara", "Kaveri Valley",
            "Shubh", "Prakriti", "Amrit", "Neelam");

    /** 20,000 products (or n): every type × brand × name × size, then repeated with pack variants and seller listings. */
    public static List<Product> products(int n) {
        Random r = new Random(22);
        List<Product> ps = new ArrayList<>();
        int id = 1;
        while (ps.size() < n) {
            Type t = TYPES.get(r.nextInt(TYPES.size()));
            String brand = BRANDS.get(r.nextInt(BRANDS.size()));
            String name = brand + " " + t.names().get(r.nextInt(t.names().size()));
            String size = t.sizes().get(r.nextInt(t.sizes().size()));
            int pop = (int) Math.floor(Math.exp(r.nextGaussian() * 1.5 + 3));            // long-tailed popularity
            ps.add(new Product(id++, t.key(), name, brand, t.category(), size, pop, r.nextDouble() > 0.15, 2_000 + r.nextInt(80_000)));
        }
        return ps;
    }

    // ---------------- judged queries ----------------
    public record Judged(String query, Set<String> types, String size) {}
    public static final List<Judged> QUERIES = List.of(
        new Judged("atta 5kg", Set.of("atta"), "5 kg"),
        new Judged("aata", Set.of("atta"), null),
        new Judged("आटा", Set.of("atta"), null),
        new Judged("doodh", Set.of("milk"), null),
        new Judged("दूध 1 litre", Set.of("milk"), "1 L"),
        new Judged("curd", Set.of("curd"), null),
        new Judged("tomatos", Set.of("tomato"), null),
        new Judged("toor daal", Set.of("toor-dal"), null),
        new Judged("basmati chawal", Set.of("basmati"), null),
        new Judged("cooking oil", Set.of("sunflower-oil", "mustard-oil", "groundnut-oil"), null),
        new Judged("chini", Set.of("sugar"), null),
        new Judged("shampo", Set.of("shampoo"), null),
        new Judged("paneer 200g", Set.of("paneer"), "200 g"),
        new Judged("मूंग दाल", Set.of("moong-dal"), null),
        new Judged("haldi", Set.of("turmeric"), null),
        new Judged("chai patti", Set.of("tea"), null),
        new Judged("gur", Set.of("jaggery"), null),
        new Judged("anda", Set.of("eggs"), null),
        new Judged("namkeen", Set.of("namkeen"), null),
        new Judged("potato", Set.of("potato"), null));

    /** Graded relevance: 2 = right type and requested size, 1 = right type, 0 = not relevant. */
    public static int grade(Judged q, Product p) {
        if (!q.types().contains(p.type())) return 0;
        return q.size() == null || q.size().equals(p.size()) ? 2 : 1;
    }

    /** nDCG@k of a ranking against the ideal ranking over the whole catalog. */
    public static double ndcg(Judged q, List<Product> ranked, List<Product> all, int k) {
        double dcg = 0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) dcg += (Math.pow(2, grade(q, ranked.get(i))) - 1) / (Math.log(i + 2) / Math.log(2));
        int[] ideal = all.stream().mapToInt(p -> grade(q, p)).filter(g -> g > 0).boxed().sorted(Comparator.reverseOrder()).limit(k).mapToInt(Integer::intValue).toArray();
        double idcg = 0;
        for (int i = 0; i < ideal.length; i++) idcg += (Math.pow(2, ideal[i]) - 1) / (Math.log(i + 2) / Math.log(2));
        return idcg == 0 ? 0 : dcg / idcg;
    }

    // ---------------- analyzer building blocks ----------------
    public static List<String> tokenize(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
        List<String> out = new ArrayList<>();
        for (String t : n.split("[^\\p{L}\\p{M}\\p{N}]+")) if (!t.isEmpty()) out.add(t);
        return out;
    }

    static final Map<String, String> UNITS = Map.ofEntries(Map.entry("kg", "kg"), Map.entry("kgs", "kg"), Map.entry("g", "g"), Map.entry("gm", "g"),
            Map.entry("gms", "g"), Map.entry("grams", "g"), Map.entry("l", "l"), Map.entry("ltr", "l"), Map.entry("litre", "l"), Map.entry("liter", "l"),
            Map.entry("ml", "ml"), Map.entry("pcs", "pcs"), Map.entry("pc", "pcs"));

    /** "5 kg", "5kg", "5 kgs" → "5kg"; "1 litre" → "1l". */
    public static List<String> units(List<String> ts) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < ts.size(); i++) {
            String t = ts.get(i);
            var m = java.util.regex.Pattern.compile("^(\\d+)([a-z]+)$").matcher(t);
            if (m.matches() && UNITS.containsKey(m.group(2))) { out.add(m.group(1) + UNITS.get(m.group(2))); continue; }
            if (t.matches("\\d+") && i + 1 < ts.size() && UNITS.containsKey(ts.get(i + 1))) { out.add(t + UNITS.get(ts.get(i + 1))); i++; continue; }
            out.add(t);
        }
        return out;
    }

    /** Minimal English plural stemming: tomatoes → tomato, biscuits → biscuit (not "gms", "pcs" units). */
    public static String stem(String t) {
        if (t.length() > 4 && t.endsWith("oes")) return t.substring(0, t.length() - 2);
        if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss") && !Character.isDigit(t.charAt(0))) return t.substring(0, t.length() - 1);
        return t;
    }

    // Devanagari → Latin (ITRANS-like, with schwa at word end dropped)
    static final Map<Character, String> CONS = new HashMap<>(), VOW = new HashMap<>(), MATRA = new HashMap<>();
    static {
        String[][] c = {{"क","k"},{"ख","kh"},{"ग","g"},{"घ","gh"},{"ङ","n"},{"च","ch"},{"छ","chh"},{"ज","j"},{"झ","jh"},{"ञ","n"},{"ट","t"},{"ठ","th"},
            {"ड","d"},{"ढ","dh"},{"ण","n"},{"त","t"},{"थ","th"},{"द","d"},{"ध","dh"},{"न","n"},{"प","p"},{"फ","ph"},{"ब","b"},{"भ","bh"},{"म","m"},
            {"य","y"},{"र","r"},{"ल","l"},{"व","v"},{"श","sh"},{"ष","sh"},{"स","s"},{"ह","h"},{"ड़","r"},{"ढ़","rh"}};
        for (String[] x : c) CONS.put(x[0].charAt(0), x[1]);
        String[][] v = {{"अ","a"},{"आ","aa"},{"इ","i"},{"ई","ii"},{"उ","u"},{"ऊ","uu"},{"ए","e"},{"ऐ","ai"},{"ओ","o"},{"औ","au"},{"ऋ","ri"}};
        for (String[] x : v) VOW.put(x[0].charAt(0), x[1]);
        String[][] m = {{"ा","aa"},{"ि","i"},{"ी","ii"},{"ु","u"},{"ू","uu"},{"े","e"},{"ै","ai"},{"ो","o"},{"ौ","au"},{"ृ","ri"},{"ॉ","o"}};
        for (String[] x : m) MATRA.put(x[0].charAt(0), x[1]);
    }
    public static String transliterate(String w) {
        w = w.replace("ड़", "ड़").replace("ढ़", "ढ़");                 // decompose nukta forms
        var sb = new StringBuilder();
        for (int i = 0; i < w.length(); i++) {
            char ch = w.charAt(i);
            if (ch == '़') continue;
            if (CONS.containsKey(ch)) {
                sb.append(CONS.get(ch));
                char next = i + 1 < w.length() ? w.charAt(i + 1) : 0;
                if (next == '़') next = i + 2 < w.length() ? w.charAt(i + 2) : 0;
                boolean last = next == 0;
                if (!MATRA.containsKey(next) && next != '्' && !last) sb.append('a');   // inherent schwa (not at word end)
            } else if (MATRA.containsKey(ch)) sb.append(MATRA.get(ch));
            else if (VOW.containsKey(ch)) sb.append(VOW.get(ch));
            else if (ch == 'ं' || ch == 'ँ') sb.append('n');
            else if (ch == '्') { /* virama: no vowel */ }
            else if (ch >= '०' && ch <= '९') sb.append((char) ('0' + (ch - '०')));
            else sb.append(ch);
        }
        return sb.toString();
    }

    /** Phonetic normalisation for Hinglish spellings: aata/atta/आटा → ata, doodh/dudh/दूध → dudh, daal/dal → dal. */
    public static String phonetic(String t) {
        if (t.chars().anyMatch(c -> c >= 0x0900 && c <= 0x097F)) t = transliterate(t);
        if (t.chars().anyMatch(Character::isDigit)) return t;
        t = t.replace("ee", "i").replace("oo", "u").replace("aa", "a").replace("ii", "i").replace("uu", "u")
             .replace("w", "v").replace("ph", "f").replace("q", "k").replace("z", "j");
        var sb = new StringBuilder();
        for (char c : t.toCharArray()) if (sb.isEmpty() || sb.charAt(sb.length() - 1) != c) sb.append(c);   // collapse doubles
        return sb.toString();
    }

    /** Query-side synonyms, keyed and valued in phonetic form (so they apply after normalisation). */
    public static final Map<String, List<String>> SYNONYMS = new HashMap<>();
    static {
        String[][] groups = {{"curd", "dahi"}, {"milk", "dudh"}, {"sugar", "chini"}, {"rice", "chaval"}, {"oil", "tel"}, {"salt", "namak"},
            {"turmeric", "haldi"}, {"cumin", "jeera", "jira"}, {"tea", "chai", "chay"}, {"jagery", "gur", "gud"}, {"dal", "daal", "lentil"},
            {"egs", "eg", "anda", "ande"}, {"potato", "alu"}, {"tomato", "tamatar"}, {"onion", "pyaj", "pyaz"}, {"banana", "kela"},
            {"buter", "makhan"}, {"soap", "sabun"}, {"tur", "arhar"}};
        for (String[] g : groups) {
            List<String> norm = Arrays.stream(g).map(Catalog::phonetic).distinct().toList();
            for (String k : norm) SYNONYMS.put(k, norm);
        }
    }
}
