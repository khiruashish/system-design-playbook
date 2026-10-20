# Ch 58 lab — One question, four levels of answer

JDK 21 + PostgreSQL (port 5581, db `shop`; edit the JDBC URL for your setup).

    java -Duser.timezone=UTC -cp postgresql-42.7.4.jar WebhookLevelsL.java

Implements "handle the payment gateway's webhooks" at B1 (in-memory dedup), B2 (DB dedup in the same transaction),
B3 (+ forward-only state machine + outbox) and B4 (+ reconciliation). Output: levels_out.txt.
Drops/creates tables pay, processed_event, effect, outbox. B1's duplicate count varies slightly run to run.
