// ObjectStoreL1.java — run with: java ObjectStoreL1.java     (needs an S3-compatible endpoint; the lab uses SeaweedFS 3.80 on :8333)
// Product images and invoices in object storage, using the plain S3 REST API with AWS Signature V4 written out by hand:
// bucket + PUT/GET/HEAD, range reads, prefix listing, presigned upload/download URLs (direct from the app, bypassing our servers),
// expiry and tampering, multipart upload with a failed part retried alone, and content-addressed keys for deduplication.
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class ObjectStoreL1 {
    static final String ENDPOINT = "http://127.0.0.1:8333", HOST = "127.0.0.1:8333", REGION = "us-east-1";
    static final String ACCESS = "AKIDGROCERY", SECRET = "grocery-secret-key";
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final DateTimeFormatter AMZ = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    // ---------------- Signature V4 ----------------
    static String hex(byte[] b) { return HexFormat.of().formatHex(b); }
    static byte[] sha256(byte[] b) throws Exception { return MessageDigest.getInstance("SHA-256").digest(b); }
    static byte[] hmac(byte[] key, String data) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(key, "HmacSHA256")); return m.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
    static String enc(String s, boolean keepSlash) {
        String e = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
        return keepSlash ? e.replace("%2F", "/") : e;
    }
    static String canonicalQuery(Map<String, String> q) {
        var sb = new StringJoiner("&");
        new TreeMap<>(q).forEach((k, v) -> sb.add(enc(k, false) + "=" + enc(v, false)));
        return sb.toString();
    }
    static String signature(String method, String path, Map<String, String> query, Map<String, String> headers, String payloadHash, Instant t) throws Exception {
        String date = AMZ.format(t).substring(0, 8), scope = date + "/" + REGION + "/s3/aws4_request";
        var sorted = new TreeMap<String, String>(); headers.forEach((k, v) -> sorted.put(k.toLowerCase(), v.trim()));
        var ch = new StringBuilder(); sorted.forEach((k, v) -> ch.append(k).append(':').append(v).append('\n'));
        String signed = String.join(";", sorted.keySet());
        String canonical = method + "\n" + enc(path, true) + "\n" + canonicalQuery(query) + "\n" + ch + "\n" + signed + "\n" + payloadHash;
        String toSign = "AWS4-HMAC-SHA256\n" + AMZ.format(t) + "\n" + scope + "\n" + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        byte[] k = hmac(("AWS4" + SECRET).getBytes(StandardCharsets.UTF_8), date);
        k = hmac(k, REGION); k = hmac(k, "s3"); k = hmac(k, "aws4_request");
        return hex(hmac(k, toSign));
    }

    /** A signed request with the Authorization header (what an SDK does for server-side calls). */
    static HttpResponse<byte[]> s3(String method, String path, Map<String, String> query, byte[] body, Map<String, String> extra) throws Exception {
        Instant t = Instant.now();
        String payloadHash = body == null ? hex(sha256(new byte[0])) : hex(sha256(body));
        var headers = new LinkedHashMap<String, String>();
        headers.put("host", HOST); headers.put("x-amz-date", AMZ.format(t)); headers.put("x-amz-content-sha256", payloadHash);
        headers.putAll(extra);
        String sig = signature(method, path, query, headers, payloadHash, t);
        String date = AMZ.format(t).substring(0, 8);
        var signedNames = new TreeSet<String>(); headers.keySet().forEach(h -> signedNames.add(h.toLowerCase()));
        String auth = "AWS4-HMAC-SHA256 Credential=" + ACCESS + "/" + date + "/" + REGION + "/s3/aws4_request, SignedHeaders="
                + String.join(";", signedNames) + ", Signature=" + sig;
        String qs = query.isEmpty() ? "" : "?" + canonicalQuery(query);
        var b = HttpRequest.newBuilder(URI.create(ENDPOINT + enc(path, true) + qs)).header("Authorization", auth);
        headers.forEach((k, v) -> { if (!k.equals("host")) b.header(k, v); });
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    static HttpResponse<byte[]> s3(String method, String path) throws Exception { return s3(method, path, Map.of(), null, Map.of()); }

    /** A presigned URL: the signature travels in the query string; anyone holding the URL may do exactly this operation until it expires. */
    static String presign(String method, String path, Duration expiresIn, Instant t) throws Exception {
        String date = AMZ.format(t).substring(0, 8);
        var q = new TreeMap<String, String>();
        q.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        q.put("X-Amz-Credential", ACCESS + "/" + date + "/" + REGION + "/s3/aws4_request");
        q.put("X-Amz-Date", AMZ.format(t));
        q.put("X-Amz-Expires", String.valueOf(expiresIn.toSeconds()));
        q.put("X-Amz-SignedHeaders", "host");
        String sig = signature(method, path, q, Map.of("host", HOST), "UNSIGNED-PAYLOAD", t);
        return ENDPOINT + enc(path, true) + "?" + canonicalQuery(q) + "&X-Amz-Signature=" + sig;
    }

    static int plain(String method, String url, byte[] body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url)).method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    static String xml(byte[] body, String tag) { Matcher m = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">").matcher(new String(body)); return m.find() ? m.group(1) : null; }

    public static void main(String[] args) throws Exception {
        String bucket = "/catalog-media";
        s3("PUT", bucket);                                                                         // create bucket (idempotent here)
        Random rnd = new Random(1);

        System.out.println("A. Basic object operations (SeaweedFS S3 API, SigV4 signed)");
        byte[] img = new byte[480_000]; rnd.nextBytes(img);
        var put = s3("PUT", bucket + "/products/sku-1001/main.jpg", Map.of(), img, Map.of("content-type", "image/jpeg", "cache-control", "public, max-age=31536000, immutable"));
        System.out.printf("   PUT products/sku-1001/main.jpg (%,d B)  → %d, ETag %s%n", img.length, put.statusCode(), put.headers().firstValue("etag").orElse("?"));
        var head = s3("HEAD", bucket + "/products/sku-1001/main.jpg");
        System.out.printf("   HEAD                                  → %d, Content-Length %s, Content-Type %s%n", head.statusCode(),
                head.headers().firstValue("content-length").orElse("?"), head.headers().firstValue("content-type").orElse("?"));
        var range = s3("GET", bucket + "/products/sku-1001/main.jpg", Map.of(), null, Map.of("range", "bytes=0-1023"));
        System.out.printf("   GET Range bytes=0-1023                → %d, %,d bytes (thumbnail header / resumable download)%n", range.statusCode(), range.body().length);
        for (int i = 1; i <= 3; i++) s3("PUT", bucket + "/products/sku-1002/img-" + i + ".jpg", Map.of(), new byte[1000 * i], Map.of());
        var list = s3("GET", bucket, Map.of("list-type", "2", "prefix", "products/sku-1002/"), null, Map.of());
        System.out.printf("   LIST prefix products/sku-1002/        → %d, %s keys%n", list.statusCode(), xml(list.body(), "KeyCount"));

        System.out.println("B. Presigned URLs: the app uploads straight to storage; our servers only sign");
        Instant now = Instant.now();
        String upUrl = presign("PUT", bucket + "/uploads/rider-77/proof-of-delivery.jpg", Duration.ofMinutes(5), now);
        System.out.printf("   presigned PUT, valid 5 min            → %d%n", plain("PUT", upUrl, img));
        String getUrl = presign("GET", bucket + "/uploads/rider-77/proof-of-delivery.jpg", Duration.ofMinutes(5), now);
        System.out.printf("   presigned GET                         → %d%n", plain("GET", getUrl, null));
        String tampered = getUrl.replace("rider-77", "rider-78");
        System.out.printf("   same URL, key changed to rider-78     → %d (signature covers the path)%n", plain("GET", tampered, null));
        String expired = presign("GET", bucket + "/uploads/rider-77/proof-of-delivery.jpg", Duration.ofSeconds(60), now.minus(Duration.ofMinutes(10)));
        System.out.printf("   URL signed 10 min ago, valid 60 s     → %d (expired)%n", plain("GET", expired, null));
        System.out.printf("   unsigned GET of a private object      → %d%n", plain("GET", ENDPOINT + bucket + "/uploads/rider-77/proof-of-delivery.jpg", null));

        System.out.println("C. Multipart upload: 40 MB invoice archive in 8 MB parts, part 3 fails once and is retried alone");
        byte[] big = new byte[40 * 1024 * 1024]; rnd.nextBytes(big);
        String key = bucket + "/exports/2026-09-25/invoices.tar";
        long t0 = System.nanoTime();
        String uploadId = xml(s3("POST", key, Map.of("uploads", ""), null, Map.of()).body(), "UploadId");
        var etags = new ArrayList<String>(); long sent = 0; int partSize = 8 * 1024 * 1024; boolean failedOnce = false;
        for (int part = 1; part <= 5; part++) {
            byte[] chunk = Arrays.copyOfRange(big, (part - 1) * partSize, part * partSize);
            if (part == 3 && !failedOnce) { failedOnce = true; sent += chunk.length / 2; System.out.println("   part 3: (simulated) connection reset after 4 MB → only part 3 is sent again"); }
            var r = s3("PUT", key, Map.of("partNumber", String.valueOf(part), "uploadId", uploadId), chunk, Map.of());
            etags.add(r.headers().firstValue("etag").orElseThrow()); sent += chunk.length;
        }
        var cx = new StringBuilder("<CompleteMultipartUpload>");
        for (int i = 0; i < etags.size(); i++) cx.append("<Part><PartNumber>").append(i + 1).append("</PartNumber><ETag>").append(etags.get(i)).append("</ETag></Part>");
        cx.append("</CompleteMultipartUpload>");
        var done = s3("POST", key, Map.of("uploadId", uploadId), cx.toString().getBytes(), Map.of());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        var check = s3("GET", key);
        System.out.printf("   complete → %d in %,d ms; bytes sent %,d MB for a %,d MB object (a failed single PUT resends all 40 MB)%n",
                done.statusCode(), ms, sent >> 20, big.length >> 20);
        System.out.printf("   multipart ETag %s: not the MD5 of the object; verify with your own checksum%n", xml(done.body(), "ETag").replace("&#34;", "\""));
        System.out.printf("   downloaded object identical: %s%n", Arrays.equals(sha256(check.body()), sha256(big)));

        System.out.println("D. Content-addressed keys: the same image uploaded by 3 sellers is stored once");
        byte[] sellerImg = new byte[300_000]; rnd.nextBytes(sellerImg);
        int stored = 0;
        s3("DELETE", bucket + "/blobs/sha256/" + hex(sha256(sellerImg)));                        // clean start for re-runs
        for (String seller : List.of("seller-a", "seller-b", "seller-c")) {
            String cas = bucket + "/blobs/sha256/" + hex(sha256(sellerImg));
            if (s3("HEAD", cas).statusCode() == 404) { s3("PUT", cas, Map.of(), sellerImg, Map.of()); stored++; }
            // the catalog row stores (seller, sku) → blob key; the blob key never changes, so it can be cached forever
        }
        System.out.printf("   uploads 3, objects stored %d, key blobs/sha256/%s…%n", stored, hex(sha256(sellerImg)).substring(0, 16));
    }
}
