# Chapter 42 Labs — Search Autocomplete (Typeahead)

JDK 21, no dependencies, no external services. Run with `-Dstdout.encoding=UTF-8`. Fixed seeds; L1 latencies are real JVM
timings and vary by machine. L2–L4 use synthetic vocabularies and stated behavioural assumptions. L4 takes about a minute.

| Lab | Command | Output | What it shows |
|---|---|---|---|
| L1 | `java -Xmx2g TrieTopKL1.java` | trie_out.txt | 200k candidates: scan 2.8 ms p50; trie + subtree 108 µs p50 / 3.1 ms p99; precomputed top-5 0.2 µs / 2.2 µs, identical results, 24 MB |
| L2 | `java SuggestPipelineL2.java` | pipeline_out.txt | 30 days of logs: mango found day 26 (all-time) vs day 17 (decay + trending); rakhi 4/0 with event calendar; 96 junk prefix-days without filtering |
| L3 | `java KeystrokeL3.java` | keystroke_out.txt | 20k sessions: 41.8 stale jump-backs/100 without seq numbers; debounce −17%; edge + client cache origin 5.82 → 3.12/search, p95 479 → 308 ms |
| L4 | `java PersonalL4.java` | personal_out.txt | Global 16% top-5 @1 char, 15% unstocked; availability filter 0%; + personal history 73.4%, keystrokes 11.6 → 4.2 |
