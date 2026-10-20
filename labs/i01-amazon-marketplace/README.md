# I01 — Amazon-Style Global Marketplace labs

JDK 21 single-file programs.

```
java -Dstdout.encoding=UTF-8 CartMergeL1.java        # L1: LWW vs union vs OR-Set carts under partition
java -Dstdout.encoding=UTF-8 -Duser.timezone=UTC -cp postgresql-42.7.4.jar BuyBoxL2.java   # L2: needs PostgreSQL
java -Dstdout.encoding=UTF-8 PromiseL3.java          # L3: fulfilment planning and the delivery promise
java -Dstdout.encoding=UTF-8 ShuffleShardL4.java     # L4: shuffle sharding vs fixed shards
```

L2 connects to `jdbc:postgresql://localhost:5581/shop?user=claude`. It creates and drops the tables `offers` and `buy_box`, and takes about 45 s.
