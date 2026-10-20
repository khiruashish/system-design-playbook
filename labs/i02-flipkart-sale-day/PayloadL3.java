// L3 — The sale page on a slow phone network. Build a real product-listing response (40 products), measure its REAL
// size as full JSON, trimmed JSON (only fields the list view renders) and gzip-compressed, then estimate load time on
// 2G/3G/4G profiles with TCP slow start (initial window 10 segments). Network profiles are assumptions (VERIFY).
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public class PayloadL3 {
    public static void main(String[] a) throws Exception {
        Random r = new Random(1204);
        String full = listing(r, true), trim = listing(new Random(1204), false);
        byte[] fullB = full.getBytes(StandardCharsets.UTF_8), trimB = trim.getBytes(StandardCharsets.UTF_8);
        byte[] fullGz = gzip(fullB, 6), trimGz = gzip(trimB, 6), trimGz9 = gzip(trimB, 9);
        String[] names = {"full JSON", "full JSON + gzip", "trimmed JSON", "trimmed + gzip", "trimmed + gzip -9"};
        int[] sizes = {fullB.length, fullGz.length, trimB.length, trimGz.length, trimGz9.length};
        // name, downlink kbit/s, RTT ms (illustrative profiles)
        Object[][] nets = {{"2G/EDGE", 100, 700}, {"3G congested", 400, 350}, {"4G busy (sale evening)", 3000, 120}, {"4G good", 15000, 50}};
        System.out.printf("40-product listing response, sizes measured; times = new HTTPS connection + transfer with slow start%n%n");
        System.out.printf("%-20s %10s", "payload", "bytes");
        for (Object[] n : nets) System.out.printf(" %22s", n[0]);
        System.out.println();
        for (int i = 0; i < names.length; i++) {
            System.out.printf("%-20s %,10d", names[i], sizes[i]);
            for (Object[] n : nets) System.out.printf(" %20.2f s", seconds(sizes[i], (int) n[1], (int) n[2], true));
            System.out.println();
        }
        System.out.printf("%nsame, on an already-open connection (no DNS/TCP/TLS setup, window already grown):%n");
        for (int i = 0; i < names.length; i++) {
            System.out.printf("%-20s %,10d", names[i], sizes[i]);
            for (Object[] n : nets) System.out.printf(" %20.2f s", seconds(sizes[i], (int) n[1], (int) n[2], false));
            System.out.println();
        }
        System.out.println("\nfull: 17 fields per product incl. description, specs, 6 image URLs, offers, seller; trimmed: id, title, price, mrp, rating, 1 image");
    }

    // setup: DNS 1 RTT + TCP 1 RTT + TLS 1.3 1 RTT, then request 1 RTT; transfer in slow-start rounds (10 × 1460 B, doubling)
    // until the bandwidth-delay product is reached, then at link rate
    static double seconds(int bytes, int kbps, int rttMs, boolean cold) {
        double rtt = rttMs / 1000.0, bw = kbps * 1000 / 8.0, t = cold ? 3 * rtt : 0; double sent = 0, window = cold ? 14600 : Math.max(14600, bw * rtt);
        t += rtt / 2;                                                    // request reaches the server
        while (sent < bytes) {
            double burst = Math.min(window, bytes - sent);
            t += Math.max(burst / bw, 0) + (sent + burst < bytes ? Math.max(0, rtt - burst / bw) : rtt / 2);
            sent += burst; window = Math.min(window * 2, Math.max(bw * rtt, 14600));
        }
        return t;
    }
    static byte[] gzip(byte[] in, int level) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(bo) {{ def.setLevel(level); }}) { g.write(in); }
        return bo.toByteArray();
    }
    // product copy varies per product (a repeated sentence would compress unrealistically well)
    static final String[] WORDS = ("powerful fast octa core processor smooth display amoled bright sunlight battery lasts all day charging watt turbo camera portrait night mode " +
        "sensor lens ultra wide selfie video stabilisation gaming cooling chamber storage expandable memory slot dual sim network band support sleek design slim body " +
        "glass back matte finish fingerprint unlock face secure stereo speakers dolby audio headphone jack software updates security patches years durable splash resistant " +
        "premium value best in segment students professionals everyday use travel streaming social media multitasking apps").split(" ");
    static String prose(Random r, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) { if (i > 0) b.append(i % 12 == 0 ? ". " : " "); b.append(WORDS[r.nextInt(WORDS.length)]); } return b.append('.').toString(); }
    static String listing(Random r, boolean full) {
        String[] brands = {"Redmi", "Samsung", "realme", "POCO", "Moto", "iQOO", "OnePlus", "vivo"}, colors = {"Black", "Blue", "Green", "Silver", "Purple"};
        StringBuilder sb = new StringBuilder("{\"page\":1,\"items\":[");
        for (int i = 0; i < 40; i++) {
            String brand = brands[r.nextInt(brands.length)]; int mrp = 9999 + r.nextInt(30) * 1000, price = (int) (mrp * (0.6 + r.nextDouble() * 0.3));
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"MOB").append(100000 + r.nextInt(900000)).append("\",\"title\":\"").append(brand).append(" ").append(r.nextInt(90) + 10)
              .append(" 5G (").append(colors[r.nextInt(5)]).append(", ").append(4 << r.nextInt(3)).append("GB RAM, ").append(64 << r.nextInt(3)).append("GB)\",")
              .append("\"price\":").append(price).append(",\"mrp\":").append(mrp).append(",\"rating\":").append(3.5 + r.nextInt(15) / 10.0)
              .append(",\"img\":\"https://img.example.in/p/").append(Long.toHexString(r.nextLong())).append("_312x312.webp\"");
            if (full) {
                sb.append(",\"ratingCount\":").append(r.nextInt(90000)).append(",\"reviewCount\":").append(r.nextInt(9000))
                  .append(",\"description\":\"").append(prose(r, 60 + r.nextInt(60))).append("\"")
                  .append(",\"specs\":{\"display\":\"6.7 inch FHD+\",\"battery\":\"").append(5000 + r.nextInt(3) * 500).append(" mAh\",\"camera\":\"50MP + 2MP\",\"processor\":\"Octa-core\",\"warranty\":\"1 year\"}")
                  .append(",\"images\":[");
                for (int k = 0; k < 6; k++) sb.append(k > 0 ? "," : "").append("\"https://img.example.in/p/").append(Long.toHexString(r.nextLong())).append("_832x832.jpeg\"");
                sb.append("],\"offers\":[\"10% instant discount on select bank cards\",\"No-cost EMI from ₹").append(price / 12).append("/month\",\"Exchange offer up to ₹").append(r.nextInt(15) * 1000).append(" off\"]")
                  .append(",\"seller\":{\"id\":\"S").append(r.nextInt(99999)).append("\",\"name\":\"RetailNet ").append(r.nextInt(99)).append("\",\"rating\":4.").append(r.nextInt(9)).append("}")
                  .append(",\"deliveryPromise\":\"Delivery by tomorrow\",\"returnPolicy\":\"7 days replacement\",\"trackingTags\":[\"sale\",\"bbd\",\"slot").append(r.nextInt(9)).append("\"]");
            } else {
                for (int k = 0; k < 18; k++) r.nextInt(99999);   // keep the random stream aligned with the full listing where it matters little
            }
            sb.append('}');
        }
        return sb.append("]}").toString();
    }
}
