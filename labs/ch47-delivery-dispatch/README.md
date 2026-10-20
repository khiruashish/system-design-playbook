# Ch 47 labs — Delivery Dispatch & Live Tracking

JDK 21, no dependencies. Run each with `java -Dstdout.encoding=UTF-8 <File>.java`.

| Lab | File | Needs | Output |
|---|---|---|---|
| L1 geo indexes (polygons + nearest riders) | GeoL1.java | Redis 7 on :6390 | geo_out.txt |
| L2 GPS ingestion (Lua ordering, pipelining, thinning) | TelemetryL2.java | Redis 7 on :6390 | telemetry_out.txt |
| L3 dispatch policies | DispatchL3.java | — | dispatch_out.txt |
| L4 ETA estimators | EtaL4.java | — | eta_out.txt |

Start Redis: `redis-server --port 6390 --daemonize yes --save '' --appendonly no`.
Timings vary by machine; simulations use fixed seeds.
