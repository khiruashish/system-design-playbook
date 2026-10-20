// UploadL2.java — run with: java -Dsun.net.httpserver.nodelay=true UploadL2.java   (JDK 21, no dependencies)
// UPLOADING A 160 MB FILE OVER A FLAKY MOBILE/SELLER NETWORK — a seller's catalogue video, a day's CCTV clip for a
// dispute, a bulk price file. A REAL HTTP object-store stub (JDK HttpServer) with realistic limits: each connection is
// throttled to 16 MB/s, the whole link to 64 MB/s, and the network drops a connection with probability 0.4% per 64 KB
// sent (on average one drop every ~16 MB). Four client strategies, all over real HTTP on localhost:
//   U1 one PUT of the whole file; on failure start again from byte 0 (max 5 attempts)
//   U2 multipart: 8 MB parts, one at a time; a failed part is retried alone; then "complete" assembles the parts
//   U3 multipart, 4 parts in parallel
//   U4 multipart, 8 parts in parallel
// The server stores parts, assembles on complete and returns the SHA-256 of the object, which the client checks.
// Measured: success, wall time, MB the server actually received, and whether the stored object is byte-identical.
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class UploadL2 {
    static final int CHUNK = 64 * 1024; static final double DROP = 0.004;
    static final Map<String, byte[]> parts = new ConcurrentHashMap<>(); static final Random rnd = new Random(52);
    static final Object linkLock = new Object(); static long linkNextFree = 0;                   // global 64 MB/s link

    public static void main(String[] args) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 128);
        srv.setExecutor(Executors.newFixedThreadPool(16));
        srv.createContext("/", UploadL2::handle); srv.start(); int port = srv.getAddress().getPort();
        byte[] file = new byte[160 * 1024 * 1024]; new Random(7).nextBytes(file); String want = hex(sha(file));
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).executor(Executors.newFixedThreadPool(16)).build();
        System.out.println("160 MB upload; 16 MB/s per connection, 64 MB/s link; 0.4% drop chance per 64 KB. Real HTTP.\n");
        System.out.printf("   %-50s %-24s %8s %12s %10s%n", "strategy", "result", "time", "MB received", "intact");
        String[] names = {"U1) single PUT, restart from 0 on failure (max 5)", "U2) multipart 8 MB, sequential, retry the part", "U3) multipart 8 MB, 4 in parallel", "U4) multipart 8 MB, 8 in parallel"};
        for (int s = 0; s < 4; s++) {
            parts.clear(); bytesIn.set(0); AtomicLong sent = new AtomicLong(); long t0 = System.nanoTime(); String result, intact = "—";
            String base = "http://127.0.0.1:" + port;
            if (s == 0) {
                result = "FAILED";
                for (int a = 0; a < 5; a++) {
                    HttpResponse<String> resp = send(http, base + "/put/obj" + s, file, 0, file.length, sent);
                    if (resp != null && resp.statusCode() == 200) { result = "ok (" + (a + 1) + " tries)"; intact = resp.body().equals(want) ? "yes" : "NO"; break; } }
            } else {
                int partSize = 8 << 20, n = file.length / partSize, par = s == 1 ? 1 : s == 2 ? 4 : 8;
                ExecutorService ex = Executors.newFixedThreadPool(par); List<Future<Integer>> fs = new ArrayList<>(); final int ss = s;
                for (int p = 0; p < n; p++) { final int pn = p; fs.add(ex.submit(() -> {
                    for (int a = 1; a <= 20; a++) { HttpResponse<String> resp = send(http, base + "/part/obj" + ss + "/" + pn, file, pn * partSize, partSize, sent);
                        if (resp != null && resp.statusCode() == 200) return a; }
                    return -1; })); }
                int retries = 0; boolean ok = true; for (Future<Integer> f : fs) { int a = f.get(); if (a < 0) ok = false; else retries += a - 1; } ex.shutdown();
                HttpResponse<String> done = http.send(HttpRequest.newBuilder(URI.create(base + "/complete/obj" + s + "/" + n)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                result = ok && done.statusCode() == 200 ? "ok (" + retries + " part retries)" : "FAILED"; intact = done.statusCode() == 200 && done.body().equals(want) ? "yes" : "NO";
            }
            System.out.printf("   %-50s %-24s %6.1f s %12.0f %10s%n", names[s], result, (System.nanoTime() - t0) / 1e9, bytesIn.get() / 1048576.0, intact);
        }
        System.out.println("\n   → with a drop every ~16 MB on average, a 160 MB single request almost never survives, and every retry starts");
        System.out.println("     again from zero. Splitting the object into parts turns one fragile transfer into many small, independently");
        System.out.println("     retryable ones: the re-sent bytes are bounded by the part size. Parallel parts use more of the link; past");
        System.out.println("     4 connections the 64 MB/s cap limits the gain. The server assembles parts only on 'complete', and the");
        System.out.println("     end-to-end checksum proves the object is intact. Uploads go straight to the object store with pre-signed URLs");
        System.out.println("     — the application servers never carry the bytes.");
        srv.stop(0); System.exit(0);
    }

    static HttpResponse<String> send(HttpClient http, String url, byte[] file, int off, int len, AtomicLong sent) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(file, off, len)).timeout(java.time.Duration.ofSeconds(60)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp;
        } catch (Exception e) { return null; }
    }
    static final AtomicLong bytesIn = new AtomicLong();

    static void handle(HttpExchange ex) throws IOException {
        String[] p = ex.getRequestURI().getPath().split("/");
        try (ex) {
            if (p[1].equals("complete")) {
                int n = Integer.parseInt(p[3]); ByteArrayOutputStream all = new ByteArrayOutputStream();
                for (int i = 0; i < n; i++) { byte[] b = parts.get(p[2] + "/" + i); if (b == null) { ex.sendResponseHeaders(400, -1); return; } all.write(b); }
                byte[] body = hex(sha(all.toByteArray())).getBytes(); ex.sendResponseHeaders(200, body.length); ex.getResponseBody().write(body); return;
            }
            InputStream in = ex.getRequestBody(); ByteArrayOutputStream buf = new ByteArrayOutputStream(); byte[] chunk = new byte[CHUNK];
            long connStart = System.nanoTime(); long got = 0; int k;
            while ((k = in.readNBytes(chunk, 0, CHUNK)) > 0) {
                buf.write(chunk, 0, k); got += k; counted(k);
                pace(connStart, got);
                boolean drop; synchronized (rnd) { drop = rnd.nextDouble() < DROP; }
                if (drop) { ex.sendResponseHeaders(503, -1); return; }                        // connection lost mid-body
            }
            byte[] body = buf.toByteArray();
            if (p[1].equals("put")) { byte[] h = hex(sha(body)).getBytes(); ex.sendResponseHeaders(200, h.length); ex.getResponseBody().write(h); }
            else { parts.put(p[2] + "/" + p[3], body); ex.sendResponseHeaders(200, -1); }
        } catch (Exception e) { /* client side sees failure */ }
    }
    static void counted(int k) { bytesIn.addAndGet(k); }
    static void pace(long connStart, long got) throws InterruptedException {
        long perConnDue = connStart + (long) (got / (16.0 * 1048576) * 1e9);                    // 16 MB/s per connection
        long linkDue; synchronized (linkLock) { long now = System.nanoTime(); linkNextFree = Math.max(linkNextFree, now) + (long) (CHUNK / (64.0 * 1048576) * 1e9); linkDue = linkNextFree; }
        long wait = Math.max(perConnDue, linkDue) - System.nanoTime(); if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
    }
    static byte[] sha(byte[] b) { try { return MessageDigest.getInstance("SHA-256").digest(b); } catch (Exception e) { throw new RuntimeException(e); } }
    static String hex(byte[] b) { StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x)); return sb.toString(); }
}
