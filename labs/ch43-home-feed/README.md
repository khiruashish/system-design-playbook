# Chapter 43 Labs — Home Feed & Recommendations

JDK 21, no dependencies. Run with `-Dstdout.encoding=UTF-8`. L3 needs Redis 7 on 127.0.0.1:6390. Fixed seeds; L3 latencies
are real localhost timings (the cross-zone column is an estimate). Behavioural parameters and hidden "true" models are stated.

| Lab | Command | Output | What it shows |
|---|---|---|---|
| L1 | `javac -d out World.java CandidatesL1.java && java -cp out CandidatesL1` | candidates_out.txt | Recall@20: best-sellers 22.1%, buy-again 65.6% (0 new items), item-to-item 2.3% (5,411 new), blend 59.2% (3,521 new) |
| L2 | `java RankingL2.java` | ranking_out.txt | Position bias: naive LR popularity weight 1.63 vs true 0.30; position feature / IPS 0.25 / 0.29; NDCG@10 0.537 → 0.939 → 0.999 |
| L3 | `java FeatureFetchL3.java` | features_out.txt | Real Redis, 400 candidates: 800 round trips 30.9 ms (~430 ms cross-zone) vs one MGET+HMGET 0.61 ms; 30 s item cache → 5.0% stale stock |
| L4 | `java ExploreL4.java` | explore_out.txt | 40 launches: greedy 57.3% of oracle, 0/40 fair trials; ε-greedy 73.9%; Thompson 92.5%, best new #1; reserved slot 93.2% |
