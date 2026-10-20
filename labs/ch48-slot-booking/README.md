# Ch 48 labs — Delivery Slot Booking & Capacity

JDK 21. Run with `java -Dstdout.encoding=UTF-8 <File>.java`; JDBC labs also need `-Duser.timezone=UTC -cp postgresql-42.7.4.jar`.

| Lab | File | Needs | Output |
|---|---|---|---|
| L1 capacity rules | SlotCapacityL1.java | — | cap_out.txt |
| L2 concurrent booking | SlotBookingL2.java | PostgreSQL :5581 db `shop` | booking_out.txt |
| L3 event-sourced slots | SlotEventsL3.java | PostgreSQL :5581 db `shop` | events_out.txt |
| L4 demand shaping | DemandShapingL4.java | — | shaping_out.txt |

Edit the JDBC URL in L2/L3 for your database user. Simulations use fixed seeds; timings vary by machine.
L4's customer acceptance and price-response rates are assumptions — measure your own.
