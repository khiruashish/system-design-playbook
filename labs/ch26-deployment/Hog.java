// Hog.java — a container citizen: prints what the JVM sees, then either allocates or burns CPU.
public class Hog {
  public static void main(String[] a) throws Exception {
    System.out.printf("JVM sees: availableProcessors=%d, maxMemory=%d MB%n",
        Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory()/1024/1024);
    if (a.length>0 && a[0].equals("mem")) {
      java.util.List<byte[]> keep = new java.util.ArrayList<>();
      for (int i=0;;i++){ keep.add(new byte[10*1024*1024]); System.out.println("allocated "+((i+1)*10)+" MB (live)"); Thread.sleep(30); }
    } else if (a.length>0 && a[0].equals("cpu")) {
      long ops=Long.parseLong(a[1]); long t0=System.nanoTime(); double x=1;
      for(long i=0;i<ops;i++) x=Math.sqrt(x*1.0000001+1);
      System.out.printf("cpu work %,d ops in %,d ms (checksum %.2f)%n", ops, (System.nanoTime()-t0)/1_000_000, x);
    }
  }
}
