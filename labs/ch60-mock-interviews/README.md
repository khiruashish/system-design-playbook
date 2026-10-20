# Ch 60 — Mock Interviews labs

JDK 21 single-file programs.

```
java -Dstdout.encoding=UTF-8 PacingL1.java        # L1: phase pacing, checkpoint-rule replay (illustrative logs)
java -Dstdout.encoding=UTF-8 CalibrationL2.java   # L2: interviewer bias/noise simulation
java -Dstdout.encoding=UTF-8 -Duser.timezone=UTC -cp postgresql-42.7.4.jar ClaimCheckL3.java   # L3: needs PostgreSQL
java -Dstdout.encoding=UTF-8 ProgressL4.java      # L4: progress-vs-noise simulation
```

L3 connects to `jdbc:postgresql://localhost:5581/shop?user=claude`; edit `URL` for your database. It creates and drops the table `mock_stock`.
`*_out.txt` are the outputs quoted in the chapter.
