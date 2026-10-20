# Ch 21 labs: Data at Scale

Tools (all from GitHub releases): SeaweedFS 3.80 (`weed`, S3 API), DuckDB 1.1.3 CLI, PostgreSQL 16 binaries, Java 21, pgjdbc 42.7.x.

```bash
# object storage with an S3 gateway on :8333 (credentials in s3.json)
weed server -dir=/tmp/datalab/sw -ip=127.0.0.1 -filer -s3 -s3.port=8333 -s3.config=s3.json &
# PostgreSQL :5543 (run as a non-root user)
./setup-postgres.sh
```

| Level | Run | Output |
|---|---|---|
| L1 object storage, SigV4, presigned URLs, multipart | `java ObjectStoreL1.java` | `objstore_out.txt` |
| L2 CDN edge: keys, coalescing, invalidation, stale-if-error | `java -Dsun.net.httpserver.nodelay=true CdnL2.java` (~50 s) | `cdn_out.txt` |
| L3 OLTP vs OLAP, ELT to Parquet | `./exp-olap.sh` (~90 s; `SKIP_LOAD=1` reuses data) | `olap_out.txt` |
| L4 restartable batch + event-time streaming | `java -Xmx2g -Duser.timezone=UTC -cp pgjdbc.jar BatchStreamL4.java` (needs L3's table) | `batchstream_out.txt` |

L3 writes `extract.csv` (754 MB) and `lake/` (58 MB) in the working directory. Timings vary by machine; answers are hash-checked.
