# Chapter 37 Labs — Leading Architecture at Scale

JDK 21, no dependencies. Run with `-Dstdout.encoding=UTF-8`. Output is deterministic.
Inputs: `services.csv` (26 services: context, current owner, context-aligned owner, migration effort) and
`features.csv` (14 recurring feature types with yearly weights). Lead-time, workaround and codemod figures are stated assumptions.

| Lab | Command | Output | What it shows |
|---|---|---|---|
| L1 | `java ConwayL1.java services.csv features.csv` | conway_out.txt | 500 features: by-layer ownership 91% multi-team, 26% ≥3 teams, every context split across 2–3 teams; context-aligned 86% / 14% |
| L2 | `java TeamsL2.java services.csv features.csv` | teams_out.txt | Local search within 2–4 services/team and load cap: hand-offs 591 (current) → 500 (aligned) → 240 (optimised), multi-team 43%; ~45 s |
| L3 | `java PavedRoadL3.java services.csv` | pavedroad_out.txt | Generates 26 repos in ./repos and scans 8 golden-path checks: v3 100%, v2 60%, v1 28%, hand-rolled 19%; 8/26 fully on path |
| L4 | `java MigrationL4.java services.csv` | migration_out.txt | Boot 2→3: central 31 sprints; platform-first team-owned 11; start-immediately 10.4 but +32% effort; + codemods 8.6 sprints, 36.8 effort |
