// ImageVariantsL3.java — run with: java -Djava.awt.headless=true ImageVariantsL3.java   (JDK 21, no dependencies)
// PRODUCT IMAGES: RESIZE ONCE, OR ON DEMAND? Part 1 is REAL: a 2400×2400 synthetic product photo (gradients, shapes,
// texture, sensor noise — so it compresses like a photo) is resized with bicubic interpolation and JPEG-encoded by
// the JDK's ImageIO at the sizes the apps use. Part 2 uses those measured costs in a one-day model of a 200,000-image
// catalogue: 3,000 new images, 5,000,000 image requests (Zipf over images; 5 sizes by device mix; fixed seed), and
// compares serving strategies. CDN = an LRU cache holding 20 GB at the edge.
//   S1 serve the original 2400 px JPEG to everyone
//   S2 eager: make all 5 variants at upload, serve the right one
//   S3 lazy: make a variant on its first request, keep it in the object store
//   S4 S3 behind the CDN
import javax.imageio.*;
import javax.imageio.stream.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.io.*;
import java.util.*;

public class ImageVariantsL3 {
    static final int[] SIZES = {1200, 600, 300, 150, 80};

    public static void main(String[] args) throws Exception {
        BufferedImage orig = photo(2400, 52);
        byte[] origJpeg = jpeg(orig, 0.85f);
        System.out.printf("PART 1 — real resize + JPEG encode (JDK ImageIO, bicubic). Original 2400×2400: %,d KB at q=0.85.%n%n", origJpeg.length / 1024);
        System.out.printf("   %-10s %12s %16s %20s%n", "variant", "bytes", "resize+encode", "vs original bytes");
        double[] ms = new double[SIZES.length]; long[] bytes = new long[SIZES.length];
        for (int i = 0; i < SIZES.length; i++) {
            for (int w = 0; w < 3; w++) jpeg(resize(orig, SIZES[i]), 0.8f);                       // warm-up
            long t0 = System.nanoTime(); byte[] b = null; for (int rep = 0; rep < 5; rep++) b = jpeg(resize(orig, SIZES[i]), 0.8f);
            ms[i] = (System.nanoTime() - t0) / 5e6; bytes[i] = b.length;
            System.out.printf("   %-10s %9.1f KB %13.1f ms %19.1f%%%n", SIZES[i] + " px", b.length / 1024.0, ms[i], 100.0 * b.length / origJpeg.length);
        }
        System.out.printf("%n   JPEG quality at 600 px: ");
        for (float q : new float[]{0.95f, 0.8f, 0.6f}) System.out.printf("q=%.2f → %.1f KB   ", q, jpeg(resize(orig, 600), q).length / 1024.0);
        System.out.println("\n");
        // PART 2 — one-day model
        int catalogue = 200_000, uploads = 3_000, requests = 5_000_000; Random r = new Random(52);
        double[] cdf = new double[catalogue]; double acc = 0; for (int i = 0; i < catalogue; i++) { acc += 1 / Math.pow(i + 1, 1.1); cdf[i] = acc; } for (int i = 0; i < catalogue; i++) cdf[i] /= acc;
        double[] mix = {0.05, 0.15, 0.45, 0.25, 0.10};                                             // share of requests per size
        int[] reqImg = new int[requests], reqSize = new int[requests]; boolean[] isNew = new boolean[catalogue]; for (int u = 0; u < uploads; ) { int x = r.nextInt(catalogue); if (!isNew[x]) { isNew[x] = true; u++; } }
        for (int q = 0; q < requests; q++) { int k = Arrays.binarySearch(cdf, r.nextDouble()); reqImg[q] = Math.min(catalogue - 1, k < 0 ? -k - 1 : k);
            double x = r.nextDouble(), c = 0; int s = 0; while (s < 4 && x > (c += mix[s])) s++; reqSize[q] = s; }
        System.out.println("PART 2 — one day: 200,000 images, 3,000 of them uploaded today, 5,000,000 requests (same traffic on the 29 days before).\n");
        System.out.printf("   %-42s %14s %14s %16s %16s%n", "strategy", "bytes to users", "transform CPU", "origin requests", "variants stored");
        System.out.printf("   %-42s %14s %14s %16s %16s%n", "", "", "today", "", "(catalogue)");
        String[] names = {"S1) original 2400 px to everyone", "S2) eager: 5 variants at upload", "S3) lazy: variant on first request, kept", "S4) S3 behind a 20 GB CDN (LRU)"};
        long totalVariantBytes = Arrays.stream(bytes).sum();
        for (int st = 0; st < 4; st++) {
            double toUsers = 0, cpu = 0; long origin = 0, stored;
            if (st == 0) { toUsers = (double) requests * origJpeg.length; origin = requests; stored = 0; }
            else if (st == 1) { for (int q = 0; q < requests; q++) toUsers += bytes[reqSize[q]]; cpu = uploads * Arrays.stream(ms).sum(); origin = requests; stored = (long) catalogue * SIZES.length; }
            else {
                // lazy: a variant exists today if it was requested at least once in the previous 29 days (same traffic),
                // except for today's 3,000 new uploads, which have no variants yet
                HashSet<Long> made = new HashSet<>(); Random rr = new Random(9);
                for (int img = 0; img < catalogue; img++) { if (isNew[img]) continue; double pImg = cdf[img] - (img > 0 ? cdf[img - 1] : 0);
                    for (int s = 0; s < 5; s++) if (rr.nextDouble() < 1 - Math.exp(-29.0 * requests * pImg * mix[s])) made.add((long) img * 8 + s); }
                long existing = made.size();
                LinkedHashMap<Long, Integer> lru = new LinkedHashMap<>(16, 0.75f, true); long lruBytes = 0, cap = 20L << 30;
                for (int q = 0; q < requests; q++) {
                    int img = reqImg[q], s = reqSize[q]; long key = (long) img * 8 + s; toUsers += bytes[s];
                    if (st == 3) { if (lru.get(key) != null) continue; lru.put(key, (int) bytes[s]); lruBytes += bytes[s];
                        while (lruBytes > cap) { var it = lru.entrySet().iterator(); lruBytes -= it.next().getValue(); it.remove(); } }
                    origin++;
                    if (made.add(key)) cpu += ms[s];
                }
                stored = made.size();
                if (st == 2) System.out.printf("   (lazy: %,d of %,d possible variants existed before today)%n", existing, (long) catalogue * 5);
            }
            System.out.printf("   %-42s %11.0f GB %11.0f s %,16d %,16d%n", names[st], toUsers / 1e9, cpu / 1000, origin, stored);
        }
        System.out.printf("%n   (variant bytes per image, all 5 sizes: %.0f KB; a 5-variant catalogue = %.0f GB; S2 transforms every upload.)%n", totalVariantBytes / 1024.0, (double) catalogue * totalVariantBytes / 1e9);
        System.out.println("\n   → the big wins are the first and the last: serving the right-sized variant instead of the original cuts bytes");
        System.out.println("     to users ~40x, and a CDN keeps ~92% of requests off the origin. Eager vs lazy matters less than it sounds on a");
        System.out.println("     busy catalogue: with this traffic almost every variant is requested within a month (97% existed), so lazy saves");
        System.out.println("     little storage and about half of today's transform CPU. Lazy wins when catalogues are huge and sparse (millions");
        System.out.println("     of seller images) and when you add a new size — nothing to backfill. Common design: lazy transforms + object-store");
        System.out.println("     cache + CDN, with the one or two most-used sizes made eagerly so a new product's first viewers don't wait.");
    }

