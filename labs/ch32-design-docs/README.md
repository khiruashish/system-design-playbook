# Chapter 32 Labs — Design Documents & Architecture Communication

Self-contained JDK 21 programs that read real files in this folder. Run from this directory with `-Dstdout.encoding=UTF-8`.

| Lab | Command | Output | What it shows |
|---|---|---|---|
| L1 | `java DocLintL1.java docs/bad_design.md docs/good_design.md` | doclint_out.txt | Linter: bad doc 18 findings (7 missing sections, 9 unmeasurable NFRs, 0 alternatives, thin rollout, no ADR); template doc passes |
| L2 | `java C4ModelL2.java` | c4_out.txt | One model → C4 context + container Mermaid; validation: no owner (Dispatch), orphan (Search index), missing protocol (checkout→Kafka) |
| L3 | `java EstimateL3.java` | estimate_out.txt | Recompute capacity figures from assumptions: peak writes overstated 8.6x (500 vs 58/s), storage understated 2.2x (2.0 vs 4.5 TB) |
| L4 | `java DriftL4.java src documented_model.txt` | drift_out.txt | Scan src/ for Feign/Kafka/WebClient deps: 8 recovered, 3 undocumented (incl. sync checkout→promotions), 1 stale (dispatch→checkout) |

`src/` is a deliberately small sample codebase (not compilable — it only needs to be parseable). Production equivalents: Vale (prose lint),
Structurizr DSL (model-as-code, VERIFY snippet in chapter), trace/service-mesh dependency discovery.
