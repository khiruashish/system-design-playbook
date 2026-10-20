# Ch 22 labs: Search Systems

Requirements: **JDK 22+** (these labs use multi-file source launch; tested with Temurin 25.0.1), PostgreSQL 16 (`:5543`, database
`orders`, see Ch 21's `setup-postgres.sh`), Meilisearch 1.11 (single binary from its GitHub releases), pgjdbc 42.7.x.

```bash
meilisearch --db-path ./meili --http-addr 127.0.0.1:7700 --master-key grocery-master-key-0123456789 --no-analytics &
java ExportCatalog.java 200000 > products.csv          # for L2
```

| Level | Run | Output |
|---|---|---|
| L1 inverted index, BM25, analyzers, judged queries | `java SearchL1.java` | `search_out.txt` |
| L2 PostgreSQL trigram + full-text + facets (200K rows) | `./exp-pgsearch.sh` (run as the DB's OS user) | `pgsearch_out.txt` |
| L3 Meilisearch: relevance configs, facets, outbox sync, ordering, index swap | `java -Duser.timezone=UTC -cp pgjdbc.jar MeiliL3.java` (~2 min) | `meili_out.txt` |
| L4 HNSW from scratch + hybrid BM25/vector (toy embeddings) | `java -Xmx3g VectorL4.java` (~1 min) | `vector_out.txt` |

`Catalog.java` holds the generated catalog (invented brands), the 20 judged queries and the analyzer building blocks shared by L1, L3, L4.
Timings, lag and race counts vary run to run; relevance numbers are deterministic.
