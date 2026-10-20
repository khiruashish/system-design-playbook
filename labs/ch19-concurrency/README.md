# Ch 19 labs: Concurrency at Scale in Java

No dependencies. Machine used: 2 vCPU container. JDK 21 = `java`; JDK 25 = `/opt/jdk25/bin/java` (Temurin 25.0.1 from the Adoptium GitHub releases).

| Level | Run | Output |
|---|---|---|
| L1 threads & pool sizing | `java ThreadsL1.java` | `threads_out.txt` |
| L2 pinning | `java PinningL2.java` (JDK 21) and `/opt/jdk25/bin/java PinningL2.java` | `pin21.txt`, `pin25.txt` |
| L2 ThreadLocal vs ScopedValue | `/opt/jdk25/bin/java --enable-preview --source 25 ThreadLocalL2.java` | `tl_out.txt` |
| L3 product-page fan-out (real HTTP, ports 19301–19304) | `/opt/jdk25/bin/java --enable-preview --source 25 FanOutL3.java` | `fanout_out.txt` |
| L4 backpressure | `java -Xmx512m BackpressureL4.java` | `bp_out.txt` |

`StructuredTaskScope` is a preview API in JDK 25 (JEP 505), so L2's ScopedValue part and L3 need `--enable-preview`. Timings vary by machine; the shapes (ratios) are what matter.
