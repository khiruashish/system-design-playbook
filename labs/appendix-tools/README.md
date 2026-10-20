# Appendix tools

These programs regenerate the appendix pages from the chapters. Run them from the handbook root after editing any chapter. They need JDK 21 and no dependencies.

```
# Q&A bank + full question index (Ch 59 L1)
java -Dstdout.encoding=UTF-8 labs/appendix-tools/QaBankL1.java docs /tmp/bank
#   then paste /tmp/bank/bank_starred.md below the header of docs/07-interview/qa-bank-starred.md
#   and /tmp/bank/index_all.md below the header of docs/99-appendix/qa-index.md

# Glossary: checks every term appears in the chapter it links to (run inside labs/appendix-tools: reads glossary.tsv)
cd labs/appendix-tools && java -Dstdout.encoding=UTF-8 GlossaryGen.java ../../docs /tmp/glossary_body.md && cd ../..
#   then paste /tmp/glossary_body.md below the header of docs/99-appendix/glossary.md

# Lab catalogue: every chapter's L1–L4 with band, programs and run requirements
java -Dstdout.encoding=UTF-8 labs/appendix-tools/LabCatalog.java docs labs /tmp/catalog_body.md
#   then paste below the header of docs/99-appendix/lab-catalogue.md
```

`glossary.tsv` columns: term, chapter number, definition, and optional probe(s) separated by `|` (prefix `=` for a case-sensitive whole-word match).

## Whole-book audit

```
mkdocs build -q && python3 labs/appendix-tools/audit.py
```

For every chapter, the audit checks:

- sections 1–9 are all present;
- Q1–Q20 are numbered in order with the right band per range;
- there are at least 4 mermaid blocks, with happy and failure sequence diagrams plus a state diagram;
- the design doc has requirements;
- there are at least 8 anti-patterns;
- there are no leftover TODO/TBD markers.

Across the built site, it checks that every `#anchor` link resolves.
