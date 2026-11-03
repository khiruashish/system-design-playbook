# Ch 62 labs — GenAI Shopping Assistant (RAG & LLM Serving)

JDK 21, no dependencies, no model or GPU needed. Run each with `java -Dstdout.encoding=UTF-8 <File>.java`.
Every lab uses fixed seeds and no timing, so the output is identical on every run.

| Lab | File | Output | What it shows |
|---|---|---|---|
| L1 retrieval: chunk size x BM25 / embeddings / hybrid RRF | RetrievalL1.java | retrieval_out.txt | Complete answer in the prompt: 25-word chunks 67%, 120-word 93%, 400-word 98% at 1.8x the tokens (hybrid). BM25 misses 45% of paraphrases; embeddings miss every SKU code; hybrid covers both |
| L2 LLM serving: batching, paged KV, prefix cache | ServingL2.java | serving_out.txt | Same model, one GPU: one-at-a-time and static batching never meet TTFT p99 2 s; continuous batching reserving 16k KV 2 req/s (90 GPUs for 180 req/s); paged KV 7 req/s (26); + prefix cache 9 req/s (20) |
| L3 cost controls: semantic cache and model routing | CostControlsL3.java | cost_out.txt | Semantic cache serves wrong answers for 22-40% of questions at any threshold (another customer's order is 0.99 similar); keyed cache 62% hits, 0 wrong. Routing + escalation $0.96 vs $8.10 per 1k answers at equal quality |
| L4 agent tools under injection, mistakes and retries | AgentToolsL4.java | agent_out.txt | Naive agent: 527 unauthorized actions, 398 on other customers' orders, 135 double refunds in 20k conversations; idempotency, argument checks, intent allow-list and confirmation take each to 0 |

What is real and what is simulated:

- **Real algorithms:** chunking, BM25, cosine kNN, reciprocal rank fusion, recall/MRR (L1); the schedulers and KV accounting (L2);
  cache policies and routing logic (L3); the policy layer, idempotency keys and retries (L4).
- **Simulated, with stated assumptions:** the embedding model is a toy (synonym concepts + character trigrams; codes and numbers
  barely move the vector, as with real models); the GPU is a cost model for an 8B model on one 80 GB GPU; traffic, model quality,
  prices and model mistakes are illustrative rates printed at the top of each output.
