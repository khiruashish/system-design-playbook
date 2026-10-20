# Chapter 45 Labs — Payment System

JDK 21. Run with `-Dstdout.encoding=UTF-8`. L1 and L2 need PostgreSQL 16 on 127.0.0.1:5581 (db `shop`, user `claude`, trust
auth) and `postgresql-42.7.4.jar`; add `-Duser.timezone=UTC`. L2 also needs `-Dsun.net.httpserver.nodelay=true`.
The fake gateway, fee rates (2% + 18% GST cards, 0% UPI) and traffic/incident shapes are ILLUSTRATIVE, not any gateway's terms.

| Lab | Command | Output | What it shows |
|---|---|---|---|
| L1 | `java -cp postgresql-42.7.4.jar -Duser.timezone=UTC LedgerL1.java` | ledger_out.txt | 20k wallet ops: read-then-write 116/200 wallets wrong, ₹34,011.55 created; FOR UPDATE + journal exact; SERIALIZABLE exact but 58,764 retries |
| L2 | `java -cp postgresql-42.7.4.jar -Duser.timezone=UTC -Dsun.net.httpserver.nodelay=true PaymentFlowL2.java` | flow_out.txt | Fake PSP over HTTP: naive 1,621 double / 35 unpaid fulfilments; state machine 87 paid-unfulfilled; + poller 1,708/1,708 |
| L3 | `java ReconcileL3.java` | reconcile_out.txt | Settlement recon: totals explain nothing; exact match 582 breaks (500 false); rules 232 real exceptions, ₹93,130 |
| L4 | `java RoutingL4.java` | routing_out.txt | Gateway vs bank failures: gateway failover 78.7% in bank outage; per-(gateway, bank) + steering 88.7%; + auto-retry 96.9% in gateway incident |
