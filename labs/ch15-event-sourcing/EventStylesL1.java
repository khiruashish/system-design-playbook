// EventStylesL1.java — run with: java EventStylesL1.java
// The same fact — "the customer changed the delivery address of an order" — shared three ways:
// event notification, event-carried state transfer, and event sourcing.
import java.util.*;

public class EventStylesL1 {

    record Address(String line, String pincode) { }

    /** order-service: the owner of the data. It can be down. */
    static final class OrderService {
        final Map<String, Address> addresses = new HashMap<>();
        boolean up = true; int reads = 0;
        Address getAddress(String orderId) {
            if (!up) throw new IllegalStateException("order-service unavailable");
            reads++; return addresses.get(orderId);
        }
    }

    // 1. Event notification: "something changed, ask me"
    record AddressChanged(String orderId) { }
    // 2. Event-carried state transfer: "here is the new state"
    record AddressChangedWithState(String orderId, Address newAddress) { }
    // 3. Event sourcing: the events ARE the state
    record AddressSet(String orderId, Address address, int version) { }

    public static void main(String[] args) {
        OrderService orders = new OrderService();
        List<String> changes = new ArrayList<>();
        List<AddressSet> log = new ArrayList<>();
        Random rnd = new Random(1);
        for (int i = 0; i < 10_000; i++) {
            String id = "ord-" + rnd.nextInt(2_000);
            Address a = new Address("Flat " + i, "5600" + (10 + rnd.nextInt(90)));
            orders.addresses.put(id, a);
            changes.add(id);
            int v = (int) log.stream().filter(e -> e.orderId().equals(id)).count() + 1;
            log.add(new AddressSet(id, a, v));
        }
        orders.up = false;                               // order-service is down while delivery-service consumes

        // delivery-service consuming style 1
        int failed = 0; Map<String, Address> local1 = new HashMap<>();
        for (String id : changes) {
            try { local1.put(id, orders.getAddress(id)); } catch (IllegalStateException e) { failed++; }
        }
        System.out.println("1. Event notification (AddressChanged{orderId}), order-service down:");
        System.out.printf("   %,d events → %,d callbacks needed, %,d failed; delivery-service knows %,d addresses%n", changes.size(), changes.size(), failed, local1.size());
        orders.up = true; orders.reads = 0;
        for (String id : changes) local1.put(id, orders.getAddress(id));
        System.out.printf("   with order-service up: %,d synchronous reads back into the producer (coupling + load)%n", orders.reads);

        // style 2
        Map<String, Address> local2 = new HashMap<>();
        orders.up = false;
        for (int i = 0; i < changes.size(); i++) local2.put(changes.get(i), log.get(i).address());
        System.out.println("2. Event-carried state transfer (AddressChangedWithState{orderId, address}), order-service down:");
        System.out.printf("   0 callbacks; delivery-service's local copy has %,d addresses, identical to the source: %s%n",
                local2.size(), local2.equals(orders.addresses));

        // style 3
        Map<String, Address> state = new HashMap<>();
        for (AddressSet e : log) state.put(e.orderId(), e.address());          // current state = fold(events)
        String sample = log.get(0).orderId();
        List<AddressSet> history = log.stream().filter(e -> e.orderId().equals(sample)).toList();
        System.out.println("3. Event sourcing (AddressSet events are the record of truth):");
        System.out.printf("   state rebuilt from %,d events equals the current table: %s%n", log.size(), state.equals(orders.addresses));
        System.out.printf("   %s history: %d versions, first %s, current %s — the other two styles only know 'current'%n",
                sample, history.size(), history.get(0).address().pincode(), history.get(history.size() - 1).address().pincode());
    }
}
