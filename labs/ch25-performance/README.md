# Ch 25 labs: Performance Engineering

Java 21 (`java`) and JDK 25 at /opt/jdk25. No external deps; JFR and all GCs are built into the JDK.

| Level | Run | Output |
|---|---|---|
| L1 tail latency, hedging, queueing knee, coordinated omission | `java TailL1.java` | `tail_out.txt` |
| L2 GC comparison (same workload, real GC logs) | `java -XX:+UseG1GC -Xms1g -Xmx1g -Xlog:gc:file=gc.log GcWorkload.java` (repeat with `-XX:+UseParallelGC`, `-XX:+UseZGC`, and `/opt/jdk25/bin/java`) | `gc_out.txt` |
| L3 JFR profiling → profile-guided fix | `java -XX:StartFlightRecording=settings=profile,filename=pricing.jfr HotspotL3.java` then `jfr print --events jdk.ExecutionSample,jdk.ObjectAllocationSample pricing.jfr` | `profile_out.txt` |
| L4 load model, USL fit, Little's law | `java LoadL4.java` | `load_out.txt` |

L2's `gc_out.txt` is assembled from five runs (JDK21 Parallel/G1/ZGC, JDK25 G1/ZGC); the driver parses pauses from each `-Xlog:gc` file.
Timings vary by machine and core count; the shapes (average vs tail, pause size, 175x fix, USL peak) are the point.