    static BufferedImage photo(int n, long seed) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB); Graphics2D g = img.createGraphics(); Random r = new Random(seed);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(245, 240, 230), n, n, new Color(210, 205, 200))); g.fillRect(0, 0, n, n);
        for (int i = 0; i < 40; i++) { g.setPaint(new GradientPaint(r.nextInt(n), r.nextInt(n), new Color(r.nextInt(256), r.nextInt(256), r.nextInt(256), 180), r.nextInt(n), r.nextInt(n), new Color(r.nextInt(256), r.nextInt(256), r.nextInt(256), 180)));
            g.fill(new Ellipse2D.Double(r.nextInt(n), r.nextInt(n), 100 + r.nextInt(900), 100 + r.nextInt(900))); }
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 140)); g.setColor(new Color(40, 60, 120)); g.drawString("ORGANIC ATTA 5 kg", 200, 1300);
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < px.length; i++) { int c = px[i], d = (int) (r.nextGaussian() * 6);                  // sensor noise + texture
            int rr = clamp(((c >> 16) & 255) + d), gg = clamp(((c >> 8) & 255) + d), bb = clamp((c & 255) + d); px[i] = (rr << 16) | (gg << 8) | bb; }
        g.dispose(); return img;
    }
    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
    static BufferedImage resize(BufferedImage src, int w) {
        BufferedImage out = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB); Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC); g.drawImage(src, 0, 0, w, w, null); g.dispose(); return out;
    }
    static byte[] jpeg(BufferedImage img, float q) throws IOException {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpg").next(); ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); p.setCompressionQuality(q);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(); try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) { w.setOutput(ios); w.write(null, new IIOImage(img, null, null), p); } w.dispose();
        return bos.toByteArray();
    }
}
