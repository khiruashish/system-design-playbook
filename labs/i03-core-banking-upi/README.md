# I03 — Core Banking & UPI Payments labs

JDK 21 single-file programs.

```
java -Dstdout.encoding=UTF-8 -Duser.timezone=UTC -cp postgresql-42.7.4.jar HotAccountL1.java   # L1: needs PostgreSQL (~35 s)
java -Dstdout.encoding=UTF-8 UpiTimeoutL2.java    # L2: pending-outcome policies (illustrative)
java -Dstdout.encoding=UTF-8 InterestL3.java      # L3: exact interest on 1M accounts (~90 s)
java -Dstdout.encoding=UTF-8 MuleL4.java          # L4: mule and collector detection (synthetic graph)
```

L1 connects to `jdbc:postgresql://localhost:5581/shop?user=claude`. It creates and drops the tables `acct`, `sub_acct` and `journal`.
