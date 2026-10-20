// WsChatL2.java — run with (PostgreSQL 16 on :5581 db "shop", Redis 7 on :6390):
//   java -cp postgresql-42.7.4.jar -Duser.timezone=UTC WsChatL2.java
// A REAL MULTI-NODE WEBSOCKET CHAT. Two chat nodes, each a hand-written RFC 6455 WebSocket server (JDK ServerSocket +
// virtual threads — the handshake and framing are ~60 lines below), clients are the JDK's own java.net.http.WebSocket.
// The customer is connected to node A and the rider to node B, so every message must cross nodes:
//   SEND → node stores it in PostgreSQL (per-conversation sequence from `update … returning`, unique client message id
//          for idempotent retries) → ACK to the sender with the seq → PUBLISH on Redis `conv:<id>` → every node delivers
//          to its local sockets in that conversation.
//   HELLO with lastSeq → the node replays everything after lastSeq from PostgreSQL, then live messages follow.
// Scenario: 200 conversations × 20 messages across nodes; then 50 riders drop, customers keep sending, riders reconnect
// to the OTHER node; and 5% of sends are retried with the same client id (a lost ACK). Every phone checks its sequence.
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class WsChatL2 {
    static final String DB = "jdbc:postgresql://127.0.0.1:5581/shop?user=claude";

    // ======================= chat node =======================
    static final class Node {
        final String name; final ServerSocket ss; final BlockingQueue<Connection> pool = new LinkedBlockingQueue<>();
        final Map<Long, Set<Session>> byConv = new ConcurrentHashMap<>(); final Redis pub;
        Node(String name) throws Exception {
            this.name = name; ss = new ServerSocket(0, 512, InetAddress.getLoopbackAddress());
            for (int i = 0; i < 8; i++) pool.add(DriverManager.getConnection(DB));
            pub = new Redis(); Redis sub = new Redis(); sub.write("PSUBSCRIBE", "conv:*"); sub.flush(); sub.read();
            Thread.ofVirtual().start(() -> {                                    // Redis → local sockets
                try { while (true) { List<?> m = (List<?>) sub.read(); String ch = (String) m.get(2), payload = (String) m.get(3);
                    long conv = Long.parseLong(ch.substring(5));
                    for (Session s : byConv.getOrDefault(conv, Set.of())) s.send("MSG|" + payload); } } catch (Exception ignored) {}
            });
            Thread.ofVirtual().start(() -> { try { while (true) { Socket s = ss.accept(); Thread.ofVirtual().start(() -> serve(s)); } } catch (IOException ignored) {} });
        }
        int port() { return ss.getLocalPort(); }

        void serve(Socket sock) {
            Session sess = null;
            try (sock) {
                sock.setTcpNoDelay(true);
                InputStream in = new BufferedInputStream(sock.getInputStream()); OutputStream out = sock.getOutputStream();
                String key = null; String line;
                while (!(line = readLine(in)).isEmpty()) if (line.toLowerCase().startsWith("sec-websocket-key:")) key = line.substring(18).trim();
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes());
                out.flush();
                sess = new Session(out);
                String text;
                while ((text = readFrame(in, sess)) != null) {
                    String[] f = text.split("\\|", 5);
                    switch (f[0]) {
                        case "HELLO" -> {                                         // HELLO|user|conv|lastSeq
                            sess.user = f[1]; sess.conv = Long.parseLong(f[2]);
                            byConv.computeIfAbsent(sess.conv, k -> ConcurrentHashMap.newKeySet()).add(sess);
                            replay(sess, Long.parseLong(f[3]));
                        }
                        case "SEND" -> send(sess, f[1], Integer.parseInt(f[2]), f[3]);   // SEND|clientMsgId|clientSeq|text
                        default -> {}
                    }
                }
            } catch (Exception ignored) {
            } finally { if (sess != null && sess.conv != 0) byConv.getOrDefault(sess.conv, new HashSet<>()).remove(sess); }
        }

        void replay(Session s, long after) throws Exception {
            Connection c = pool.take();
            try (PreparedStatement ps = c.prepareStatement("select seq, sender, body from chat_messages where conv_id = ? and seq > ? order by seq")) {
                ps.setLong(1, s.conv); ps.setLong(2, after);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) { replayed.incrementAndGet(); s.send("MSG|" + rs.getLong(1) + "|" + rs.getString(2) + "|" + rs.getString(3)); } }
            } finally { pool.add(c); }
        }

        void send(Session s, String clientId, int clientSeq, String body) throws Exception {
            Connection c = pool.take(); long seq; boolean fresh;
            try {
                c.setAutoCommit(false);
                try (PreparedStatement ex = c.prepareStatement("select seq from chat_messages where conv_id = ? and sender = ? and client_msg_id = ?")) {
                    ex.setLong(1, s.conv); ex.setString(2, s.user); ex.setString(3, clientId);
                    try (ResultSet rs = ex.executeQuery()) { fresh = !rs.next(); seq = fresh ? 0 : rs.getLong(1); }
                }
                if (fresh) {
                    try (PreparedStatement up = c.prepareStatement("update conversations set last_seq = last_seq + 1 where id = ? returning last_seq")) {
                        up.setLong(1, s.conv); try (ResultSet rs = up.executeQuery()) { rs.next(); seq = rs.getLong(1); }
                    }
                    try (PreparedStatement ins = c.prepareStatement("insert into chat_messages(conv_id, seq, sender, client_msg_id, body) values (?,?,?,?,?)")) {
                        ins.setLong(1, s.conv); ins.setLong(2, seq); ins.setString(3, s.user); ins.setString(4, clientId); ins.setString(5, body); ins.executeUpdate();
                    }
                }
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; } finally { c.setAutoCommit(true); pool.add(c); }
            s.send("ACK|" + clientId + "|" + seq);
            if (fresh) synchronized (pub) { pub.cmd("PUBLISH", "conv:" + s.conv, seq + "|" + s.user + "|" + body); }
            else dedupedRetries.incrementAndGet();
        }
    }
    static final AtomicLong replayed = new AtomicLong(), dedupedRetries = new AtomicLong();
    static final Map<String, Long> SENT_AT = new ConcurrentHashMap<>();   // send time per client message id (shared by all phones)

    static final class Session {
        final OutputStream out; String user; long conv;
        Session(OutputStream out) { this.out = out; }
        synchronized void send(String text) throws IOException {                   // unmasked server text frame
            byte[] p = text.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream b = new ByteArrayOutputStream(); b.write(0x81);
            if (p.length < 126) b.write(p.length); else { b.write(126); b.write(p.length >> 8); b.write(p.length & 0xff); }
            b.write(p); out.write(b.toByteArray()); out.flush();
        }
    }
    static String readLine(InputStream in) throws IOException { StringBuilder sb = new StringBuilder(); int c; while ((c = in.read()) != '\n') { if (c < 0) throw new EOFException(); if (c != '\r') sb.append((char) c); } return sb.toString(); }
    static String readFrame(InputStream in, Session s) throws IOException {       // client frames are always masked
        while (true) {
            int b0 = in.read(); if (b0 < 0) return null; int b1 = in.read(); int op = b0 & 0x0f;
            long len = b1 & 0x7f; if (len == 126) len = (in.read() << 8) | in.read(); else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | in.read(); }
            byte[] mask = in.readNBytes(4), p = in.readNBytes((int) len);
            for (int i = 0; i < p.length; i++) p[i] ^= mask[i % 4];
            if (op == 8) return null;
            if (op == 1) return new String(p, StandardCharsets.UTF_8);
        }
    }

    // ======================= phone (client) =======================
    static final class Phone implements WebSocket.Listener {
        final String user; final long conv; WebSocket ws; long lastSeq = 0; final StringBuilder buf = new StringBuilder();
        final List<Long> seen = Collections.synchronizedList(new ArrayList<>()); final Map<String, Long> sentAt = SENT_AT;
        final ConcurrentHashMap<String, Long> acks = new ConcurrentHashMap<>(); long gaps = 0, dupes = 0; final List<Long> latencies;
        Phone(String user, long conv, List<Long> latencies) { this.user = user; this.conv = conv; this.latencies = latencies; }
        void connect(HttpClient http, int port) throws Exception {
            ws = http.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + port + "/chat"), this).get(5, TimeUnit.SECONDS);
            ws.sendText("HELLO|" + user + "|" + conv + "|" + lastSeq, true).get();
        }
        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            buf.append(data);
            if (last) { handle(buf.toString()); buf.setLength(0); }
            w.request(1); return null;
        }
        synchronized void handle(String m) {
            String[] f = m.split("\\|", 4);
            if (f[0].equals("ACK")) { acks.put(f[1], Long.parseLong(f[2])); return; }
            long seq = Long.parseLong(f[1]);
            if (f[2].equals(user)) { lastSeq = Math.max(lastSeq, seq); return; }            // own message echoed to my other devices
            if (seq <= lastSeq && seen.contains(seq)) { dupes++; return; }
            if (seq > lastSeq + 1 && !seen.isEmpty()) gaps++;
            seen.add(seq); lastSeq = Math.max(lastSeq, seq);
            Long t0 = sentAt.get(f[3]); if (t0 != null) latencies.add(System.nanoTime() - t0);
        }
    }

    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection(DB); Statement s = c.createStatement()) {
            s.execute("drop table if exists chat_messages"); s.execute("drop table if exists conversations");
            s.execute("create table conversations(id bigint primary key, last_seq bigint not null default 0)");
            s.execute("create table chat_messages(conv_id bigint, seq bigint, sender text, client_msg_id text, body text, sent_at timestamptz default now(), primary key(conv_id, seq), unique(conv_id, sender, client_msg_id))");
            s.execute("insert into conversations(id) select g from generate_series(1, 200) g");
        }
        Node a = new Node("A"), b = new Node("B");
        HttpClient http = HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        List<Long> lat = Collections.synchronizedList(new ArrayList<>());
        int convs = 200; Phone[] cust = new Phone[convs], rider = new Phone[convs];
        for (int i = 0; i < convs; i++) {
            cust[i] = new Phone("cust-" + i, i + 1, lat); rider[i] = new Phone("rider-" + i, i + 1, lat);
            cust[i].connect(http, a.port()); rider[i].connect(http, b.port());
        }
        Thread.sleep(300);
        System.out.println("Two WebSocket chat nodes (A, B), PostgreSQL for history, Redis pub/sub between nodes.");
        System.out.printf("%d conversations: every customer on node A, every rider on node B.%n%n", convs);

        // Phase 1: 20 messages per conversation, alternating, with 5% retried sends (same client id)
        Random rnd = new Random(41); AtomicLong sends = new AtomicLong();
        long t0 = System.nanoTime();
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < convs; i++) {
                final int k = i; final long seed = rnd.nextLong();
                ex.submit(() -> {
                    Random r = new Random(seed);
                    for (int m = 0; m < 20; m++) {
                        Phone p = m % 2 == 0 ? cust[k] : rider[k]; String id = p.user + "-m" + m;
                        p.sentAt.put(id, System.nanoTime());
                        p.ws.sendText("SEND|" + id + "|" + m + "|" + id, true).join(); sends.incrementAndGet();
                        if (r.nextDouble() < 0.05) { p.ws.sendText("SEND|" + id + "|" + m + "|" + id, true).join(); sends.incrementAndGet(); }   // lost ACK → retry
                        long wait = System.nanoTime() + 2_000_000_000L; while (!p.acks.containsKey(id) && System.nanoTime() < wait) { try { Thread.sleep(1); } catch (InterruptedException e) { return; } }   // never spin on a virtual thread
                        try { Thread.sleep(5 + r.nextInt(10)); } catch (InterruptedException e) { return; }
                    }
                });
            }
        }
        Thread.sleep(500);
        double secs = (System.nanoTime() - t0) / 1e9;
        List<Long> l = new ArrayList<>(lat); Collections.sort(l);
        System.out.printf("   Phase 1 — %,d sends (%,d retries of the same client id) in %.1f s%n", sends.get(), dedupedRetries.get(), secs);
        System.out.printf("        delivered across nodes: %,d   end-to-end p50 %.2f ms   p99 %.2f ms   (localhost; timings vary)%n",
                l.size(), l.get(l.size() / 2) / 1e6, l.get((int) (l.size() * 0.99)) / 1e6);

        // Phase 2: 50 riders drop; customers send 5 more; riders reconnect to node A with their lastSeq
        for (int i = 0; i < 50; i++) rider[i].ws.sendClose(WebSocket.NORMAL_CLOSURE, "network change").join();
        Thread.sleep(300);
        for (int i = 0; i < 50; i++) for (int m = 20; m < 25; m++) {
            String id = cust[i].user + "-m" + m; cust[i].sentAt.put(id, System.nanoTime());
            cust[i].ws.sendText("SEND|" + id + "|" + m + "|" + id, true).join();
        }
        Thread.sleep(500); long before = replayed.get();
        for (int i = 0; i < 50; i++) { rider[i] = reconnect(rider[i], http, a.port(), lat); }
        Thread.sleep(800);
        System.out.printf("%n   Phase 2 — 50 riders dropped; customers sent 250 messages meanwhile; riders reconnected to node A%n");
        System.out.printf("        replayed from PostgreSQL on reconnect: %,d%n", replayed.get() - before);

        // Verification: every rider and customer saw each of the other side's messages exactly once, in sequence
        long expected = 0, got = 0, gaps = 0, dupes = 0; int[] outOfOrder = {0};
        try (Connection c = DriverManager.getConnection(DB); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("select count(*), count(distinct (conv_id, sender, client_msg_id)) from chat_messages")) {
            rs.next(); System.out.printf("%n   Stored messages: %,d (distinct client ids: %,d — retries did not create duplicates)%n", rs.getLong(1), rs.getLong(2));
        }
        for (int i = 0; i < convs; i++) for (Phone p : new Phone[]{cust[i], rider[i]}) {
            List<Long> seen = new ArrayList<>(p.seen); got += seen.size(); gaps += p.gaps; dupes += p.dupes;
            for (int j = 1; j < seen.size(); j++) if (seen.get(j) < seen.get(j - 1)) outOfOrder[0]++;
            try (Connection c = DriverManager.getConnection(DB); PreparedStatement ps = c.prepareStatement("select count(*) from chat_messages where conv_id = ? and sender <> ?")) {
                ps.setLong(1, p.conv); ps.setString(2, p.user); try (ResultSet rs = ps.executeQuery()) { rs.next(); expected += rs.getLong(1); }
            }
        }
        System.out.printf("   Phones: expected to see %,d messages from the other side, saw %,d; duplicates dropped by seq %,d; out of order %d; gaps %d%n",
                expected, got, dupes, outOfOrder[0], gaps);
        System.out.println("\n   → the node a message arrives at is irrelevant: the database assigns the conversation's order, Redis carries it");
        System.out.println("     to whichever node holds the other phone, and a phone that was away asks for everything after its last");
        System.out.println("     sequence number — from any node. Retries with the same client id return the original sequence, so a lost");
        System.out.println("     ACK never becomes a second message. Pub/sub is fire-and-forget; the database is what makes delivery complete.");
        System.exit(0);
    }

    static Phone reconnect(Phone old, HttpClient http, int port, List<Long> lat) throws Exception {
        Phone p = new Phone(old.user, old.conv, lat); p.lastSeq = old.lastSeq; p.seen.addAll(old.seen);
        p.connect(http, port); return p;
    }

    /** Minimal RESP client with pub/sub support. */
    static final class Redis {
        final Socket s; final OutputStream out; final InputStream in;
        Redis() throws IOException { s = new Socket("127.0.0.1", 6390); s.setTcpNoDelay(true); out = new BufferedOutputStream(s.getOutputStream()); in = new BufferedInputStream(s.getInputStream()); }
        void write(String... parts) throws IOException {
            StringBuilder sb = new StringBuilder("*").append(parts.length).append("\r\n");
            for (String p : parts) { byte[] b = p.getBytes(StandardCharsets.UTF_8); sb.append('$').append(b.length).append("\r\n").append(p).append("\r\n"); }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        void flush() throws IOException { out.flush(); }
        Object cmd(String... parts) throws IOException { write(parts); flush(); return read(); }
        Object read() throws IOException {
            int type = in.read(); String line = line();
            return switch (type) {
                case '+' -> line; case ':' -> Long.parseLong(line);
                case '$' -> { int n = Integer.parseInt(line); if (n < 0) yield null; byte[] b = in.readNBytes(n); line(); yield new String(b, StandardCharsets.UTF_8); }
                case '*' -> { int n = Integer.parseInt(line); List<Object> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(read()); yield l; }
                case '-' -> throw new IOException("redis error: " + line);
                default -> throw new IOException("unexpected reply type " + (char) type);
            };
        }
        String line() throws IOException { StringBuilder sb = new StringBuilder(); int c; while ((c = in.read()) != '\r') sb.append((char) c); in.read(); return sb.toString(); }
    }
}
