// LsmL1.java — run with: java -Xmx2g LsmL1.java   (JDK 21, no dependencies; writes ~1 GB under /tmp/kvlab/data)
// HOW A KEY-VALUE STORE'S STORAGE ENGINE WORKS. 2,000,000 puts of 100-byte values (fixed seed): 1,200,000 distinct keys
// (so 40% are overwrites), then 100,000 deletes. Three REAL on-disk engines built here, same workload:
//   E1 Bitcask-style: append every write to a log file; an in-memory hash map points each key to its latest offset
//   E2 LSM tree: writes go to a sorted in-memory memtable; at 4 MB it is flushed as an immutable sorted file (SSTable)
//      with a sparse index (every 16th key) kept in memory; reads check memtable, then SSTables newest → oldest
//   E3 E2 + a Bloom filter per SSTable (1% false positives) so most SSTables are skipped without touching disk
//   E4 E3 after compaction: all SSTables merged into one, overwritten values and deleted keys dropped
// Measured: write throughput, point-read latency for existing and missing keys, SSTable files actually read per
// lookup (read amplification), bytes on disk per byte of live data (space amplification), and RAM held for lookups.
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

public class LsmL1 {
    static final Path DIR = Paths.get("/tmp/kvlab/data");
    static final int VAL = 100, PUTS = 2_000_000, KEYS = 1_200_000, DELS = 100_000;

    public static void main(String[] args) throws Exception {
        Random r = new Random(54); String[] ops = new String[PUTS + DELS]; boolean[] isDel = new boolean[PUTS + DELS];
        for (int i = 0; i < PUTS; i++) ops[i] = key(i < KEYS ? i : r.nextInt(KEYS));
        for (int i = 0; i < DELS; i++) { ops[PUTS + i] = key(r.nextInt(KEYS)); isDel[PUTS + i] = true; }
        List<Integer> shuffle = new ArrayList<>(); for (int i = 0; i < PUTS; i++) shuffle.add(i); Collections.shuffle(shuffle, r);
        String[] pk = new String[PUTS]; for (int i = 0; i < PUTS; i++) pk[i] = ops[shuffle.get(i)]; System.arraycopy(pk, 0, ops, 0, PUTS);
        Map<String, Integer> truth = new HashMap<>(); for (int i = 0; i < ops.length; i++) { if (isDel[i]) truth.remove(ops[i]); else truth.put(ops[i], i); }
        long live = (long) truth.size() * (16 + VAL);
        System.out.printf("%,d puts (%,d distinct keys) + %,d deletes; %,d live keys = %.0f MB of live data.%n%n", PUTS, KEYS, DELS, truth.size(), live / 1048576.0);
        System.out.printf("   %-42s %10s %11s %11s %12s %12s %10s %10s%n", "engine", "write", "read hit", "read miss", "files read", "files read", "space", "RAM for");
        System.out.printf("   %-42s %10s %11s %11s %12s %12s %10s %10s%n", "", "MB/s", "p50 / p99", "p50", "per hit", "per miss", "amplif.", "lookups");
        String[] probeHit = new String[20_000], probeMiss = new String[20_000]; List<String> liveKeys = new ArrayList<>(truth.keySet()); Collections.sort(liveKeys);
        for (int i = 0; i < probeHit.length; i++) { probeHit[i] = liveKeys.get(r.nextInt(liveKeys.size())); probeMiss[i] = "x" + key(r.nextInt(KEYS)).substring(1); }
        // E1
        clean(); Bitcask bc = new Bitcask(); long t0 = System.nanoTime(); for (int i = 0; i < ops.length; i++) { if (isDel[i]) bc.delete(ops[i]); else bc.put(ops[i], val(i)); } bc.sync();
        double wMB = (ops.length * (16.0 + VAL)) / 1048576 / ((System.nanoTime() - t0) / 1e9);
        report("E1) Bitcask: log + in-memory hash index", wMB, probeHit, probeMiss, k -> bc.get(k), () -> 1.0, () -> 0.0, bc.diskBytes() / (double) live, bc.ramBytes(), truth, ops);
        // E2/E3/E4
        for (int e = 2; e <= 4; e++) {
            clean(); Lsm lsm = new Lsm(e >= 3); t0 = System.nanoTime(); for (int i = 0; i < ops.length; i++) { if (isDel[i]) lsm.delete(ops[i]); else lsm.put(ops[i], val(i)); } lsm.flush();
            double w = (ops.length * (16.0 + VAL)) / 1048576 / ((System.nanoTime() - t0) / 1e9);
            if (e == 4) lsm.compactAll();
            String name = e == 2 ? "E2) LSM: memtable + " + lsm.tables.size() + " SSTables, no Bloom" : e == 3 ? "E3) LSM + Bloom filters (" + lsm.tables.size() + " SSTables)" : "E4) LSM + Bloom, after full compaction";
            final Lsm L = lsm;
            report(name, e == 4 ? Double.NaN : w, probeHit, probeMiss, k -> L.get(k), () -> L.lastProbesHit, () -> L.lastProbesMiss, L.diskBytes() / (double) live, L.ramBytes(), truth, ops);
        }
        clean();
        System.out.println("\n   → the log-structured designs never overwrite in place, so writes are sequential and fast. Bitcask reads are one");
        System.out.println("     disk access, but every key must fit in RAM. The LSM keeps only a sparse index in RAM and pays on reads: a key");
        System.out.println("     may be in any SSTable, and a MISSING key must check them all — Bloom filters turn that into almost no file");
        System.out.println("     reads. Old versions and tombstones pile up (space amplification) until compaction merges files, which also");
        System.out.println("     brings every read to one file. Compaction is the price: it rewrites data in the background, competing with");
        System.out.println("     foreground I/O — the core tuning problem of RocksDB, Cassandra and friends.");
    }

