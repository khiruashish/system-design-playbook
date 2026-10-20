# Ch 53 labs — Web Crawler (Price Monitoring)

JDK 21, no dependencies. No lab touches the internet (L1 crawls a local server it starts itself).

| Lab | File | Run | Output |
|---|---|---|---|
| L1 polite frontier vs global FIFO (real local HTTP) | FrontierL1.java | `java -Dsun.net.httpserver.nodelay=true FrontierL1.java` | frontier_out.txt |
| L2 URL canonicalisation + dedup structures | DedupL2.java | `java -Xmx4g DedupL2.java` | dedup_out.txt |
| L3 re-crawl scheduling | RecrawlL3.java | `java RecrawlL3.java` | recrawl_out.txt |
| L4 traps, robots.txt, sitemaps | TrapsL4.java | `java TrapsL4.java` | traps_out.txt |
