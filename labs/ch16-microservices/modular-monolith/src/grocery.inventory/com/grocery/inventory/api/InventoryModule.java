package com.grocery.inventory.api;

import com.grocery.shared.events.EventBus;
import com.grocery.shared.events.OrderPlaced;
import java.util.HashMap;
import java.util.Map;

public final class InventoryModule {
    private final Map<String, Integer> stock = new HashMap<>(Map.of("ATTA-5KG", 40));
    public InventoryModule(EventBus bus) { bus.subscribe(OrderPlaced.class, this::onOrderPlaced); }   // reacts to events
    private void onOrderPlaced(OrderPlaced e) { stock.merge(e.sku(), -e.qty(), Integer::sum); }
    public int available(String sku) { return stock.getOrDefault(sku, 0); }
}
