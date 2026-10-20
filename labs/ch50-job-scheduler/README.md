# Ch 50 labs — Distributed Job Scheduler

JDK 21. Run with `java -Dstdout.encoding=UTF-8 <File>.java`; JDBC labs also need `-Duser.timezone=UTC -cp postgresql-42.7.4.jar`.

| Lab | File | Needs | Output |
|---|---|---|---|
| L1 run-once: locks, owner tokens, fencing | LeaderLockL1.java | Redis :6390, PostgreSQL :5581 db `shop` | lock_out.txt |
| L2 firing delayed jobs | DueJobsL2.java | PostgreSQL :5581 db `shop` | due_out.txt |
| L3 cron chaining vs DAG | DagL3.java | — | dag_out.txt |
| L4 multi-tenant fair queuing | FairnessL4.java | — | fair_out.txt |

Edit the JDBC URL for your user. L1/L2 timings vary by machine; L2 poller p99s vary run to run (max column is the bound).
