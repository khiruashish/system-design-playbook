package com.grocery.order.api;

import com.grocery.catalog.api.CatalogApi;
import com.grocery.shared.events.EventBus;
import com.grocery.shared.events.OrderPlaced;

public final class OrderModule {
    private final CatalogApi catalog; private final EventBus bus; private int seq;
    public OrderModule(CatalogApi catalog, EventBus bus) { this.catalog = catalog; this.bus = bus; }
    public String place(String sku, int qty) {
        var product = catalog.product(sku);                          // via the catalog's published API
        String id = "ord-" + (++seq);
        bus.publish(new OrderPlaced(id, sku, qty));                  // inventory reacts; order doesn't know inventory exists
        return id + " " + product.name() + " x" + qty + " = ₹" + product.pricePaise() * qty / 100;
    }
}
