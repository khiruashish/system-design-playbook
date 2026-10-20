# Ch 24 labs: Observability

Java 21, no external deps except L2, which uses a real Prometheus 2.55 (single binary from its GitHub releases).

| Level | Run | Output |
|---|---|---|
| L1 structured logs, correlation ids, sampling, cardinality | `java LogsL1.java` | `logs_out.txt` |
| L2 RED metrics + histogram, scraped by real Prometheus | `java -Dsun.net.httpserver.nodelay=true MetricsL2.java` | `metrics_out.txt`, `prom_out.txt` |
| L3 distributed tracing: critical path, p99 driver, sampling | `java TracingL3.java` | `tracing_out.txt` |
| L4 SLOs, error budgets, multi-window burn-rate alerting | `java SloL4.java` | `slo_out.txt` |

For L2's live Prometheus view:
```bash
java -Dsun.net.httpserver.nodelay=true MetricsL2.java &          # exposes :19701/metrics for ~12s
prometheus --config.file=prometheus.yml --storage.tsdb.path=./promdata --web.listen-address=127.0.0.1:19090 &
# then query http://127.0.0.1:19090/api/v1/query?query=histogram_quantile(0.99, rate(http_request_duration_ms_bucket[1m]))
```
Timings and per-run counts vary; the shapes (average vs p99, bucket error, page counts) are the point.
