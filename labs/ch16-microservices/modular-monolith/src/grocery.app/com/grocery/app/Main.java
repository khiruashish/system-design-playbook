package com.grocery.app;

import com.grocery.catalog.api.CatalogApi;
import com.grocery.inventory.api.InventoryModule;
import com.grocery.order.api.OrderModule;
import com.grocery.shared.events.EventBus;

public final class Main {
    public static void main(String[] args) {
        EventBus bus = new EventBus();
        InventoryModule inventory = new InventoryModule(bus);
        OrderModule orders = new OrderModule(new CatalogApi(), bus);
        System.out.println("placed: " + orders.place("ATTA-5KG", 3));
        System.out.println("inventory after the event: ATTA-5KG available = " + inventory.available("ATTA-5KG"));
        System.out.println("module graph: " + ModuleLayer.boot().modules().stream().map(Module::getName)
                .filter(n -> n.startsWith("grocery")).sorted().toList());
    }
}
