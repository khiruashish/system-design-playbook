# Ch 52 labs — Object Storage & Media Pipeline

JDK 21, no dependencies.

| Lab | File | Run | Output |
|---|---|---|---|
| L1 Reed–Solomon codec + durability model | ErasureL1.java | `java ErasureL1.java` | ec_out.txt |
| L2 multipart uploads over a flaky network (real HTTP) | UploadL2.java | `java -Xmx2g -Dsun.net.httpserver.nodelay=true UploadL2.java` | upload_out.txt |
| L3 image variants (real ImageIO) + serving model | ImageVariantsL3.java | `java -Xmx3g -Djava.awt.headless=true ImageVariantsL3.java` | image_out.txt |
| L4 CDN TTLs, purges, versioned URLs | CdnL4.java | `java -Xmx3g CdnL4.java` | cdn_out.txt |

Add `-Dstdout.encoding=UTF-8` on Windows consoles. Timings vary by machine; L1's durability table is a model.
