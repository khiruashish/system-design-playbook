# Ch 16 labs — microservices architecture

No external dependencies: Java 21+ only.

```bash
java CallChainL1.java          # availability and latency arithmetic
java FitnessL2.java            # fitness functions over the service graph (before/after)
./modular-monolith/build.sh    # JPMS modular monolith: builds, runs, then shows 3 boundary violations as compile errors
java HttpL4.java               # real HTTP on localhost ports 18081-18102: discovery, composition, strangler fig (~3 min)
```
