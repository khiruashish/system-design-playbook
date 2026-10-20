// WalL2.java — run with: java WalL2.java   (JDK 21, no dependencies)
// "ACKNOWLEDGED" MUST MEAN "WILL SURVIVE". A child JVM appends 128-byte records (seq + payload + CRC32) to a write-ahead
// log and prints "A <seq>" to the parent the moment it considers each record durable. After 2.5 s the parent KILLS the
// child (SIGKILL — no shutdown hooks, no flush), reads the log back, stops at the first torn/corrupt record, and counts
// acknowledged records that are missing. Four ack policies:
// Acks go to a file (stdout redirected) so the parent sees exactly what was acknowledged before the kill.
//   M1 write into a 64 KB user-space buffer, ack immediately (no flush)
//   M2 write() each record to the OS (flush), ack — no fsync
//   M3 fsync (FileChannel.force) after every record, then ack
//   M4 group commit: 8 writer threads hand records to a committer that writes and fsyncs a batch, then acks the batch
// Note: SIGKILL loses what is still in the PROCESS; data already handed to the OS survives a process crash but NOT a
// power loss or kernel crash, which this lab cannot simulate — only fsync (M3, M4) is safe against those.
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;
import java.nio.channels.Channels;

public class WalL2 {
    static final int REC = 128;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) { child(Integer.parseInt(args[0]), Paths.get(args[1])); return; }
        System.out.println("child JVM appends 128-byte records, acks each; parent SIGKILLs it after 2.5 s and recovers the log.\n");
        System.out.printf("   %-50s %12s %12s %14s %14s%n", "ack policy", "acked", "recovered", "ACKED BUT LOST", "acks/s");
        String[] names = {"M1) buffered in the process, ack at once", "M2) write() to the OS each record, no fsync", "M3) fsync every record", "M4) group commit: 8 writers, one fsync per batch"};
        for (int m = 1; m <= 4; m++) {
            Path log = Paths.get("/tmp/kvlab/wal-" + m + ".log"); Files.deleteIfExists(log);
            Path ackFile = Paths.get("/tmp/kvlab/acks-" + m + ".txt"); Files.deleteIfExists(ackFile);
            Process p = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"), "-cp", System.getProperty("java.class.path", "."), "WalL2Child", String.valueOf(m), log.toString())
                    .redirectOutput(ackFile.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();   // acks land in a file the parent reads after the kill
            long started = System.nanoTime(); Thread.sleep(2500); long killAt = System.nanoTime(); p.destroyForcibly(); p.waitFor();
            Set<Long> acked = new HashSet<>(); for (String line : Files.readAllLines(ackFile)) if (line.startsWith("A ") && line.length() > 2) acked.add(Long.parseLong(line.substring(2).trim()));
            long[] first = {started + 1_000_000_000L};                                               // child JVM start-up ≈ 1 s (excluded from the rate)
            Set<Long> recovered = recover(log); long lost = acked.stream().filter(s -> !recovered.contains(s)).count();
            double secs = (killAt - first[0]) / 1e9;
            System.out.printf("   %-50s %,12d %,12d %,14d %,14.0f%n", names[m - 1], acked.size(), recovered.size(), lost, acked.size() / secs);
        }
        System.out.println("\n   → an acknowledgement is a promise. Acking from a user-space buffer breaks it on any crash. Handing bytes to the");
        System.out.println("     OS survives a process kill (the kernel still has them) but not a power cut. fsync per record keeps the");
        System.out.println("     promise at the cost of one device flush per write; group commit keeps it too and amortises the flush over");
        System.out.println("     a batch — the technique behind database and Kafka durability settings. Recovery must also detect a torn");
        System.out.println("     last record (length + CRC) and stop there. (fsync speed depends heavily on the device; this sandbox's disk");
        System.out.println("     may be faster or slower than yours.)");
    }

    static Set<Long> recover(Path log) throws IOException {
        Set<Long> s = new HashSet<>(); if (!Files.exists(log)) return s; byte[] all = Files.readAllBytes(log); ByteBuffer b = ByteBuffer.wrap(all);
        while (b.remaining() >= REC) { int start = b.position(); long seq = b.getLong(); byte[] payload = new byte[REC - 16]; b.get(payload); long crc = b.getLong();
            CRC32 c = new CRC32(); c.update(all, start, REC - 8); if (c.getValue() != crc) break; s.add(seq); }
        return s;
    }

    static void child(int mode, Path log) throws Exception { WalL2Child.main(new String[]{String.valueOf(mode), log.toString()}); }
}

class WalL2Child {
    public static void main(String[] args) throws Exception {
        int mode = Integer.parseInt(args[0]); Path log = Paths.get(args[1]);
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true);           // autoflush: ack lines leave immediately
        FileChannel ch = FileChannel.open(log, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        if (mode == 1) {
            BufferedOutputStream bos = new BufferedOutputStream(Channels.newOutputStream(ch), 64 * 1024);
            for (long seq = 1; ; seq++) { bos.write(record(seq)); out.println("A " + seq); }
        } else if (mode == 2 || mode == 3) {
            for (long seq = 1; ; seq++) { ByteBuffer b = ByteBuffer.wrap(record(seq)); while (b.hasRemaining()) ch.write(b); if (mode == 3) ch.force(false); out.println("A " + seq); }
        } else {
            BlockingQueue<long[]> q = new LinkedBlockingQueue<>(); java.util.concurrent.atomic.AtomicLong next = new java.util.concurrent.atomic.AtomicLong(1);
            Map<Long, CountDownLatch> waiting = new ConcurrentHashMap<>();
            for (int w = 0; w < 8; w++) new Thread(() -> { try { while (true) { long seq = next.getAndIncrement(); CountDownLatch l = new CountDownLatch(1); waiting.put(seq, l); q.put(new long[]{seq}); l.await(); out.println("A " + seq); } } catch (Exception e) { } }).start();
            List<long[]> batch = new ArrayList<>();
            while (true) { batch.clear(); batch.add(q.take()); q.drainTo(batch);
                ByteBuffer b = ByteBuffer.allocate(batch.size() * WalL2.REC); for (long[] s : batch) b.put(record(s[0])); b.flip(); while (b.hasRemaining()) ch.write(b); ch.force(false);
                for (long[] s : batch) waiting.remove(s[0]).countDown(); }
        }
    }
    static byte[] record(long seq) {
        ByteBuffer b = ByteBuffer.allocate(WalL2.REC); b.putLong(seq); for (int i = 0; i < WalL2.REC - 16; i++) b.put((byte) (seq + i));
        java.util.zip.CRC32 c = new java.util.zip.CRC32(); c.update(b.array(), 0, WalL2.REC - 8); b.putLong(c.getValue()); return b.array();
    }
}
