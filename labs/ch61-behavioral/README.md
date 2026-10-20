# Ch 61 — Behavioural & Leadership labs

JDK 21 single-file programs. Run them in this folder; they read `stories.md` and `stories_v2.md`, which are illustrative, so replace them with your own.

```
java -Dstdout.encoding=UTF-8 StoryBankL1.java stories.md B3         # L1: coverage against a target level
java -Dstdout.encoding=UTF-8 StoryLintL2.java stories.md stories_v2.md   # L2: story lint
java -Dstdout.encoding=UTF-8 -Duser.timezone=UTC -cp postgresql-42.7.4.jar ImpactL3.java   # L3: needs PostgreSQL
java -Dstdout.encoding=UTF-8 LoopPlanL4.java                         # L4: loop plan (greedy vs optimal)
```

L3 connects to `jdbc:postgresql://localhost:5581/shop?user=claude`. It creates and drops the table `beh_orders` (1.5M rows, takes about a minute).
