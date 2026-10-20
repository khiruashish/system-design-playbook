// FailoverL4.java — run with: java FailoverL4.java   (JDK 21; redis-server 7 on the PATH; Linux, uses kill -STOP/-KILL)
// WHAT A CACHE FAILOVER LOSES. Real Redis: a primary (:6391) and a replica (:6392) with the default ASYNCHRONOUS
// replication. A client writes session/cart keys in pipelined batches of 50 and records every write the primary
// acknowledged. Then the replica stalls for 300 ms (SIGSTOP — standing in for a GC pause, a noisy neighbour or a network
// hiccup), the primary dies during the stall (SIGKILL), the replica resumes and is promoted (REPLICAOF NO ONE), and we
// check which ACKNOWLEDGED writes the new primary is missing. Two write modes:
//   ASYNC        count a write as done when the primary replies OK (Redis default)
//   WAIT 1 100   after each batch, WAIT for 1 replica to confirm (timeout 100 ms); count the batch as done only if it did
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class FailoverL4 {
    public static void main(String[] args) throws Exception {
        System.out.println("Primary :6391 → replica :6392 (async replication). Replica stalls 300 ms, primary is killed during the stall.\n");
        System.out.printf("   %-44s %12s %16s %12s %12s %14s%n", "write mode", "writes sent", "acknowledged", "lost (acked", "batch p50", "batch max");
        System.out.printf("   %-44s %12s %16s %12s %12s %14s%n", "", "", "", "but missing)", "", "");
        for (int mode = 0; mode < 2; mode++) run(mode);
        System.out.println("\n   → asynchronous replication acknowledges a write before any replica has it, so a failover during replica lag");
        System.out.println("     silently discards acknowledged writes. WAIT makes the client find out: batches written during the stall");
        System.out.println("     were NOT confirmed, so the client knows they aren't safe (retry, or rebuild from the source of truth) —");
        System.out.println("     at the price of waiting for a replica on every batch. WAIT is not consensus (a promoted replica can still");
        System.out.println("     miss writes WAIT counted if another replica had them), so the rule stands: a cache may lose data; keep the");
        System.out.println("     truth in a database, and don't store in a cache anything you can't rebuild or afford to lose.");
    }

    static void run(int mode) throws Exception {
        sh("redis-cli -p 6391 shutdown nosave; redis-cli -p 6392 shutdown nosave; sleep 0.5; rm -rf /tmp/fo && mkdir -p /tmp/fo/p /tmp/fo/r");
        sh("cd /tmp/fo/p && redis-server --port 6391 --save '' --appendonly no --daemonize yes --repl-diskless-sync-delay 0");
        sh("cd /tmp/fo/r && redis-server --port 6392 --save '' --appendonly no --daemonize yes --replicaof 127.0.0.1 6391");
        Thread.sleep(1500);
        long primaryPid = pid(6391), replicaPid = pid(6392);
        List<Integer> acked = new ArrayList<>(); List<Long> lat = new ArrayList<>(); int sent = 0; long t0 = System.currentTimeMillis();
        boolean stalled = false, killed = false;
        try (Redis p = new Redis(6391)) {
            while (true) {
                long now = System.currentTimeMillis() - t0;
                if (!stalled && now > 1000) { sh("kill -STOP " + replicaPid); stalled = true; }
                if (!killed && now > 1300) { sh("kill -KILL " + primaryPid); killed = true; }
                int base = sent; long b0 = System.nanoTime();
                try {
                    for (int i = 0; i < 50; i++) p.write("SET", "cart:" + (base + i), "item-" + (base + i));
                    p.flush(); sent += 50;
                    for (int i = 0; i < 50; i++) p.read();
                    boolean ok = true;
                    if (mode == 1) { Object n = p.cmd("WAIT", "1", "100"); ok = n instanceof Long l && l >= 1; }
                    lat.add(System.nanoTime() - b0);
                    if (ok) for (int i = 0; i < 50; i++) acked.add(base + i);
                } catch (IOException e) { break; }                              // primary is gone
            }
        }
        sh("kill -CONT " + replicaPid); Thread.sleep(300);
        int lost = 0;
        try (Redis r = new Redis(6392)) {
            r.cmd("REPLICAOF", "NO", "ONE");
            for (int k : acked) if (r.cmd("GET", "cart:" + k) == null) lost++;
        }
        Collections.sort(lat);
        System.out.printf("   %-44s %,12d %,16d %,12d %9.2f ms %11.2f ms%n", mode == 0 ? "ASYNC (reply OK = done)" : "WAIT 1 100 after each batch",
                sent, acked.size(), lost, lat.get(lat.size() / 2) / 1e6, lat.get(lat.size() - 1) / 1e6);
        sh("redis-cli -p 6392 shutdown nosave");
    }

    static long pid(int port) throws IOException { try (Redis r = new Redis(port)) { for (String l : ((String) r.cmd("INFO", "server")).split("\r\n")) if (l.startsWith("process_id:")) return Long.parseLong(l.substring(11)); } throw new IOException("no pid"); }
    static void sh(String c) throws Exception { Process p = new ProcessBuilder("bash", "-c", c + " >/dev/null 2>&1").start(); p.waitFor(); }

    static final class Redis implements Closeable {
        final Socket s; final OutputStream out; final InputStream in;
        Redis(int port) throws IOException { s = new Socket("127.0.0.1", port); s.setTcpNoDelay(true); out = new BufferedOutputStream(s.getOutputStream(), 1 << 16); in = new BufferedInputStream(s.getInputStream()); }
        void write(String... parts) throws IOException {
            StringBuilder sb = new StringBuilder("*").append(parts.length).append("\r\n");
            for (String p : parts) { byte[] b = p.getBytes(StandardCharsets.UTF_8); sb.append('$').append(b.length).append("\r\n").append(p).append("\r\n"); }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        void flush() throws IOException { out.flush(); }
        Object cmd(String... parts) throws IOException { write(parts); flush(); return read(); }
        Object read() throws IOException {
            int type = in.read(); if (type < 0) throw new EOFException(); String line = line();
            return switch (type) {
                case '+' -> line; case ':' -> Long.parseLong(line); case '-' -> throw new IOException(line);
                case '$' -> { int n = Integer.parseInt(line); if (n < 0) yield null; byte[] b = in.readNBytes(n); line(); yield new String(b, StandardCharsets.UTF_8); }
                default -> throw new IOException("unexpected reply type " + (char) type);
            };
        }
        String line() throws IOException { StringBuilder sb = new StringBuilder(); int c; while ((c = in.read()) != '\r') { if (c < 0) throw new EOFException(); sb.append((char) c); } in.read(); return sb.toString(); }
        public void close() throws IOException { s.close(); }
    }
}