    static String key(int i) { return String.format("k%015d", i); }
    static byte[] val(int i) { byte[] b = new byte[VAL]; Arrays.fill(b, (byte) ('a' + i % 26)); ByteBuffer.wrap(b).putInt(i); return b; }
    static void clean() throws IOException { if (Files.exists(DIR)) try (var s = Files.walk(DIR)) { s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); } Files.createDirectories(DIR); }

    interface Getter { byte[] get(String k) throws IOException; }
    interface D { double get(); }
    static void report(String name, double wMB, String[] hit, String[] miss, Getter g, D probesHit, D probesMiss, double spaceAmp, long ram, Map<String, Integer> truth, String[] ops) throws IOException {
        long[] lat = new long[hit.length]; double ph = 0, pm = 0; int wrong = 0;
        for (int i = 0; i < hit.length; i++) { long t = System.nanoTime(); byte[] v = g.get(hit[i]); lat[i] = System.nanoTime() - t; ph += probesHit.get();
            if (v == null || ByteBuffer.wrap(v).getInt() != truth.get(hit[i])) wrong++; }
        long[] ml = new long[miss.length];
        for (int i = 0; i < miss.length; i++) { long t = System.nanoTime(); if (g.get(miss[i]) != null) wrong++; ml[i] = System.nanoTime() - t; pm += probesMiss.get(); }
        Arrays.sort(lat); Arrays.sort(ml);
        System.out.printf("   %-42s %10s %5.1f/%4.0f µs %8.1f µs %12.2f %12.2f %9.2fx %7.0f MB%s%n", name, Double.isNaN(wMB) ? "—" : String.format("%.0f", wMB),
                lat[lat.length / 2] / 1e3, lat[(int) (lat.length * 0.99)] / 1e3, ml[ml.length / 2] / 1e3, ph / hit.length, pm / miss.length, spaceAmp, ram / 1048576.0, wrong > 0 ? "  WRONG=" + wrong : "");
    }

    // ---------------- E1: Bitcask ----------------
    static final class Bitcask {
        final FileChannel ch; final HashMap<String, Long> idx = new HashMap<>(); long pos = 0; final ByteBuffer buf = ByteBuffer.allocate(1 << 20);
        Bitcask() throws IOException { ch = FileChannel.open(DIR.resolve("bitcask.log"), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE); }
        void put(String k, byte[] v) throws IOException { append(k, v); }
        void delete(String k) throws IOException { append(k, null); idx.remove(k); }
        void append(String k, byte[] v) throws IOException {
            byte[] kb = k.getBytes(); int len = 2 + kb.length + 4 + (v == null ? 0 : v.length); if (buf.remaining() < len) drain();
            long at = pos + buf.position(); buf.putShort((short) kb.length).put(kb).putInt(v == null ? -1 : v.length); if (v != null) { buf.put(v); idx.put(k, at); } }
        void drain() throws IOException { buf.flip(); while (buf.hasRemaining()) pos += ch.write(buf, pos); buf.clear(); }
        void sync() throws IOException { drain(); ch.force(false); }
        byte[] get(String k) throws IOException { Long at = idx.get(k); if (at == null) return null; ByteBuffer b = ByteBuffer.allocate(2 + k.length() + 4 + VAL); ch.read(b, at); b.flip(); int kl = b.getShort(); b.position(2 + kl); int vl = b.getInt(); byte[] v = new byte[vl]; b.get(v); return v; }
        long diskBytes() { return pos; }
        long ramBytes() { return idx.size() * 96L; }                                            // ~String + Long + map entry
    }

    // ---------------- E2–E4: LSM ----------------
    static final byte[] TOMB = new byte[0];
    static final class Lsm {
        final boolean bloom; TreeMap<String, byte[]> mem = new TreeMap<>(); long memBytes = 0; final List<SSTable> tables = new ArrayList<>(); int seq = 0; double lastProbesHit, lastProbesMiss;
        Lsm(boolean bloom) { this.bloom = bloom; }
        void put(String k, byte[] v) throws IOException { mem.put(k, v); memBytes += k.length() + v.length + 32; if (memBytes > 4 << 20) flush(); }
        void delete(String k) throws IOException { put(k, TOMB); }
        void flush() throws IOException { if (mem.isEmpty()) return; tables.add(SSTable.write(DIR.resolve("sst-" + (seq++)), mem, bloom)); mem = new TreeMap<>(); memBytes = 0; }
        byte[] get(String k) throws IOException {
            int probes = 0; byte[] v = mem.get(k);
            if (v == null) for (int i = tables.size() - 1; i >= 0; i--) { SSTable t = tables.get(i); if (t.bloom != null && !t.bloom.mightContain(k)) continue; probes++; v = t.get(k); if (v != null) break; }
            lastProbesHit = probes; lastProbesMiss = probes;
            return v == null || v.length == 0 ? null : v;
        }
        void compactAll() throws IOException {
            TreeMap<String, byte[]> merged = new TreeMap<>(); for (SSTable t : tables) t.scanInto(merged);           // oldest → newest: newer wins
            merged.values().removeIf(v -> v.length == 0);                                                            // drop tombstones (full compaction)
            for (SSTable t : tables) Files.delete(t.path); tables.clear(); tables.add(SSTable.write(DIR.resolve("sst-" + (seq++)), merged, bloom));
        }
        long diskBytes() throws IOException { long s = 0; for (SSTable t : tables) s += Files.size(t.path); return s; }
        long ramBytes() { long s = 0; for (SSTable t : tables) s += t.index.size() * 80L + (t.bloom == null ? 0 : t.bloom.bits.length * 8L); return s; }
    }
    static final class SSTable {
        Path path; FileChannel ch; TreeMap<String, Long> index = new TreeMap<>(); Bloom bloom; long size;
        static SSTable write(Path p, TreeMap<String, byte[]> data, boolean withBloom) throws IOException {
            SSTable t = new SSTable(); t.path = p; if (withBloom) t.bloom = new Bloom(data.size());
            try (FileChannel ch = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                ByteBuffer buf = ByteBuffer.allocate(1 << 20); long pos = 0; int n = 0;
                for (var e : data.entrySet()) { byte[] kb = e.getKey().getBytes(); byte[] v = e.getValue();
                    if (buf.remaining() < 2 + kb.length + 4 + v.length) { buf.flip(); while (buf.hasRemaining()) pos += ch.write(buf); buf.clear(); }
                    if (n++ % 16 == 0) t.index.put(e.getKey(), pos + buf.position());
                    buf.putShort((short) kb.length).put(kb).putInt(v.length).put(v); if (t.bloom != null) t.bloom.add(e.getKey()); }
                buf.flip(); while (buf.hasRemaining()) pos += ch.write(buf); ch.force(false); t.size = pos; }
            t.ch = FileChannel.open(p, StandardOpenOption.READ); return t;
        }
        byte[] get(String k) throws IOException {                                                 // one block read: from the index entry ≤ k, scan ≤ 16 records
            var fl = index.floorEntry(k); if (fl == null) return null; long start = fl.getValue();
            var hi = index.higherEntry(fl.getKey()); long end = hi == null ? size : hi.getValue();
            ByteBuffer b = ByteBuffer.allocate((int) (end - start)); ch.read(b, start); b.flip();
            while (b.hasRemaining()) { int kl = b.getShort(); byte[] kb = new byte[kl]; b.get(kb); int vl = b.getInt(); int c = new String(kb).compareTo(k);
                if (c == 0) { byte[] v = new byte[vl]; b.get(v); return v; } if (c > 0) return null; b.position(b.position() + vl); }
            return null;
        }
        void scanInto(TreeMap<String, byte[]> m) throws IOException {
            ByteBuffer b = ByteBuffer.allocate((int) size); ch.read(b, 0); b.flip();
            while (b.hasRemaining()) { int kl = b.getShort(); byte[] kb = new byte[kl]; b.get(kb); int vl = b.getInt(); byte[] v = new byte[vl]; b.get(v); m.put(new String(kb), v); }
            ch.close();
        }
    }
    static final class Bloom {
        final long[] bits; final int m, k = 7;
        Bloom(int n) { m = Math.max(64, n * 10); bits = new long[(m + 63) / 64]; }                  // ~10 bits/key → ~1% FP
        void add(String s) { int h1 = s.hashCode(), h2 = mix(h1); for (int i = 0; i < k; i++) { int b = Math.floorMod(h1 + i * h2, m); bits[b >>> 6] |= 1L << (b & 63); } }
        boolean mightContain(String s) { int h1 = s.hashCode(), h2 = mix(h1); for (int i = 0; i < k; i++) { int b = Math.floorMod(h1 + i * h2, m); if ((bits[b >>> 6] & (1L << (b & 63))) == 0) return false; } return true; }
        static int mix(int x) { x ^= x >>> 16; x *= 0x7feb352d; x ^= x >>> 15; x *= 0x846ca68b; x ^= x >>> 16; return x | 1; }
    }
}
