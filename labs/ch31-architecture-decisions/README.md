# Chapter 31 Labs — Architecture Decision Making

All four labs are self-contained JDK 21 programs with deterministic output (fixed seeds, no timing dependence).
Run each with `java -Dstdout.encoding=UTF-8 <File>.java`.

| Lab | File | Output | What it shows |
|---|---|---|---|
| L1 | DecisionL1.java | decision_out.txt | Weighted matrix: Modular Monolith 4.57 / Serverless 3.64 / Microservices 2.86. Sensitivity: robust; only scalability flips it, at weight ≈8.75, to Serverless. |
| L2 | FitnessL2.java | fitness_out.txt | Fitness functions (layering, no cycles, domain purity) fail an eroded graph with 3 named violations; fixed graph passes. ArchUnit equivalent in the chapter (VERIFY). |
| L3 | BudgetL3.java | budget_out.txt | M/M/1 latency budget at 800 req/s vs 300 ms p99: sequential 334 ms (breach), parallel 111 ms (meets); queueing cliff 196 ms → 16 s as ρ 0.5 → 0.99. |
| L4 | MonteCarloL4.java | montecarlo_out.txt | Point estimate picks serverless ($23k vs $28k); 200k-trial Monte Carlo over right-skewed demand picks VMs ($29.6k vs $35.9k mean, 64% of trials); break-even ~147M req/month. |

Cost and capacity parameters are illustrative but internally consistent — substitute your own.
